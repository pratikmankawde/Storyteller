package com.dramebaz.app.ai.llm.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.tasks.AnalysisTask
import com.dramebaz.app.ai.llm.tasks.TaskProgress
import com.dramebaz.app.ui.main.MainActivity
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android Foreground Service for running analysis tasks.
 * 
 * This service provides:
 * - Wake lock to prevent CPU sleep during long tasks
 * - Persistent notification with progress
 * - Progress broadcasts for UI updates
 * - Completion broadcasts with results
 * 
 * NOT tied to specific models, pipelines, or passes.
 * 
 * Use for tasks with estimatedDurationSeconds >= 60.
 */
class AnalysisForegroundService : Service() {
    
    companion object {
        private const val TAG = "AnalysisForegroundService"
        private const val CHANNEL_ID = "analysis_channel"
        private const val NOTIFICATION_ID = 3001
        private const val WAKE_LOCK_TAG = "Storyteller::AnalysisService"
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L  // 30 minutes
        
        // Broadcast actions
        const val ACTION_PROGRESS = "com.dramebaz.app.ANALYSIS_PROGRESS"
        const val ACTION_COMPLETE = "com.dramebaz.app.ANALYSIS_COMPLETE"
        const val ACTION_CANCEL = "com.dramebaz.app.ANALYSIS_CANCEL"
        
        // Broadcast extras
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_PROGRESS_MESSAGE = "progress_message"
        const val EXTRA_PROGRESS_PERCENT = "progress_percent"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        
        // Pending task to be executed (set before starting service)
        @Volatile
        private var pendingTask: AnalysisTask? = null
        
        @Volatile
        private var currentTaskId: String? = null
        
        @Volatile
        private var llmModel: LlmModel? = null
        
        /**
         * Start the service with a task.
         */
        fun start(context: Context, task: AnalysisTask, model: LlmModel) {
            pendingTask = task
            llmModel = model
            val intent = Intent(context, AnalysisForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Cancel the currently running task.
         */
        fun cancel(context: Context) {
            val intent = Intent(context, AnalysisForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
        
        /**
         * Get the currently running task ID.
         */
        fun getCurrentTaskId(): String? = currentTaskId

        /**
         * Cancel the task for a specific book if it's currently running.
         * Checks if the current task ID contains the book ID pattern.
         */
        fun cancelForBook(context: Context, bookId: Long) {
            val taskId = currentTaskId ?: return
            // Task IDs are formatted as "chapter_analysis_${bookId}_${chapterId}"
            if (taskId.contains("_${bookId}_") || taskId.endsWith("_$bookId")) {
                AppLogger.i(TAG, "Cancelling task $taskId for book $bookId")
                cancel(context)
            } else {
                AppLogger.d(TAG, "Current task $taskId is not for book $bookId")
            }
        }
    }
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var analysisJob: Job? = null
    private var currentProgress = 0
    private var currentMessage = "Starting analysis..."
    
    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "AnalysisForegroundService created")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                AppLogger.i(TAG, "Cancellation requested")
                analysisJob?.cancel()
                sendCompleteBroadcast(success = false, errorMessage = "Cancelled by user")
                stopAnalysis()
                return START_NOT_STICKY
            }
        }
        
        val task = pendingTask
        val model = llmModel
        pendingTask = null
        
        if (task == null || model == null) {
            AppLogger.e(TAG, "No pending task or model")
            stopSelf()
            return START_NOT_STICKY
        }
        
        currentTaskId = task.taskId
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification(task.displayName, "Starting..."))
        
        analysisJob = serviceScope.launch {
            runTask(task, model)
        }

        return START_NOT_STICKY
    }

    private suspend fun runTask(task: AnalysisTask, model: LlmModel) {
        val startTime = System.currentTimeMillis()
        AppLogger.i(TAG, "Starting task: ${task.displayName} (${task.taskId})")

        try {
            val result = task.execute(model) { progress ->
                currentProgress = progress.percent
                currentMessage = progress.message
                updateNotification(task.displayName, progress.message)
                sendProgressBroadcast(progress)
            }

            val durationSec = (System.currentTimeMillis() - startTime) / 1000
            AppLogger.i(TAG, "Task completed in ${durationSec}s: ${result.success}")

            withContext(Dispatchers.Main) {
                sendCompleteBroadcast(
                    success = result.success,
                    resultData = result.resultData,
                    errorMessage = result.error
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.w(TAG, "Task cancelled: ${task.displayName}")
            withContext(Dispatchers.Main) {
                sendCompleteBroadcast(success = false, errorMessage = "Cancelled")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Task failed: ${task.displayName}", e)
            withContext(Dispatchers.Main) {
                sendCompleteBroadcast(success = false, errorMessage = e.message)
            }
        } finally {
            stopAnalysis()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Analysis Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress during LLM analysis"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                AppLogger.i(TAG, "Wake lock acquired")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to acquire wake lock", e)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun buildNotification(title: String, message: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AnalysisForegroundService::class.java).apply {
                action = ACTION_CANCEL
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, currentProgress, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()
    }

    private fun updateNotification(title: String, message: String) {
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(title, message))
    }

    private fun sendProgressBroadcast(progress: TaskProgress) {
        Intent(ACTION_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_TASK_ID, progress.taskId)
            putExtra(EXTRA_PROGRESS_MESSAGE, progress.message)
            putExtra(EXTRA_PROGRESS_PERCENT, progress.percent)
            sendBroadcast(this)
        }
    }

    private fun sendCompleteBroadcast(
        success: Boolean,
        resultData: Map<String, Any>? = null,
        errorMessage: String? = null
    ) {
        Intent(ACTION_COMPLETE).apply {
            setPackage(packageName)
            putExtra(EXTRA_TASK_ID, currentTaskId)
            putExtra(EXTRA_SUCCESS, success)
            resultData?.let { putExtra(EXTRA_RESULT_DATA, HashMap(it)) }
            errorMessage?.let { putExtra(EXTRA_ERROR_MESSAGE, it) }
            sendBroadcast(this)
        }
    }

    private fun stopAnalysis() {
        currentTaskId = null
        Companion.llmModel = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        analysisJob?.cancel()
        sendCompleteBroadcast(success = false, errorMessage = "App closed")
        stopAnalysis()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
    }
}


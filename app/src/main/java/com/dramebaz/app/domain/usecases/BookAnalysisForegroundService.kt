package com.dramebaz.app.domain.usecases

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
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ui.main.MainActivity
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service for running book analysis in the background.
 * Uses a wake lock to prevent the OS from freezing the app during LLM inference.
 * Shows a persistent notification with progress updates.
 * 
 * This allows book analysis to continue even when the app is minimized,
 * similar to how a music player continues playing in the background.
 */
class BookAnalysisForegroundService : Service() {

    companion object {
        private const val TAG = "BookAnalysisFgService"
        const val CHANNEL_ID = "book_analysis_channel"
        const val NOTIFICATION_ID = 3002
        private const val WAKE_LOCK_TAG = "Dramebaz::BookAnalysis"
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L // 60 minutes max

        // Actions
        const val ACTION_START = "com.dramebaz.app.BOOK_ANALYSIS_START"
        const val ACTION_STOP = "com.dramebaz.app.BOOK_ANALYSIS_STOP"
        const val ACTION_UPDATE_PROGRESS = "com.dramebaz.app.BOOK_ANALYSIS_UPDATE"

        // Extras
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PROGRESS = "progress"

        @Volatile
        private var isRunning = false

        fun isServiceRunning(): Boolean = isRunning

        /**
         * Start the foreground service for book analysis.
         */
        fun start(context: Context) {
            if (isRunning) {
                AppLogger.d(TAG, "Service already running")
                return
            }
            val intent = Intent(context, BookAnalysisForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the foreground service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, BookAnalysisForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Update the notification with current progress.
         */
        fun updateProgress(context: Context, message: String, progress: Int) {
            if (!isRunning) return
            val intent = Intent(context, BookAnalysisForegroundService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_PROGRESS, progress)
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentProgress = 0
    private var currentMessage = "Analyzing book..."

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "BookAnalysisForegroundService created")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    isRunning = true
                    acquireWakeLock()
                    startForeground(NOTIFICATION_ID, buildNotification(currentMessage, currentProgress))
                    AppLogger.i(TAG, "✅ Foreground service started")
                }
            }
            ACTION_STOP -> {
                AppLogger.i(TAG, "Stop requested")
                stopAnalysis()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_PROGRESS -> {
                currentMessage = intent.getStringExtra(EXTRA_MESSAGE) ?: currentMessage
                currentProgress = intent.getIntExtra(EXTRA_PROGRESS, currentProgress)
                updateNotification()
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Book Analysis",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress during book analysis"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager?.createNotificationChannel(channel)
            AppLogger.d(TAG, "Notification channel created")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                AppLogger.i(TAG, "✅ Wake lock ACQUIRED (timeout=${WAKE_LOCK_TIMEOUT_MS}ms)")
            } catch (e: Exception) {
                AppLogger.e(TAG, "❌ FAILED to acquire wake lock", e)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                AppLogger.i(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    private fun buildNotification(message: String, progress: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BookAnalysisForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            cancelIntent
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Analyzing Book")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .addAction(cancelAction)
            .build()
    }

    private fun updateNotification() {
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(currentMessage, currentProgress))
    }

    private fun stopAnalysis() {
        AppLogger.i(TAG, "🛑 stopAnalysis() called - releasing resources")
        isRunning = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        AppLogger.i(TAG, "Service stopped")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AppLogger.i(TAG, "🔴 onTaskRemoved() - App closed by user")
        // Don't stop analysis when app is swiped away - that's the whole point of the foreground service
        // Analysis will continue in background
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i(TAG, "🔴 Service onDestroy() called")
        isRunning = false
        serviceScope.cancel()
        releaseWakeLock()
        AppLogger.i(TAG, "Service destroyed")
    }
}

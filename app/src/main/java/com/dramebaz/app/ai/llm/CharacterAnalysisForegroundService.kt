package com.dramebaz.app.ai.llm

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
import com.dramebaz.app.domain.usecases.GemmaCharacterAnalysisUseCase
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
 * Foreground service for running 2-pass character analysis using Gemma 3n E2B Lite model.
 * Uses a wake lock to prevent the OS from freezing the app during LLM inference.
 * Shows a persistent notification with progress updates.
 *
 * The 2-pass workflow (via GemmaCharacterAnalysisUseCase):
 * - Pass-1: Extract character names + complete voice profiles (~3000 tokens per segment)
 * - Pass-2: Extract dialogs with speaker attribution (~1500 tokens per segment)
 */
class CharacterAnalysisForegroundService : Service() {

    companion object {
        private const val TAG = "CharAnalysisFgService"
        private const val CHANNEL_ID = "character_analysis_channel"
        private const val NOTIFICATION_ID = 2001
        private const val WAKE_LOCK_TAG = "Storyteller::CharacterAnalysis"
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L // 30 minutes max

        // Intent extras
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER_TEXT = "chapter_text"
        const val EXTRA_CHAPTER_INDEX = "chapter_index"
        const val EXTRA_TOTAL_CHAPTERS = "total_chapters"
        const val EXTRA_CHAPTER_TITLE = "chapter_title"

        // Broadcast actions
        const val ACTION_PROGRESS = "com.dramebaz.app.CHARACTER_ANALYSIS_PROGRESS"
        const val ACTION_COMPLETE = "com.dramebaz.app.CHARACTER_ANALYSIS_COMPLETE"
        const val ACTION_CANCEL = "com.dramebaz.app.CHARACTER_ANALYSIS_CANCEL"

        // Broadcast extras
        const val EXTRA_PROGRESS_MESSAGE = "progress_message"
        const val EXTRA_PROGRESS_PERCENT = "progress_percent"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_CHARACTER_COUNT = "character_count"
        const val EXTRA_ERROR_MESSAGE = "error_message"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var analysisJob: Job? = null
    private var currentProgress = 0
    private var currentMessage = "Starting analysis..."

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "CharacterAnalysisForegroundService created")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_CANCEL -> {
                AppLogger.i(TAG, "Cancellation requested")
                analysisJob?.cancel()
                sendCompleteBroadcast(success = false, errorMessage = "Cancelled by user")
                stopAnalysis()
                return START_NOT_STICKY
            }
        }

        val bookId = intent?.getLongExtra(EXTRA_BOOK_ID, -1L) ?: -1L
        val chapterText = intent?.getStringExtra(EXTRA_CHAPTER_TEXT)
        val chapterIndex = intent?.getIntExtra(EXTRA_CHAPTER_INDEX, 0) ?: 0
        val totalChapters = intent?.getIntExtra(EXTRA_TOTAL_CHAPTERS, 1) ?: 1
        val chapterTitle = intent?.getStringExtra(EXTRA_CHAPTER_TITLE) ?: "Chapter ${chapterIndex + 1}"

        if (bookId == -1L || chapterText.isNullOrBlank()) {
            AppLogger.e(TAG, "Invalid intent: bookId=$bookId, chapterText=${chapterText?.length}")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Acquire wake lock
        acquireWakeLock()

        // Start foreground with notification
        startForeground(NOTIFICATION_ID, buildNotification("Starting character analysis..."))

        // Run analysis in background
        analysisJob = serviceScope.launch {
            runAnalysis(bookId, chapterText, chapterIndex, totalChapters, chapterTitle, startId)
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Character Analysis",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress during character analysis"
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
                AppLogger.i(TAG, "✅ Wake lock ACQUIRED (timeout=${WAKE_LOCK_TIMEOUT_MS}ms, isHeld=${wakeLock?.isHeld})")
            } catch (e: Exception) {
                AppLogger.e(TAG, "❌ FAILED to acquire wake lock", e)
            }
        } else {
            AppLogger.d(TAG, "Wake lock already exists (isHeld=${wakeLock?.isHeld})")
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

    private suspend fun runAnalysis(
        bookId: Long,
        chapterText: String,
        chapterIndex: Int,
        totalChapters: Int,
        chapterTitle: String,
        startId: Int
    ) {
        val startMs = System.currentTimeMillis()
        AppLogger.i(TAG, "🚀 runAnalysis() ENTERED - bookId=$bookId, chapter='$chapterTitle', textLen=${chapterText.length}")
        AppLogger.i(TAG, "   Wake lock status: isHeld=${wakeLock?.isHeld}")

        var gemmaUseCase: GemmaCharacterAnalysisUseCase? = null
        try {
            AppLogger.i(TAG, "📖 Starting Gemma 2-pass analysis: bookId=$bookId, chapter=$chapterTitle")
            val app = applicationContext as DramebazApplication
            AppLogger.d(TAG, "   Got DramebazApplication, getting characterDao...")

            // Create Gemma-based use case with checkpoint support
            gemmaUseCase = GemmaCharacterAnalysisUseCase(app.db.characterDao(), applicationContext)
            AppLogger.d(TAG, "   GemmaCharacterAnalysisUseCase created, initializing Gemma engine...")

            // Initialize the Gemma engine
            val initialized = gemmaUseCase.initialize()
            if (!initialized) {
                AppLogger.e(TAG, "❌ Failed to initialize Gemma engine")
                withContext(Dispatchers.Main) {
                    sendCompleteBroadcast(success = false, errorMessage = "Failed to initialize Gemma model")
                }
                return
            }

            AppLogger.i(TAG, "✅ Gemma engine initialized: ${gemmaUseCase.getExecutionProvider()}")
            AppLogger.d(TAG, "   Calling analyzeChapter()...")

            val results = gemmaUseCase.analyzeChapter(
                bookId = bookId,
                chapterText = chapterText,
                chapterIndex = chapterIndex,
                totalChapters = totalChapters,
                onProgress = { message ->
                    AppLogger.d(TAG, "📊 Progress: $message")
                    currentMessage = message
                    currentProgress = parseProgress(message)
                    updateNotification(message)
                    sendProgressBroadcast(message, currentProgress)
                },
                onCharacterProcessed = { charName ->
                    AppLogger.d(TAG, "👤 Character processed: $charName")
                }
            )

            val durationSec = (System.currentTimeMillis() - startMs) / 1000
            AppLogger.i(TAG, "✅ Analysis COMPLETE in ${durationSec}s, found ${results.size} characters")
            AppLogger.i(TAG, "   Characters: ${results.map { it.name }}")

            withContext(Dispatchers.Main) {
                AppLogger.d(TAG, "📤 Sending completion broadcast (success=true, count=${results.size})")
                sendCompleteBroadcast(success = true, characterCount = results.size)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            val durationSec = (System.currentTimeMillis() - startMs) / 1000
            AppLogger.w(TAG, "⚠️ Analysis CANCELLED after ${durationSec}s", e)
            withContext(Dispatchers.Main) {
                sendCompleteBroadcast(success = false, errorMessage = "Cancelled")
            }
        } catch (e: Exception) {
            val durationSec = (System.currentTimeMillis() - startMs) / 1000
            AppLogger.e(TAG, "❌ Analysis FAILED after ${durationSec}s: ${e.javaClass.simpleName}: ${e.message}", e)
            withContext(Dispatchers.Main) {
                sendCompleteBroadcast(success = false, errorMessage = e.message ?: "Unknown error")
            }
        } finally {
            // Release Gemma engine resources
            gemmaUseCase?.release()
            AppLogger.i(TAG, "🔚 runAnalysis() FINALLY block - stopping analysis")
            stopAnalysis()
        }
    }

    private fun parseProgress(message: String): Int {
        // Gemma 2-pass workflow progress parsing:
        // Initializing (0-5%): Engine initialization with GPU/CPU backend
        // Pass 1 (5-45%): Extract character names + voice profiles per segment
        // Pass 1 complete (45%): Database save after Pass 1
        // Pass 2 (45-85%): Extract dialogs with speaker attribution per segment
        // Pass 2 complete (85-90%): Dialog extraction complete
        // Saving (90-100%): Final database persistence
        //
        // Note: Check more specific patterns FIRST (e.g., "Pass 1 complete" before "Pass 1")
        return when {
            message.contains("Initializing") -> 2
            message.contains("Pass 1 complete") -> 45
            message.contains("Pass 2 complete") -> 88
            message.contains("Saving") && message.contains("database") -> 92
            message.contains("Pass 1") -> {
                val segmentMatch = Regex("Segment (\\d+)/(\\d+)").find(message)
                if (segmentMatch != null) {
                    val (current, total) = segmentMatch.destructured
                    5 + (current.toInt() * 40 / total.toInt().coerceAtLeast(1))
                } else if (message.contains("Extracting characters")) {
                    8
                } else 25
            }
            message.contains("Pass 2") -> {
                val segmentMatch = Regex("Segment (\\d+)/(\\d+)").find(message)
                if (segmentMatch != null) {
                    val (current, total) = segmentMatch.destructured
                    45 + (current.toInt() * 40 / total.toInt().coerceAtLeast(1))
                } else if (message.contains("Extracting dialogs")) {
                    50
                } else 65
            }
            message.contains("complete") -> 100
            else -> currentProgress
        }
    }

    private fun buildNotification(message: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CharacterAnalysisForegroundService::class.java).apply {
                action = ACTION_CANCEL
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Cancel",
            cancelIntent
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Analyzing Characters")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, currentProgress, false)
            .addAction(cancelAction)
            .build()
    }

    private fun updateNotification(message: String) {
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun sendProgressBroadcast(message: String, percent: Int) {
        try {
            val intent = Intent(ACTION_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_PROGRESS_MESSAGE, message)
                putExtra(EXTRA_PROGRESS_PERCENT, percent)
            }
            sendBroadcast(intent)
            AppLogger.d(TAG, "📡 Progress broadcast sent: $percent% - $message")
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ Failed to send progress broadcast", e)
        }
    }

    private fun sendCompleteBroadcast(success: Boolean, characterCount: Int = 0, errorMessage: String? = null) {
        try {
            AppLogger.i(TAG, "📡 Sending COMPLETE broadcast: success=$success, count=$characterCount, error=$errorMessage")
            val intent = Intent(ACTION_COMPLETE).apply {
                setPackage(packageName)
                putExtra(EXTRA_SUCCESS, success)
                putExtra(EXTRA_CHARACTER_COUNT, characterCount)
                if (errorMessage != null) {
                    putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
                }
            }
            sendBroadcast(intent)
            AppLogger.i(TAG, "✅ COMPLETE broadcast sent successfully")
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ Failed to send complete broadcast", e)
        }
    }

    private fun stopAnalysis() {
        AppLogger.i(TAG, "🛑 stopAnalysis() called - releasing resources")
        AppLogger.d(TAG, "   Wake lock status before release: isHeld=${wakeLock?.isHeld}")
        releaseWakeLock()
        AppLogger.d(TAG, "   Calling stopForeground...")
        stopForeground(STOP_FOREGROUND_REMOVE)
        AppLogger.d(TAG, "   Calling stopSelf...")
        stopSelf()
        AppLogger.i(TAG, "   stopAnalysis() complete")
    }

    /**
     * Called when the app is removed from recent tasks (user swipes it away).
     * This ensures the service stops when the app is "closed" by the user.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AppLogger.i(TAG, "🔴 onTaskRemoved() - App closed by user, stopping analysis")
        analysisJob?.cancel()
        sendCompleteBroadcast(success = false, errorMessage = "App closed")
        stopAnalysis()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i(TAG, "🔴 Service onDestroy() called")
        AppLogger.d(TAG, "   Cancelling analysisJob: isActive=${analysisJob?.isActive}")
        analysisJob?.cancel()
        AppLogger.d(TAG, "   Cancelling serviceScope")
        serviceScope.cancel()
        releaseWakeLock()
        AppLogger.i(TAG, "   Service destroyed completely")
    }
}

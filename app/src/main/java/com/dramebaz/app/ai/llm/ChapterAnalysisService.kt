package com.dramebaz.app.ai.llm

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Runs chapter analysis (LLM/ONNX) in a background thread.
 * Previously ran in a separate :onnx process, but this caused broadcast issues
 * on Android 13+. ONNX failures are now handled with try-catch in QwenStub,
 * so running in the main process is safe.
 */
class ChapterAnalysisService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            AppLogger.e(TAG, "Service started with null intent")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val chapterPath = intent.getStringExtra(EXTRA_CHAPTER_FILE) ?: run {
            AppLogger.e(TAG, "Missing $EXTRA_CHAPTER_FILE")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val resultPath = intent.getStringExtra(EXTRA_RESULT_FILE) ?: run {
            AppLogger.e(TAG, "Missing $EXTRA_RESULT_FILE")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val extendedPath = intent.getStringExtra(EXTRA_EXTENDED_FILE)
        val broadcastAction = intent.getStringExtra(EXTRA_BROADCAST_ACTION) ?: ACTION_ANALYSIS_DONE

        Thread {
            try {
                AppLogger.i(TAG, "Starting chapter analysis")
                val chapterText = File(chapterPath).readText(charset = Charsets.UTF_8)
                AppLogger.d(TAG, "Chapter text loaded: ${chapterText.length} chars")

                // Ensure QwenStub has context set (already done in Application.onCreate but safe to repeat)
                QwenStub.setApplicationContext(applicationContext)

                // Basic analysis only (characters, dialogs, sounds, summary) - no extended analysis
                // Extended analysis (themes, symbols, vocabulary) is done via "Analyze" button in Insights tab
                val resp = runBlocking {
                    QwenStub.analyzeChapter(chapterText)
                }
                AppLogger.i(TAG, "Chapter analysis complete: dialogs=${resp.dialogs?.size}, characters=${resp.characters?.size}")

                File(resultPath).writeText(QwenStub.toJson(resp), Charsets.UTF_8)
                // Note: extendedPath file is not written - extended analysis is done separately in Insights tab
                sendBroadcastResult(broadcastAction, resultPath, extendedPath, success = true)
                AppLogger.i(TAG, "Analysis broadcast sent successfully")
            } catch (e: Throwable) {
                AppLogger.e(TAG, "Chapter analysis failed", e)
                sendBroadcastResult(broadcastAction, resultPath, extendedPath, success = false)
            }
            stopSelf(startId)
        }.start()

        return START_NOT_STICKY
    }

    private fun sendBroadcastResult(
        action: String,
        resultPath: String?,
        extendedPath: String?,
        success: Boolean
    ) {
        val i = Intent(action).apply {
            setPackage(packageName)
            putExtra(EXTRA_RESULT_FILE, resultPath)
            putExtra(EXTRA_EXTENDED_FILE, extendedPath)
            putExtra(EXTRA_SUCCESS, success)
        }
        sendBroadcast(i)
    }

    companion object {
        private const val TAG = "ChapterAnalysisService"
        const val ACTION_ANALYSIS_DONE = "com.dramebaz.app.CHAPTER_ANALYSIS_DONE"
        const val EXTRA_CHAPTER_FILE = "chapter_file"
        const val EXTRA_RESULT_FILE = "result_file"
        const val EXTRA_EXTENDED_FILE = "extended_file"
        const val EXTRA_BROADCAST_ACTION = "broadcast_action"
        const val EXTRA_SUCCESS = "success"
    }
}

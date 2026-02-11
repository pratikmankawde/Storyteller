package com.dramebaz.app.ai.audio

import android.content.Context
import android.os.Process
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages GPU shader compilation for Stable Audio.
 * 
 * Strategy:
 * 1. First run: Use CPU (fast init, ~1-2 seconds)
 * 2. Background: Compile GPU shaders with low priority (1-2 threads)
 * 3. Next run: If shaders are ready, use GPU automatically
 * 
 * This avoids the 60-90 second GPU shader compilation blocking the UI.
 */
object GpuShaderManager {
    private const val TAG = "GpuShaderManager"
    private const val PREFS_NAME = "gpu_shader_prefs"
    private const val KEY_COMPILATION_STARTED = "compilation_started"
    private const val KEY_COMPILATION_COMPLETED = "compilation_completed"
    
    private val isCompiling = AtomicBoolean(false)
    private var compilationJob: Job? = null
    
    /**
     * Check if GPU shaders are ready to use.
     * Returns true only if:
     * 1. GPU delegate is available in the build
     * 2. Shaders have been compiled and cached
     */
    fun areShadersReady(context: Context, modelDirectory: String): Boolean {
        if (!StableAudioNative.isAvailable()) return false
        if (!StableAudioNative.isGpuDelegateAvailable()) return false
        
        // Check native cache
        val nativeReady = StableAudioNative.isGpuShadersReady(modelDirectory)
        
        // Also check our completion flag
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val completedFlag = prefs.getBoolean(KEY_COMPILATION_COMPLETED, false)
        
        return nativeReady && completedFlag
    }
    
    /**
     * Check if shader compilation is currently in progress.
     */
    fun isCompilationInProgress(): Boolean = isCompiling.get()
    
    /**
     * Schedule background GPU shader compilation.
     * Uses low priority thread with 1-2 threads to minimize system impact.
     * 
     * @param context Application context
     * @param modelDirectory Path to model directory
     * @param onComplete Callback when compilation finishes (success/failure)
     */
    fun scheduleBackgroundCompilation(
        context: Context,
        modelDirectory: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (!StableAudioNative.isAvailable()) {
            AppLogger.w(TAG, "Native library not available, skipping shader compilation")
            onComplete?.invoke(false)
            return
        }
        
        if (!StableAudioNative.isGpuDelegateAvailable()) {
            AppLogger.w(TAG, "GPU delegate not available in this build")
            onComplete?.invoke(false)
            return
        }
        
        if (isCompiling.getAndSet(true)) {
            AppLogger.d(TAG, "Shader compilation already in progress")
            return
        }
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Check if already completed
        if (prefs.getBoolean(KEY_COMPILATION_COMPLETED, false) && 
            StableAudioNative.isGpuShadersReady(modelDirectory)) {
            AppLogger.d(TAG, "Shaders already compiled, skipping")
            isCompiling.set(false)
            onComplete?.invoke(true)
            return
        }
        
        // Mark compilation as started
        prefs.edit().putBoolean(KEY_COMPILATION_STARTED, true).apply()
        
        AppLogger.i(TAG, "Scheduling background GPU shader compilation...")
        
        compilationJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Set low thread priority for background work
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                
                // Determine thread count based on available processors
                // Use 1-2 threads to minimize impact on foreground tasks
                val availableProcessors = Runtime.getRuntime().availableProcessors()
                val threadCount = if (availableProcessors >= 4) 2 else 1
                
                AppLogger.i(TAG, "Starting GPU shader compilation (threads: $threadCount)")
                val startTime = System.currentTimeMillis()
                
                val success = StableAudioNative.prepareGpuShaders(modelDirectory, threadCount)
                
                val elapsed = System.currentTimeMillis() - startTime
                if (success) {
                    AppLogger.i(TAG, "✅ GPU shaders compiled in ${elapsed}ms")
                    prefs.edit().putBoolean(KEY_COMPILATION_COMPLETED, true).apply()
                } else {
                    AppLogger.e(TAG, "❌ GPU shader compilation failed after ${elapsed}ms")
                }
                
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(success)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error during shader compilation", e)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false)
                }
            } finally {
                isCompiling.set(false)
            }
        }
    }
    
    /**
     * Cancel any ongoing shader compilation.
     */
    fun cancelCompilation() {
        compilationJob?.cancel()
        isCompiling.set(false)
    }
    
    /**
     * Clear shader cache and reset compilation state.
     * Useful for debugging or forcing recompilation.
     */
    fun clearCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_COMPILATION_STARTED)
            .remove(KEY_COMPILATION_COMPLETED)
            .apply()
        AppLogger.i(TAG, "Shader cache state cleared")
    }
}


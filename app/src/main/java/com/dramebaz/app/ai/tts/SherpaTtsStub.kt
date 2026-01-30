package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.data.models.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * @deprecated Use SherpaTtsEngine instead. This stub is kept for backward compatibility.
 * T1.1: Real implementation available in SherpaTtsEngine.kt with ONNX Runtime and VITS-VCTK model.
 */
@Deprecated("Use SherpaTtsEngine instead", ReplaceWith("SherpaTtsEngine(context)"))
class SherpaTtsStub(private val context: Context) {

    private var initialized = false

    fun init(): Boolean {
        initialized = true
        return true
    }

    fun isInitialized(): Boolean = initialized

    suspend fun speak(
        text: String,
        voiceProfile: VoiceProfile?,
        onComplete: (() -> Unit)? = null
    ): Result<File?> = withContext(Dispatchers.IO) {
        if (!initialized) init()
        // Stub: no actual audio file; caller can still drive playback with system TTS or placeholder
        onComplete?.invoke()
        Result.success(null)
    }

    fun stop() {
        // no-op stub
    }
}

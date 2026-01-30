package com.dramebaz.app.ai.audio

import android.content.Context
import java.io.File

/**
 * @deprecated Use SfxEngine instead. This stub is kept for backward compatibility.
 * T3.2: Real implementation available in SfxEngine.kt with tag-based SFX library.
 */
@Deprecated("Use SfxEngine instead", ReplaceWith("SfxEngine(context)"))
class SfxStub(private val context: Context) {
    private val realEngine = SfxEngine(context)

    suspend fun resolveToFile(soundPrompt: String, durationSeconds: Float, category: String): File? {
        // Delegate to real implementation
        return realEngine.resolveToFile(soundPrompt, durationSeconds, category)
    }
}

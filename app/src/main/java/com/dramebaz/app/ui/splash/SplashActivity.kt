package com.dramebaz.app.ui.splash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.ui.main.MainActivity
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher activity: shows splash while TTS and Qwen models load, then starts MainActivity.
 * Requests storage permissions for accessing LLM model in Downloads folder.
 */
class SplashActivity : AppCompatActivity() {
    private val app get() = applicationContext as DramebazApplication
    private var statusText: TextView? = null

    // Permission request launcher for Android 11+
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Check if permission was granted after returning from settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                loadModelsAndStart()
            } else {
                // Permission denied, continue anyway (LLM will use stub fallback)
                Toast.makeText(this, "Storage permission denied - LLM will use fallback", Toast.LENGTH_SHORT).show()
                loadModelsAndStart()
            }
        }
    }

    // Legacy permission request
    private val legacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Continue regardless of result
        loadModelsAndStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        statusText = findViewById(R.id.splash_status)

        // Check and request storage permissions
        checkStoragePermissions()
    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ needs MANAGE_EXTERNAL_STORAGE for Downloads access
            if (!Environment.isExternalStorageManager()) {
                updateStatus("Requesting storage permission...")
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    manageStorageLauncher.launch(intent)
                } catch (e: Exception) {
                    // Fallback to general settings
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        manageStorageLauncher.launch(intent)
                    } catch (e2: Exception) {
                        // Can't request permission, continue anyway
                        loadModelsAndStart()
                    }
                }
            } else {
                loadModelsAndStart()
            }
        } else {
            // Android 10 and below
            val readPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            if (readPermission != PackageManager.PERMISSION_GRANTED) {
                legacyStorageLauncher.launch(arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ))
            } else {
                loadModelsAndStart()
            }
        }
    }

    private fun loadModelsAndStart() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Load TTS model
            try {
                withContext(Dispatchers.Main) { updateStatus("Loading TTS model...") }
                if (!app.ttsEngine.isInitialized()) {
                    app.ttsEngine.init()
                }
            } catch (_: OutOfMemoryError) {
                // Continue to main; TTS will fail later on low-memory devices
            }

            // Load LLM model (llama.cpp with GGUF)
            var llmLoaded = false
            var gpuStatus = "unknown"
            var isGpu = false
            try {
                withContext(Dispatchers.Main) { updateStatus("Loading LLM model...") }
                LlmService.ensureInitialized()
                llmLoaded = LlmService.isUsingLlama()
                gpuStatus = LlmService.getExecutionProvider()
                isGpu = LlmService.isUsingGpu()

                // Log GPU hand-off status
                AppLogger.i("SplashActivity", "LLM loaded: $llmLoaded, Execution provider: $gpuStatus, GPU: $isGpu")
            } catch (e: Exception) {
                // Continue to main; LLM will use stub fallback
                AppLogger.w("SplashActivity", "LLM initialization failed, using stub", e)
            }

            withContext(Dispatchers.Main) {
                // Show Toast with model load and GPU hand-off status
                val message = when {
                    llmLoaded && isGpu -> "✅ LLM loaded with GPU (Vulkan) acceleration"
                    llmLoaded -> "⚠️ LLM loaded (CPU only - Vulkan unavailable)"
                    else -> "❌ LLM Model failed to load (using stub)"
                }
                Toast.makeText(this@SplashActivity, message, Toast.LENGTH_LONG).show()
                AppLogger.i("SplashActivity", "Model status: $message")

                updateStatus("Ready!")
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun updateStatus(status: String) {
        statusText?.text = status
    }
}

package com.dramebaz.app.ui.test

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.R
import com.dramebaz.app.ai.audio.StableAudioEngine
import com.dramebaz.app.ai.audio.StableAudioModelFiles
import com.dramebaz.app.ai.audio.StableAudioNative
import com.dramebaz.app.ui.common.ShimmerProgressBar
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Test activity for Stable Audio Open Small SFX generation.
 * Uses Material 3 components and implements progress callback for detailed feedback.
 */
class StableAudioTestActivity : AppCompatActivity(), StableAudioEngine.ProgressCallback {

    companion object {
        private const val TAG = "StableAudioTest"
        private const val REQUEST_STORAGE_PERMISSION = 100
        private val MODEL_PATH = "${Environment.getExternalStorageDirectory().absolutePath}/Download/StabilityAI"
    }

    // Views
    private lateinit var toolbar: MaterialToolbar
    private lateinit var textStatus: TextView
    private lateinit var textModelPath: TextView
    private lateinit var editPrompt: TextInputEditText
    private lateinit var sliderDuration: Slider
    private lateinit var textDuration: TextView
    private lateinit var buttonInspectModels: MaterialButton
    private lateinit var buttonGenerate: MaterialButton
	    private lateinit var progressGeneration: ShimmerProgressBar
    private lateinit var textProgressStage: TextView
    private lateinit var layoutAudioList: LinearLayout
    private lateinit var textEmptyState: TextView
    private lateinit var layoutLogsHeader: View
    private lateinit var iconExpandLogs: ImageView
    private lateinit var textLogs: TextView

    private var stableAudioEngine: StableAudioEngine? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingView: View? = null
    private val generatedAudioFiles = mutableListOf<File>()
    private var isLogsExpanded = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stable_audio_test)
        setupViews()
        setupListeners()
        checkPermissionsAndInitialize()
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar)
        textStatus = findViewById(R.id.textStatus)
        textModelPath = findViewById(R.id.textModelPath)
        editPrompt = findViewById(R.id.editPrompt)
        sliderDuration = findViewById(R.id.sliderDuration)
        textDuration = findViewById(R.id.textDuration)
        buttonInspectModels = findViewById(R.id.buttonInspectModels)
        buttonGenerate = findViewById(R.id.buttonGenerate)
        progressGeneration = findViewById(R.id.progressGeneration)
        textProgressStage = findViewById(R.id.textProgressStage)
        layoutAudioList = findViewById(R.id.layoutAudioList)
        textEmptyState = findViewById(R.id.textEmptyState)
        layoutLogsHeader = findViewById(R.id.layoutLogsHeader)
        iconExpandLogs = findViewById(R.id.iconExpandLogs)
        textLogs = findViewById(R.id.textLogs)

        // Set initial values
        textModelPath.text = "Models: $MODEL_PATH"
        textDuration.text = "${sliderDuration.value}s"
    }

    private fun setupListeners() {
        // Toolbar navigation
        toolbar.setNavigationOnClickListener { finish() }

        // Duration slider
        sliderDuration.addOnChangeListener { _, value, _ ->
            textDuration.text = "${value}s"
        }

        // Inspect models button
        buttonInspectModels.setOnClickListener { inspectModels() }

        // Generate button
        buttonGenerate.setOnClickListener { generateAudio() }

        // Logs expand/collapse
        layoutLogsHeader.setOnClickListener { toggleLogs() }
    }

    private fun toggleLogs() {
        isLogsExpanded = !isLogsExpanded
        textLogs.visibility = if (isLogsExpanded) View.VISIBLE else View.GONE
        iconExpandLogs.rotation = if (isLogsExpanded) 180f else 0f
    }

    private fun inspectModels() {
        log("Inspecting models at: $MODEL_PATH")
        buttonInspectModels.isEnabled = false
        buttonInspectModels.text = "Inspecting..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val report = StableAudioNative.inspectModels(MODEL_PATH)
                withContext(Dispatchers.Main) {
                    buttonInspectModels.isEnabled = true
                    buttonInspectModels.text = "Inspect Models"
                    log("=== MODEL INSPECTION ===")
                    // Split report into lines and log each
                    report.split("\n").forEach { line ->
                        if (line.isNotBlank()) log(line)
                    }
                    // Expand logs to show report
                    if (!isLogsExpanded) toggleLogs()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Model inspection failed", e)
                withContext(Dispatchers.Main) {
                    buttonInspectModels.isEnabled = true
                    buttonInspectModels.text = "Inspect Models"
                    log("ERROR: ${e.message}")
                }
            }
        }
    }

    // ProgressCallback implementation
    override fun onProgress(progress: Float, stage: String) {
        runOnUiThread {
	            // ShimmerProgressBar expects a 0.0–1.0 value
	            progressGeneration.progress = progress.coerceIn(0f, 1f)
            textProgressStage.text = stage
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            log("ERROR: $error")
            textStatus.text = "❌ $error"
            textStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    private fun setStatusReady() {
        textStatus.text = "✅ Ready to generate"
        textStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
    }

    private fun setStatusError(message: String) {
        textStatus.text = "❌ $message"
        textStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
    }

    private fun setStatusWarning(message: String) {
        textStatus.text = "⚠️ $message"
        textStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
    }

    private fun checkPermissionsAndInitialize() {
        // Skip permission check for auto-run test - assume permission already granted
        // This allows the test to run automatically when launched via ADB
        log("Skipping permission check - starting auto-run test...")
        autoRunTest()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            // Auto-run after permission granted
            autoRunTest()
        }
    }

    /**
     * Auto-run test sequence: inspect models -> initialize -> generate with "drums"
     */
    private fun autoRunTest() {
        log("=== AUTO-RUN TEST STARTED ===")
        log("Step 1: Inspecting models...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Step 1: Inspect models
                val report = StableAudioNative.inspectModels(MODEL_PATH)
                withContext(Dispatchers.Main) {
                    log("=== MODEL INSPECTION REPORT ===")
                    report.split("\n").forEach { line ->
                        if (line.isNotBlank()) log(line)
                    }
                    // Expand logs to show report
                    if (!isLogsExpanded) toggleLogs()
                }

                // Step 2: Initialize engine
                withContext(Dispatchers.Main) {
                    log("Step 2: Initializing engine...")
                }
                initializeEngineAndGenerate()

            } catch (e: Exception) {
                AppLogger.e(TAG, "Auto-run model inspection failed", e)
                withContext(Dispatchers.Main) {
                    log("ERROR inspecting models: ${e.message}")
                    // Still try to initialize even if inspection fails
                    initializeEngine()
                }
            }
        }
    }

    /**
     * Initialize engine and then auto-generate with "drums" prompt
     */
    private suspend fun initializeEngineAndGenerate() {
        val modelDir = File(MODEL_PATH)

        // Check if directory exists
        if (!modelDir.exists()) {
            withContext(Dispatchers.Main) {
                log("ERROR: Model directory not found!")
                setStatusError("Model directory not found")
            }
            return
        }

        // Check for required files
        val missingFiles = StableAudioModelFiles.getMissingFiles(modelDir)
        if (missingFiles.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                log("Missing model files: ${missingFiles.joinToString(", ")}")
                setStatusError("Missing: ${missingFiles.joinToString(", ")}")
            }
            return
        }

        withContext(Dispatchers.Main) {
            log("All model files found. Initializing engine...")
            textStatus.text = "Initializing tokenizer..."
        }

        // Initialize the engine
        try {
            stableAudioEngine = StableAudioEngine(this@StableAudioTestActivity)
            stableAudioEngine!!.setProgressCallback(this@StableAudioTestActivity)

            val success = stableAudioEngine!!.initializeSmart(MODEL_PATH, forceCpu = true)

            withContext(Dispatchers.Main) {
                if (success) {
                    val implType = stableAudioEngine!!.getImplementationType()
                    val isNative = stableAudioEngine!!.isNativeAvailable()
                    val isGpu = stableAudioEngine!!.isGpuEnabled()
                    log("✅ StableAudioEngine initialized")
                    log("Implementation: $implType")
                    log("Accelerator: ${if (isGpu) "GPU" else "CPU"}")

                    textStatus.text = if (isNative) {
                        val accel = if (isGpu) "GPU" else "CPU"
                        "✅ Ready (Native C++ - $accel)"
                    } else {
                        "✅ Ready (Kotlin API)"
                    }
                    textStatus.setTextColor(ContextCompat.getColor(
                        this@StableAudioTestActivity,
                        android.R.color.holo_green_dark
                    ))
                    buttonGenerate.isEnabled = true

                    // Step 3: Auto-generate with "drums" prompt
                    log("Step 3: Auto-generating audio with prompt 'drums'...")
                    editPrompt.setText("drums")
                    sliderDuration.value = 2.0f
                    generateAudio()
                } else {
                    val error = stableAudioEngine?.getLastError() ?: "Unknown error"
                    log("ERROR: Failed to initialize - $error")
                    setStatusError(error)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Engine initialization failed", e)
            withContext(Dispatchers.Main) {
                log("ERROR: ${e.message}")
                setStatusError("Init failed: ${e.message?.take(50)}")
            }
        }
    }

    private fun initializeEngine() {
        log("Checking model files at: $MODEL_PATH")
        lifecycleScope.launch(Dispatchers.IO) {
            val modelDir = File(MODEL_PATH)

            // Check if directory exists
            if (!modelDir.exists()) {
                withContext(Dispatchers.Main) {
                    log("ERROR: Model directory not found!")
                    setStatusError("Model directory not found")
                }
                return@launch
            }

            // Check for required files
            val missingFiles = StableAudioModelFiles.getMissingFiles(modelDir)
            if (missingFiles.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    log("Missing model files: ${missingFiles.joinToString(", ")}")
                    setStatusError("Missing: ${missingFiles.joinToString(", ")}")
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                log("All model files found. Initializing engine...")
                textStatus.text = "Initializing tokenizer..."
            }

            // Initialize the engine with smart GPU management
            try {
                stableAudioEngine = StableAudioEngine(this@StableAudioTestActivity)
                stableAudioEngine!!.setProgressCallback(this@StableAudioTestActivity)

                // Use smart initialization: CPU first, GPU shaders compiled in background
                // DEBUG: Force CPU mode to test CPU-specific crash
                val success = stableAudioEngine!!.initializeSmart(MODEL_PATH, forceCpu = true)

                withContext(Dispatchers.Main) {
                    if (success) {
                        val implType = stableAudioEngine!!.getImplementationType()
                        val isNative = stableAudioEngine!!.isNativeAvailable()
                        val isGpu = stableAudioEngine!!.isGpuEnabled()
                        log("✅ StableAudioEngine initialized")
                        log("Implementation: $implType")
                        log("Accelerator: ${if (isGpu) "GPU" else "CPU"}")

                        textStatus.text = if (isNative) {
                            val accel = if (isGpu) "GPU" else "CPU"
                            "✅ Ready (Native C++ - $accel)"
                        } else {
                            "✅ Ready (Kotlin API)"
                        }
                        textStatus.setTextColor(ContextCompat.getColor(
                            this@StableAudioTestActivity,
                            android.R.color.holo_green_dark
                        ))
                        buttonGenerate.isEnabled = true
                    } else {
                        val error = stableAudioEngine?.getLastError() ?: "Unknown error"
                        log("ERROR: Failed to initialize - $error")
                        setStatusError(error)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Engine initialization failed", e)
                withContext(Dispatchers.Main) {
                    log("ERROR: ${e.message}")
                    setStatusError("Init failed: ${e.message?.take(50)}")
                }
            }
        }
    }

    private fun generateAudio() {
        val prompt = editPrompt.text?.toString()?.trim() ?: ""
        if (prompt.isEmpty()) {
            Toast.makeText(this, "Please enter a sound description", Toast.LENGTH_SHORT).show()
            return
        }

        val duration = sliderDuration.value
        log("Generating: \"$prompt\" (${duration}s)")

        buttonGenerate.isEnabled = false
        progressGeneration.visibility = View.VISIBLE
	        progressGeneration.progress = 0f
        textProgressStage.visibility = View.VISIBLE
        textProgressStage.text = "Starting..."
        textStatus.text = "🔄 Generating audio..."

        // Acquire wake lock to prevent CPU from sleeping during generation
        acquireWakeLock()

        lifecycleScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val audioFile = stableAudioEngine?.generateAudio(prompt, duration)
                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0

                withContext(Dispatchers.Main) {
                    releaseWakeLock()
                    progressGeneration.visibility = View.GONE
                    textProgressStage.visibility = View.GONE
                    buttonGenerate.isEnabled = true

                    if (audioFile != null && audioFile.exists()) {
                        log("✅ Generated in ${String.format("%.1f", elapsedSec)}s: ${audioFile.name}")
                        textStatus.text = "✅ Generated in ${String.format("%.1f", elapsedSec)}s"
                        textStatus.setTextColor(ContextCompat.getColor(this@StableAudioTestActivity, android.R.color.holo_green_dark))
                        addAudioToList(audioFile, prompt)
                    } else {
                        val error = stableAudioEngine?.getLastError() ?: "Generation failed"
                        log("ERROR: $error")
                        setStatusError(error)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Audio generation failed", e)
                withContext(Dispatchers.Main) {
                    releaseWakeLock()
                    progressGeneration.visibility = View.GONE
                    textProgressStage.visibility = View.GONE
                    buttonGenerate.isEnabled = true
                    log("ERROR: ${e.message}")
                    setStatusError("Error: ${e.message?.take(50)}")
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StableAudioTest:GenerationWakeLock"
            )
        }
        wakeLock?.let {
            if (!it.isHeld) {
                // Acquire with timeout of 10 minutes max (safety net)
                it.acquire(10 * 60 * 1000L)
                log("Wake lock acquired")
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                log("Wake lock released")
            }
        }
    }

    private fun addAudioToList(audioFile: File, prompt: String) {
        generatedAudioFiles.add(0, audioFile)

        // Hide empty state
        textEmptyState.visibility = View.GONE

        // Create Material 3 card for audio item
        val cardView = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
            radius = 12f * resources.displayMetrics.density
            cardElevation = 2f * resources.displayMetrics.density
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // File name with icon
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_music_note)
            layoutParams = LinearLayout.LayoutParams(24, 24).apply { marginEnd = 8 }
            imageTintList = ContextCompat.getColorStateList(this@StableAudioTestActivity, android.R.color.holo_purple)
        })
        titleRow.addView(TextView(this).apply {
            text = audioFile.name
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
        })
        contentLayout.addView(titleRow)

        // Prompt text
        contentLayout.addView(TextView(this).apply {
            text = "\"${prompt.take(80)}${if (prompt.length > 80) "..." else ""}\""
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@StableAudioTestActivity, android.R.color.darker_gray))
            setPadding(0, 4, 0, 8)
        })

        // Button row using Material buttons
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        val playButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Play"
            setIconResource(R.drawable.ic_play_circle)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            setOnClickListener { playAudio(audioFile, this) }
        }
        buttonRow.addView(playButton)

        val stopButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Stop"
            setIconResource(R.drawable.ic_stop_circle)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            setOnClickListener { stopAudio() }
        }
        buttonRow.addView(stopButton)

        contentLayout.addView(buttonRow)
        cardView.addView(contentLayout)

        // Add at top of list
        layoutAudioList.addView(cardView, 0)
    }

    private fun playAudio(file: File, playButton: MaterialButton) {
        try {
            stopAudio() // Stop any currently playing

            log("Playing: ${file.name}")
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    runOnUiThread {
                        (currentPlayingView as? MaterialButton)?.apply {
                            text = "Play"
                            setIconResource(R.drawable.ic_play_circle)
                        }
                        currentPlayingView = null
                    }
                    release()
                    mediaPlayer = null
                    log("Playback completed")
                }
                setOnErrorListener { _, what, extra ->
                    AppLogger.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    runOnUiThread {
                        (currentPlayingView as? MaterialButton)?.apply {
                            text = "Play"
                            setIconResource(R.drawable.ic_play_circle)
                        }
                        currentPlayingView = null
                        log("ERROR: Playback failed (code=$what)")
                    }
                    release()
                    mediaPlayer = null
                    true
                }
                start()
            }

            currentPlayingView = playButton
            playButton.text = "Playing..."
            playButton.setIconResource(R.drawable.ic_stop_circle)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error playing audio", e)
            log("ERROR: ${e.message}")
            Toast.makeText(this, "Error playing audio: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAudio() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error stopping audio", e)
            }
        }
        mediaPlayer = null
        (currentPlayingView as? MaterialButton)?.apply {
            text = "Play"
            setIconResource(R.drawable.ic_play_circle)
        }
        currentPlayingView = null
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread {
            val currentText = textLogs.text?.toString() ?: ""
            val newText = if (currentText.isEmpty()) {
                "[$timestamp] $message"
            } else {
                "[$timestamp] $message\n$currentText"
            }
            // Keep only last 25 lines
            val lines = newText.split("\n").take(25)
            textLogs.text = lines.joinToString("\n")
        }
        AppLogger.d(TAG, message)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        stopAudio()
        stableAudioEngine?.release()
        stableAudioEngine = null
    }
}


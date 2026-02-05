package com.dramebaz.app.ui.test

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.domain.usecases.ImportBookUseCase
import com.dramebaz.app.ui.main.MainActivity
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Test entry: lists test actions for each feature/milestone.
 * Run the corresponding test after implementing each task.
 */
class TestActivity : AppCompatActivity() {
    private val app get() = applicationContext as DramebazApplication
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var logPane: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.setPadding(48, 48, 48, 48)

        fun addTest(name: String, block: () -> Unit) {
            val b = Button(this).apply {
                text = name
                setOnClickListener { block() }
            }
            layout.addView(b)
        }

        addTest("Open Main (Library)", { startActivity(Intent(this, MainActivity::class.java)) })
        addTest("Load demo book (Space story.pdf)", { loadDemoBook() })
        addTest("M1: Import & Library", { startActivity(Intent(this, MainActivity::class.java)) })
        addTest("M2: LLM Test - Chapter Analysis", { testLlmAnalysis() })
        addTest("M2: LLM Test - Extended Analysis", { testLlmExtended() })
        addTest("M3: TTS Test - Simple", { testTts("Hello, this is a test of the text to speech engine.") })
        addTest("M3: TTS Test - Long Text", { testTts("The quick brown fox jumps over the lazy dog. This is a longer sentence to test the text to speech synthesis capabilities of the Piper VCTK medium model.") })
        addTest("M4: Character voices (stub)", { startActivity(Intent(this, MainActivity::class.java)) })
        addTest("M5: Sound cues (stub)", { startActivity(Intent(this, MainActivity::class.java)) })
        addTest("M6: Characters & Insights", { startActivity(Intent(this, MainActivity::class.java)) })
        addTest("M7: Themes & bookmarks", { startActivity(Intent(this, MainActivity::class.java)) })

        layout.addView(TextView(this).apply {
            text = "Space story.pdf is bundled with the APK. Tap \"Load demo book\" to copy it into the app and add to Library, then open Main to read/analyze. TTS tests save to app Download folder. Logs below."
            setPadding(0, 24, 0, 0)
        })
        layout.addView(TextView(this).apply {
            text = "Test log:"
            setPadding(0, 16, 0, 4)
        })
        logPane = TextView(this).apply {
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xFFEEEEEE.toInt())
            minLines = 12
            text = "(Run a test to see logs here.)"
        }
        layout.addView(logPane)
        scroll.addView(layout)
        setContentView(scroll)
    }

    /** Copy bundled Space story.pdf from assets to app files and import as book. */
    private fun loadDemoBook() {
        log("Demo: copying Space story.pdf from assets...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val destDir = File(getExternalFilesDir(null), "demo").apply { mkdirs() }
                val destFile = File(destDir, "Space story.pdf")
                assets.open("demo/Space story.pdf").use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                log("Demo: copied to ${destFile.absolutePath}")

                // Delete existing book with the same title (re-import with proper PDF extraction)
                val existingBook = app.bookRepository.findBookByTitle("Space story")
                if (existingBook != null) {
                    log("Demo: deleting existing book (id=${existingBook.id}) to re-import with PDF extraction...")
                    app.bookRepository.deleteBookWithChapters(existingBook.id)
                }

                val importUseCase = ImportBookUseCase(app.bookRepository)
                val bookId = importUseCase.importFromFile(this@TestActivity, destFile.absolutePath, "pdf")
                withContext(Dispatchers.Main) {
                    if (bookId > 0) {
                        log("Demo: imported bookId=$bookId (Space story). Open Library to see it.")
                        Toast.makeText(this@TestActivity, "Demo book imported (bookId=$bookId). Open Library.", Toast.LENGTH_LONG).show()
                    } else {
                        log("Demo: import failed (bookId=$bookId)")
                        Toast.makeText(this@TestActivity, "Demo book import failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("TestActivity", "Load demo book failed", e)
                log("Demo: ERROR ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TestActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $msg"
            logPane.text = (logPane.text?.toString()?.takeIf { it != "(Run a test to see logs here.)" } ?: "").let { prev ->
                if (prev.isEmpty()) line else "$prev\n$line"
            }
        }
    }

    private fun testTts(text: String) {
        log("TTS: starting (text length=${text.length})")
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@TestActivity, "Synthesizing speech...", Toast.LENGTH_SHORT).show()
            }
            try {
                if (!app.ttsEngine.isInitialized()) {
                    log("TTS: initializing engine...")
                    val initialized = try {
                        app.ttsEngine.init()
                    } catch (e: OutOfMemoryError) {
                        AppLogger.e("TestActivity", "TTS init OOM on device", e)
                        log("TTS: ERROR Out of memory during init")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@TestActivity, "TTS needs more memory (try on emulator or high-RAM device)", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    if (!initialized) {
                        log("TTS: init failed")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@TestActivity, "Failed to initialize TTS engine", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    log("TTS: init OK")
                }
                log("TTS: synthesizing...")
                val result = try {
                    app.ttsEngine.speak(text, null, null, null)
                } catch (e: OutOfMemoryError) {
                    AppLogger.e("TestActivity", "TTS synthesis OOM", e)
                    log("TTS: ERROR Out of memory during synthesis")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TestActivity, "TTS ran out of memory", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                result.onSuccess { audioFile ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TestActivity, "TTS synthesis completed", Toast.LENGTH_SHORT).show()
                        audioFile?.let { file ->
                            log("TTS: synthesis OK, file=${file.absolutePath}, size=${file.length()}")
                            val savedPath = saveTtsToDownloads(file)
                            if (savedPath != null) {
                                log("TTS: saved to Downloads: $savedPath")
                            } else {
                                log("TTS: could not save to Downloads (dir unavailable)")
                            }
                            playAudioFile(file.absolutePath)
                            Toast.makeText(this@TestActivity, "Playing audio: ${file.name}", Toast.LENGTH_SHORT).show()
                            log("TTS: playing ${file.name}")
                        } ?: run {
                            log("TTS: no audio file generated")
                            Toast.makeText(this@TestActivity, "TTS completed but no audio file generated", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                result.onFailure { error ->
                    AppLogger.e("TestActivity", "TTS synthesis failed", error)
                    val msg = error.message ?: error.toString()
                    log("TTS: FAILED $msg")
                    error.cause?.let { log("TTS: cause: ${it.message}") }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TestActivity, "TTS failed: ${msg.take(80)}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("TestActivity", "TTS test error", e)
                val msg = e.message ?: e.toString()
                log("TTS: ERROR $msg")
                e.cause?.let { log("TTS: cause: ${it.message}") }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TestActivity, "TTS error: ${msg.take(80)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Copies TTS output to app's Download folder. Returns destination path or null. */
    private fun saveTtsToDownloads(audioFile: File): String? {
        if (!audioFile.exists()) return null
        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: getExternalFilesDir(null)?.let { File(it, "Download").apply { mkdirs() } } ?: return null
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) return null
        val dest = File(downloadsDir, "tts_test_${System.currentTimeMillis()}.wav")
        return try {
            audioFile.copyTo(dest, overwrite = true)
            dest.absolutePath
        } catch (e: Exception) {
            AppLogger.e("TestActivity", "Failed to copy to Downloads", e)
            null
        }
    }

    private fun playAudioFile(filePath: String) {
        try {
            // Stop any currently playing audio
            mediaPlayer?.release()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                setOnCompletionListener {
                    release()
                    mediaPlayer = null
                    Toast.makeText(this@TestActivity, "Audio playback completed", Toast.LENGTH_SHORT).show()
                }
                setOnErrorListener { _, what, extra ->
                    AppLogger.e("TestActivity", "MediaPlayer error: what=$what, extra=$extra")
                    release()
                    mediaPlayer = null
                    Toast.makeText(this@TestActivity, "Audio playback error", Toast.LENGTH_SHORT).show()
                    true
                }
                start()
            }
        } catch (e: Exception) {
            AppLogger.e("TestActivity", "Error playing audio file", e)
            Toast.makeText(this@TestActivity, "Error playing audio: ${e.message}", Toast.LENGTH_LONG).show()
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    private fun testLlmAnalysis() {
        log("LLM: starting chapter analysis (Space story - Chapter 2)")
        lifecycleScope.launch {
            Toast.makeText(this@TestActivity, "Testing LLM chapter analysis with Space story...", Toast.LENGTH_SHORT).show()
            // Using Chapter 2: The Hydra's Riddle from Space story.pdf
            val testChapter = """
                Chapter 2: The Hydra's Riddle

                The trek through the Whispering Jungles was a test of nerves. Lyra was distracted every five
                minutes, trying to pet Echo-Foxes—creatures that could mimic a person's deepest secrets.

                "Don't listen to it, Zane!" Lyra laughed as a fox chirped in Zane's own voice about his secret fear
                of space-toasters.

                Suddenly, the ground shook. They reached the Chasm of Glass, where the Hydra waited. It
                wasn't a beast of flesh, but of translucent quartz, its three heads refracting the sunlight into
                blinding lasers.

                "Standard protocol?" Kael asked, his arm transforming into a kinetic shield.

                "Standard protocol," Jax smirked. "Zane, cause a distraction. Lyra, find its weak
                spot. Kael, try not to get shattered."

                The battle was a chaotic dance. The Hydra fired beams of concentrated violet light that turned
                the ground to magma. Zane threw "glitter-bombs"—conductive dust that scrambled the Hydra's
                internal resonance—while Lyra noticed the beast's heads pulsed in rhythm with the wind.

                "It's not attacking!" Lyra shouted over the roar. "It's harmonizing! We're out of tune!"

                Taking the hint, Jax grabbed a discarded metal plate and began drumming a counter-rhythm.
                The Hydra paused, its crystalline scales dimming from an angry red to a calm azure. It lowered
                its heads, allowing them to pass over the bridge of its own back.
            """.trimIndent()
            try {
                val result = LlmService.analyzeChapter(testChapter)
                val characters = result.characters?.map { it.name }?.joinToString(", ") ?: "none"
                val dialogs = result.dialogs?.size ?: 0
                val message = if (result.chapterSummary != null) {
                    "LLM Analysis Success!\nSummary: ${result.chapterSummary.shortSummary.take(80)}...\nCharacters: $characters\nDialogs: $dialogs"
                } else {
                    "LLM Analysis returned null (using stub fallback)"
                }
                log("LLM: $message")
                Toast.makeText(this@TestActivity, message, Toast.LENGTH_LONG).show()
                AppLogger.i("TestActivity", "LLM Analysis Result: characters=$characters, dialogs=$dialogs")
            } catch (e: Exception) {
                AppLogger.e("TestActivity", "LLM analysis failed", e)
                log("LLM: FAILED ${e.message}")
                Toast.makeText(this@TestActivity, "LLM Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun testLlmExtended() {
        log("LLM: starting extended analysis (Space story - Chapter 3)")
        lifecycleScope.launch {
            Toast.makeText(this@TestActivity, "Testing LLM extended analysis with Space story...", Toast.LENGTH_SHORT).show()
            // Using Chapter 3: The Ascent of the Void-Griffin from Space story.pdf
            val testChapter = """
                Chapter 3: The Ascent of the Void-Griffin

                The final stretch took them to the Floating Spires, islands of rock held aloft by the planet's
                massive magnetic core. To reach the Aether-Pulse at the summit, they had to bypass the
                Void-Griffin, a creature of literal starlight and shadow.

                As they climbed the gravity-defying stairs, the air grew thin. The Griffin descended, its wings
                spanning thirty feet, shedding feathers of pure dark matter. It didn't attack; it simply stood
                between them and the glowing orb of the Pulse.

                "It requires a trade," Mina whispered. "The Griffin doesn't want gold. It wants a memory.
                Something heavy enough to ground it to this world."

                The crew looked at each other. Jax stepped forward, but Kael stopped him. The cyborg touched
                the Griffin's beak. He uploaded a file from his neural core—the memory of his first day as a
                human, before the augmentations. A memory of feeling the sun on skin he no longer
                possessed.

                The Griffin let out a haunting, melodic shriek and dissipated into a cloud of stardust, leaving the
                Aether-Pulse pulsing gently on its pedestal.

                "Let's get out of here," Jax said, his voice unusually soft. "I think the Rambler has had enough
                adventure for one day."

                With the Pulse in hand and Mina's blessing, they hiked back to the wreckage. By the violet sun
                dipped below the horizon, the Stardust Rambler wasn't just flying—it was glowing,
                trailing a wake of Aurelian light as it pierced the atmosphere.
            """.trimIndent()
            try {
                val result = LlmService.extendedAnalysisJson(testChapter)
                val message = if (result.isNotBlank() && !result.contains("stub")) {
                    "Extended Analysis Success!\nResult length: ${result.length} chars"
                } else {
                    "Extended Analysis returned stub (model not loaded)"
                }
                log("LLM extended: $message")
                Toast.makeText(this@TestActivity, message, Toast.LENGTH_LONG).show()
                AppLogger.i("TestActivity", "Extended Analysis Result: ${result.take(300)}")
            } catch (e: Exception) {
                AppLogger.e("TestActivity", "Extended analysis failed", e)
                log("LLM extended: FAILED ${e.message}")
                Toast.makeText(this@TestActivity, "Extended Analysis Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

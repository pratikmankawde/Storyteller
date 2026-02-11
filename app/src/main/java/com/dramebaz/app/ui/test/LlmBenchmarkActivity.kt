package com.dramebaz.app.ui.test

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.ai.llm.pipeline.IncrementalMerger
import com.dramebaz.app.ai.llm.tasks.BatchedChapterAnalysisTask
import com.dramebaz.app.ai.llm.tasks.TaskProgress
import com.dramebaz.app.pdf.PdfChapterDetector
import com.dramebaz.app.pdf.PdfExtractor
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LLM Benchmark Activity - Tests character and dialog extraction against expected results.
 *
 * Uses the SpaceStory.pdf demo book and compares extraction results to SpaceStoryAnalysis.json.
 * Reports precision, recall, and F1 score for both characters and dialogs.
 * Tracks timing for each pass and saves results for comparison with future runs.
 * Uses Material 3 design components.
 */
class LlmBenchmarkActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LlmBenchmarkActivity"
        private const val PDF_ASSET = "demo/SpaceStory.pdf"
        private const val EXPECTED_JSON_ASSET = "demo/SpaceStoryAnalysis.json"
        private const val BENCHMARK_HISTORY_FILE = "benchmark_history.json"
    }

    private val app get() = applicationContext as DramebazApplication
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var logPane: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var btnRun: MaterialButton
    private lateinit var statusText: TextView

    // Expected data from JSON
    data class ExpectedData(
        val characters: List<String>,
        val chapters: List<ChapterData>
    )
    data class ChapterData(
        val chapter: Int,
        val title: String,
        val characters: List<String>? = null,
        val dialogs: List<DialogData>
    )
    data class DialogData(
        val speaker: String,
        val text: String
    )

    // Timing data for each batch
    data class BatchTiming(
        val batchIndex: Int,
        val durationMs: Long,
        val charactersFound: Int,
        val dialogsFound: Int
    )

    // Benchmark results with detailed timing
    data class BenchmarkResult(
        val timestamp: String,
        val modelName: String,
        val backend: String,
        val characterPrecision: Float,
        val characterRecall: Float,
        val characterF1: Float,
        val dialogPrecision: Float,
        val dialogRecall: Float,
        val dialogF1: Float,
        val pdfExtractionTimeMs: Long,
        val batchedAnalysisTimeMs: Long,
        val totalTimeMs: Long,
        val extractedCharacters: List<String>,
        val expectedCharacters: List<String>,
        val extractedDialogs: Int,
        val expectedDialogs: Int,
        val batchCount: Int,
        val batchTiming: List<BatchTiming>
    )

    // Benchmark history for comparison
    data class BenchmarkHistory(
        val results: MutableList<BenchmarkResult> = mutableListOf()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_benchmark)

        // Setup toolbar
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        // Get views
        statusText = findViewById(R.id.textStatus)
        btnRun = findViewById(R.id.btnRun)
        progressBar = findViewById(R.id.progressBar)
        logPane = findViewById(R.id.textResults)

        // Setup button click
        btnRun.setOnClickListener { runBenchmark() }

        updateModelStatus()
    }

    private fun updateModelStatus() {
        val isReady = LlmService.isReady()
        val provider = LlmService.getExecutionProvider()
        val modelName = if (isReady) LlmService.getModelCapabilities().modelName else "Not loaded"
        statusText.text = "Model: $modelName\nBackend: $provider\nStatus: ${if (isReady) "✓ Ready" else "✗ Not Ready"}"
    }

    private fun log(msg: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$timestamp $msg"
            val current = logPane.text?.toString() ?: ""
            logPane.text = if (current == "(Tap 'Run Benchmark' to start)") line else "$current\n$line"
            AppLogger.d(TAG, msg)
        }
    }

    private fun updateBenchmarkProgress(percent: Int) {
        runOnUiThread { progressBar.progress = percent }
    }

    private fun runBenchmark() {
        if (!LlmService.isReady()) {
            Toast.makeText(this, "LLM model not loaded. Please load a model in Settings first.", Toast.LENGTH_LONG).show()
            return
        }

        btnRun.isEnabled = false
        progressBar.visibility = android.view.View.VISIBLE
        logPane.text = ""
        log("Starting benchmark...")

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { performBenchmark() }
                saveBenchmarkResult(result)
                displayResults(result)
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                AppLogger.e(TAG, "Benchmark failed", e)
            } finally {
                withContext(Dispatchers.Main) {
                    btnRun.isEnabled = true
                    progressBar.visibility = android.view.View.GONE
                }
            }
        }
    }

    private suspend fun performBenchmark(): BenchmarkResult {
        val overallStartTime = System.currentTimeMillis()
        val batchTimingList = mutableListOf<BatchTiming>()

        // Load expected data
        log("Loading expected results from $EXPECTED_JSON_ASSET...")
        val expectedJson = assets.open(EXPECTED_JSON_ASSET).bufferedReader().readText()
        val expected = Gson().fromJson(expectedJson, ExpectedData::class.java)
        log("Expected: ${expected.characters.size} characters, ${expected.chapters.sumOf { it.dialogs.size }} dialogs")
        updateBenchmarkProgress(5)

        // Copy PDF from assets and extract text
        log("Extracting text from $PDF_ASSET...")
        val pdfStartTime = System.currentTimeMillis()
        val pdfFile = copyAssetToTemp(PDF_ASSET)
        val pdfExtractor = PdfExtractor(this)
        val pages = pdfExtractor.extractText(pdfFile)
        val pdfExtractionTime = System.currentTimeMillis() - pdfStartTime
        log("Extracted ${pages.size} PDF pages in ${pdfExtractionTime}ms")
        updateBenchmarkProgress(10)

        // Get the LLM model
        val model = LlmService.getModel()
        if (model == null) {
            log("ERROR: LLM model not available")
            throw IllegalStateException("LLM model not loaded")
        }

        val modelName = LlmService.getModelCapabilities().modelName
        val backend = LlmService.getExecutionProvider()

        // Create benchmark cache directory
        val benchmarkCacheDir = File(cacheDir, "benchmark_analysis").apply { mkdirs() }

        // Run batched analysis on full book content
        log("═══ BATCHED CHAPTER ANALYSIS (New Pipeline) ═══")
        val analysisStartTime = System.currentTimeMillis()

        // Collect characters and dialogs across all batches
        var lastCharacters: List<IncrementalMerger.MergedCharacterData> = emptyList()
        var batchStartTime = System.currentTimeMillis()
        var totalBatchCount = 0

        // Create the batched task - using bookId=0 and chapterId=0 for benchmark
        val task = BatchedChapterAnalysisTask(
            bookId = 0L,
            chapterId = 0L,
            chapterTitle = "SpaceStory (Benchmark)",
            rawPages = pages,
            cacheDir = benchmarkCacheDir,
            onBatchComplete = { batchData ->
                totalBatchCount = batchData.totalBatches
                val batchDuration = System.currentTimeMillis() - batchStartTime
                val dialogCount = batchData.characters.sumOf { it.dialogs.size }
                batchTimingList.add(BatchTiming(batchData.batchIndex, batchDuration, batchData.characters.size, dialogCount))
                log("  Batch ${batchData.batchIndex + 1}/${batchData.totalBatches}: ${batchData.characters.size} chars, $dialogCount dialogs in ${batchDuration}ms")
                lastCharacters = batchData.characters
                batchStartTime = System.currentTimeMillis()
            }
        )

        // Execute the task with progress callback
        val result = task.execute(model) { progress: TaskProgress ->
            // Map batch progress to overall progress (15-90%)
            val mappedProgress = 15 + (progress.percent * 75 / 100)
            updateBenchmarkProgress(mappedProgress)
            log("Progress: ${progress.message}")
        }

        val analysisTime = System.currentTimeMillis() - analysisStartTime

        if (!result.success) {
            log("ERROR: Batched analysis failed: ${result.error}")
            throw IllegalStateException("Batched analysis failed: ${result.error}")
        }

        // Extract results
        val extractedCharacters = lastCharacters.map { it.name }.toSet()
        val extractedDialogs = lastCharacters.flatMap { char ->
            char.dialogs.map { Pair(char.name, it) }
        }

        log("Batched analysis complete: ${extractedCharacters.size} characters, ${extractedDialogs.size} dialogs in ${analysisTime}ms ($totalBatchCount batches)")
        updateBenchmarkProgress(90)

        // Calculate metrics
        val expectedCharSet = expected.characters.map { it.lowercase() }.toSet()
        val extractedCharSet = extractedCharacters.map { it.lowercase() }.toSet()

        val charTruePositives = extractedCharSet.intersect(expectedCharSet).size.toFloat()
        val charPrecision = if (extractedCharSet.isNotEmpty()) charTruePositives / extractedCharSet.size else 0f
        val charRecall = if (expectedCharSet.isNotEmpty()) charTruePositives / expectedCharSet.size else 0f
        val charF1 = if (charPrecision + charRecall > 0) 2 * charPrecision * charRecall / (charPrecision + charRecall) else 0f

        // Dialog matching (fuzzy - check if expected dialog text is contained in extracted)
        val expectedDialogTexts = expected.chapters.flatMap { ch -> ch.dialogs.map { it.text.lowercase().trim() } }
        val extractedDialogTexts = extractedDialogs.map { it.second.lowercase().trim() }

        var dialogMatches = 0
        for (expectedText in expectedDialogTexts) {
            val found = extractedDialogTexts.any { extracted ->
                extracted.contains(expectedText.take(30)) || expectedText.contains(extracted.take(30))
            }
            if (found) dialogMatches++
        }

        val dialogPrecision = if (extractedDialogs.isNotEmpty()) dialogMatches.toFloat() / extractedDialogs.size else 0f
        val dialogRecall = if (expectedDialogTexts.isNotEmpty()) dialogMatches.toFloat() / expectedDialogTexts.size else 0f
        val dialogF1 = if (dialogPrecision + dialogRecall > 0) 2 * dialogPrecision * dialogRecall / (dialogPrecision + dialogRecall) else 0f

        val totalTime = System.currentTimeMillis() - overallStartTime
        updateBenchmarkProgress(100)

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        return BenchmarkResult(
            timestamp = timestamp,
            modelName = modelName,
            backend = backend,
            characterPrecision = charPrecision,
            characterRecall = charRecall,
            characterF1 = charF1,
            dialogPrecision = dialogPrecision,
            dialogRecall = dialogRecall,
            dialogF1 = dialogF1,
            pdfExtractionTimeMs = pdfExtractionTime,
            batchedAnalysisTimeMs = analysisTime,
            totalTimeMs = totalTime,
            extractedCharacters = extractedCharacters.toList(),
            expectedCharacters = expected.characters,
            extractedDialogs = extractedDialogs.size,
            expectedDialogs = expectedDialogTexts.size,
            batchCount = totalBatchCount,
            batchTiming = batchTimingList
        )
    }

    private fun copyAssetToTemp(assetPath: String): File {
        val tempDir = File(cacheDir, "benchmark").apply { mkdirs() }
        val fileName = assetPath.substringAfterLast("/")
        val tempFile = File(tempDir, fileName)
        assets.open(assetPath).use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }

    private fun saveBenchmarkResult(result: BenchmarkResult) {
        try {
            val historyFile = File(filesDir, BENCHMARK_HISTORY_FILE)
            val history = loadBenchmarkHistory()
            history.results.add(result)
            // Keep only last 10 results
            while (history.results.size > 10) {
                history.results.removeAt(0)
            }
            historyFile.writeText(gson.toJson(history))
            log("Benchmark result saved to history (${history.results.size} total)")
        } catch (e: Exception) {
            log("Failed to save benchmark result: ${e.message}")
            AppLogger.e(TAG, "Failed to save benchmark result", e)
        }
    }

    private fun loadBenchmarkHistory(): BenchmarkHistory {
        return try {
            val historyFile = File(filesDir, BENCHMARK_HISTORY_FILE)
            if (historyFile.exists()) {
                gson.fromJson(historyFile.readText(), BenchmarkHistory::class.java)
            } else {
                BenchmarkHistory()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load benchmark history", e)
            BenchmarkHistory()
        }
    }

    private fun displayResults(result: BenchmarkResult) {
        val history = loadBenchmarkHistory()
        val previousResult = if (history.results.size >= 2) {
            history.results[history.results.size - 2]
        } else null

        val sb = StringBuilder()
        sb.appendLine("\n════════════════════════════════════")
        sb.appendLine("       BENCHMARK RESULTS")
        sb.appendLine("    (Batched Pipeline - New Flow)")
        sb.appendLine("════════════════════════════════════")
        sb.appendLine("Timestamp: ${result.timestamp}")
        sb.appendLine("Model: ${result.modelName}")
        sb.appendLine("Backend: ${result.backend}")
        sb.appendLine()

        // Timing section
        sb.appendLine("─── TIMING ───")
        sb.appendLine("PDF Extraction:       ${formatMs(result.pdfExtractionTimeMs)}${deltaStr(previousResult?.pdfExtractionTimeMs, result.pdfExtractionTimeMs, true)}")
        sb.appendLine("Batched Analysis:     ${formatMs(result.batchedAnalysisTimeMs)}${deltaStr(previousResult?.batchedAnalysisTimeMs, result.batchedAnalysisTimeMs, true)}")
        sb.appendLine("TOTAL:                ${formatMs(result.totalTimeMs)}${deltaStr(previousResult?.totalTimeMs, result.totalTimeMs, true)}")
        sb.appendLine("Batch Count:          ${result.batchCount}")
        sb.appendLine()

        // Batch details (show first 5 + last)
        if (result.batchTiming.isNotEmpty()) {
            sb.appendLine("─── BATCH DETAILS ───")
            val batchesToShow = if (result.batchTiming.size <= 6) {
                result.batchTiming
            } else {
                result.batchTiming.take(5) + listOf(result.batchTiming.last())
            }
            for (batch in batchesToShow) {
                sb.appendLine("Batch ${batch.batchIndex + 1}: ${batch.charactersFound} chars, ${batch.dialogsFound} dialogs in ${formatMs(batch.durationMs)}")
            }
            if (result.batchTiming.size > 6) {
                sb.appendLine("... (${result.batchTiming.size - 6} more batches)")
            }
            sb.appendLine()
        }

        // Character metrics
        sb.appendLine("─── CHARACTER METRICS ───")
        sb.appendLine("Extracted: ${result.extractedCharacters.size} | Expected: ${result.expectedCharacters.size}")
        sb.appendLine("Precision: ${formatPercent(result.characterPrecision)}${deltaStr(previousResult?.characterPrecision, result.characterPrecision, false)}")
        sb.appendLine("Recall:    ${formatPercent(result.characterRecall)}${deltaStr(previousResult?.characterRecall, result.characterRecall, false)}")
        sb.appendLine("F1 Score:  ${formatPercent(result.characterF1)}${deltaStr(previousResult?.characterF1, result.characterF1, false)}")
        sb.appendLine("Extracted: ${result.extractedCharacters}")
        sb.appendLine("Expected:  ${result.expectedCharacters}")
        sb.appendLine()

        // Dialog metrics
        sb.appendLine("─── DIALOG METRICS ───")
        sb.appendLine("Extracted: ${result.extractedDialogs} | Expected: ${result.expectedDialogs}")
        sb.appendLine("Precision: ${formatPercent(result.dialogPrecision)}${deltaStr(previousResult?.dialogPrecision, result.dialogPrecision, false)}")
        sb.appendLine("Recall:    ${formatPercent(result.dialogRecall)}${deltaStr(previousResult?.dialogRecall, result.dialogRecall, false)}")
        sb.appendLine("F1 Score:  ${formatPercent(result.dialogF1)}${deltaStr(previousResult?.dialogF1, result.dialogF1, false)}")
        sb.appendLine()

        // Comparison with previous run
        if (previousResult != null) {
            sb.appendLine("─── COMPARISON WITH PREVIOUS RUN ───")
            sb.appendLine("Previous run: ${previousResult.timestamp}")
            val timeDiff = result.totalTimeMs - previousResult.totalTimeMs
            val timeImprovement = if (timeDiff < 0) "✓ ${-timeDiff}ms faster" else "✗ ${timeDiff}ms slower"
            sb.appendLine("Time: $timeImprovement")

            val f1Diff = result.characterF1 - previousResult.characterF1
            val charImprovement = if (f1Diff >= 0) "✓ +${formatPercent(f1Diff)}" else "✗ ${formatPercent(f1Diff)}"
            sb.appendLine("Character F1: $charImprovement")

            val dialogF1Diff = result.dialogF1 - previousResult.dialogF1
            val dialogImprovement = if (dialogF1Diff >= 0) "✓ +${formatPercent(dialogF1Diff)}" else "✗ ${formatPercent(dialogF1Diff)}"
            sb.appendLine("Dialog F1: $dialogImprovement")
        } else {
            sb.appendLine("(No previous run to compare)")
        }
        sb.appendLine("════════════════════════════════════")

        log(sb.toString())
    }

    private fun formatMs(ms: Long): String {
        return if (ms >= 1000) {
            String.format(Locale.US, "%.2fs", ms / 1000.0)
        } else {
            "${ms}ms"
        }
    }

    private fun formatPercent(value: Float): String {
        return String.format(Locale.US, "%.1f%%", value * 100)
    }

    private fun deltaStr(previous: Long?, current: Long, lowerIsBetter: Boolean): String {
        if (previous == null) return ""
        val diff = current - previous
        return if (diff == 0L) " (=)"
        else if ((diff < 0) == lowerIsBetter) " (↓${kotlin.math.abs(diff)}ms ✓)"
        else " (↑${kotlin.math.abs(diff)}ms)"
    }

    private fun deltaStr(previous: Float?, current: Float, lowerIsBetter: Boolean): String {
        if (previous == null) return ""
        val diff = current - previous
        val diffPercent = String.format(Locale.US, "%.1f%%", kotlin.math.abs(diff) * 100)
        return if (kotlin.math.abs(diff) < 0.001f) " (=)"
        else if ((diff < 0) == lowerIsBetter) " (↓$diffPercent)"
        else if ((diff > 0) == !lowerIsBetter) " (↑$diffPercent ✓)"
        else " (↓$diffPercent)"
    }
}

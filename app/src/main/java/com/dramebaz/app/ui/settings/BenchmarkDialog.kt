package com.dramebaz.app.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.ai.llm.LlmService
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SETTINGS-004: System Benchmark Dialog.
 * Displays CPU/memory metrics, model loading status, and compute benchmarks.
 */
class BenchmarkDialog : DialogFragment() {

    companion object {
        private const val TAG = "BenchmarkDialog"
        fun newInstance(): BenchmarkDialog = BenchmarkDialog()
    }

    private val app get() = requireContext().applicationContext as DramebazApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        // Title
        layout.addView(TextView(requireContext()).apply {
            text = "System Benchmark"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        // Divider
        layout.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { setMargins(0, 24, 0, 24) }
            setBackgroundColor(0x33FFFFFF)
        })

        // Results TextView
        val resultsText = TextView(requireContext()).apply {
            text = "Loading..."
            textSize = 14f
            setLineSpacing(8f, 1f)
        }
        layout.addView(resultsText)

        // Progress bar
        val progressBar = ProgressBar(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 16) }
            visibility = View.GONE
        }
        layout.addView(progressBar)

        // Button row
        val buttonRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 32, 0, 0) }
        }

        val btnRefresh = MaterialButton(requireContext()).apply {
            text = "Refresh"
            setOnClickListener { runBenchmark(resultsText, progressBar, this) }
        }
        buttonRow.addView(btnRefresh)

        val btnClose = MaterialButton(requireContext()).apply {
            text = "Close"
            setOnClickListener { dismiss() }
        }
        buttonRow.addView(btnClose)

        layout.addView(buttonRow)

        // Run benchmark on dialog open
        runBenchmark(resultsText, progressBar, btnRefresh)

        return layout
    }

    private fun runBenchmark(resultsText: TextView, progressBar: ProgressBar, btnRefresh: MaterialButton) {
        progressBar.visibility = View.VISIBLE
        btnRefresh.isEnabled = false
        resultsText.text = "Running benchmark..."

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { performBenchmark() }
            resultsText.text = result
            progressBar.visibility = View.GONE
            btnRefresh.isEnabled = true
        }
    }

    private fun performBenchmark(): String {
        val sb = StringBuilder()

        // CPU info
        val cpuCores = Runtime.getRuntime().availableProcessors()
        sb.appendLine("═══ CPU ═══")
        sb.appendLine("Cores: $cpuCores")

        // Memory info
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val sysTotal = memInfo.totalMem / (1024 * 1024)
        val sysAvail = memInfo.availMem / (1024 * 1024)

        sb.appendLine("\n═══ Memory ═══")
        sb.appendLine("App: ${usedMemory}MB / ${maxMemory}MB")
        sb.appendLine("System: ${sysTotal - sysAvail}MB / ${sysTotal}MB")
        sb.appendLine("Available: ${sysAvail}MB")

        // Model status
        sb.appendLine("\n═══ Models ═══")
        sb.appendLine("TTS: ${if (app.ttsEngine.isInitialized()) "✓ Ready" else "✗ Not Loaded"}")
        sb.appendLine("LLM: ${if (LlmService.isReady()) "✓ Ready" else "✗ Not Loaded"}")
        sb.appendLine("Provider: ${LlmService.getExecutionProvider()}")

        // Compute benchmark
        val t0 = System.nanoTime()
        var sum = 0.0
        for (i in 0 until 1_000_000) sum += kotlin.math.sin(i.toDouble())
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        sb.appendLine("\n═══ Compute ═══")
        sb.appendLine("1M sin ops: ${String.format("%.1f", elapsedMs)}ms")
        val tier = when { cpuCores >= 8 && maxMemory >= 4096 -> "High-End"; cpuCores >= 4 && maxMemory >= 2048 -> "Mid-Range"; else -> "Entry-Level" }
        sb.appendLine("Device Tier: $tier")

        return sb.toString()
    }
}


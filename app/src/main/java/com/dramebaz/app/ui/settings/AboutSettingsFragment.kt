package com.dramebaz.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.BuildConfig
import com.dramebaz.app.R
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SETTINGS-001: About tab fragment.
 * Shows app info, version, and benchmark functionality.
 */
class AboutSettingsFragment : Fragment() {
    
    companion object {
        private const val TAG = "AboutSettingsFragment"
    }
    
    private lateinit var versionText: TextView
    private lateinit var benchmarkResult: TextView
    private lateinit var btnRunBenchmark: MaterialButton
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.settings_tab_about, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        versionText = view.findViewById(R.id.version_text)
        benchmarkResult = view.findViewById(R.id.benchmark_result)
        btnRunBenchmark = view.findViewById(R.id.btn_run_benchmark)

        // Set version
        versionText.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)

        // SETTINGS-004: Open BenchmarkDialog for detailed system diagnostics
        btnRunBenchmark.setOnClickListener {
            BenchmarkDialog.newInstance().show(childFragmentManager, "BenchmarkDialog")
        }

        // Also run quick inline benchmark for quick stats display
        runBenchmark()
    }
    
    private fun runBenchmark() {
        btnRunBenchmark.isEnabled = false
        benchmarkResult.text = "Running benchmark..."
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    performBenchmark()
                }
                benchmarkResult.text = result
                AppLogger.d(TAG, "Benchmark completed: $result")
            } catch (e: Exception) {
                benchmarkResult.text = "Benchmark failed: ${e.message}"
                AppLogger.e(TAG, "Benchmark failed", e)
            } finally {
                btnRunBenchmark.isEnabled = true
            }
        }
    }
    
    private fun performBenchmark(): String {
        val results = StringBuilder()
        
        // Memory info
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        val totalMemory = runtime.totalMemory() / (1024 * 1024)
        val freeMemory = runtime.freeMemory() / (1024 * 1024)
        val usedMemory = totalMemory - freeMemory
        
        results.appendLine("Memory: ${usedMemory}MB / ${maxMemory}MB")
        
        // CPU cores
        val cpuCores = runtime.availableProcessors()
        results.appendLine("CPU Cores: $cpuCores")
        
        // Simple compute benchmark
        val startTime = System.nanoTime()
        var sum = 0.0
        for (i in 0 until 1_000_000) {
            sum += kotlin.math.sin(i.toDouble())
        }
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0
        results.appendLine("Compute: ${String.format("%.1f", elapsedMs)}ms (1M ops)")
        
        // Device capability assessment
        val capability = when {
            cpuCores >= 8 && maxMemory >= 4096 -> "High-End"
            cpuCores >= 4 && maxMemory >= 2048 -> "Mid-Range"
            else -> "Entry-Level"
        }
        results.appendLine("Device Tier: $capability")
        
        return results.toString().trim()
    }
}


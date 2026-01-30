package com.dramebaz.app.util

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.material.appbar.MaterialToolbar

/**
 * Utility class to monitor and display memory usage on a toolbar subtitle.
 * Updates every 2 seconds with app and system memory info.
 */
class MemoryMonitor(
    private val context: Context,
    private val toolbar: MaterialToolbar
) {
    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 2000L // 2 seconds
    private var isRunning = false
    
    private val memoryRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                updateMemoryDisplay()
                handler.postDelayed(this, updateInterval)
            }
        }
    }
    
    /**
     * Start monitoring memory and updating the toolbar subtitle.
     */
    fun start() {
        if (!isRunning) {
            isRunning = true
            handler.post(memoryRunnable)
        }
    }
    
    /**
     * Stop monitoring memory.
     */
    fun stop() {
        isRunning = false
        handler.removeCallbacks(memoryRunnable)
    }
    
    private fun updateMemoryDisplay() {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        
        // Get system memory info
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val availableSystemMemory = memoryInfo.availMem / (1024 * 1024)
        val totalSystemMemory = memoryInfo.totalMem / (1024 * 1024)
        val usedSystemMemory = totalSystemMemory - availableSystemMemory
        
        // Calculate percentage
        val appPercent = (usedMemory * 100 / maxMemory).toInt()
        val sysPercent = (usedSystemMemory * 100 / totalSystemMemory).toInt()
        
        // Format: "Mem: 123MB (24%) | Sys: 4.2GB (52%)"
        val appMemStr = "${usedMemory}MB ($appPercent%)"
        val sysMemStr = "%.1fGB (%d%%)".format(usedSystemMemory / 1024.0, sysPercent)
        
        toolbar.subtitle = "App: $appMemStr | Sys: $sysMemStr"
    }
    
    companion object {
        /**
         * Get current memory stats as a formatted string.
         */
        fun getMemoryStats(context: Context): String {
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val maxMemory = runtime.maxMemory() / (1024 * 1024)
            
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            val availableSystemMemory = memoryInfo.availMem / (1024 * 1024)
            val totalSystemMemory = memoryInfo.totalMem / (1024 * 1024)
            
            return "App: ${usedMemory}/${maxMemory}MB | Sys Free: ${availableSystemMemory}/${totalSystemMemory}MB"
        }
    }
}

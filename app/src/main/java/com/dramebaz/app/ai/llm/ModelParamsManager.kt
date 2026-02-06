package com.dramebaz.app.ai.llm

import android.content.Context
import android.os.Environment
import com.dramebaz.app.data.models.ModelParams
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Manager for loading and saving model parameters from/to an external JSON file.
 * 
 * The parameters file is stored at a user-accessible location so users can:
 * 1. Edit parameters via the in-app dialog
 * 2. Manually edit the JSON file for advanced tuning
 * 
 * Default location: Downloads/Dramebaz/model_params.json
 * Fallback for testing: D:\Learning\Ai\Models\LLM\model_params.json
 */
object ModelParamsManager {
    private const val TAG = "ModelParamsManager"
    private const val PARAMS_FILE_NAME = "model_params.json"
    private const val APP_FOLDER_NAME = "Dramebaz"
    
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    
    @Volatile
    private var cachedParams: ModelParams? = null
    
    /**
     * Get the external params file path.
     * Tries Downloads/Dramebaz folder first, with fallback to app external files.
     */
    fun getParamsFilePath(context: Context): File {
        // Try Downloads folder first
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appFolder = File(downloadsDir, APP_FOLDER_NAME)
        
        // Create directory if it doesn't exist
        if (!appFolder.exists()) {
            appFolder.mkdirs()
        }
        
        return File(appFolder, PARAMS_FILE_NAME)
    }
    
    /**
     * Load model parameters from the external JSON file.
     * Returns cached params if available, otherwise loads from disk.
     * Falls back to defaults if file doesn't exist or parsing fails.
     */
    fun loadParams(context: Context): ModelParams {
        cachedParams?.let { return it }
        
        val paramsFile = getParamsFilePath(context)
        
        return try {
            if (paramsFile.exists()) {
                val json = paramsFile.readText()
                val params = gson.fromJson(json, ModelParams::class.java)?.validated()
                    ?: ModelParams.DEFAULT
                AppLogger.i(TAG, "Loaded model params from ${paramsFile.absolutePath}")
                cachedParams = params
                params
            } else {
                AppLogger.i(TAG, "No params file found, using defaults. Path: ${paramsFile.absolutePath}")
                // Create default file for user reference
                saveParams(context, ModelParams.DEFAULT)
                ModelParams.DEFAULT
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading model params, using defaults", e)
            ModelParams.DEFAULT
        }
    }
    
    /**
     * Save model parameters to the external JSON file.
     */
    fun saveParams(context: Context, params: ModelParams): Boolean {
        val paramsFile = getParamsFilePath(context)
        
        return try {
            // Ensure parent directory exists
            paramsFile.parentFile?.mkdirs()
            
            val json = gson.toJson(params.validated())
            paramsFile.writeText(json)
            cachedParams = params.validated()
            AppLogger.i(TAG, "Saved model params to ${paramsFile.absolutePath}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error saving model params", e)
            false
        }
    }
    
    /**
     * Clear cached params to force reload from disk.
     */
    fun clearCache() {
        cachedParams = null
    }
    
    /**
     * Get the cached params or load from disk.
     */
    fun getParams(context: Context): ModelParams {
        return cachedParams ?: loadParams(context)
    }
    
    /**
     * Check if a custom params file exists.
     */
    fun hasCustomParams(context: Context): Boolean {
        return getParamsFilePath(context).exists()
    }
    
    /**
     * Reset params to defaults by deleting the external file.
     */
    fun resetToDefaults(context: Context): Boolean {
        val paramsFile = getParamsFilePath(context)
        return try {
            if (paramsFile.exists()) {
                paramsFile.delete()
            }
            cachedParams = null
            AppLogger.i(TAG, "Reset model params to defaults")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error resetting model params", e)
            false
        }
    }
}


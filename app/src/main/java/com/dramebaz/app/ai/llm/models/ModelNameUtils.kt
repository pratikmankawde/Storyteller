package com.dramebaz.app.ai.llm.models

import java.io.File
import java.util.Locale

/**
 * Utility class for deriving clean display names from model file paths.
 * Centralizes the name formatting logic to avoid duplication across engine implementations.
 *
 * Design Pattern: Utility/Helper class with static methods.
 */
object ModelNameUtils {

    /**
     * Derive a clean, properly cased display name from the model file path.
     *
     * Examples:
     * - "gemma-3n-E2B-it-int4.task" -> "Gemma 3n E2B It (INT4)"
     * - "phi-4-mini-instruct-int4.task" -> "Phi 4 Mini Instruct (INT4)"
     * - "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm" -> "Qwen2.5 1.5B Instruct (Q8)"
     * - "llama-3.2-1b-instruct-q4_k_m.gguf" -> "Llama 3.2 1b Instruct (Q4_K_M)"
     *
     * @param filePath Full path or just the filename of the model
     * @return Clean display name with proper casing and quantization suffix
     */
    fun deriveDisplayName(filePath: String): String {
        val fileName = File(filePath).nameWithoutExtension

        // Extract quantization suffix patterns
        val quantPatterns = listOf(
            // Matches patterns like "-int4", "-int8", "_int4", "_int8"
            Regex("[-_](int[48])$", RegexOption.IGNORE_CASE),
            // Matches patterns like "-q4_k_m", "-q8_0", "_q4", "_q8"
            Regex("[-_](q[48]_?[kK]?_?[mMsS]?)$", RegexOption.IGNORE_CASE),
            // Matches LiteRT-LM patterns like "_q8_ekv4096", "_q4_ekv4096"
            Regex("_(q[48])_ekv[0-9]+$", RegexOption.IGNORE_CASE)
        )

        var baseName = fileName
        var quantSuffix = ""

        // Try to extract quantization suffix
        for (pattern in quantPatterns) {
            val match = pattern.find(fileName)
            if (match != null) {
                quantSuffix = " (${match.groupValues[1].uppercase(Locale.ROOT)})"
                baseName = fileName.substring(0, match.range.first)
                break
            }
        }

        // Clean up common suffixes that shouldn't appear in display name
        baseName = baseName
            .replace("_multi-prefill-seq", "")
            .replace("-multi-prefill-seq", "")

        // Convert separators to spaces and clean up
        val words = baseName
            .replace("-", " ")
            .replace("_", " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .map { word -> formatWord(word) }

        return (words.joinToString(" ") + quantSuffix).trim()
    }

    /**
     * Format a single word with proper casing.
     * - Preserve acronyms that are all uppercase (e.g., "E2B", "GPU")
     * - Preserve version numbers (e.g., "3n", "1.5B", "2.5")
     * - Capitalize first letter of regular words
     */
    private fun formatWord(word: String): String {
        // If it's all uppercase and short (like "E2B", "GPU"), preserve it
        if (word.length <= 4 && word.all { it.isUpperCase() || it.isDigit() }) {
            return word
        }

        // If it contains digits mixed with letters (like "3n", "1.5B", "2.5"), preserve original
        if (word.any { it.isDigit() } && word.any { it.isLetter() }) {
            return word
        }

        // If it's a pure number, preserve it
        if (word.all { it.isDigit() || it == '.' }) {
            return word
        }

        // Standard word - capitalize first letter
        return word.replaceFirstChar { 
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() 
        }
    }

    /**
     * Get a default display name for a model type when no file path is available.
     *
     * @param modelType The type of model engine
     * @return Default display name
     */
    fun getDefaultName(modelType: String): String {
        return when (modelType.lowercase()) {
            "gguf" -> "GGUF Model"
            "litertlm" -> "LiteRT-LM Model"
            "mediapipe" -> "MediaPipe Model"
            else -> "Unknown Model"
        }
    }
}


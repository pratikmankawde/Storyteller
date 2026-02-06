package com.dramebaz.app.data.models

import com.google.gson.annotations.SerializedName

/**
 * VIS-001: Scene Prompt Generation
 * 
 * Data class representing a generated image prompt for scene visualization.
 * Contains the main prompt, negative prompt, and style hints for image generation.
 * 
 * Based on NovelReaderWeb generateScenePrompt() functionality.
 */
data class ScenePrompt(
    /** The main positive prompt describing what should be in the image */
    @SerializedName("prompt")
    val prompt: String,
    
    /** Negative prompt describing what to avoid in the image */
    @SerializedName("negative_prompt")
    val negativePrompt: String = "blurry, low quality, distorted, text, watermark, signature",
    
    /** Art style hints (e.g., "oil painting", "digital art", "anime") */
    @SerializedName("style")
    val style: String = "detailed digital illustration",
    
    /** Detected mood from the scene (affects lighting/color palette) */
    @SerializedName("mood")
    val mood: String = "neutral",
    
    /** List of characters present in the scene */
    @SerializedName("characters")
    val characters: List<String> = emptyList(),
    
    /** Scene setting/location */
    @SerializedName("setting")
    val setting: String = "",
    
    /** Time of day (affects lighting) */
    @SerializedName("time_of_day")
    val timeOfDay: String = "",
    
    /** Suggested aspect ratio for the image */
    @SerializedName("aspect_ratio")
    val aspectRatio: String = "16:9"
) {
    companion object {
        /** Default prompts for common scene types */
        val STYLE_PRESETS = mapOf(
            "fantasy" to "fantasy art, magical atmosphere, detailed environment",
            "scifi" to "sci-fi art, futuristic, high tech, cinematic lighting",
            "horror" to "dark atmosphere, ominous shadows, unsettling mood",
            "romance" to "warm lighting, soft colors, intimate setting",
            "mystery" to "noir style, dramatic shadows, atmospheric",
            "action" to "dynamic composition, motion blur, intense lighting"
        )
        
        /** Common negative prompts */
        val DEFAULT_NEGATIVE = "blurry, low quality, distorted, text, watermark, signature, deformed, bad anatomy"
    }
    
    /**
     * Returns a combined prompt string suitable for Stable Diffusion or similar models.
     */
    fun toFullPrompt(): String {
        val parts = mutableListOf(prompt)
        if (style.isNotBlank()) parts.add(style)
        if (mood.isNotBlank() && mood != "neutral") parts.add("$mood atmosphere")
        if (timeOfDay.isNotBlank()) parts.add("$timeOfDay lighting")
        return parts.joinToString(", ")
    }
}


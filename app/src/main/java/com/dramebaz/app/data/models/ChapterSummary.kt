package com.dramebaz.app.data.models

import com.google.gson.annotations.SerializedName

data class ChapterSummary(
    val title: String = "",
    @SerializedName("short_summary") val shortSummary: String = "",
    @SerializedName("main_events") val mainEvents: List<String> = emptyList(),
    @SerializedName("emotional_arc") val emotionalArc: List<EmotionalSegment> = emptyList()
)

data class EmotionalSegment(
    val segment: String = "",
    val emotion: String = "",
    val intensity: Float = 0f
)

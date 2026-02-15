package com.dramebaz.app.ui.insights

import android.app.ProgressDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.ai.llm.pipeline.ForeshadowingDetectionPass
import com.dramebaz.app.ai.llm.pipeline.PassConfig
import com.dramebaz.app.ai.llm.pipeline.PlotPointExtractionPass
import com.dramebaz.app.ai.llm.prompts.ForeshadowingInput
import com.dramebaz.app.ai.llm.prompts.PlotPointInput
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.data.models.EmotionalSegment
import com.dramebaz.app.data.models.Foreshadowing
import com.dramebaz.app.data.models.PlotOutlineResult
import com.dramebaz.app.data.models.PlotPoint
import com.dramebaz.app.data.models.ReadingLevel
import com.dramebaz.app.data.models.SentimentDistribution
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * T9.3: Insights tab – emotional graph, themes list, vocabulary builder.
 * AUG-022: Emotional arc visualization
 * AUG-027: Reading statistics dashboard
 * AUG-028: Vocabulary builder with definitions
 * AUG-029: Themes and symbols analysis
 */
class InsightsFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val vm: InsightsViewModel by viewModels { InsightsViewModel.Factory(app.bookRepository) }
    private var bookId: Long = 0L
    private val gson = Gson()

    // AUG-028: Vocabulary data
    data class VocabWord(val word: String, val definition: String, var learned: Boolean = false)
    private var allVocabulary = mutableListOf<VocabWord>()
    private var showOnlyUnlearned = false
    private val learnedWordsKey get() = "learned_words_$bookId"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = arguments?.getLong("bookId", 0L) ?: 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_insights, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val themes = view.findViewById<TextView>(R.id.themes)
        val emotionalHint = view.findViewById<TextView>(R.id.emotional_hint)
        val emotionalArcContainer = view.findViewById<LinearLayout>(R.id.emotional_arc_container)

        // INS-001: Enhanced emotional arc views
        val emotionalArcView = view.findViewById<EmotionalArcView>(R.id.emotional_arc_view)
        val emotionLegend = view.findViewById<LinearLayout>(R.id.emotion_legend)

        // AUG-027: Statistics views
        val statChapters = view.findViewById<TextView>(R.id.stat_chapters)
        val statCharacters = view.findViewById<TextView>(R.id.stat_characters)
        val statDialogs = view.findViewById<TextView>(R.id.stat_dialogs)

        // AUG-028: Vocabulary views
        val vocabularyContainer = view.findViewById<LinearLayout>(R.id.vocabulary_container)
        val vocabularyCount = view.findViewById<TextView>(R.id.vocabulary_count)
        val chipUnlearned = view.findViewById<Chip>(R.id.chip_show_unlearned)
        val btnExportVocab = view.findViewById<Button>(R.id.btn_export_vocab)

        // AUG-029: Symbols and foreshadowing views
        val symbolsLabel = view.findViewById<TextView>(R.id.symbols_label)
        val symbolsText = view.findViewById<TextView>(R.id.symbols)
        val foreshadowingLabel = view.findViewById<TextView>(R.id.foreshadowing_label)
        val foreshadowingText = view.findViewById<TextView>(R.id.foreshadowing)
        // INS-002: Foreshadowing timeline visualization
        val foreshadowingView = view.findViewById<ForeshadowingView>(R.id.foreshadowing_view)

        // INS-003: Sentiment distribution views
        val sentimentCard = view.findViewById<MaterialCardView>(R.id.sentiment_card)
        val sentimentView = view.findViewById<SentimentDistributionView>(R.id.sentiment_distribution_view)
        val sentimentToneChip = view.findViewById<Chip>(R.id.sentiment_tone_chip)

        // INS-004: Reading level views
        val readingLevelContainer = view.findViewById<LinearLayout>(R.id.reading_level_container)
        val readingLevelDescription = view.findViewById<TextView>(R.id.reading_level_description)
        val readingLevelChip = view.findViewById<Chip>(R.id.reading_level_chip)
        val complexityBreakdown = view.findViewById<LinearLayout>(R.id.complexity_breakdown)
        val statReadingEase = view.findViewById<TextView>(R.id.stat_reading_ease)
        val statAvgSentence = view.findViewById<TextView>(R.id.stat_avg_sentence)
        val statVocabComplexity = view.findViewById<TextView>(R.id.stat_vocab_complexity)

        // INS-005: Plot outline views
        val plotOutlineCard = view.findViewById<MaterialCardView>(R.id.plot_outline_card)
        val plotOutlineView = view.findViewById<PlotOutlineView>(R.id.plot_outline_view)
        val plotOutlineDescription = view.findViewById<TextView>(R.id.plot_outline_description)
        val plotPointsContainer = view.findViewById<LinearLayout>(R.id.plot_points_container)

        // AUG-028: Load learned words from preferences
        val prefs = requireContext().getSharedPreferences("vocabulary_prefs", 0)
        val learnedSet = prefs.getStringSet(learnedWordsKey, emptySet()) ?: emptySet()

        // AUG-028: Filter chip toggle
        chipUnlearned?.setOnCheckedChangeListener { _, isChecked ->
            showOnlyUnlearned = isChecked
            displayVocabularyCards(vocabularyContainer, vocabularyCount)
        }

        // AUG-028: Export button
        btnExportVocab?.setOnClickListener { exportVocabulary() }

        // Extended analysis button - analyze themes/symbols/vocab for all chapters
        val btnAnalyzeThemes = view.findViewById<MaterialButton>(R.id.btn_analyze_themes)
        btnAnalyzeThemes?.setOnClickListener {
            runExtendedAnalysisForAllChapters(
                learnedSet, themes, vocabularyContainer, vocabularyCount,
                symbolsLabel, symbolsText, foreshadowingLabel, foreshadowingText, foreshadowingView,
                emotionalHint, emotionalArcContainer, emotionalArcView, emotionLegend,
                sentimentCard, sentimentView, sentimentToneChip,
                plotOutlineCard, plotOutlineView, plotOutlineDescription, plotPointsContainer
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Load statistics and reading level (INS-004)
            loadStatistics(
                statChapters, statCharacters, statDialogs,
                readingLevelContainer, readingLevelDescription, readingLevelChip,
                complexityBreakdown, statReadingEase, statAvgSentence, statVocabComplexity
            )

            // Load insights data and extended analysis
            var data = withContext(Dispatchers.IO) { vm.insightsForBook(bookId) }
            themes.text = data.themes.ifEmpty { "No themes yet (run chapter analysis)." }

            // AUG-028/029: Load vocabulary and symbols from extended analysis
            loadVocabularyAndSymbols(learnedSet, vocabularyContainer, vocabularyCount, symbolsLabel, symbolsText, foreshadowingLabel, foreshadowingText, foreshadowingView)

            // AUG-022 + INS-001: Load emotional arc with enhanced view
            // INS-003: Also load sentiment distribution
            loadEmotionalArc(emotionalHint, emotionalArcContainer, emotionalArcView, emotionLegend, sentimentCard, sentimentView, sentimentToneChip)

            // INS-005: Load plot outline
            loadPlotOutline(plotOutlineCard, plotOutlineView, plotOutlineDescription, plotPointsContainer)

            // Lazy-load extended analysis for first chapter that needs it (one LLM call), then refresh
            val updated = withContext(Dispatchers.IO) { vm.ensureExtendedAnalysisForFirstNeeding(bookId) }
            if (updated && isAdded) {
                data = withContext(Dispatchers.IO) { vm.insightsForBook(bookId) }
                themes.text = data.themes.ifEmpty { "No themes yet." }
                loadVocabularyAndSymbols(learnedSet, vocabularyContainer, vocabularyCount, symbolsLabel, symbolsText, foreshadowingLabel, foreshadowingText, foreshadowingView)
                loadEmotionalArc(emotionalHint, emotionalArcContainer, emotionalArcView, emotionLegend, sentimentCard, sentimentView, sentimentToneChip)
                loadPlotOutline(plotOutlineCard, plotOutlineView, plotOutlineDescription, plotPointsContainer)
                Toast.makeText(requireContext(), "Insights updated.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // AUG-027: Load reading statistics
    // INS-004: Also calculate and display reading level
    private suspend fun loadStatistics(
        chaptersView: TextView,
        charactersView: TextView,
        dialogsView: TextView,
        readingLevelContainer: LinearLayout? = null,
        readingLevelDescription: TextView? = null,
        readingLevelChip: Chip? = null,
        complexityBreakdown: LinearLayout? = null,
        statReadingEase: TextView? = null,
        statAvgSentence: TextView? = null,
        statVocabComplexity: TextView? = null
    ) {
        withContext(Dispatchers.IO) {
            // BLOB-FIX: Use lightweight projection for chapter count and dialog count
            val chapterWithAnalysisList = app.bookRepository.getChaptersWithAnalysis(bookId)
            val characterList = app.db.characterDao().getByBookId(bookId).first()
            val chapters = chapterWithAnalysisList.size
            val characters = characterList.size
            // Estimate dialogs from chapter analysis using ChapterAnalysisResponse
            var dialogCount = 0
            chapterWithAnalysisList.forEach { chapter ->
                chapter.fullAnalysisJson?.let { json ->
                    try {
                        val analysis = gson.fromJson(json, com.dramebaz.app.ai.llm.ChapterAnalysisResponse::class.java)
                        dialogCount += analysis?.dialogs?.size ?: 0
                    } catch (e: Exception) { }
                }
            }

            // INS-004: Calculate reading level from chapter text samples
            // BLOB-FIX: Load chapters one at a time to avoid CursorWindow overflow
            // Sample up to first 3 chapters or 50k chars total to avoid performance issues
            val textBuilder = StringBuilder()
            val chapterIds = chapterWithAnalysisList.sortedBy { it.orderIndex }.map { it.id }
            for (chapterId in chapterIds) {
                if (textBuilder.length >= 50000) break
                val chapter = app.bookRepository.getChapter(chapterId) ?: continue
                textBuilder.append(chapter.body).append(" ")
            }
            val allText = textBuilder.toString()
            val readingLevel = if (allText.length > 100) {
                ReadingLevel.analyze(allText.take(50000))
            } else null

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                chaptersView.text = chapters.toString()
                charactersView.text = characters.toString()
                dialogsView.text = dialogCount.toString()

                // INS-004: Display reading level
                readingLevel?.let { level ->
                    readingLevelContainer?.visibility = View.VISIBLE
                    complexityBreakdown?.visibility = View.VISIBLE

                    readingLevelChip?.text = level.gradeDescription
                    readingLevelChip?.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                        getReadingLevelColor(level.gradeLevel)
                    )
                    readingLevelChip?.setTextColor(Color.WHITE)

                    readingLevelDescription?.text = buildString {
                        append("Flesch-Kincaid Grade Level: ${String.format("%.1f", level.gradeLevel)}")
                    }

                    statReadingEase?.text = level.readingEaseScore.toInt().toString()
                    statAvgSentence?.text = String.format("%.1f", level.avgSentenceLength)
                    statVocabComplexity?.text = "${level.vocabularyComplexity.toInt()}%"
                }
            }
        }
    }

    // INS-004: Get color based on reading grade level
    private fun getReadingLevelColor(gradeLevel: Float): Int {
        return when {
            gradeLevel < 6f -> Color.parseColor("#4CAF50")  // Green - easy
            gradeLevel < 9f -> Color.parseColor("#8BC34A")  // Light green
            gradeLevel < 12f -> Color.parseColor("#FFC107") // Amber - medium
            gradeLevel < 14f -> Color.parseColor("#FF9800") // Orange
            else -> Color.parseColor("#F44336")             // Red - advanced
        }
    }

    // INS-005: Load and display plot outline
    private suspend fun loadPlotOutline(
        card: MaterialCardView?,
        plotView: PlotOutlineView?,
        description: TextView?,
        pointsContainer: LinearLayout?
    ) {
        if (card == null || plotView == null) return

        withContext(Dispatchers.IO) {
            // BLOB-FIX: Use lightweight projection first to get chapter count
            val chapterSummaries = app.bookRepository.chapterSummariesList(bookId).sortedBy { it.orderIndex }
            val totalChapters = chapterSummaries.size
            if (totalChapters < 3) {
                // Need at least 3 chapters for meaningful plot extraction
                withContext(Dispatchers.Main) {
                    if (isAdded) card.visibility = View.GONE
                }
                return@withContext
            }

            // BLOB-FIX: Load chapters one at a time for plot extraction
            val chapterBodies = mutableListOf<Pair<Int, String>>()
            for ((idx, summary) in chapterSummaries.withIndex()) {
                val chapter = app.bookRepository.getChapter(summary.id) ?: continue
                chapterBodies.add(idx to chapter.body)
            }

            // Use the new modular PlotPointExtractionPass
            val plotPoints = try {
                val model = LlmService.getModel()
                if (model != null) {
                    val pass = PlotPointExtractionPass()
                    val input = PlotPointInput(
                        bookId = bookId,
                        chapters = chapterBodies
                    )
                    val output = pass.execute(model, input, PassConfig())
                    output.plotPoints.map { it.copy(bookId = bookId) }
                } else {
                    // Fallback to stub when no model available
                    PlotPointExtractionPass.generateStubPlotPoints(bookId, totalChapters)
                }
            } catch (e: Exception) {
                AppLogger.e("InsightsFragment", "Plot point extraction failed", e)
                PlotPointExtractionPass.generateStubPlotPoints(bookId, totalChapters)
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (plotPoints.isEmpty()) {
                    card.visibility = View.GONE
                    return@withContext
                }

                card.visibility = View.VISIBLE
                plotView.setPlotPoints(plotPoints, totalChapters)

                description?.text = "Story structure with ${plotPoints.size} key plot points"

                // Populate plot points list
                pointsContainer?.removeAllViews()
                plotPoints.sortedBy { it.type.order }.forEach { point ->
                    val itemView = TextView(requireContext()).apply {
                        text = "• ${point.type.displayName} (Ch. ${point.chapterIndex + 1}): ${point.description}"
                        textSize = 12f
                        setTextColor(Color.parseColor("#B0FFFFFF"))
                        setPadding(0, 4, 0, 4)
                    }
                    pointsContainer?.addView(itemView)
                }
            }
        }
    }

    // AUG-022 + INS-001: Load and display emotional arc with enhanced line graph
    // INS-003: Also calculates and displays sentiment distribution
    private suspend fun loadEmotionalArc(
        hintView: TextView,
        container: LinearLayout,
        arcView: EmotionalArcView? = null,
        legendContainer: LinearLayout? = null,
        sentimentCard: MaterialCardView? = null,
        sentimentView: SentimentDistributionView? = null,
        sentimentToneChip: Chip? = null
    ) {
        withContext(Dispatchers.IO) {
            val emotionalData = mutableListOf<Pair<String, List<EmotionalSegment>>>()
            // BLOB-FIX: Use lightweight projection - only need title and fullAnalysisJson
            val chapterList = app.bookRepository.getChaptersWithAnalysis(bookId).sortedBy { it.orderIndex }
            val chapterIndices = mutableListOf<Int>()

            chapterList.forEachIndexed { index, chapter ->
                chapter.fullAnalysisJson?.let { json ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val analysis = gson.fromJson(json, Map::class.java) as? Map<String, Any>
                        val summary = analysis?.get("chapter_summary") as? Map<*, *>
                        val arc = summary?.get("emotional_arc") as? List<*>
                        val segments = arc?.mapNotNull { seg ->
                            if (seg is Map<*, *>) {
                                EmotionalSegment(
                                    segment = seg["segment"] as? String ?: "",
                                    emotion = seg["emotion"] as? String ?: "neutral",
                                    intensity = (seg["intensity"] as? Number)?.toFloat() ?: 0.5f
                                )
                            } else null
                        } ?: emptyList()
                        if (segments.isNotEmpty()) {
                            emotionalData.add(chapter.title to segments)
                            chapterIndices.add(index)
                        }
                    } catch (e: Exception) { }
                }
            }

            // INS-001: Build data points for the line graph
            val dataPoints = emotionalData.mapIndexed { idx, (title, segments) ->
                // Use average intensity and dominant emotion from segments
                val avgIntensity = segments.map { it.intensity }.average().toFloat() * 10f // Scale to 1-10
                val dominantEmotion = segments.maxByOrNull { it.intensity }?.emotion ?: "neutral"
                val secondaryEmotions = segments.map { it.emotion }.distinct().filter { it != dominantEmotion }
                EmotionalArcView.EmotionalDataPoint(
                    chapterIndex = chapterIndices.getOrElse(idx) { idx },
                    chapterTitle = title,
                    dominantEmotion = dominantEmotion,
                    intensity = avgIntensity.coerceIn(1f, 10f),
                    secondaryEmotions = secondaryEmotions
                )
            }

            // INS-003: Calculate sentiment distribution from all emotional segments
            val allEmotions = emotionalData.flatMap { (_, segments) ->
                segments.map { it.emotion to (it.intensity * 10f) }
            }
            val sentimentDistribution = SentimentDistribution.fromEmotions(allEmotions)

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                container.removeAllViews()

                if (emotionalData.isEmpty()) {
                    hintView.text = "No emotional data yet. Analyze chapters to see the arc."
                    arcView?.visibility = View.GONE
                    legendContainer?.visibility = View.GONE
                    sentimentCard?.visibility = View.GONE
                    return@withContext
                }

                hintView.text = "Tap any point to navigate to that chapter"

                // INS-001: Populate the EmotionalArcView
                arcView?.let { view ->
                    view.visibility = View.VISIBLE
                    view.setData(dataPoints, animate = true)
                    view.onChapterClickListener = { chapterIndex ->
                        // Navigate to chapter (could use navigation or callback)
                        Toast.makeText(requireContext(), "Navigate to Chapter ${chapterIndex + 1}", Toast.LENGTH_SHORT).show()
                    }
                }

                // INS-001: Build emotion legend
                legendContainer?.let { legend ->
                    legend.removeAllViews()
                    legend.visibility = View.VISIBLE
                    val uniqueEmotions = dataPoints.map { it.dominantEmotion }.distinct().take(5)
                    uniqueEmotions.forEach { emotion ->
                        val chip = TextView(requireContext()).apply {
                            text = "● ${emotion.replaceFirstChar { it.uppercase() }}"
                            textSize = 10f
                            setTextColor(getEmotionColor(emotion))
                            setPadding(8.dpToPx(), 4.dpToPx(), 8.dpToPx(), 4.dpToPx())
                        }
                        legend.addView(chip)
                    }
                }

                // INS-003: Display sentiment distribution
                sentimentCard?.visibility = View.VISIBLE
                sentimentView?.setDistribution(sentimentDistribution, animate = true)
                sentimentToneChip?.apply {
                    text = sentimentDistribution.dominantTone
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(sentimentDistribution.getToneColor())
                    setTextColor(Color.WHITE)
                }
            }
        }
    }

    private fun getEmotionColor(emotion: String): Int {
        return when (emotion.lowercase()) {
            "joy", "happy", "happiness" -> Color.parseColor("#4CAF50")
            "sadness", "sad", "melancholy" -> Color.parseColor("#2196F3")
            "anger", "angry", "rage" -> Color.parseColor("#F44336")
            "fear", "scared", "anxiety" -> Color.parseColor("#9C27B0")
            "tension", "suspense" -> Color.parseColor("#FF9800")
            "love", "romance" -> Color.parseColor("#E91E63")
            "curiosity" -> Color.parseColor("#00BCD4")
            "resolution", "peace", "calm" -> Color.parseColor("#8BC34A")
            else -> Color.parseColor("#757575")
        }
    }

    // AUG-028/029: Load vocabulary and symbols from extended analysis
    // INS-002: Also loads foreshadowing detection using LLM
    private suspend fun loadVocabularyAndSymbols(
        learnedSet: Set<String>,
        vocabContainer: LinearLayout?,
        vocabCount: TextView?,
        symbolsLabel: TextView?,
        symbolsText: TextView?,
        foreshadowingLabel: TextView?,
        foreshadowingText: TextView?,
        foreshadowingView: ForeshadowingView? = null
    ) {
        withContext(Dispatchers.IO) {
            // BLOB-FIX: Use lightweight projection for analysisJson parsing
            val chaptersWithAnalysis = app.bookRepository.getChaptersWithAnalysis(bookId).sortedBy { it.orderIndex }
            val chapterSummaries = app.bookRepository.chapterSummariesList(bookId).sortedBy { it.orderIndex }
            val totalChapters = chapterSummaries.size

            allVocabulary.clear()
            val allSymbols = mutableListOf<String>()
            val allForeshadowing = mutableListOf<String>()

            chaptersWithAnalysis.forEach { chapter ->
                chapter.analysisJson?.let { json ->
                    try {
                        val extAnalysis = gson.fromJson(json, JsonObject::class.java)
                        // Parse vocabulary
                        extAnalysis?.getAsJsonArray("vocabulary")?.forEach { v ->
                            val obj = v.asJsonObject
                            val word = obj.get("word")?.asString ?: return@forEach
                            val def = obj.get("definition")?.asString ?: ""
                            if (allVocabulary.none { it.word.equals(word, true) }) {
                                allVocabulary.add(VocabWord(word, def, learnedSet.contains(word.lowercase())))
                            }
                        }
                        // Parse symbols
                        extAnalysis?.getAsJsonArray("symbols")?.forEach { s ->
                            val sym = s.asString
                            if (sym !in allSymbols) allSymbols.add(sym)
                        }
                        // Parse foreshadowing (legacy text-based)
                        extAnalysis?.getAsJsonArray("foreshadowing")?.forEach { f ->
                            val fore = f.asString
                            if (fore !in allForeshadowing) allForeshadowing.add(fore)
                        }
                    } catch (_: Exception) { }
                }
            }

            // INS-002: Detect foreshadowing elements using new modular ForeshadowingDetectionPass
            // BLOB-FIX: Load chapters one at a time for foreshadowing detection
            val detectedForeshadowing = if (totalChapters >= 2) {
                try {
                    val chapterPairs = mutableListOf<Pair<Int, String>>()
                    for ((idx, summary) in chapterSummaries.withIndex()) {
                        val chapter = app.bookRepository.getChapter(summary.id) ?: continue
                        if (chapter.body.isNotBlank()) {
                            chapterPairs.add(idx to chapter.body)
                        }
                    }

                    val model = LlmService.getModel()
                    if (model != null) {
                        val pass = ForeshadowingDetectionPass()
                        val input = ForeshadowingInput(
                            bookId = bookId,
                            chapters = chapterPairs
                        )
                        val output = pass.execute(model, input, PassConfig())
                        com.dramebaz.app.data.models.ForeshadowingResult(
                            bookId = bookId,
                            foreshadowings = output.foreshadowings.map { it.copy(bookId = bookId) },
                            analyzedChapters = totalChapters
                        )
                    } else {
                        // Fallback to stub when no model available
                        val stubs = ForeshadowingDetectionPass.generateStubForeshadowing(bookId, totalChapters)
                        com.dramebaz.app.data.models.ForeshadowingResult(bookId, stubs, analyzedChapters = totalChapters)
                    }
                } catch (e: Exception) {
                    AppLogger.e("InsightsFragment", "Foreshadowing detection failed", e)
                    null
                }
            } else null

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                // AUG-029: Display symbols and foreshadowing
                if (allSymbols.isNotEmpty()) {
                    symbolsLabel?.visibility = View.VISIBLE
                    symbolsText?.visibility = View.VISIBLE
                    symbolsText?.text = allSymbols.joinToString(" • ") { "🔷 $it" }
                }
                if (allForeshadowing.isNotEmpty()) {
                    foreshadowingLabel?.visibility = View.VISIBLE
                    foreshadowingText?.visibility = View.VISIBLE
                    foreshadowingText?.text = allForeshadowing.joinToString("\n") { "🔮 $it" }
                }
                // INS-002: Display foreshadowing timeline if we have detected elements
                detectedForeshadowing?.let { result ->
                    if (result.foreshadowings.isNotEmpty()) {
                        foreshadowingLabel?.visibility = View.VISIBLE
                        foreshadowingView?.visibility = View.VISIBLE
                        foreshadowingView?.setData(result.foreshadowings, totalChapters)
                        foreshadowingView?.onForeshadowingClickListener = { f ->
                            // Navigate to the setup chapter when clicked
                            Toast.makeText(
                                requireContext(),
                                "Foreshadowing: Ch${f.setupChapter + 1} → Ch${f.payoffChapter + 1} (${f.theme})",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                // AUG-028: Display vocabulary
                displayVocabularyCards(vocabContainer, vocabCount)
            }
        }
    }

    // AUG-028: Display vocabulary cards with interactive learn/unlearn
    private fun displayVocabularyCards(container: LinearLayout?, countView: TextView?) {
        container?.removeAllViews()
        val wordsToShow = if (showOnlyUnlearned) allVocabulary.filter { !it.learned } else allVocabulary
        val learnedCount = allVocabulary.count { it.learned }
        countView?.text = "${wordsToShow.size} words • $learnedCount learned"

        if (wordsToShow.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = if (allVocabulary.isEmpty()) "No vocabulary yet (run chapter analysis)." else "All words learned! 🎉"
                textSize = 14f
                setTextColor(Color.parseColor("#B0FFFFFF"))
            }
            container?.addView(emptyText)
            return
        }

        wordsToShow.forEach { vocabWord ->
            val card = createVocabCard(vocabWord, container, countView)
            container?.addView(card)
        }
    }

    // AUG-028: Create a vocabulary word card with glassmorphism styling
    private fun createVocabCard(vocabWord: VocabWord, container: LinearLayout?, countView: TextView?): View {
        val card = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dpToPx() }
            radius = 12.dpToPx().toFloat()
            cardElevation = 0f
            strokeWidth = 1.dpToPx()
            strokeColor = Color.parseColor("#40FFFFFF")
            setCardBackgroundColor(Color.parseColor("#30FFFFFF"))
            setContentPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
            if (vocabWord.learned) alpha = 0.6f
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        val wordText = TextView(requireContext()).apply {
            text = if (vocabWord.learned) "✓ ${vocabWord.word}" else vocabWord.word
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (vocabWord.learned) Color.parseColor("#80FFAB") else Color.WHITE)
        }
        content.addView(wordText)

        val defText = TextView(requireContext()).apply {
            text = vocabWord.definition.ifEmpty { "(no definition)" }
            textSize = 13f
            setTextColor(Color.parseColor("#B0FFFFFF"))
        }
        content.addView(defText)

        card.addView(content)

        // Click to toggle learned status
        card.setOnClickListener {
            vocabWord.learned = !vocabWord.learned
            saveLearnedWords()
            displayVocabularyCards(container, countView)
        }

        return card
    }

    // AUG-028: Save learned words to SharedPreferences
    private fun saveLearnedWords() {
        val learnedSet = allVocabulary.filter { it.learned }.map { it.word.lowercase() }.toSet()
        requireContext().getSharedPreferences("vocabulary_prefs", 0)
            .edit()
            .putStringSet(learnedWordsKey, learnedSet)
            .apply()
    }

    // AUG-028: Export vocabulary to file
    private fun exportVocabulary() {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, "vocabulary_book_$bookId.txt")
            val content = buildString {
                appendLine("Vocabulary List - Book $bookId")
                appendLine("=" .repeat(40))
                appendLine()
                allVocabulary.forEach { v ->
                    appendLine("${if (v.learned) "✓" else "○"} ${v.word}")
                    appendLine("  ${v.definition}")
                    appendLine()
                }
                appendLine("Total: ${allVocabulary.size} words, ${allVocabulary.count { it.learned }} learned")
            }
            file.writeText(content)
            Toast.makeText(requireContext(), "Exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    /**
     * Run extended analysis (themes, symbols, vocabulary) for all chapters that need it.
     * Shows a progress dialog and refreshes the UI after completion.
     */
    @Suppress("DEPRECATION")
    private fun runExtendedAnalysisForAllChapters(
        learnedSet: Set<String>,
        themesView: TextView,
        vocabContainer: LinearLayout?,
        vocabCount: TextView?,
        symbolsLabel: TextView?,
        symbolsText: TextView?,
        foreshadowingLabel: TextView?,
        foreshadowingText: TextView?,
        foreshadowingView: ForeshadowingView?,
        emotionalHint: TextView,
        emotionalArcContainer: LinearLayout,
        emotionalArcView: EmotionalArcView? = null,
        emotionLegend: LinearLayout? = null,
        sentimentCard: MaterialCardView? = null,
        sentimentView: SentimentDistributionView? = null,
        sentimentToneChip: Chip? = null,
        // INS-005: Plot outline parameters
        plotOutlineCard: MaterialCardView? = null,
        plotOutlineView: PlotOutlineView? = null,
        plotOutlineDescription: TextView? = null,
        plotPointsContainer: LinearLayout? = null
    ) {
        val ctx = context ?: return

        val pd = ProgressDialog(ctx).apply {
            setMessage("Preparing extended analysis...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                AppLogger.d("InsightsFragment", "Extended analysis button clicked, starting analysis for bookId=$bookId")

                // BLOB-FIX: Use lightweight projection to find chapters needing analysis
                val chaptersWithAnalysis = withContext(Dispatchers.IO) {
                    app.bookRepository.getChaptersWithAnalysis(bookId).sortedBy { it.orderIndex }
                }

                AppLogger.d("InsightsFragment", "Found ${chaptersWithAnalysis.size} total chapters")

                // Find chapters that have fullAnalysisJson (basic analysis done) but no analysisJson (extended)
                val needAnalysisIds = chaptersWithAnalysis.filter {
                    it.fullAnalysisJson != null && it.analysisJson.isNullOrBlank()
                }.map { it.id }

                // Log why chapters might be filtered out
                chaptersWithAnalysis.forEachIndexed { idx, ch ->
                    AppLogger.d("InsightsFragment", "Ch[$idx] '${ch.title.take(20)}': fullAnalysisJson=${ch.fullAnalysisJson != null}, analysisJson=${ch.analysisJson?.take(30) ?: "null"}")
                }

                AppLogger.d("InsightsFragment", "Chapters needing extended analysis: ${needAnalysisIds.size}")

                if (needAnalysisIds.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        pd.dismiss()
                        Toast.makeText(ctx, "All chapters already have extended analysis.", Toast.LENGTH_SHORT).show()

                        // Load and display existing extended analysis data
                        if (isAdded) {
                            val data = withContext(Dispatchers.IO) { vm.insightsForBook(bookId) }
                            themesView.text = data.themes.ifEmpty { "No themes yet." }
                            loadVocabularyAndSymbols(learnedSet, vocabContainer, vocabCount, symbolsLabel, symbolsText, foreshadowingLabel, foreshadowingText, foreshadowingView)
                            loadEmotionalArc(emotionalHint, emotionalArcContainer, emotionalArcView, emotionLegend, sentimentCard, sentimentView, sentimentToneChip)
                            loadPlotOutline(plotOutlineCard, plotOutlineView, plotOutlineDescription, plotPointsContainer)
                        }
                    }
                    return@launch
                }

                pd.max = needAnalysisIds.size

                // BLOB-FIX: Load and process chapters one at a time
                needAnalysisIds.forEachIndexed { index, chapterId ->
                    if (!isAdded) {
                        pd.dismiss()
                        return@launch
                    }

                    // Load full chapter only when needed
                    val chapter = withContext(Dispatchers.IO) {
                        app.bookRepository.getChapter(chapterId)
                    } ?: return@forEachIndexed

                    // Skip if body too short
                    if (chapter.body.length <= 50) return@forEachIndexed

                    withContext(Dispatchers.Main) {
                        pd.progress = index
                        pd.setMessage("Analyzing chapter ${index + 1}/${needAnalysisIds.size}:\n${chapter.title.take(30)}...")
                    }

                    AppLogger.d("InsightsFragment", "Calling LlmService.extendedAnalysisJson for chapter: ${chapter.title}")
                    val extendedJson = withContext(Dispatchers.IO) {
                        LlmService.extendedAnalysisJson(chapter.body)
                    }
                    AppLogger.d("InsightsFragment", "extendedAnalysisJson result length: ${extendedJson.length}, preview: ${extendedJson.take(100)}")

                    if (extendedJson.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            app.bookRepository.updateChapter(chapter.copy(analysisJson = extendedJson))
                        }
                        AppLogger.d("InsightsFragment", "Saved extended analysis for chapter: ${chapter.title}")
                    } else {
                        AppLogger.w("InsightsFragment", "Extended analysis returned empty for chapter: ${chapter.title}")
                    }
                }

                // Refresh UI after all chapters analyzed
                withContext(Dispatchers.Main) {
                    pd.dismiss()
                    if (!isAdded) return@withContext

                    Toast.makeText(ctx, "Extended analysis complete for ${needAnalysisIds.size} chapters.", Toast.LENGTH_SHORT).show()

                    // Reload insights
                    val data = withContext(Dispatchers.IO) { vm.insightsForBook(bookId) }
                    themesView.text = data.themes.ifEmpty { "No themes yet." }
                    loadVocabularyAndSymbols(learnedSet, vocabContainer, vocabCount, symbolsLabel, symbolsText, foreshadowingLabel, foreshadowingText, foreshadowingView)
                    loadEmotionalArc(emotionalHint, emotionalArcContainer, emotionalArcView, emotionLegend, sentimentCard, sentimentView, sentimentToneChip)
                    loadPlotOutline(plotOutlineCard, plotOutlineView, plotOutlineDescription, plotPointsContainer)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pd.dismiss()
                    if (isAdded) {
                        Toast.makeText(ctx, "Analysis failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

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
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.data.models.EmotionalSegment
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
                symbolsLabel, symbolsText, foreshadowingLabel, foreshadowingText,
                emotionalHint, emotionalArcContainer
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Load statistics
            loadStatistics(statChapters, statCharacters, statDialogs)

            // Load insights data and extended analysis
            var data = withContext(Dispatchers.IO) { vm.insightsForBook(bookId) }
            themes.text = data.themes.ifEmpty { "No themes yet (run chapter analysis)." }

            // AUG-028/029: Load vocabulary and symbols from extended analysis
            loadVocabularyAndSymbols(learnedSet, vocabularyContainer, vocabularyCount, symbolsLabel, symbolsText, foreshadowingLabel, foreshadowingText)

            // AUG-022: Load emotional arc
            loadEmotionalArc(emotionalHint, emotionalArcContainer)

            // Lazy-load extended analysis for first chapter that needs it (one LLM call), then refresh
            val updated = withContext(Dispatchers.IO) { vm.ensureExtendedAnalysisForFirstNeeding(bookId) }
            if (updated && isAdded) {
                data = withContext(Dispatchers.IO) { vm.insightsForBook(bookId) }
                themes.text = data.themes.ifEmpty { "No themes yet." }
                loadVocabularyAndSymbols(learnedSet, vocabularyContainer, vocabularyCount, symbolsLabel, symbolsText, foreshadowingLabel, foreshadowingText)
                loadEmotionalArc(emotionalHint, emotionalArcContainer)
                Toast.makeText(requireContext(), "Insights updated.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // AUG-027: Load reading statistics
    private suspend fun loadStatistics(chaptersView: TextView, charactersView: TextView, dialogsView: TextView) {
        withContext(Dispatchers.IO) {
            val chapterList = app.db.chapterDao().getByBookId(bookId).first()
            val characterList = app.db.characterDao().getByBookId(bookId).first()
            val chapters = chapterList.size
            val characters = characterList.size
            // Estimate dialogs from chapter analysis
            var dialogCount = 0
            chapterList.forEach { chapter ->
                chapter.fullAnalysisJson?.let { json ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val analysis = gson.fromJson(json, Map::class.java) as? Map<String, Any>
                        val dialogs = analysis?.get("dialogs") as? List<*>
                        dialogCount += dialogs?.size ?: 0
                    } catch (e: Exception) { }
                }
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                chaptersView.text = chapters.toString()
                charactersView.text = characters.toString()
                dialogsView.text = dialogCount.toString()
            }
        }
    }

    // AUG-022: Load and display emotional arc
    private suspend fun loadEmotionalArc(hintView: TextView, container: LinearLayout) {
        withContext(Dispatchers.IO) {
            val emotionalData = mutableListOf<Pair<String, List<EmotionalSegment>>>()
            val chapterList = app.db.chapterDao().getByBookId(bookId).first()

            chapterList.forEach { chapter ->
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
                        }
                    } catch (e: Exception) { }
                }
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                container.removeAllViews()

                if (emotionalData.isEmpty()) {
                    hintView.text = "No emotional data yet. Analyze chapters to see the arc."
                    return@withContext
                }

                hintView.text = "Emotional journey across ${emotionalData.size} chapters:"

                // Create visual bars for each chapter's emotions
                emotionalData.forEach { (chapterTitle, segments) ->
                    val chapterRow = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = 8.dpToPx() }
                    }

                    // Chapter label
                    val label = TextView(requireContext()).apply {
                        text = chapterTitle.take(15) + if (chapterTitle.length > 15) "..." else ""
                        textSize = 12f
                        layoutParams = LinearLayout.LayoutParams(80.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    chapterRow.addView(label)

                    // Emotion bars
                    segments.forEach { seg ->
                        val bar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                            max = 100
                            progress = (seg.intensity * 100).toInt()
                            layoutParams = LinearLayout.LayoutParams(0, 20.dpToPx(), 1f).apply { marginEnd = 4.dpToPx() }
                            progressDrawable.setTint(getEmotionColor(seg.emotion))
                        }
                        chapterRow.addView(bar)
                    }

                    container.addView(chapterRow)
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
    private suspend fun loadVocabularyAndSymbols(
        learnedSet: Set<String>,
        vocabContainer: LinearLayout?,
        vocabCount: TextView?,
        symbolsLabel: TextView?,
        symbolsText: TextView?,
        foreshadowingLabel: TextView?,
        foreshadowingText: TextView?
    ) {
        withContext(Dispatchers.IO) {
            val chapters = app.db.chapterDao().getByBookId(bookId).first()
            allVocabulary.clear()
            val allSymbols = mutableListOf<String>()
            val allForeshadowing = mutableListOf<String>()

            chapters.forEach { chapter ->
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
                        // Parse foreshadowing
                        extAnalysis?.getAsJsonArray("foreshadowing")?.forEach { f ->
                            val fore = f.asString
                            if (fore !in allForeshadowing) allForeshadowing.add(fore)
                        }
                    } catch (_: Exception) { }
                }
            }

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
                setTextColor(Color.GRAY)
            }
            container?.addView(emptyText)
            return
        }

        wordsToShow.forEach { vocabWord ->
            val card = createVocabCard(vocabWord, container, countView)
            container?.addView(card)
        }
    }

    // AUG-028: Create a vocabulary word card
    private fun createVocabCard(vocabWord: VocabWord, container: LinearLayout?, countView: TextView?): View {
        val card = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dpToPx() }
            radius = 8.dpToPx().toFloat()
            cardElevation = 2.dpToPx().toFloat()
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
            setTextColor(if (vocabWord.learned) Color.parseColor("#4CAF50") else Color.parseColor("#333333"))
        }
        content.addView(wordText)

        val defText = TextView(requireContext()).apply {
            text = vocabWord.definition.ifEmpty { "(no definition)" }
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
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
        emotionalHint: TextView,
        emotionalArcContainer: LinearLayout
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

                val chapters = withContext(Dispatchers.IO) {
                    app.db.chapterDao().getByBookId(bookId).first()
                }

                AppLogger.d("InsightsFragment", "Found ${chapters.size} total chapters")

                // Find chapters that have fullAnalysisJson (basic analysis done) but no analysisJson (extended)
                val needAnalysis = chapters.filter {
                    it.fullAnalysisJson != null && it.body.length > 50 && it.analysisJson.isNullOrBlank()
                }

                AppLogger.d("InsightsFragment", "Chapters needing extended analysis: ${needAnalysis.size}")
                // Log why chapters might be filtered out
                chapters.forEachIndexed { idx, ch ->
                    AppLogger.d("InsightsFragment", "Ch[$idx] '${ch.title.take(20)}': fullAnalysisJson=${ch.fullAnalysisJson != null}, bodyLen=${ch.body.length}, analysisJson=${ch.analysisJson?.take(30) ?: "null"}")
                }

                if (needAnalysis.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        pd.dismiss()
                        Toast.makeText(ctx, "All chapters already have extended analysis.", Toast.LENGTH_SHORT).show()

                        // Load and display existing extended analysis data
                        if (isAdded) {
                            val data = withContext(Dispatchers.IO) { vm.insightsForBook(bookId) }
                            themesView.text = data.themes.ifEmpty { "No themes yet." }
                            loadVocabularyAndSymbols(learnedSet, vocabContainer, vocabCount, symbolsLabel, symbolsText, foreshadowingLabel, foreshadowingText)
                            loadEmotionalArc(emotionalHint, emotionalArcContainer)
                        }
                    }
                    return@launch
                }

                pd.max = needAnalysis.size

                needAnalysis.forEachIndexed { index, chapter ->
                    if (!isAdded) {
                        pd.dismiss()
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        pd.progress = index
                        pd.setMessage("Analyzing chapter ${index + 1}/${needAnalysis.size}:\n${chapter.title.take(30)}...")
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

                    Toast.makeText(ctx, "Extended analysis complete for ${needAnalysis.size} chapters.", Toast.LENGTH_SHORT).show()

                    // Reload insights
                    val data = withContext(Dispatchers.IO) { vm.insightsForBook(bookId) }
                    themesView.text = data.themes.ifEmpty { "No themes yet." }
                    loadVocabularyAndSymbols(learnedSet, vocabContainer, vocabCount, symbolsLabel, symbolsText, foreshadowingLabel, foreshadowingText)
                    loadEmotionalArc(emotionalHint, emotionalArcContainer)
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

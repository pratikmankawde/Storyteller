package com.dramebaz.app.ui.library

import android.app.ProgressDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.llm.QwenStub
import com.dramebaz.app.domain.usecases.MergeCharactersUseCase
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BookDetailFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val vm: BookDetailViewModel by viewModels { BookDetailViewModel.Factory(app.bookRepository) }
    private var bookId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = arguments?.getLong("bookId", 0L) ?: 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_book_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = view.findViewById<TextView>(R.id.title)
        val format = view.findViewById<TextView>(R.id.format)
        viewLifecycleOwner.lifecycleScope.launch {
            val book = vm.getBook(bookId)
            book?.let {
                title.text = it.title
                format.text = it.format.uppercase()
            }
        }
        
        // Setup action cards
        val actionsGrid = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.actions_grid)
        val gridLayoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
        actionsGrid.layoutManager = gridLayoutManager
        
        val actions = listOf(
            ActionCard(
                id = "start",
                title = "Start Reading",
                subtitle = "Begin your journey",
                iconRes = android.R.drawable.ic_media_play,
                onClick = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val firstId = vm.firstChapterId(bookId)
                        if (firstId == null) {
                            Toast.makeText(requireContext(), "No chapters in this book", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        findNavController().navigate(R.id.readerFragment, Bundle().apply {
                            putLong("bookId", bookId)
                            putLong("chapterId", firstId)
                        })
                    }
                }
            ),
            ActionCard(
                id = "characters",
                title = "Characters",
                subtitle = "Meet the cast",
                iconRes = android.R.drawable.ic_menu_myplaces,
                onClick = {
                    findNavController().navigate(R.id.charactersFragment, Bundle().apply { putLong("bookId", bookId) })
                }
            ),
            ActionCard(
                id = "bookmarks",
                title = "Bookmarks",
                subtitle = "Your saved places",
                iconRes = android.R.drawable.ic_menu_agenda,
                onClick = {
                    findNavController().navigate(R.id.bookmarksFragment, Bundle().apply { putLong("bookId", bookId) })
                }
            ),
            ActionCard(
                id = "insights",
                title = "Insights",
                subtitle = "Deep analysis",
                iconRes = android.R.drawable.ic_menu_info_details,
                onClick = {
                    findNavController().navigate(R.id.insightsFragment, Bundle().apply { putLong("bookId", bookId) })
                }
            )
        )
        
        val adapter = ActionCardAdapter()
        adapter.submitList(actions)
        actionsGrid.adapter = adapter
        actionsGrid.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 300
            removeDuration = 300
        }
        
        // Analyse Chapters button - initially disabled until ready
        val btnAnalyze = view.findViewById<Button>(R.id.btn_analyze)
        btnAnalyze?.isEnabled = false
        btnAnalyze?.alpha = 0.5f
        btnAnalyze?.text = "Loading..."
        
        // Check readiness: chapters loaded + TTS ready (LLM uses stub fallback if not loaded)
        viewLifecycleOwner.lifecycleScope.launch {
            val chapters = app.bookRepository.chapters(bookId).first()
            val hasChapters = chapters.isNotEmpty()
            val ttsReady = app.ttsEngine.isInitialized()
            
            AppLogger.d("BookDetailFragment", "Readiness check: hasChapters=$hasChapters (${chapters.size}), ttsReady=$ttsReady")
            
            if (hasChapters && ttsReady) {
                btnAnalyze?.isEnabled = true
                btnAnalyze?.alpha = 1.0f
                btnAnalyze?.text = "Analyse Chapters"
            } else if (!hasChapters) {
                btnAnalyze?.text = "No chapters"
            } else {
                btnAnalyze?.text = "Loading TTS..."
                // Keep checking until TTS is ready
                launch {
                    while (!app.ttsEngine.isInitialized()) {
                        kotlinx.coroutines.delay(500)
                    }
                    if (app.bookRepository.chapters(bookId).first().isNotEmpty()) {
                        btnAnalyze?.isEnabled = true
                        btnAnalyze?.alpha = 1.0f
                        btnAnalyze?.text = "Analyse Chapters"
                    }
                }
            }
        }
        
        btnAnalyze?.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val appInstance = ctx.applicationContext as? DramebazApplication ?: return@setOnClickListener
            val pd = ProgressDialog(ctx).apply {
                setMessage("Preparing…")
                setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                setIndeterminate(false)
                max = 100
                progress = 0
                setProgressNumberFormat("%1d / %2d")
                setCancelable(false)
                show()
            }
            view?.post {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                val analyseStartMs = System.currentTimeMillis()
                val chapters = appInstance.bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
                if (chapters.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            pd.dismiss()
                            Toast.makeText(ctx, "No chapters found in this book", Toast.LENGTH_LONG).show()
                        }
                    }
                    return@launch
                }
                // Only process the first chapter here; rest are analyzed when reached in the reader (one at a time).
                // Skip extraction pipeline (Phase 1/2/3) – one LLM call per segment took ~111s on device; use only analyzeChapter.
                val firstChapter = chapters.first()
                fun normalizeChapterBody(body: String): String =
                    body.trim().replace(Regex("\n{3,}"), "\n\n").trim().ifBlank { " " }
                val chapter = firstChapter.copy(body = normalizeChapterBody(firstChapter.body))
                val totalSteps = 2 // analyze + merge
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        pd.max = totalSteps
                        pd.progress = 0
                        pd.setMessage("Analyzing chapter: ${chapter.title}…")
                    }
                }
                AppLogger.i("BookDetailFragment", "Analyzing first chapter (single LLM call): ${chapter.title}")
                val resp = QwenStub.analyzeChapter(chapter.body)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        pd.progress = 1
                        pd.setMessage("Merging characters…")
                    }
                }
                val summaryJson = Gson().toJson(resp.chapterSummary)
                val fullAnalysisJson = Gson().toJson(resp)
                // analysisJson (extended) deferred to Insights / reader when needed
                appInstance.bookRepository.updateChapter(chapter.copy(summaryJson = summaryJson, analysisJson = null, fullAnalysisJson = fullAnalysisJson))
                val characterJsonList = mutableListOf<String>()
                resp.characters?.let { chars -> characterJsonList.add(Gson().toJson(chars)) }
                if (characterJsonList.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) pd.setMessage("Merging characters…")
                    }
                    val mergeUseCase = MergeCharactersUseCase(appInstance.db.characterDao(), ctx)
                    mergeUseCase.mergeAndSave(bookId, characterJsonList)
                }
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        pd.progress = totalSteps
                        pd.dismiss()
                        Toast.makeText(ctx, "First chapter analyzed. Other chapters will be analyzed when you reach them.", Toast.LENGTH_LONG).show()
                    }
                }
                AppLogger.logPerformance("BookDetailFragment", "Analyse first chapter (end-to-end)", System.currentTimeMillis() - analyseStartMs)
                AppLogger.i("BookDetailFragment", "First chapter analysis complete: ${chapter.title}")
                } catch (e: Exception) {
                    AppLogger.e("BookDetailFragment", "Analysis failed", e)
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            pd.dismiss()
                            Toast.makeText(ctx, "Analysis failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                }
            }
        }
    }
}

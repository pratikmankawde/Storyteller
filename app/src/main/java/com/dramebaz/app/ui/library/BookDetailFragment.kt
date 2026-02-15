package com.dramebaz.app.ui.library

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.domain.usecases.AnalysisQueueManager
import com.dramebaz.app.ui.reader.ChapterManagerDialog
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.card.MaterialCardView
import com.google.android.material.transition.MaterialContainerTransform
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookDetailFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val vm: BookDetailViewModel by viewModels { BookDetailViewModel.Factory(app.bookRepository) }
    private var bookId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = arguments?.getLong("bookId", 0L) ?: 0L

        // UI-004: Set up shared element enter transition
        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = 300L
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(Color.TRANSPARENT)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_book_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = view.findViewById<TextView>(R.id.title)
        val format = view.findViewById<TextView>(R.id.format)

        // METADATA-001: Book metadata views
        val authorView = view.findViewById<TextView>(R.id.author)
        val descriptionView = view.findViewById<TextView>(R.id.description)
        val ratingContainer = view.findViewById<LinearLayout>(R.id.rating_container)
        val ratingBar = view.findViewById<RatingBar>(R.id.rating_bar)
        val ratingText = view.findViewById<TextView>(R.id.rating_text)

        // UI-004: Set up book cover for shared element transition
        val bookCover = view.findViewById<ImageView>(R.id.book_cover)
        val transitionName = "book_cover_$bookId"
        ViewCompat.setTransitionName(bookCover, transitionName)

        // FAV-001: Set up favorite button
        val btnFavorite = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_favorite)

        // AUG-030: Chapter summaries card
        val chapterSummariesCard = view.findViewById<MaterialCardView>(R.id.chapter_summaries_card)
        val chapterSummariesContainer = view.findViewById<LinearLayout>(R.id.chapter_summaries_container)

        viewLifecycleOwner.lifecycleScope.launch {
            val book = vm.getBook(bookId)
            book?.let {
                title.text = it.title
                format.text = it.format.uppercase()
                // COVER-001: Load appropriate book cover into detail view
                BookCoverLoader.loadCoverInto(bookCover, it)
                // FAV-001: Update favorite button state
                updateFavoriteButton(btnFavorite, it.isFavorite)

                // METADATA-001: Bind book metadata
                bindBookMetadata(it, authorView, descriptionView, ratingContainer, ratingBar, ratingText)
            }

            // AUG-030: Load chapter summaries
            loadChapterSummaries(chapterSummariesCard, chapterSummariesContainer)
        }

        // FAV-001: Set up favorite button click listener
        btnFavorite.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val newStatus = vm.toggleFavorite(bookId)
                updateFavoriteButton(btnFavorite, newStatus)
                val message = if (newStatus)
                    getString(R.string.added_to_favorites)
                else
                    getString(R.string.removed_from_favorites)
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
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
            ),
            // CHAP-001: Chapter Manager action card
            ActionCard(
                id = "chapters",
                title = "Chapters",
                subtitle = "Manage chapters",
                iconRes = android.R.drawable.ic_menu_sort_by_size,
                onClick = {
                    ChapterManagerDialog.newInstance(bookId)
                        .show(childFragmentManager, "ChapterManagerDialog")
                }
            ),
            // SUMMARY-002: Series Linking action card
            ActionCard(
                id = "series",
                title = "Series",
                subtitle = "Link to a series",
                iconRes = android.R.drawable.ic_menu_my_calendar,
                onClick = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val book = vm.getBook(bookId)
                        book?.let {
                            SeriesLinkingDialog.show(requireContext(), it) { seriesId, seriesOrder ->
                                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                    val updated = it.copy(
                                        seriesId = if (seriesId == 0L) null else seriesId,
                                        seriesOrder = if (seriesId == 0L) null else seriesOrder
                                    )
                                    app.bookRepository.updateBook(updated)
                                    withContext(Dispatchers.Main) {
                                        val msg = if (seriesId == 0L) "Removed from series" else "Linked to series"
                                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
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

        // AUTO-ANALYSIS: Analysis status button - shows current analysis state
        // Since analysis is now automatic after import, this button shows status
        // and allows re-analysis if needed
        val btnAnalyze = view.findViewById<Button>(R.id.btn_analyze)
        btnAnalyze?.isEnabled = false
        btnAnalyze?.alpha = 0.5f
        btnAnalyze?.text = "Loading..."

        // Load initial book status and check analysis state
        // BLOB-FIX: Use lightweight summaries instead of full chapters
        viewLifecycleOwner.lifecycleScope.launch {
            val book = vm.getBook(bookId)
            val chapterSummaries = app.bookRepository.chapterSummariesList(bookId)
            val hasChapters = chapterSummaries.isNotEmpty()

            if (book != null) {
                updateAnalyzeButtonFromBookState(btnAnalyze, book, chapterSummaries.size)
            } else if (!hasChapters) {
                btnAnalyze?.text = "No chapters"
            }
        }

        // Observe live analysis queue status for this book
        viewLifecycleOwner.lifecycleScope.launch {
            AnalysisQueueManager.analysisStatus.collect { statusMap ->
                val status = statusMap[bookId] ?: return@collect
                when (status.state) {
                    AnalysisQueueManager.AnalysisState.PENDING -> {
                        btnAnalyze?.text = "Analysis Queued..."
                        btnAnalyze?.isEnabled = false
                        btnAnalyze?.alpha = 0.7f
                    }
                    AnalysisQueueManager.AnalysisState.ANALYZING -> {
                        val chapterInfo = if (status.totalChapters > 0) {
                            " (${status.analyzedChapters}/${status.totalChapters})"
                        } else {
                            " (${status.progress}%)"
                        }
                        btnAnalyze?.text = "Analyzing$chapterInfo..."
                        btnAnalyze?.isEnabled = false
                        btnAnalyze?.alpha = 0.7f
                    }
                    AnalysisQueueManager.AnalysisState.COMPLETE -> {
                        btnAnalyze?.text = "✓ Analysis Complete"
                        btnAnalyze?.isEnabled = true
                        btnAnalyze?.alpha = 1.0f
                        // Reload chapter summaries after analysis completes
                        loadChapterSummaries(chapterSummariesCard, chapterSummariesContainer)
                    }
                    AnalysisQueueManager.AnalysisState.FAILED -> {
                        btnAnalyze?.text = "Analysis Failed - Tap to Retry"
                        btnAnalyze?.isEnabled = true
                        btnAnalyze?.alpha = 1.0f
                    }
                }
            }
        }

        // Button click: Re-enqueue for analysis or show current status
        btnAnalyze?.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val book = vm.getBook(bookId) ?: return@launch
                val analysisState = book.getAnalysisState()

                when (analysisState) {
                    com.dramebaz.app.data.db.AnalysisState.COMPLETED -> {
                        // Already analyzed - offer to view characters
                        Toast.makeText(requireContext(),
                            "All chapters analyzed! View Characters or Insights for results.",
                            Toast.LENGTH_SHORT).show()
                    }
                    com.dramebaz.app.data.db.AnalysisState.FAILED,
                    com.dramebaz.app.data.db.AnalysisState.CANCELLED -> {
                        // Failed/cancelled - re-enqueue for first chapter analysis
                        Toast.makeText(requireContext(), "Re-starting analysis...", Toast.LENGTH_SHORT).show()
                        AnalysisQueueManager.enqueueFirstChapter(bookId)
                    }
                    com.dramebaz.app.data.db.AnalysisState.ANALYZING -> {
                        Toast.makeText(requireContext(),
                            "Analysis in progress: ${book.analyzedChapterCount}/${book.totalChaptersToAnalyze} chapters",
                            Toast.LENGTH_SHORT).show()
                    }
                    com.dramebaz.app.data.db.AnalysisState.PENDING -> {
                        Toast.makeText(requireContext(),
                            "Analysis is queued and will start soon...",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * AUTO-ANALYSIS: Update the analyze button based on book's persisted analysis state.
     */
    private fun updateAnalyzeButtonFromBookState(btnAnalyze: Button?, book: com.dramebaz.app.data.db.Book, totalChapters: Int) {
        if (btnAnalyze == null) return

        when (book.getAnalysisState()) {
            com.dramebaz.app.data.db.AnalysisState.PENDING -> {
                btnAnalyze.text = "Analysis Pending..."
                btnAnalyze.isEnabled = false
                btnAnalyze.alpha = 0.7f
            }
            com.dramebaz.app.data.db.AnalysisState.ANALYZING -> {
                val progress = if (book.totalChaptersToAnalyze > 0) {
                    "${book.analyzedChapterCount}/${book.totalChaptersToAnalyze}"
                } else {
                    "${book.analysisProgress}%"
                }
                btnAnalyze.text = "Analyzing ($progress)..."
                btnAnalyze.isEnabled = false
                btnAnalyze.alpha = 0.7f
            }
            com.dramebaz.app.data.db.AnalysisState.COMPLETED -> {
                btnAnalyze.text = "✓ Analysis Complete"
                btnAnalyze.isEnabled = true
                btnAnalyze.alpha = 1.0f
            }
            com.dramebaz.app.data.db.AnalysisState.FAILED -> {
                btnAnalyze.text = "Analysis Failed - Tap to Retry"
                btnAnalyze.isEnabled = true
                btnAnalyze.alpha = 1.0f
            }
            com.dramebaz.app.data.db.AnalysisState.CANCELLED -> {
                btnAnalyze.text = "Analysis Cancelled - Tap to Retry"
                btnAnalyze.isEnabled = true
                btnAnalyze.alpha = 1.0f
            }
        }
    }

    /**
     * METADATA-001: Bind book metadata (author, description, rating) to views.
     */
    private fun bindBookMetadata(
        book: com.dramebaz.app.data.db.Book,
        authorView: TextView?,
        descriptionView: TextView?,
        ratingContainer: LinearLayout?,
        ratingBar: RatingBar?,
        ratingText: TextView?
    ) {
        // Author
        book.author?.takeIf { it.isNotBlank() }?.let { author ->
            authorView?.apply {
                text = "by $author"
                visibility = View.VISIBLE
            }
        }

        // Description
        book.description?.takeIf { it.isNotBlank() }?.let { desc ->
            descriptionView?.apply {
                text = desc
                visibility = View.VISIBLE
            }
        }

        // Rating
        book.rating?.takeIf { it > 0f }?.let { rating ->
            ratingContainer?.visibility = View.VISIBLE
            ratingBar?.rating = rating
            ratingText?.text = String.format("%.1f", rating)
        }
    }

    /**
     * AUG-030: Load and display smart chapter summaries with key points.
     * BLOB-FIX: Uses lightweight projection to avoid CursorWindow overflow.
     */
    private suspend fun loadChapterSummaries(card: MaterialCardView?, container: LinearLayout?) {
        if (card == null || container == null) return

        // BLOB-FIX: Use lightweight projection that excludes body
        val chapters = withContext(Dispatchers.IO) {
            app.db.chapterDao().getChaptersWithAnalysis(bookId)
        }

        if (chapters.isEmpty()) return

        // Check if any chapters have analysis data
        val analyzedChapters = chapters.filter { it.fullAnalysisJson != null }
        if (analyzedChapters.isEmpty()) return

        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            card.visibility = View.VISIBLE
            container.removeAllViews()

            val gson = Gson()
            analyzedChapters.forEachIndexed { index, chapter ->
                val summaryView = createChapterSummaryView(index + 1, chapter.title, chapter.fullAnalysisJson, gson)
                container.addView(summaryView)
            }
        }
    }

    /**
     * AUG-030: Create expandable chapter summary view with key points.
     */
    private fun createChapterSummaryView(chapterNum: Int, title: String, analysisJson: String?, gson: Gson): View {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(12) }
        }

        // Parse analysis JSON
        var shortSummary = "Analysis available (tap to expand)"
        var mainEvents = emptyList<String>()
        var characters = emptyList<String>()

        try {
            analysisJson?.let { json ->
                val analysis = gson.fromJson(json, JsonObject::class.java)
                analysis?.getAsJsonObject("chapter_summary")?.let { summary ->
                    summary.get("short_summary")?.asString?.let { shortSummary = it }
                    summary.getAsJsonArray("main_events")?.mapNotNull { it.asString }?.let { mainEvents = it }
                }
                // Extract character names from dialog
                analysis?.getAsJsonArray("characters")?.mapNotNull {
                    it.asJsonObject?.get("name")?.asString
                }?.let { characters = it.distinct().take(5) }
            }
        } catch (_: Exception) { }

        // Header row (clickable to expand)
        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val chapterLabel = TextView(requireContext()).apply {
            text = "Ch. $chapterNum"
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
            setTextColor(Color.parseColor("#6200EE"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(45), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        headerRow.addView(chapterLabel)

        val titleLabel = TextView(requireContext()).apply {
            text = title.take(30) + if (title.length > 30) "..." else ""
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleLabel)

        val expandIcon = TextView(requireContext()).apply {
            text = "▼"
            textSize = 12f
        }
        headerRow.addView(expandIcon)

        container.addView(headerRow)

        // Expandable content
        val detailsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        }

        // Short summary
        val summaryText = TextView(requireContext()).apply {
            text = shortSummary
            textSize = 13f
            setTextColor(Color.parseColor("#555555"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
        }
        detailsContainer.addView(summaryText)

        // Key events
        if (mainEvents.isNotEmpty()) {
            val eventsLabel = TextView(requireContext()).apply {
                text = "📌 Key Events:"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#333333"))
            }
            detailsContainer.addView(eventsLabel)

            mainEvents.take(3).forEach { event ->
                val eventText = TextView(requireContext()).apply {
                    text = "• $event"
                    textSize = 12f
                    setTextColor(Color.parseColor("#666666"))
                    setPadding(dpToPx(8), 0, 0, 0)
                }
                detailsContainer.addView(eventText)
            }
        }

        // Characters involved
        if (characters.isNotEmpty()) {
            val charsLabel = TextView(requireContext()).apply {
                text = "👤 Characters: ${characters.joinToString(", ")}"
                textSize = 12f
                setTextColor(Color.parseColor("#666666"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(4) }
            }
            detailsContainer.addView(charsLabel)
        }

        container.addView(detailsContainer)

        // Toggle expand/collapse on click
        headerRow.setOnClickListener {
            if (detailsContainer.visibility == View.GONE) {
                detailsContainer.visibility = View.VISIBLE
                expandIcon.text = "▲"
            } else {
                detailsContainer.visibility = View.GONE
                expandIcon.text = "▼"
            }
        }

        return container
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * FAV-001: Update the favorite button icon based on favorite status.
     */
    private fun updateFavoriteButton(button: com.google.android.material.button.MaterialButton, isFavorite: Boolean) {
        val iconRes = if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        button.setIconResource(iconRes)
    }
}

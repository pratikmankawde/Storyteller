package com.dramebaz.app.ui.library

import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.llm.CharacterAnalysisForegroundService
import com.dramebaz.app.domain.usecases.AnalysisQueueManager
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.card.MaterialCardView
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

    // AUG-025: Track analysis for cancellation
    private var isCancelled = false
    private var analysisStartTime = 0L

    // Progress dialog for analysis (shown while foreground service is running)
    private var progressDialog: ProgressDialog? = null
    private var analysisReceiver: BroadcastReceiver? = null

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

        // AUG-030: Chapter summaries card
        val chapterSummariesCard = view.findViewById<MaterialCardView>(R.id.chapter_summaries_card)
        val chapterSummariesContainer = view.findViewById<LinearLayout>(R.id.chapter_summaries_container)

        viewLifecycleOwner.lifecycleScope.launch {
            val book = vm.getBook(bookId)
            book?.let {
                title.text = it.title
                format.text = it.format.uppercase()
            }

            // AUG-030: Load chapter summaries
            loadChapterSummaries(chapterSummariesCard, chapterSummariesContainer)
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

        // Observe analysis queue status for this book (auto-triggered after import)
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
                        btnAnalyze?.text = "Analyzing (${status.progress}%)..."
                        btnAnalyze?.isEnabled = false
                        btnAnalyze?.alpha = 0.7f
                    }
                    AnalysisQueueManager.AnalysisState.COMPLETE -> {
                        btnAnalyze?.text = "Re-analyze Chapters"
                        btnAnalyze?.isEnabled = true
                        btnAnalyze?.alpha = 1.0f
                    }
                    AnalysisQueueManager.AnalysisState.FAILED -> {
                        btnAnalyze?.text = "Analysis Failed - Retry"
                        btnAnalyze?.isEnabled = true
                        btnAnalyze?.alpha = 1.0f
                    }
                }
            }
        }

        btnAnalyze?.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val appInstance = ctx.applicationContext as? DramebazApplication ?: return@setOnClickListener

            // Reset cancellation flag and start time
            isCancelled = false
            analysisStartTime = System.currentTimeMillis()

            // Show progress dialog
            progressDialog = ProgressDialog(ctx).apply {
                setMessage("Preparing…")
                setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                setIndeterminate(false)
                max = 100
                progress = 0
                setProgressNumberFormat("%1d / %2d")
                setCancelable(false)
                setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancel") { dialog, _ ->
                    isCancelled = true
                    // Send cancel intent to foreground service
                    val cancelIntent = Intent(ctx, CharacterAnalysisForegroundService::class.java).apply {
                        action = CharacterAnalysisForegroundService.ACTION_CANCEL
                    }
                    ctx.startService(cancelIntent)
                    dialog.dismiss()
                    Toast.makeText(ctx, "Analysis cancelled", Toast.LENGTH_SHORT).show()
                }
                show()
            }

            // Register broadcast receiver for progress and completion updates
            registerAnalysisReceiver(ctx)

            // Load chapters and start foreground service
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val chapters = appInstance.bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
                    if (chapters.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                progressDialog?.dismiss()
                                unregisterAnalysisReceiver(ctx)
                                Toast.makeText(ctx, "No chapters found in this book", Toast.LENGTH_LONG).show()
                            }
                        }
                        return@launch
                    }

                    // Determine which chapter to analyze:
                    // 1. Check reading session to see if user has started this book
                    // 2. If reading, analyze current chapter (or next unanalyzed)
                    // 3. If not reading, analyze first unanalyzed chapter
                    val readingSession = app.db.readingSessionDao().getCurrent()
                    val chapterToAnalyze = if (readingSession != null && readingSession.bookId == bookId) {
                        // User is reading this book - find the chapter they're on or next unanalyzed
                        val currentChapter = chapters.find { it.id == readingSession.chapterId }
                        if (currentChapter != null && currentChapter.fullAnalysisJson.isNullOrBlank()) {
                            // Current chapter needs analysis
                            currentChapter
                        } else {
                            // Current chapter analyzed, find next unanalyzed
                            val currentIdx = chapters.indexOfFirst { it.id == readingSession.chapterId }
                            chapters.drop(currentIdx + 1).firstOrNull { it.fullAnalysisJson.isNullOrBlank() }
                                ?: chapters.firstOrNull { it.fullAnalysisJson.isNullOrBlank() }
                        }
                    } else {
                        // User hasn't started reading - find first unanalyzed chapter
                        chapters.firstOrNull { it.fullAnalysisJson.isNullOrBlank() }
                    }

                    if (chapterToAnalyze == null) {
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                progressDialog?.dismiss()
                                unregisterAnalysisReceiver(ctx)
                                Toast.makeText(ctx, "All chapters are already analyzed!", Toast.LENGTH_LONG).show()
                            }
                        }
                        return@launch
                    }

                    fun normalizeChapterBody(body: String): String =
                        body.trim().replace(Regex("\n{3,}"), "\n\n").trim().ifBlank { " " }
                    val chapter = chapterToAnalyze.copy(body = normalizeChapterBody(chapterToAnalyze.body))
                    val chapterIndex = chapters.indexOfFirst { it.id == chapter.id }

                    val wordCount = chapter.body.split("\\s+".toRegex()).size
                    val charLen = chapter.body.length
                    val estimatedMinutes = (wordCount / 500).coerceAtLeast(1)

                    withContext(Dispatchers.Main) {
                        if (isAdded && !isCancelled) {
                            progressDialog?.apply {
                                max = 100
                                progress = 0
                                setMessage("Starting Character Analysis…\n" +
                                    "Chapter: ${chapter.title}\n" +
                                    "$wordCount words, $charLen chars\n" +
                                    "~$estimatedMinutes min (runs in background)")
                            }
                        }
                    }

                    AppLogger.i("BookDetailFragment", "Starting foreground service for analysis: ${chapter.title}")

                    // Start the foreground service
                    val serviceIntent = Intent(ctx, CharacterAnalysisForegroundService::class.java).apply {
                        putExtra(CharacterAnalysisForegroundService.EXTRA_BOOK_ID, bookId)
                        putExtra(CharacterAnalysisForegroundService.EXTRA_CHAPTER_TEXT, chapter.body)
                        putExtra(CharacterAnalysisForegroundService.EXTRA_CHAPTER_INDEX, chapterIndex.coerceAtLeast(0))
                        putExtra(CharacterAnalysisForegroundService.EXTRA_TOTAL_CHAPTERS, chapters.size)
                        putExtra(CharacterAnalysisForegroundService.EXTRA_CHAPTER_TITLE, chapter.title)
                    }

                    withContext(Dispatchers.Main) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ctx.startForegroundService(serviceIntent)
                        } else {
                            ctx.startService(serviceIntent)
                        }
                    }

                } catch (e: Exception) {
                    AppLogger.e("BookDetailFragment", "Failed to start analysis service", e)
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            progressDialog?.dismiss()
                            unregisterAnalysisReceiver(ctx)
                            Toast.makeText(ctx, "Analysis failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    /**
     * Register broadcast receiver for analysis progress and completion.
     */
    private fun registerAnalysisReceiver(ctx: Context) {
        AppLogger.i("BookDetailFragment", "📡 Registering analysis broadcast receiver...")
        analysisReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                AppLogger.d("BookDetailFragment", "📥 Broadcast received: action=${intent?.action}")
                when (intent?.action) {
                    CharacterAnalysisForegroundService.ACTION_PROGRESS -> {
                        val message = intent.getStringExtra(CharacterAnalysisForegroundService.EXTRA_PROGRESS_MESSAGE) ?: ""
                        val percent = intent.getIntExtra(CharacterAnalysisForegroundService.EXTRA_PROGRESS_PERCENT, 0)
                        AppLogger.d("BookDetailFragment", "   Progress: $percent% - $message")
                        if (isAdded && !isCancelled) {
                            // Ensure UI update happens on main thread
                            activity?.runOnUiThread {
                                progressDialog?.let { dialog ->
                                    if (dialog.isShowing) {
                                        dialog.progress = percent
                                        dialog.setMessage(message)
                                        AppLogger.d("BookDetailFragment", "   ✅ Dialog updated: $percent% - ${message.take(50)}...")
                                    } else {
                                        AppLogger.w("BookDetailFragment", "   Dialog not showing, skipping update")
                                    }
                                } ?: AppLogger.w("BookDetailFragment", "   progressDialog is null")
                            }
                        } else {
                            AppLogger.w("BookDetailFragment", "   Cannot update UI: isAdded=$isAdded, isCancelled=$isCancelled")
                        }
                    }
                    CharacterAnalysisForegroundService.ACTION_COMPLETE -> {
                        val success = intent.getBooleanExtra(CharacterAnalysisForegroundService.EXTRA_SUCCESS, false)
                        val characterCount = intent.getIntExtra(CharacterAnalysisForegroundService.EXTRA_CHARACTER_COUNT, 0)
                        val errorMessage = intent.getStringExtra(CharacterAnalysisForegroundService.EXTRA_ERROR_MESSAGE)
                        AppLogger.i("BookDetailFragment", "🏁 COMPLETE broadcast: success=$success, count=$characterCount, error=$errorMessage")

                        if (isAdded) {
                            AppLogger.d("BookDetailFragment", "   Dismissing progress dialog and unregistering receiver")
                            progressDialog?.dismiss()
                            unregisterAnalysisReceiver(ctx)

                            if (success) {
                                val totalTimeSec = (System.currentTimeMillis() - analysisStartTime) / 1000
                                AppLogger.i("BookDetailFragment", "✅ Analysis SUCCESS in ${totalTimeSec}s, $characterCount characters")
                                Toast.makeText(ctx,
                                    "Analysis complete in ${totalTimeSec}s!\n" +
                                    "Found $characterCount characters with voice profiles.\n" +
                                    "Use Insights tab to analyze themes.",
                                    Toast.LENGTH_LONG).show()

                                if (characterCount > 0) {
                                    AppLogger.d("BookDetailFragment", "   Navigating to characters screen")
                                    val bundle = Bundle().apply { putLong("bookId", bookId) }
                                    findNavController().navigate(R.id.action_bookDetail_to_characters, bundle)
                                }
                            } else {
                                val msg = errorMessage ?: "Unknown error"
                                AppLogger.e("BookDetailFragment", "❌ Analysis FAILED: $msg")
                                if (!isCancelled) {
                                    Toast.makeText(ctx, "Analysis failed: $msg", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            AppLogger.w("BookDetailFragment", "   Fragment not added, cannot handle completion")
                        }
                    }
                    else -> {
                        AppLogger.w("BookDetailFragment", "   Unknown action: ${intent?.action}")
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(CharacterAnalysisForegroundService.ACTION_PROGRESS)
            addAction(CharacterAnalysisForegroundService.ACTION_COMPLETE)
        }
        ContextCompat.registerReceiver(ctx, analysisReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        AppLogger.i("BookDetailFragment", "✅ Analysis receiver registered for actions: ${filter.actionsIterator().asSequence().toList()}")
    }

    /**
     * Unregister the analysis broadcast receiver.
     */
    private fun unregisterAnalysisReceiver(ctx: Context) {
        analysisReceiver?.let {
            try {
                ctx.unregisterReceiver(it)
                AppLogger.d("BookDetailFragment", "Analysis receiver unregistered")
            } catch (e: Exception) {
                // Receiver not registered
            }
        }
        analysisReceiver = null
    }

    /**
     * AUG-030: Load and display smart chapter summaries with key points.
     */
    private suspend fun loadChapterSummaries(card: MaterialCardView?, container: LinearLayout?) {
        if (card == null || container == null) return

        val chapters = withContext(Dispatchers.IO) {
            app.db.chapterDao().getByBookId(bookId).first()
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

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up progress dialog and broadcast receiver
        progressDialog?.dismiss()
        progressDialog = null
        context?.let { unregisterAnalysisReceiver(it) }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}

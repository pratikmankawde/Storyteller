package com.dramebaz.app.ui.reader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.llm.ChapterAnalysisResponse
import com.dramebaz.app.ai.llm.QwenStub
import com.dramebaz.app.domain.usecases.ChapterCharacterExtractionUseCase
import com.dramebaz.app.domain.usecases.MergeCharactersUseCase
import com.dramebaz.app.data.db.Bookmark
import com.dramebaz.app.data.db.ReadingSession
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.EmotionalSegment
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.playback.engine.PlaybackEngine
import com.dramebaz.app.playback.engine.TextAudioSync
import com.dramebaz.app.playback.mixer.AudioMixer
import com.dramebaz.app.playback.mixer.PlaybackTheme
import com.dramebaz.app.playback.service.AudioPlaybackService
import com.dramebaz.app.ui.player.PlayerBottomSheet
import com.dramebaz.app.util.MemoryMonitor
import com.dramebaz.app.ui.common.ErrorDialog
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.utils.CacheManager
import com.dramebaz.app.utils.DegradedModeManager
import com.dramebaz.app.pdf.PdfPageRenderer
import com.google.android.material.card.MaterialCardView
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderFragment : Fragment(), PlayerBottomSheet.PlayerControlsListener {
    private val tag = "ReaderFragment"
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val vm: ReaderViewModel by viewModels {
        ReaderViewModel.Factory(app.bookRepository, app.getRecapUseCase)
    }
    private val gson = Gson()

    private var bookId: Long = 0L
    private var chapterId: Long = 0L
    /** Chapter ID actually loaded and displayed (resolved from arguments or first chapter). Used for bookmark. */
    private var loadedChapterId: Long = 0L
    private var currentModeIndex = 0
    private val modes = listOf("reading", "listening", "mixed")

    // Playback components
    private var playbackEngine: PlaybackEngine? = null
    private var audioMixer: AudioMixer? = null
    private var textSegments: List<TextAudioSync.TextSegment> = emptyList()
    private var chapterText: String = ""
    private var chapterAnalysis: ChapterAnalysisResponse? = null
    private var currentTheme = PlaybackTheme.CLASSIC
    private var playerBottomSheet: PlayerBottomSheet? = null

    // Audio playback service for background playback
    private var audioService: AudioPlaybackService? = null
    private var isServiceBound = false
    private var bookTitle: String = "Storyteller"
    private var chapterTitle: String = "Chapter"

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioPlaybackService.LocalBinder
            audioService = binder.getService()
            isServiceBound = true
            AppLogger.d(tag, "AudioPlaybackService connected")

            // Set up callbacks
            audioService?.setOnProgressListener { position, duration ->
                if (isNovelFormat && novelPages.isNotEmpty()) {
                    updateNovelHighlighting(position)
                }
                playerBottomSheet?.updateProgress(position, duration)
            }

            audioService?.setOnCompleteListener {
                playerBottomSheet?.updatePlaybackState(false)
                view?.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_play)?.text = "Play"
                if (isNovelFormat) novelPageAdapter?.clearHighlighting()
                playingPageIndex = -1
                // Auto-continue to next page when current page finishes
                if (isNovelFormat && novelPages.isNotEmpty() && currentPageIndex + 1 < novelPages.size) {
                    view?.post {
                        currentPageIndex = currentPageIndex + 1
                        viewPager?.setCurrentItem(currentPageIndex, true)
                        playCurrentPage()
                    }
                } else {
                    updateFabPlayCurrentPageVisibility()
                }
            }

            audioService?.setOnPlayStateChangeListener { isPlaying ->
                view?.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_play)?.text =
                    if (isPlaying) "Pause" else "Play"
                playerBottomSheet?.updatePlaybackState(isPlaying)
                if (!isPlaying) playingPageIndex = -1
                updateFabPlayCurrentPageVisibility()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            isServiceBound = false
            AppLogger.d(tag, "AudioPlaybackService disconnected")
        }
    }

    // Back button callback to stop playback
    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            AppLogger.d(tag, "Back pressed - stopping playback and processing")

            // Stop audio service playback
            if (audioService?.isPlaying() == true) {
                audioService?.stop()
                // Stop the foreground service
                requireContext().stopService(Intent(requireContext(), AudioPlaybackService::class.java))
            }

            // Stop local playback engine
            playbackEngine?.stop()

            // Disable this callback and let the system handle navigation
            isEnabled = false
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    // Novel reading components
    private var novelPageAdapter: NovelPageAdapter? = null
    private var viewPager: androidx.viewpager2.widget.ViewPager2? = null
    private var isNovelFormat = false
    private var novelPages: List<NovelPage> = emptyList()

    // PDF rendering for native PDF display
    private var pdfPageRenderer: PdfPageRenderer? = null
    private var isPdfBook = false
    private var pdfFilePath: String? = null

    // Pre-generated audio for pages (page index -> audio file)
    private val preGeneratedAudio = mutableMapOf<Int, java.io.File>()
    private var currentPageIndex = 0
    /** Page index that is currently playing (or -1 if none). Used to show/hide FAB. */
    private var playingPageIndex = -1

    // Memory monitoring
    private var memoryMonitor: MemoryMonitor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = arguments?.getLong("bookId", 0L) ?: 0L
        chapterId = arguments?.getLong("chapterId", 0L) ?: 0L
        AppLogger.d(tag, "onCreate: bookId=$bookId, chapterId=$chapterId")

        // Bind to audio playback service
        Intent(requireContext(), AudioPlaybackService::class.java).also { intent ->
            requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Register back button handler to stop playback
        requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Default to novel layout - we'll check format in onViewCreated
        return inflater.inflate(R.layout.fragment_reader_novel, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLogger.d(tag, "onViewCreated: bookId=$bookId, chapterId=$chapterId")

        // Always treat all books as novels for 3D page turning experience
        isNovelFormat = true
        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)

        // Start memory monitoring
        memoryMonitor = MemoryMonitor(requireContext(), toolbar)
        memoryMonitor?.start()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val book = vm.getBook(bookId)
            bookTitle = book?.title ?: "Storyteller"
            AppLogger.d(tag, "Book format: ${book?.format}, displaying as Novel with 3D page turning")

            // Check if this is a PDF book and initialize the PDF renderer
            if (book?.format == "pdf" && book.filePath.isNotEmpty()) {
                isPdfBook = true
                pdfFilePath = book.filePath
                val pdfFile = File(book.filePath)
                if (pdfFile.exists()) {
                    pdfPageRenderer = PdfPageRenderer.create(requireContext(), pdfFile)
                    AppLogger.d(tag, "PDF renderer initialized for: ${book.filePath}")
                } else {
                    AppLogger.w(tag, "PDF file not found: ${book.filePath}")
                    isPdfBook = false
                }
            } else {
                isPdfBook = false
            }

            withContext(Dispatchers.Main) {
                toolbar.title = bookTitle
                // Set the PDF renderer on the adapter after it's created
                if (isPdfBook && pdfPageRenderer != null) {
                    novelPageAdapter?.setPdfRenderer(pdfPageRenderer)
                }
            }
        }

        // Initialize novel page view
        viewPager = view.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewpager_novel)
        val highlightColor = ContextCompat.getColor(requireContext(), android.R.color.holo_blue_light).let { c ->
            android.graphics.Color.argb(100, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
        }

        novelPageAdapter = NovelPageAdapter(highlightColor, viewLifecycleOwner.lifecycleScope) { pageIndex ->
            // Page changed callback
            AppLogger.d(tag, "Page changed to: $pageIndex")
            currentPageIndex = pageIndex
            updateFabPlayCurrentPageVisibility()

            // Pre-generate audio for the next page in background while user is reading this page
            if (pageIndex + 1 < novelPages.size && !preGeneratedAudio.containsKey(pageIndex + 1)) {
                preGenerateAudioForPages(pageIndex + 1, 1)
            }

            // AUG-037: Pre-analyze next chapter when user reaches 80% of current chapter
            if (novelPages.isNotEmpty()) {
                val progressPercent = (pageIndex + 1).toFloat() / novelPages.size * 100
                if (progressPercent >= 80) {
                    triggerNextChapterPreAnalysis()
                }
            }
        }

        viewPager?.adapter = novelPageAdapter
        viewPager?.setPageTransformer(PageTurnTransformer())

        // Configure ViewPager2 for optimal 3D page turning experience
        viewPager?.offscreenPageLimit = 2 // Keep 2 pages off-screen for smoother transitions
        viewPager?.clipToPadding = false
        viewPager?.clipChildren = false

        // AUG-041: Setup degraded mode banner
        setupDegradedModeBanner(view)

        val body = view.findViewById<TextView>(R.id.body) // May be null in novel view
        val recapCard = view.findViewById<MaterialCardView>(R.id.recap_card) // May be null in novel view
        val recapText = view.findViewById<TextView>(R.id.recap_text) // May be null in novel view
        val btnMode = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_mode)
        val btnPlay = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_play)

        // Initialize playback components
        AppLogger.d(tag, "Initializing PlaybackEngine and AudioMixer")
        playbackEngine = PlaybackEngine(requireContext(), app.ttsEngine, viewLifecycleOwner.lifecycleScope).apply {
            setOnProgressListener { position, duration ->
                // Use novel highlighting when in novel format, otherwise use text highlighting
                if (isNovelFormat && novelPages.isNotEmpty()) {
                    updateNovelHighlighting(position)
                } else {
                    updateTextHighlighting(body, position)
                }
                playerBottomSheet?.updateProgress(position, duration)
            }
            setOnCompleteListener {
                playerBottomSheet?.updatePlaybackState(false)
                btnPlay?.text = "Play"
                if (isNovelFormat) novelPageAdapter?.clearHighlighting()
                // AUG-FIX: Removed lazy stub analysis for next chapter - use "Analyse Chapters" button instead
                // This prevents overwriting real LLM analysis with stub data when chapter finishes.
            }
        }
        audioMixer = AudioMixer()
        audioMixer?.applyTheme(currentTheme)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val cid = if (chapterId != 0L) chapterId else vm.firstChapterId(bookId)
            AppLogger.d(tag, "Loading chapter: chapterId=$cid")
            loadedChapterId = cid ?: 0L

            var ch = cid?.let { vm.getChapter(it) }
            chapterText = ch?.body ?: "No chapter"
            chapterTitle = ch?.title ?: "Chapter"
            AppLogger.d(tag, "Chapter loaded: title=$chapterTitle, textLength=${chapterText.length}")
            // Log preview of chapter content for debugging
            val contentPreview = chapterText.take(300).replace("\n", " ").trim()
            AppLogger.d(tag, "Chapter content preview: \"$contentPreview...\"")

            // AUG-FIX: Removed lazy stub analysis - users should use "Analyse Chapters" button
            // for proper LLM-based analysis. This prevents overwriting real LLM analysis with stub data.
            // Only log if chapter is not yet analyzed so user knows they can use the button.
            if (ch != null && ch.fullAnalysisJson.isNullOrBlank() && chapterText.length > 50) {
                AppLogger.d(tag, "Chapter not yet analyzed - use 'Analyse Chapters' in book details for full LLM analysis")
            }

            // Parse analysis from DB if available
            if (chapterAnalysis == null) {
                val json = ch?.fullAnalysisJson ?: ch?.summaryJson
                chapterAnalysis = json?.let { j ->
                    try {
                        gson.fromJson(j, ChapterAnalysisResponse::class.java)
                    } catch (e: Exception) {
                        AppLogger.e(tag, "Failed to parse chapter analysis", e)
                        null
                    }
                }
            }

            // AUG-006: Trigger character extraction if chapter has analysis but no characters in DB
            if (ch != null && !ch.fullAnalysisJson.isNullOrBlank()) {
                val existingCharacters = app.db.characterDao().getByBookId(bookId).first()
                if (existingCharacters.isEmpty()) {
                    AppLogger.i(tag, "AUG-006: Chapter has analysis but no characters - triggering extraction in background")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Analyzing characters in background...", Toast.LENGTH_SHORT).show()
                    }
                    // Run extraction in parallel, don't block reading
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val chapters = app.bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
                            val extractionUseCase = ChapterCharacterExtractionUseCase(app.db.characterDao())
                            for ((idx, chapter) in chapters.withIndex()) {
                                if (!chapter.fullAnalysisJson.isNullOrBlank() && chapter.body.length > 50) {
                                    extractionUseCase.extractAndSave(
                                        bookId = bookId,
                                        chapterText = chapter.body,
                                        chapterIndex = idx,
                                        totalChapters = chapters.size
                                    )
                                }
                            }
                            // AUG-008: Merge characters after extraction
                            val characterJsonList = chapters.mapNotNull { chapter ->
                                chapter.fullAnalysisJson?.let { json ->
                                    try {
                                        val analysis = gson.fromJson(json, ChapterAnalysisResponse::class.java)
                                        analysis.characters?.let { Gson().toJson(it) }
                                    } catch (e: Exception) { null }
                                }
                            }
                            if (characterJsonList.isNotEmpty()) {
                                MergeCharactersUseCase(app.db.characterDao(), requireContext())
                                    .mergeAndSave(bookId, characterJsonList)
                            }
                            val finalCount = app.db.characterDao().getByBookId(bookId).first().size
                            withContext(Dispatchers.Main) {
                                if (isAdded) {
                                    Toast.makeText(requireContext(), "Found $finalCount characters!", Toast.LENGTH_SHORT).show()
                                }
                            }
                            AppLogger.i(tag, "AUG-006: Background character extraction complete: $finalCount characters")
                        } catch (e: Exception) {
                            AppLogger.e(tag, "AUG-006: Error in background character extraction", e)
                        }
                    }
                }
            }

            val segmentJob = async(Dispatchers.Default) {
                AppLogger.d(tag, "Building text segments for synchronization")
                TextAudioSync.buildSegments(chapterText, chapterAnalysis?.dialogs)
            }

            val initialSegments = segmentJob.await()

            // Rebuild segments with dialogs if analysis is available (use cache)
            textSegments = if (chapterAnalysis != null && cid != null) {
                CacheManager.getOrComputeTextSegments(cid, chapterText, chapterAnalysis?.dialogs) {
                    TextAudioSync.buildSegments(chapterText, chapterAnalysis?.dialogs)
                }
            } else {
                initialSegments
            }

            AppLogger.d(tag, "Built ${textSegments.size} text segments (cached: ${CacheManager.getCacheStats()})")

            // Parallelize UI preparation and database operations
            val sessionJob = async(Dispatchers.IO) {
                app.db.readingSessionDao().getCurrent()
            }

            val recapJob = async(Dispatchers.IO) {
                val finalCid = cid ?: return@async null
                val firstId = vm.firstChapterId(bookId)
                if (firstId != null && finalCid != firstId) {
                    vm.getRecapParagraph(bookId, finalCid)
                } else null
            }

            // Prepare text highlighting on background thread
            val highlightJob = async(Dispatchers.Default) {
                val session = sessionJob.await()
                val paraIndex = (session?.paragraphIndex ?: 0).coerceAtLeast(0)
                val paragraphs = chapterText.split("\n\n", "\n").filter { it.isNotBlank() }

                if (paragraphs.isNotEmpty() && paraIndex < paragraphs.size) {
                    val sb = SpannableString(chapterText)
                    var offset = 0
                    for (i in paragraphs.indices) {
                        val p = paragraphs[i]
                        val start = chapterText.indexOf(p, offset).coerceAtLeast(0)
                        val end = (start + p.length).coerceAtMost(chapterText.length)
                        if (i == paraIndex && end > start) {
                            // Use hardcoded color value instead of ContextCompat in background thread
                            val color = android.graphics.Color.argb(60, 68, 138, 255) // holo_blue_light equivalent
                            sb.setSpan(BackgroundColorSpan(color), start, end, 0)
                            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
                        }
                        offset = end
                    }
                    sb
                } else {
                    SpannableString(chapterText)
                }
            }

            // Update UI on main thread
            withContext(Dispatchers.Main) {
                if (isNovelFormat) {
                    // Check if chapter has PDF page info - display one PDF page per screen
                    preGeneratedAudio.clear()
                    val pdfPages = ch?.pdfPagesJson?.let { json ->
                        com.dramebaz.app.pdf.PdfChapterDetector.pdfPagesFromJson(json)
                    } ?: emptyList()

                    if (pdfPages.isNotEmpty()) {
                        // Use PDF pages directly - one PDF page per screen
                        var cumulativeOffset = 0
                        novelPages = pdfPages.mapIndexed { index, pdfPageInfo ->
                            val page = NovelPage(
                                pageNumber = index + 1,
                                text = pdfPageInfo.text,
                                lines = pdfPageInfo.text.split("\n"),
                                startOffset = cumulativeOffset,
                                pdfPageNumber = pdfPageInfo.pdfPage,
                                usePdfRendering = isPdfBook && pdfPageRenderer != null  // Enable native PDF rendering
                            )
                            cumulativeOffset += pdfPageInfo.text.length
                            page
                        }
                        AppLogger.i(tag, "Loaded ${novelPages.size} PDF pages (native rendering: ${isPdfBook && pdfPageRenderer != null})")
                    } else {
                        // Fallback: dynamically split text for non-PDF or older books without PDF page info
                        novelPages = NovelPageSplitter.splitIntoPages(chapterText, requireContext())
                        AppLogger.i(tag, "Split chapter (${chapterText.length} chars) into ${novelPages.size} screen pages (no PDF info)")
                    }

                    novelPageAdapter?.submitList(novelPages)
                    if (novelPages.isNotEmpty()) {
                        AppLogger.d(tag, "First page: ${novelPages[0].lines.size} lines, ${novelPages[0].text.length} chars")
                        if (novelPages.size > 1) {
                            AppLogger.d(tag, "Second page: ${novelPages[1].lines.size} lines, ${novelPages[1].text.length} chars")
                        }
                    }
                    Toast.makeText(requireContext(), "Chapter loaded: ${novelPages.size} pages. Swipe to turn pages.", Toast.LENGTH_SHORT).show()

                    // Load any saved page audio first, THEN pre-generate missing pages
                    // Use a sequential coroutine to avoid race condition
                    if (novelPages.isNotEmpty()) {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                // First, load saved audio (blocking within this coroutine)
                                val saved = app.pageAudioStorage.getSavedPagesForChapter(bookId, chapterId)
                                withContext(Dispatchers.Main) {
                                    saved.forEach { (index, file) ->
                                        if (file.exists()) {
                                            preGeneratedAudio[index] = file
                                            AppLogger.d(tag, "Loaded saved audio for page $index")
                                        }
                                    }
                                    AppLogger.i(tag, "Loaded ${saved.size} saved page audio files")
                                }
                                // THEN pre-generate missing pages (now we know what's already saved)
                                preGenerateAudioForPagesSync(0, minOf(2, novelPages.size))
                            } catch (e: Exception) {
                                AppLogger.e(tag, "Error loading/generating page audio", e)
                            }
                        }
                    }
                } else {
                    body?.text = highlightJob.await()
                }

                val recap = recapJob.await()
                if (recap != null && recap.isNotBlank() && recapCard != null) {
                    recapCard.visibility = View.VISIBLE
                    recapText?.text = recap
                }
            }

            val finalCid = cid ?: return@launch
            // Persist session for T6.1/T6.4 (must run on IO - Room disallows main thread)
            withContext(Dispatchers.IO) {
                app.db.readingSessionDao().insert(
                    ReadingSession(1, bookId, finalCid, 0, 0, modes.getOrElse(currentModeIndex) { "mixed" })
                )
            }

            // Prepare playback engine with chapter content (async, non-blocking)
            launch(Dispatchers.IO) {
                preparePlayback()
            }

            AppLogger.logPerformance(tag, "Chapter load and setup (parallel)", System.currentTimeMillis() - startTime)
        }

        view.findViewById<View>(R.id.recap_listen)?.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val text = recapText?.text?.toString() ?: ""
                if (text.isBlank()) {
                    Toast.makeText(requireContext(), "No text to speak", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val result = app.ttsEngine.speak(text, null, {}, null)
                result.onSuccess { audioFile ->
                    audioFile?.let {
                        playbackEngine?.stop()
                        // Use simple playback for recap
                        android.media.MediaPlayer().apply {
                            setDataSource(it.absolutePath)
                            prepare()
                            setOnCompletionListener { release() }
                            start()
                        }
                    } ?: run {
                        Toast.makeText(requireContext(), "TTS generated but no audio file", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { error ->
                    AppLogger.e("ReaderFragment", "TTS synthesis failed", error)
                    // AUG-039: Use ErrorDialog for better error display with retry
                    ErrorDialog.showWithRetry(
                        context = requireContext(),
                        title = "Voice Synthesis Failed",
                        message = "Failed to synthesize speech. Please try again.",
                        onRetry = { view.findViewById<View>(R.id.recap_listen)?.performClick() }
                    )
                }
            }
        }
        view.findViewById<View>(R.id.recap_skip)?.setOnClickListener { recapCard?.visibility = View.GONE }

        // T6.4: Mode toggle
        btnMode.text = "Mode: ${modes[currentModeIndex].replaceFirstChar { it.uppercase() }}"
        btnMode.setOnClickListener {
            currentModeIndex = (currentModeIndex + 1) % modes.size
            btnMode.text = "Mode: ${modes[currentModeIndex].replaceFirstChar { it.uppercase() }}"
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val cid = if (chapterId != 0L) chapterId else vm.firstChapterId(bookId) ?: 0L
                app.db.readingSessionDao().insert(
                    ReadingSession(1, bookId, cid, 0, 0, modes[currentModeIndex])
                )
            }
        }

        // Play/Pause button - uses AudioPlaybackService for background playback with pre-generated audio
        btnPlay?.setOnClickListener {
            // Check if service is playing
            val serviceIsPlaying = audioService?.isPlaying() == true
            val engineIsPlaying = playbackEngine?.isPlaying() == true
            val isPlaying = serviceIsPlaying || engineIsPlaying

            AppLogger.d(tag, "Play/Pause button clicked: serviceIsPlaying=$serviceIsPlaying, engineIsPlaying=$engineIsPlaying")

            if (isPlaying) {
                // Pause playback
                audioService?.pause()
                playbackEngine?.pause()
                btnPlay.text = "Play"
                playerBottomSheet?.updatePlaybackState(false)
                AppLogger.i(tag, "Playback paused")
            } else {
                // Check if we can resume existing playback
                if (audioService?.getDuration() ?: 0L > 0L) {
                    audioService?.resume()
                    btnPlay.text = "Pause"
                    playerBottomSheet?.updatePlaybackState(true)
                    AppLogger.i(tag, "Service playback resumed")
                } else {
                    // Play current page (uses pre-generated audio if available)
                    playCurrentPage()
                }
            }
        }

        // Open player controls bottom sheet
        view.findViewById<View>(R.id.btn_player_controls)?.setOnClickListener {
            showPlayerBottomSheet()
        }

        // Floating play: stop current playback and start from current page
        view.findViewById<View>(R.id.fab_play_current_page)?.setOnClickListener {
            audioService?.stop()
            playbackEngine?.stop()
            playingPageIndex = -1
            view?.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_play)?.text = "Play"
            playerBottomSheet?.updatePlaybackState(false)
            novelPageAdapter?.clearHighlighting()
            playCurrentPage()
        }

        // T7.2 + AUG-020: Smart Bookmark with context capture
        view.findViewById<View>(R.id.btn_bookmark)?.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val idToSave = when {
                    loadedChapterId != 0L -> loadedChapterId
                    chapterId != 0L -> chapterId
                    else -> vm.firstChapterId(bookId)
                }
                if (idToSave == null || idToSave == 0L) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) Toast.makeText(requireContext(), "No chapter to bookmark", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                if (bookId == 0L) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) Toast.makeText(requireContext(), "Cannot save bookmark", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val ch = vm.getChapter(idToSave)

                // AUG-020: Extract smart context from chapter analysis
                val analysis = chapterAnalysis
                val dialogs = analysis?.dialogs ?: emptyList()
                val emotionalArc = analysis?.chapterSummary?.emotionalArc ?: emptyList()

                // Get characters from dialogs (unique speakers)
                val charactersInvolved = dialogs
                    .map { it.speaker }
                    .filter { it != "narrator" && it != "unknown" }
                    .distinct()
                    .take(5)
                    .joinToString(", ")

                // Get emotion from emotional arc (use current position or first available)
                val emotionSnapshot = emotionalArc.firstOrNull()?.let { seg ->
                    "${seg.emotion} (${(seg.intensity * 100).toInt()}%)"
                } ?: "neutral"

                // Generate smart context summary
                val summary = buildString {
                    append("You stopped at ${ch?.title ?: "chapter"}")
                    if (charactersInvolved.isNotEmpty()) {
                        append(" with $charactersInvolved")
                    }
                    val emotionDesc = emotionalArc.firstOrNull()?.emotion ?: ""
                    if (emotionDesc.isNotEmpty() && emotionDesc != "neutral") {
                        append(" ($emotionDesc)")
                    }
                    append(".")
                }

                AppLogger.d(tag, "AUG-020: Creating smart bookmark - chars='$charactersInvolved', emotion='$emotionSnapshot'")

                val result = runCatching {
                    app.db.bookmarkDao().insert(
                        Bookmark(
                            bookId = bookId,
                            chapterId = idToSave,
                            paragraphIndex = 0,
                            contextSummary = summary,
                            charactersInvolved = charactersInvolved,
                            emotionSnapshot = emotionSnapshot
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), "Bookmark saved", Toast.LENGTH_SHORT).show()
                    } else {
                        AppLogger.e(tag, "Bookmark insert failed", result.exceptionOrNull())
                        // AUG-039: Use ErrorDialog for bookmark save failure
                        ErrorDialog.show(
                            context = requireContext(),
                            title = "Save Failed",
                            message = "Failed to save bookmark. Please try again."
                        )
                    }
                }
            }
        }
    }

    private suspend fun preparePlayback() = withContext(Dispatchers.IO) {
        val analysis = chapterAnalysis ?: run {
            AppLogger.w(tag, "preparePlayback: No chapter analysis available")
            return@withContext
        }
        val engine = playbackEngine ?: run {
            AppLogger.w(tag, "preparePlayback: PlaybackEngine not initialized")
            return@withContext
        }

        AppLogger.d(tag, "Preparing playback: dialogs=${analysis.dialogs?.size ?: 0}, " +
                "characters=${analysis.characters?.size ?: 0}")

        // Clear previous queue
        engine.stop()

        // Get character voice profiles and speaker IDs from database
        val characterMap = mutableMapOf<String, VoiceProfile?>()
        val speakerIdMap = mutableMapOf<String, Int?>()  // T11.1: Map character name to speaker ID
        analysis.characters?.forEach { charStub ->
            charStub.voiceProfile?.let { vpMap ->
                try {
                    val emotionBiasMap = (vpMap["emotion_bias"] as? Map<*, *>)?.mapNotNull { (key, value) ->
                        (key as? String)?.let { k ->
                            k to ((value as? Number)?.toFloat() ?: 0f)
                        }
                    }?.toMap() ?: emptyMap()
                    val vp = VoiceProfile(
                        pitch = (vpMap["pitch"] as? Number)?.toFloat() ?: 1.0f,
                        speed = (vpMap["speed"] as? Number)?.toFloat() ?: 1.0f,
                        energy = (vpMap["energy"] as? Number)?.toFloat() ?: 1.0f,
                        emotionBias = emotionBiasMap
                    )
                    characterMap[charStub.name] = vp

                    // T11.1: Load speaker ID from database for this character
                    val bookId = arguments?.getLong("bookId") ?: return@withContext
                    val dbCharacters = app.db.characterDao().getByBookId(bookId)
                    // Use first() to get the current value from Flow
                    val foundCharacter = dbCharacters.first().firstOrNull { it.name == charStub.name }
                    speakerIdMap[charStub.name] = foundCharacter?.speakerId

                    AppLogger.d(tag, "Parsed voice profile for character: ${charStub.name}, pitch=${vp.pitch}, speed=${vp.speed}, speakerId=${foundCharacter?.speakerId}")
                } catch (e: Exception) {
                    AppLogger.w(tag, "Failed to parse voice profile for ${charStub.name}", e)
                }
            }
        }
        AppLogger.d(tag, "Loaded ${characterMap.size} character voice profiles")

        // Split chapter text into narration and dialog segments
        val paragraphs = chapterText.split("\n\n", "\n").filter { it.isNotBlank() }
        val emotionalArc = analysis.chapterSummary?.emotionalArc ?: emptyList()
        AppLogger.d(tag, "Processing ${paragraphs.size} paragraphs with ${emotionalArc.size} emotional arc segments")
        var segmentIndex = 0

        for (paragraph in paragraphs) {
            // Check if paragraph contains dialog
            val paragraphDialogs = analysis.dialogs?.filter { dialog ->
                paragraph.contains(dialog.dialog, ignoreCase = true)
            } ?: emptyList()

            if (paragraphDialogs.isNotEmpty()) {
                // Split paragraph into narration and dialog
                var remainingText = paragraph
                for (dialog in paragraphDialogs) {
                    val dialogIndex = remainingText.indexOf(dialog.dialog, ignoreCase = true).takeIf { it >= 0 } ?: continue
                    if (dialogIndex > 0) {
                        // Narration before dialog
                        val narrationText = remainingText.substring(0, dialogIndex).trim()
                        if (narrationText.isNotBlank()) {
                            val voiceProfile = characterMap["Narrator"] ?: characterMap.values.firstOrNull()
                            val narratorSpeakerId = speakerIdMap["Narrator"]
                            engine.addNarration(narrationText, emotionalArc, segmentIndex, voiceProfile, narratorSpeakerId)
                            segmentIndex++
                        }
                    }
                    // Add dialog
                    val dialogVoiceProfile = characterMap[dialog.speaker]
                    engine.addDialog(dialog, dialogVoiceProfile)
                    remainingText = remainingText.substring(dialogIndex + dialog.dialog.length)
                }
                // Remaining narration after dialogs
                if (remainingText.isNotBlank()) {
                    val voiceProfile = characterMap["Narrator"] ?: characterMap.values.firstOrNull()
                    val narratorSpeakerId = speakerIdMap["Narrator"]
                    engine.addNarration(remainingText, emotionalArc, segmentIndex, voiceProfile, narratorSpeakerId)
                    segmentIndex++
                }
            } else {
                // Pure narration
                val voiceProfile = characterMap["Narrator"] ?: characterMap.values.firstOrNull()
                val narratorSpeakerId = speakerIdMap["Narrator"]
                engine.addNarration(paragraph, emotionalArc, segmentIndex, voiceProfile, narratorSpeakerId)
                segmentIndex++
            }
        }
        AppLogger.i(tag, "Playback prepared: ${segmentIndex} segments queued")

        // Pre-synthesize audio in background for faster playback start
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            AppLogger.d(tag, "Starting background pre-synthesis of audio segments")
            engine.preSynthesizeAudio()
        }
    }

    private fun updateTextHighlighting(textView: TextView, positionMs: Long) {
        if (textSegments.isEmpty()) return

        val currentSegment = TextAudioSync.findCurrentSegment(textSegments, positionMs)
        if (currentSegment != null) {
            val highlightColor = ContextCompat.getColor(requireContext(), android.R.color.holo_blue_light).let { c ->
                android.graphics.Color.argb(100, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
            }
            val highlighted = TextAudioSync.highlightCurrentSegment(
                chapterText,
                textSegments,
                positionMs,
                highlightColor
            )
            textView.text = highlighted
        }
    }

    /**
     * Update highlighting for novel page view - highlights the current line being read.
     */
    private fun updateNovelHighlighting(positionMs: Long) {
        if (textSegments.isEmpty() || novelPages.isEmpty()) return

        val currentSegment = TextAudioSync.findCurrentSegment(textSegments, positionMs)
        if (currentSegment != null) {
            // Find which page contains this segment using the startOffset
            var targetPageIndex = -1
            var targetLineIndex = -1

            // Find the page that contains the current segment
            for (pageIndex in novelPages.indices) {
                val page = novelPages[pageIndex]
                val pageStartOffset: Int = page.startOffset
                val pageEndOffset: Int = pageStartOffset + page.text.length

                // Check if the segment overlaps with this page
                if (currentSegment.startIndex.toInt() < pageEndOffset && currentSegment.endIndex.toInt() > pageStartOffset) {
                    targetPageIndex = pageIndex

                    // Find which line in the page contains the segment
                    // Use the segment's start position relative to the page
                    val segmentStartInPage: Int = currentSegment.startIndex.toInt() - pageStartOffset

                    // Find the line that contains this position
                    var cumulativeOffset: Int = 0
                    for (lineIndex in page.lines.indices) {
                        val line = page.lines[lineIndex]
                        val lineLength: Int = line.length
                        val lineEndOffset: Int = cumulativeOffset + lineLength

                        // Check if segment start falls within this line
                        if (segmentStartInPage >= cumulativeOffset && segmentStartInPage < lineEndOffset) {
                            targetLineIndex = lineIndex
                            break
                        }

                        // Move to next line (account for newline character)
                        cumulativeOffset = lineEndOffset
                        if (lineIndex < page.lines.size - 1) {
                            cumulativeOffset = cumulativeOffset + 1  // Account for \n between lines
                        }
                    }

                    // If we didn't find a line, use the first or last line as fallback
                    if (targetLineIndex < 0 && page.lines.isNotEmpty()) {
                        if (segmentStartInPage < 0) {
                            targetLineIndex = 0
                        } else {
                            targetLineIndex = page.lines.size - 1
                        }
                    }

                    break
                }
            }

            // Update highlighting if we found a page and line
            if (targetPageIndex >= 0 && targetLineIndex >= 0) {
                novelPageAdapter?.highlightLine(targetPageIndex, targetLineIndex)

                // Scroll to the page if needed (with smooth animation)
                viewPager?.let { pager ->
                    if (pager.currentItem != targetPageIndex) {
                        pager.setCurrentItem(targetPageIndex, true)
                    }
                }
            }
        }
    }

    private fun showPlayerBottomSheet() {
        playerBottomSheet = PlayerBottomSheet().apply {
            setListener(this@ReaderFragment)
        }
        playerBottomSheet?.show(parentFragmentManager, "PlayerBottomSheet")
    }

    // PlayerBottomSheet.PlayerControlsListener implementation
    override fun onPlayPause() {
        // Check if service is playing first (novel mode uses service)
        val serviceIsPlaying = audioService?.isPlaying() == true
        val engineIsPlaying = playbackEngine?.isPlaying() == true
        val isCurrentlyPlaying = serviceIsPlaying || engineIsPlaying

        AppLogger.d(tag, "onPlayPause: serviceIsPlaying=$serviceIsPlaying, engineIsPlaying=$engineIsPlaying")

        if (isCurrentlyPlaying) {
            // Pause both (whichever is active)
            audioService?.pause()
            playbackEngine?.pause()
            view?.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_play)?.text = "Play"
        } else {
            // Resume - check which one to resume
            if (audioService?.getDuration() ?: 0L > 0L) {
                audioService?.resume()
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    playbackEngine?.resume()
                    if (playbackEngine?.isPlaying() != true) {
                        playbackEngine?.play()
                    }
                }
            }
            view?.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_play)?.text = "Pause"
        }
    }

    override fun onSeek(position: Long) {
        // Seek on the active player
        if (audioService?.getDuration() ?: 0L > 0L) {
            audioService?.seekTo(position)
        } else {
            playbackEngine?.seekTo(position)
        }
    }

    override fun onRewind() {
        // Rewind 10 seconds on the active player
        val currentPos = audioService?.getCurrentPosition() ?: playbackEngine?.getCurrentPosition() ?: 0L
        val newPos = (currentPos - 10000).coerceAtLeast(0L)
        if (audioService?.getDuration() ?: 0L > 0L) {
            audioService?.seekTo(newPos)
        } else {
            playbackEngine?.seekTo(newPos)
        }
    }

    override fun onForward() {
        // Forward 10 seconds on the active player
        val currentPos = audioService?.getCurrentPosition() ?: playbackEngine?.getCurrentPosition() ?: 0L
        val duration = audioService?.getDuration() ?: playbackEngine?.getDuration() ?: 0L
        val newPos = (currentPos + 10000).coerceAtMost(duration)
        if (audioService?.getDuration() ?: 0L > 0L) {
            audioService?.seekTo(newPos)
        } else {
            playbackEngine?.seekTo(newPos)
        }
    }

    override fun onThemeChanged(theme: PlaybackTheme) {
        AppLogger.i(tag, "Theme changed: $theme")
        currentTheme = theme
        audioMixer?.applyTheme(theme)
    }

    override fun onSpeedChanged(speed: Float) {
        AppLogger.i(tag, "Speed changed: $speed")
        audioService?.setSpeed(speed)
    }

    /**
     * Show FAB when user is on a different page than the one playing; hide when on the playing page or not playing.
     */
    private fun updateFabPlayCurrentPageVisibility() {
        view ?: return
        val fab = view?.findViewById<View>(R.id.fab_play_current_page) ?: return
        val show = isNovelFormat && novelPages.isNotEmpty() &&
            playingPageIndex >= 0 && currentPageIndex != playingPageIndex
        fab.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** AUG-037: Track if pre-analysis has already been triggered for next chapter */
    private var nextChapterPreAnalysisTriggered = false

    /**
     * AUG-037: Pre-analyze next chapter when user reaches 80% of current chapter.
     * AUG-FIX: Removed stub analysis - now only logs that next chapter is not analyzed.
     * Users should use "Analyse Chapters" button for proper LLM-based analysis.
     */
    private fun triggerNextChapterPreAnalysis() {
        if (nextChapterPreAnalysisTriggered) return
        nextChapterPreAnalysisTriggered = true

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val chapters = app.bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
                val currentIdx = chapters.indexOfFirst { it.id == loadedChapterId }
                if (currentIdx < 0 || currentIdx + 1 >= chapters.size) return@launch

                val nextCh = chapters[currentIdx + 1]
                if (!nextCh.fullAnalysisJson.isNullOrBlank() || nextCh.body.length <= 50) {
                    AppLogger.d(tag, "AUG-037: Next chapter already analyzed or too short")
                    return@launch
                }

                // AUG-FIX: Just log that next chapter is not analyzed - don't run stub analysis
                AppLogger.d(tag, "AUG-037: Next chapter '${nextCh.title}' not yet analyzed - use 'Analyse Chapters' for LLM analysis")
            } catch (e: Exception) {
                AppLogger.e(tag, "AUG-037: Check for next chapter failed", e)
            }
        }
    }

    /**
     * Pre-generate audio for specified pages in background (Dispatchers.IO).
     * AUG-043: Updated to use per-segment audio generation with character voices,
     * then stitch segments into a single page audio file.
     * - On page change: next page is pre-generated.
     * - When playback starts: next page is pre-generated.
     * NOTE: On chapter load, use preGenerateAudioForPagesSync() after loading saved audio.
     * When current page finishes, playback flips to next page and plays its pre-generated audio.
     */
    private fun preGenerateAudioForPages(startPage: Int, count: Int) {
        if (novelPages.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                for (i in startPage until minOf(startPage + count, novelPages.size)) {
                    if (preGeneratedAudio.containsKey(i)) {
                        AppLogger.d(tag, "Audio already available for page $i")
                        continue
                    }
                    // Check for saved stitched audio first
                    val saved = app.pageAudioStorage.getAudioFile(bookId, chapterId, i)
                    if (saved != null) {
                        withContext(Dispatchers.Main) { preGeneratedAudio[i] = saved }
                        AppLogger.i(tag, "Using saved audio for page $i")
                        continue
                    }
                    val pageText = novelPages[i].text
                    if (pageText.isBlank()) continue

                    // AUG-043: Generate per-segment audio with character voices
                    val pageNumber = i + 1  // 1-based page number
                    val allDialogs = chapterAnalysis?.dialogs
                    val dialogs = if (allDialogs != null) {
                        app.segmentAudioGenerator.getDialogsForPage(pageText, allDialogs)
                    } else {
                        emptyList()
                    }

                    AppLogger.d(tag, "Pre-generating audio for page $i: analysis=${chapterAnalysis != null}, totalDialogs=${allDialogs?.size ?: 0}, matchedDialogs=${dialogs.size}, pageChars=${pageText.length}")

                    val segmentFiles = app.segmentAudioGenerator.generatePageAudio(
                        bookId = bookId,
                        chapterId = chapterId,
                        pageNumber = pageNumber,
                        pageText = pageText,
                        dialogs = dialogs
                    )

                    if (segmentFiles.isNotEmpty()) {
                        // Stitch segments into a single page audio file
                        val stitchedFile = app.pageAudioStorage.getAudioFilePath(bookId, chapterId, i)
                        val result = com.dramebaz.app.audio.AudioStitcher.stitchWavFiles(segmentFiles, stitchedFile)

                        if (result != null) {
                            withContext(Dispatchers.Main) { preGeneratedAudio[i] = result }
                            AppLogger.i(tag, "Pre-generated and stitched audio for page $i: ${result.absolutePath}")
                        } else {
                            AppLogger.w(tag, "Failed to stitch audio for page $i")
                        }
                    } else {
                        // Fallback: generate single TTS for whole page (no dialogs found)
                        AppLogger.d(tag, "No segments generated for page $i, using single TTS fallback")
                        val result = app.ttsEngine.speak(pageText, null, null, null)
                        result.onSuccess { audioFile ->
                            audioFile?.let { file ->
                                val persisted = app.pageAudioStorage.saveAudioFile(bookId, chapterId, i, file)
                                withContext(Dispatchers.Main) { preGeneratedAudio[i] = persisted }
                                AppLogger.i(tag, "Pre-generated fallback audio for page $i: ${persisted.absolutePath}")
                            }
                        }.onFailure { error ->
                            AppLogger.e(tag, "Failed to pre-generate fallback audio for page $i", error)
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "Error in preGenerateAudioForPages", e)
            }
        }
    }

    /**
     * Synchronous version of preGenerateAudioForPages - must be called from a coroutine.
     * Used when we need to ensure saved audio is loaded before generating.
     */
    private suspend fun preGenerateAudioForPagesSync(startPage: Int, count: Int) {
        if (novelPages.isEmpty()) return
        try {
            for (i in startPage until minOf(startPage + count, novelPages.size)) {
                if (preGeneratedAudio.containsKey(i)) {
                    AppLogger.d(tag, "Audio already available for page $i")
                    continue
                }
                // Check for saved stitched audio first
                val saved = app.pageAudioStorage.getAudioFile(bookId, chapterId, i)
                if (saved != null) {
                    withContext(Dispatchers.Main) { preGeneratedAudio[i] = saved }
                    AppLogger.i(tag, "Using saved audio for page $i")
                    continue
                }
                val pageText = novelPages[i].text
                if (pageText.isBlank()) continue

                // AUG-043: Generate per-segment audio with character voices
                val pageNumber = i + 1  // 1-based page number
                val allDialogs = chapterAnalysis?.dialogs
                val dialogs = if (allDialogs != null) {
                    app.segmentAudioGenerator.getDialogsForPage(pageText, allDialogs)
                } else {
                    emptyList()
                }

                AppLogger.d(tag, "Pre-generating audio for page $i: analysis=${chapterAnalysis != null}, totalDialogs=${allDialogs?.size ?: 0}, matchedDialogs=${dialogs.size}, pageChars=${pageText.length}")

                val segmentFiles = app.segmentAudioGenerator.generatePageAudio(
                    bookId = bookId,
                    chapterId = chapterId,
                    pageNumber = pageNumber,
                    pageText = pageText,
                    dialogs = dialogs
                )

                if (segmentFiles.isNotEmpty()) {
                    // Stitch segments into a single page audio file
                    val stitchedFile = app.pageAudioStorage.getAudioFilePath(bookId, chapterId, i)
                    val result = com.dramebaz.app.audio.AudioStitcher.stitchWavFiles(segmentFiles, stitchedFile)

                    if (result != null) {
                        withContext(Dispatchers.Main) { preGeneratedAudio[i] = result }
                        AppLogger.i(tag, "Pre-generated and stitched audio for page $i: ${result.absolutePath}")
                    } else {
                        AppLogger.w(tag, "Failed to stitch audio for page $i")
                    }
                } else {
                    // Fallback: generate single TTS for whole page (no dialogs found)
                    AppLogger.d(tag, "No segments generated for page $i, using single TTS fallback")
                    val result = app.ttsEngine.speak(pageText, null, null, null)
                    result.onSuccess { audioFile ->
                        audioFile?.let { file ->
                            val persisted = app.pageAudioStorage.saveAudioFile(bookId, chapterId, i, file)
                            withContext(Dispatchers.Main) { preGeneratedAudio[i] = persisted }
                            AppLogger.i(tag, "Pre-generated fallback audio for page $i: ${persisted.absolutePath}")
                        }
                    }.onFailure { error ->
                        AppLogger.e(tag, "Failed to pre-generate fallback audio for page $i", error)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Error in preGenerateAudioForPagesSync", e)
        }
    }

    /**
     * Play audio for the current page, using pre-generated audio if available.
     */
    private fun playCurrentPage() {
        // AUG-041: Check if TTS is available before attempting playback
        if (DegradedModeManager.ttsMode.value == DegradedModeManager.TtsMode.DISABLED) {
            ErrorDialog.showWithRetry(
                context = requireContext(),
                title = "Voice Unavailable",
                message = DegradedModeManager.getTtsDegradedMessage(),
                onRetry = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { app.ttsEngine.retryInit() }
                        if (DegradedModeManager.ttsMode.value == DegradedModeManager.TtsMode.FULL) {
                            playCurrentPage()
                        }
                    }
                }
            )
            return
        }

        val pageIndex = viewPager?.currentItem ?: 0
        currentPageIndex = pageIndex

        viewLifecycleOwner.lifecycleScope.launch {
            val btnPlay = view?.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_play)

            // Check for pre-generated or saved audio first
            val preGenAudio = preGeneratedAudio[pageIndex]
            if (preGenAudio != null && preGenAudio.exists()) {
                AppLogger.d(tag, "Using pre-generated/saved audio for page $pageIndex")
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    val serviceIntent = Intent(requireContext(), AudioPlaybackService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        requireContext().startForegroundService(serviceIntent)
                    } else {
                        requireContext().startService(serviceIntent)
                    }
                    playingPageIndex = pageIndex
                    audioService?.playAudioFile(preGenAudio, bookTitle, "$chapterTitle - Page ${pageIndex + 1}", null)
                    btnPlay?.text = "Pause"
                    playerBottomSheet?.updatePlaybackState(true)
                    updateFabPlayCurrentPageVisibility()
                    if (pageIndex + 1 < novelPages.size && !preGeneratedAudio.containsKey(pageIndex + 1)) {
                        preGenerateAudioForPages(pageIndex + 1, 1)
                    }
                }
                return@launch
            }

            // Generate audio on demand
            val pageText = if (pageIndex < novelPages.size) novelPages[pageIndex].text else chapterText.take(2000)
            if (pageText.isBlank()) {
                Toast.makeText(requireContext(), "No text on this page", Toast.LENGTH_SHORT).show()
                return@launch
            }

            Toast.makeText(requireContext(), "Generating audio...", Toast.LENGTH_SHORT).show()
            btnPlay?.isEnabled = false

            withContext(Dispatchers.IO) {
                AppLogger.d(tag, "Generating audio for page $pageIndex: ${pageText.length} chars")
                val result = app.ttsEngine.speak(pageText, null, null, null)
                result.onSuccess { audioFile ->
                    audioFile?.let { file ->
                        val persisted = app.pageAudioStorage.saveAudioFile(bookId, chapterId, pageIndex, file)
                        withContext(Dispatchers.Main) {
                            preGeneratedAudio[pageIndex] = persisted
                        }
                        withContext(Dispatchers.Main) main@ {
                            if (!isAdded) return@main
                            btnPlay?.isEnabled = true
                            val serviceIntent = Intent(requireContext(), AudioPlaybackService::class.java)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                requireContext().startForegroundService(serviceIntent)
                            } else {
                                requireContext().startService(serviceIntent)
                            }
                            playingPageIndex = pageIndex
                            audioService?.playAudioFile(persisted, bookTitle, "$chapterTitle - Page ${pageIndex + 1}", null)
                            btnPlay?.text = "Pause"
                            playerBottomSheet?.updatePlaybackState(true)
                            updateFabPlayCurrentPageVisibility()
                            if (pageIndex + 1 < novelPages.size) {
                                preGenerateAudioForPages(pageIndex + 1, 1)
                            }
                        }
                    } ?: run {
                        withContext(Dispatchers.Main) {
                            btnPlay?.isEnabled = true
                            // AUG-039: Use ErrorDialog for failed audio generation
                            ErrorDialog.show(
                                context = requireContext(),
                                title = "Audio Generation Failed",
                                message = "Failed to generate audio for this page. Please try again."
                            )
                        }
                    }
                }.onFailure { error ->
                    withContext(Dispatchers.Main) {
                        btnPlay?.isEnabled = true
                        // AUG-039: Use ErrorDialog with retry for TTS errors
                        ErrorDialog.showWithRetry(
                            context = requireContext(),
                            title = "Voice Synthesis Error",
                            message = "Failed to synthesize speech: ${error.message ?: "Unknown error"}",
                            onRetry = { playCurrentPage() }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        AppLogger.d(tag, "onDestroyView: Cleaning up playback components")

        // Stop memory monitoring
        memoryMonitor?.stop()
        memoryMonitor = null

        // Clean up PDF renderer and adapter
        novelPageAdapter?.cleanup()
        pdfPageRenderer?.close()
        pdfPageRenderer = null
        novelPageAdapter = null

        playbackEngine?.cleanup()
        audioMixer?.cleanup()
        playbackEngine = null
        audioMixer = null
        playerBottomSheet = null

        // Note: We don't stop the service here - it continues playing in background
        // The back button handler stops the service when user explicitly navigates back
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unbind service but don't stop it - allows background playback
        if (isServiceBound) {
            try {
                context?.unbindService(serviceConnection)
                isServiceBound = false
            } catch (e: Exception) {
                AppLogger.w(tag, "Error unbinding service", e)
            }
        }
    }

    /**
     * AUG-041: Setup degraded mode banner and observe mode changes.
     * Shows a banner when LLM or TTS is running in fallback/disabled mode.
     */
    private fun setupDegradedModeBanner(view: View) {
        val banner = view.findViewById<View>(R.id.degraded_mode_banner) ?: return
        val bannerText = view.findViewById<TextView>(R.id.degraded_mode_text) ?: return
        val retryButton = view.findViewById<View>(R.id.degraded_mode_retry) ?: return

        // Update banner visibility based on degraded mode state
        fun updateBanner() {
            val statusMessage = DegradedModeManager.getStatusMessage()
            if (statusMessage != null) {
                bannerText.text = statusMessage
                banner.visibility = View.VISIBLE
            } else {
                banner.visibility = View.GONE
            }
        }

        // Retry button - attempt to reload models
        retryButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                bannerText.text = "Retrying..."
                retryButton.isEnabled = false

                // Retry LLM if it failed
                if (DegradedModeManager.llmMode.value == DegradedModeManager.LlmMode.STUB_FALLBACK) {
                    withContext(Dispatchers.IO) {
                        QwenStub.retryLoadModel(requireContext())
                    }
                }

                // Retry TTS if it failed
                if (DegradedModeManager.ttsMode.value == DegradedModeManager.TtsMode.DISABLED) {
                    withContext(Dispatchers.IO) {
                        app.ttsEngine.retryInit()
                    }
                }

                retryButton.isEnabled = true
                updateBanner()
            }
        }

        // Observe LLM mode changes
        viewLifecycleOwner.lifecycleScope.launch {
            DegradedModeManager.llmMode.collect { updateBanner() }
        }

        // Observe TTS mode changes
        viewLifecycleOwner.lifecycleScope.launch {
            DegradedModeManager.ttsMode.collect { updateBanner() }
        }

        // Initial check
        updateBanner()
    }
}

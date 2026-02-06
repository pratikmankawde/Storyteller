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
import com.dramebaz.app.domain.usecases.ChapterCharacterExtractionUseCase
import com.dramebaz.app.domain.usecases.ChapterLookaheadManager
import com.dramebaz.app.domain.usecases.MergeCharactersUseCase
import com.dramebaz.app.data.db.Bookmark
import com.dramebaz.app.data.db.ReadingSession
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.EmotionalSegment
import com.dramebaz.app.data.models.ReadingMode
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.playback.engine.AudioBufferManager
import com.dramebaz.app.playback.engine.KaraokeHighlighter
import com.dramebaz.app.playback.engine.PlaybackEngine
import com.dramebaz.app.playback.engine.TextAudioSync
import com.dramebaz.app.playback.mixer.AudioMixer
import com.dramebaz.app.playback.mixer.PlaybackTheme
import com.dramebaz.app.playback.service.AudioPlaybackService
import com.dramebaz.app.ui.player.PlayerBottomSheet
import com.dramebaz.app.utils.MemoryMonitor
import com.dramebaz.app.ui.common.ErrorDialog
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.utils.CacheManager
import com.dramebaz.app.utils.DegradedModeManager
import com.dramebaz.app.pdf.PdfPageRenderer
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.ai.llm.pipeline.PassConfig
import com.dramebaz.app.ai.llm.pipeline.ThemeAnalysisPass
import com.dramebaz.app.ai.llm.prompts.ThemeAnalysisInput
import com.dramebaz.app.ai.tts.VoiceConsistencyChecker
import com.dramebaz.app.data.models.GeneratedTheme
import com.dramebaz.app.ui.theme.DynamicThemeManager
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    // READ-001: Reading Mode Toggle - Using enum instead of legacy string-based modes
    private var currentReadingMode: ReadingMode = ReadingMode.MIXED

    // Playback components
    private var playbackEngine: PlaybackEngine? = null
    private var audioMixer: AudioMixer? = null
    // UI-003: Character avatar view for showing current speaker
    private var characterAvatarView: CharacterAvatarView? = null
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
                    // READ-002: Notify buffer manager of playback progress
                    if (duration > 0) {
                        val progress = position.toFloat() / duration.toFloat()
                        audioBufferManager?.onPlaybackProgress(progress, novelPages.size)
                    }
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

    // READ-002: Audio Buffer Manager for seamless playback
    private var audioBufferManager: com.dramebaz.app.playback.engine.AudioBufferManager? = null

    // READ-003: Chapter Lookahead Manager for pre-analyzing next chapter
    private var chapterLookaheadManager: ChapterLookaheadManager? = null

    // Memory monitoring
    private var memoryMonitor: MemoryMonitor? = null

    // THEME-001: Dynamic theme manager for generative UI theming
    private var dynamicThemeManager: DynamicThemeManager? = null

    // VOICE-002: Voice consistency checker
    private var voiceConsistencyChecker: VoiceConsistencyChecker? = null

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

        // THEME-001: Initialize dynamic theme manager
        dynamicThemeManager = DynamicThemeManager(requireContext())

        // VOICE-002: Initialize voice consistency checker
        voiceConsistencyChecker = VoiceConsistencyChecker(app.db.characterDao())

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

            // LIBRARY-001: Update reading progress as user reads through pages
            if (novelPages.isNotEmpty()) {
                val chapterProgress = (pageIndex + 1).toFloat() / novelPages.size
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    updateBookReadingProgress(chapterProgress)
                }
            }
        }

        viewPager?.adapter = novelPageAdapter
        viewPager?.setPageTransformer(PageTurnTransformer())

        // Configure ViewPager2 for optimal 3D page turning experience
        viewPager?.offscreenPageLimit = 2 // Keep 2 pages off-screen for smoother transitions
        viewPager?.clipToPadding = false
        viewPager?.clipChildren = false

        // SETTINGS-002: Observe reading settings and apply to adapter
        viewLifecycleOwner.lifecycleScope.launch {
            app.settingsRepository.readingSettings.collectLatest { settings ->
                AppLogger.d(tag, "Reading settings updated: fontSize=${settings.fontSize}, lineHeight=${settings.lineHeight}, theme=${settings.theme}")
                novelPageAdapter?.updateReadingSettings(settings)
            }
        }

        // AUG-041: Setup degraded mode banner
        setupDegradedModeBanner(view)

        val body = view.findViewById<TextView>(R.id.body) // May be null in novel view
        val recapCard = view.findViewById<MaterialCardView>(R.id.recap_card) // May be null in novel view
        val recapText = view.findViewById<TextView>(R.id.recap_text) // May be null in novel view
        val btnMode = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_mode)
        val btnPlay = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_play)
        // UI-003: Character avatar for showing current speaker
        characterAvatarView = view.findViewById(R.id.character_avatar)

        // Initialize playback components
        AppLogger.d(tag, "Initializing PlaybackEngine and AudioMixer")
        playbackEngine = PlaybackEngine(requireContext(), app.ttsEngine, viewLifecycleOwner.lifecycleScope).apply {
            setOnProgressListener { position, duration ->
                // Use novel highlighting when in novel format, otherwise use text highlighting
                if (isNovelFormat && novelPages.isNotEmpty()) {
                    updateNovelHighlighting(position)
                    // READ-002: Notify buffer manager of playback progress
                    if (duration > 0) {
                        val progress = position.toFloat() / duration.toFloat()
                        audioBufferManager?.onPlaybackProgress(progress, novelPages.size)
                    }
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

        // SETTINGS-001: Initialize audio settings from repository
        val audioSettings = app.settingsRepository.audioSettings.value
        currentTheme = audioSettings.playbackTheme

        audioMixer = AudioMixer()
        audioMixer?.applyTheme(currentTheme)

        // READ-002: Initialize Audio Buffer Manager for seamless playback
        audioBufferManager = AudioBufferManager(
            scope = viewLifecycleOwner.lifecycleScope,
            onPrepareAudio = { startPage, count ->
                preGenerateAudioForPagesAsync(startPage, count)
            },
            onBufferCleared = { pageIndex ->
                // Remove from preGeneratedAudio map when buffer is cleared
                preGeneratedAudio.remove(pageIndex)
                AppLogger.d(tag, "Audio buffer cleared for page $pageIndex")
            }
        )

        // READ-003: Initialize Chapter Lookahead Manager for pre-analyzing next chapter
        chapterLookaheadManager = ChapterLookaheadManager(
            scope = viewLifecycleOwner.lifecycleScope,
            bookRepository = app.bookRepository
        )

        // READ-003: Observe lookahead state for subtle UI indicator
        viewLifecycleOwner.lifecycleScope.launch {
            chapterLookaheadManager?.lookaheadState?.collectLatest { state ->
                when (state) {
                    is ChapterLookaheadManager.LookaheadState.Analyzing -> {
                        AppLogger.d(tag, "Lookahead analyzing: ${state.chapterTitle}")
                        // Could show a subtle indicator here (optional)
                    }
                    is ChapterLookaheadManager.LookaheadState.Complete -> {
                        AppLogger.i(tag, "Lookahead complete: ${state.chapterTitle}")
                    }
                    is ChapterLookaheadManager.LookaheadState.Failed -> {
                        AppLogger.w(tag, "Lookahead failed: ${state.error}")
                    }
                    ChapterLookaheadManager.LookaheadState.Idle -> {}
                }
            }
        }

        // UI-001: Set up karaoke highlighting observer
        setupKaraokeHighlightingObserver()

        // UI-002: Set up waveform amplitude observer
        setupWaveformObserver()

        // UI-003: Set up character avatar observer
        setupCharacterAvatarObserver()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // READ-001: Load saved reading mode preference first
            loadSavedReadingMode()

            val startTime = System.currentTimeMillis()
            val cid = if (chapterId != 0L) chapterId else vm.firstChapterId(bookId)
            AppLogger.d(tag, "Loading chapter: chapterId=$cid")
            loadedChapterId = cid ?: 0L

            var ch = cid?.let { vm.getChapter(it) }
            chapterText = ch?.body ?: "No chapter"
            chapterTitle = ch?.title ?: "Chapter"
            AppLogger.d(tag, "Chapter loaded: title=$chapterTitle, textLength=${chapterText.length}")

            // LIBRARY-001: Update last read timestamp when book is opened
            app.bookRepository.updateLastReadAt(bookId)
            AppLogger.d(tag, "LIBRARY-001: Updated lastReadAt for book $bookId")
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
            // AUG-FEATURE: Only run smart casting if enableSmartCasting is true
            val smartCastingEnabled = app.settingsRepository.featureSettings.first().enableSmartCasting
            if (ch != null && !ch.fullAnalysisJson.isNullOrBlank() && smartCastingEnabled) {
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

            // SUMMARY-001: Use time-aware recap instead of simple recap
            val recapJob = async(Dispatchers.IO) {
                val finalCid = cid ?: return@async null
                val firstId = vm.firstChapterId(bookId)
                if (firstId != null && finalCid != firstId) {
                    val timeAwareResult = vm.getTimeAwareRecap(bookId, finalCid)
                    vm.formatRecapForDisplay(timeAwareResult)
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

                // THEME-001: Generate and apply dynamic theme based on book content
                applyGenerativeTheme(view, bookTitle, chapterText)

                // VOICE-002: Check voice consistency for characters
                checkVoiceConsistency(view)
            }

            val finalCid = cid ?: return@launch
            // Persist session for T6.1/T6.4 (must run on IO - Room disallows main thread)
            withContext(Dispatchers.IO) {
                app.db.readingSessionDao().insert(
                    ReadingSession(1, bookId, finalCid, 0, 0, ReadingMode.toLegacyString(currentReadingMode))
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

        // READ-001: Mode toggle using ReadingMode enum
        updateModeButtonUI(btnMode)
        applyReadingMode() // Apply initial mode state
        btnMode.setOnClickListener {
            currentReadingMode = ReadingMode.next(currentReadingMode)
            updateModeButtonUI(btnMode)
            applyReadingMode()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val cid = if (chapterId != 0L) chapterId else vm.firstChapterId(bookId) ?: 0L
                app.db.readingSessionDao().insert(
                    ReadingSession(1, bookId, cid, 0, 0, ReadingMode.toLegacyString(currentReadingMode))
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

        // AUG-FEATURE: Get enableEmotionModifiers setting for prosody control
        val featureSettings = app.settingsRepository.featureSettings.first()
        val enableEmotionModifiers = featureSettings.enableEmotionModifiers
        AppLogger.d(tag, "Preparing playback: dialogs=${analysis.dialogs?.size ?: 0}, " +
                "characters=${analysis.characters?.size ?: 0}, enableEmotionModifiers=$enableEmotionModifiers")

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
                    // Add dialog - AUG-FEATURE: Pass enableEmotionModifiers setting
                    val dialogVoiceProfile = characterMap[dialog.speaker]
                    val dialogSpeakerId = speakerIdMap[dialog.speaker]
                    engine.addDialog(dialog, dialogVoiceProfile, dialogSpeakerId, enableEmotionModifiers)
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

    /**
     * UI-001: Set up observer for karaoke text highlighting.
     * Observes activeSegmentRange and currentWordSegment from PlaybackEngine
     * and applies word-by-word highlighting with smooth animations.
     */
    private fun setupKaraokeHighlightingObserver() {
        val engine = playbackEngine ?: return

        // AUG-FEATURE: Check if karaoke highlighting is enabled
        viewLifecycleOwner.lifecycleScope.launch {
            val featureSettings = app.settingsRepository.featureSettings.first()
            if (!featureSettings.enableKaraokeHighlight) {
                AppLogger.d(tag, "UI-001: Karaoke highlighting disabled, skipping setup")
                return@launch
            }

            // Define highlight colors
            val segmentHighlightColor = ContextCompat.getColor(requireContext(), android.R.color.holo_blue_light).let { c ->
                android.graphics.Color.argb(60, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
            }
            val wordHighlightColor = ContextCompat.getColor(requireContext(), android.R.color.holo_orange_light).let { c ->
                android.graphics.Color.argb(120, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
            }

            // Observe karaoke highlighting StateFlows
            combine(
                engine.activeSegmentRange,
                engine.currentWordSegment
            ) { segmentRange, currentWord ->
                Pair(segmentRange, currentWord)
            }.collectLatest { (segmentRange, currentWord) ->
                if (segmentRange != null && chapterText.isNotEmpty()) {
                    if (isNovelFormat && novelPages.isNotEmpty()) {
                        // Apply karaoke highlighting to novel page view
                        applyKaraokeToNovelView(segmentRange, currentWord, segmentHighlightColor, wordHighlightColor)
                    } else {
                        // Apply karaoke highlighting to text view (non-novel format)
                        val body = view?.findViewById<TextView>(R.id.body)
                        body?.let { textView ->
                            val highlighted = KaraokeHighlighter.highlightWord(
                                fullText = chapterText,
                                segmentRange = segmentRange,
                                currentWord = currentWord,
                                segmentHighlightColor = segmentHighlightColor,
                                wordHighlightColor = wordHighlightColor
                            )
                            textView.text = highlighted
                        }
                    }
                }
            }

            AppLogger.d(tag, "UI-001: Karaoke highlighting observer set up")
        }
    }

    /**
     * UI-001: Apply karaoke highlighting to novel page view.
     * Highlights current segment and word across pages.
     */
    private fun applyKaraokeToNovelView(
        segmentRange: KaraokeHighlighter.TextRange,
        currentWord: KaraokeHighlighter.WordSegment?,
        segmentHighlightColor: Int,
        wordHighlightColor: Int
    ) {
        // Find which page contains the current segment
        for (pageIndex in novelPages.indices) {
            val page = novelPages[pageIndex]
            val pageStartOffset = page.startOffset
            val pageEndOffset = pageStartOffset + page.text.length

            // Check if segment overlaps with this page
            if (segmentRange.start < pageEndOffset && segmentRange.end > pageStartOffset) {
                // Calculate relative positions within the page
                val relativeSegmentStart = (segmentRange.start - pageStartOffset).coerceAtLeast(0)
                val relativeSegmentEnd = (segmentRange.end - pageStartOffset).coerceAtMost(page.text.length)

                val pageSegmentRange = KaraokeHighlighter.TextRange(
                    start = relativeSegmentStart,
                    end = relativeSegmentEnd,
                    text = segmentRange.text,
                    isDialog = segmentRange.isDialog,
                    speaker = segmentRange.speaker
                )

                // Adjust word position relative to page
                val pageWord = currentWord?.let { word ->
                    if (word.startIndex >= pageStartOffset && word.endIndex <= pageEndOffset) {
                        KaraokeHighlighter.WordSegment(
                            word = word.word,
                            startIndex = word.startIndex - pageStartOffset,
                            endIndex = word.endIndex - pageStartOffset,
                            audioStartMs = word.audioStartMs,
                            audioEndMs = word.audioEndMs
                        )
                    } else null
                }

                // Apply highlighting to this page
                novelPageAdapter?.applyKaraokeHighlighting(
                    pageIndex = pageIndex,
                    segmentRange = pageSegmentRange,
                    currentWord = pageWord,
                    segmentHighlightColor = segmentHighlightColor,
                    wordHighlightColor = wordHighlightColor
                )

                // Ensure the page is visible
                viewPager?.let { pager ->
                    if (pager.currentItem != pageIndex) {
                        pager.setCurrentItem(pageIndex, true)
                    }
                }

                break
            }
        }
    }

    /**
     * UI-002: Set up observer for waveform amplitude visualization.
     * Observes audioAmplitude from PlaybackEngine and updates PlayerBottomSheet waveform.
     */
    private fun setupWaveformObserver() {
        val engine = playbackEngine ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            engine.audioAmplitude.collect { amplitude ->
                playerBottomSheet?.updateWaveformAmplitude(amplitude)
            }
        }

        AppLogger.d(tag, "UI-002: Waveform amplitude observer set up")
    }

    /**
     * UI-003: Set up observer for character avatar bubbles.
     * Observes currentSpeaker from PlaybackEngine and updates CharacterAvatarView.
     */
    private fun setupCharacterAvatarObserver() {
        val engine = playbackEngine ?: return
        val avatarView = characterAvatarView ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            engine.currentSpeaker.collect { speakerInfo ->
                withContext(Dispatchers.Main) {
                    if (speakerInfo != null) {
                        avatarView.visibility = View.VISIBLE
                        avatarView.setCharacter(speakerInfo.name, speakerInfo.speakerId)
                        avatarView.setState(CharacterAvatarView.AvatarState.SPEAKING)
                    } else {
                        avatarView.setState(CharacterAvatarView.AvatarState.IDLE)
                    }
                }
            }
        }

        // Also observe isPlayingState to hide avatar when playback stops
        viewLifecycleOwner.lifecycleScope.launch {
            engine.isPlayingState.collect { isPlaying ->
                withContext(Dispatchers.Main) {
                    if (!isPlaying) {
                        avatarView.setState(CharacterAvatarView.AvatarState.IDLE)
                        avatarView.visibility = View.GONE
                    }
                }
            }
        }

        AppLogger.d(tag, "UI-003: Character avatar observer set up")
    }

    private fun showPlayerBottomSheet() {
        // SETTINGS-001: Pass audio settings from repository
        val audioSettings = app.settingsRepository.audioSettings.value
        playerBottomSheet = PlayerBottomSheet.newInstance(audioSettings).apply {
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
        // SETTINGS-001: Persist to settings repository
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val currentSettings = app.settingsRepository.audioSettings.value
            app.settingsRepository.updateAudioSettings(currentSettings.copy(playbackTheme = theme))
        }
    }

    override fun onSpeedChanged(speed: Float) {
        AppLogger.i(tag, "Speed changed: $speed")
        audioService?.setSpeed(speed)
        // SETTINGS-001: Persist to settings repository
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val currentSettings = app.settingsRepository.audioSettings.value
            app.settingsRepository.updateAudioSettings(currentSettings.copy(playbackSpeed = speed))
        }
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

    /**
     * THEME-001: Generate and apply dynamic theme based on book content.
     * Uses LLM to analyze mood/genre and applies appropriate colors and fonts.
     */
    private fun applyGenerativeTheme(view: View, title: String, chapterText: String) {
        val themeManager = dynamicThemeManager ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // AUG-FEATURE: Check if generative visuals are enabled
                val featureSettings = app.settingsRepository.featureSettings.first()
                if (!featureSettings.enableGenerativeVisuals) {
                    AppLogger.d(tag, "THEME-001: Generative visuals disabled, skipping theme generation")
                    return@launch
                }

                // Check if theme already exists for this book
                val existingTheme = themeManager.loadTheme(bookId)
                if (existingTheme != null) {
                    AppLogger.d(tag, "THEME-001: Using cached theme for book $bookId: mood=${existingTheme.mood}")
                    withContext(Dispatchers.Main) {
                        themeManager.applyTheme(existingTheme, view)
                    }
                    return@launch
                }

                // Generate new theme using modular ThemeAnalysisPass
                AppLogger.d(tag, "THEME-001: Generating theme for book: $title")
                val generatedTheme = try {
                    val model = LlmService.getModel()
                    if (model != null) {
                        val pass = ThemeAnalysisPass()
                        val input = ThemeAnalysisInput(
                            bookId = bookId,
                            title = title,
                            firstChapterText = chapterText
                        )
                        val output = pass.execute(model, input, PassConfig())
                        GeneratedTheme.fromAnalysis(
                            bookId = bookId,
                            mood = output.mood,
                            genre = output.genre,
                            era = output.era,
                            emotionalTone = output.emotionalTone,
                            ambientSound = output.ambientSound
                        )
                    } else {
                        // Fallback to default when no model available
                        GeneratedTheme.createDefault(bookId)
                    }
                } catch (e: Exception) {
                    AppLogger.w(tag, "THEME-001: LLM analysis failed, using default", e)
                    GeneratedTheme.createDefault(bookId)
                }

                // Save theme for future use
                themeManager.saveTheme(generatedTheme)

                // Apply theme to UI
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        themeManager.applyTheme(generatedTheme, view)
                        AppLogger.i(tag, "THEME-001: Applied theme: mood=${generatedTheme.mood}, genre=${generatedTheme.genre}")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "THEME-001: Theme generation/application failed", e)
            }
        }
    }

    /**
     * VOICE-002: Check voice consistency for all characters in the book.
     * Shows a Snackbar warning if any characters have invalid/missing voice assignments.
     */
    private fun checkVoiceConsistency(view: View) {
        val checker = voiceConsistencyChecker ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = checker.checkVoiceConsistency(bookId)

                if (!result.isConsistent) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            showVoiceWarningSnackbar(view, result)
                        }
                    }
                } else {
                    AppLogger.d(tag, "VOICE-002: All ${result.totalChecked} characters have valid voices")
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "VOICE-002: Voice consistency check failed", e)
            }
        }
    }

    /**
     * VOICE-002: Show Snackbar warning about invalid voice assignments.
     */
    private fun showVoiceWarningSnackbar(view: View, result: VoiceConsistencyChecker.ConsistencyResult) {
        Snackbar.make(view, result.summary, Snackbar.LENGTH_LONG)
            .setAction("Fix") {
                fixInvalidVoices(result.invalidCharacters)
            }
            .show()
    }

    /**
     * VOICE-002: Fix invalid voice assignments by applying suggested fallbacks.
     */
    private fun fixInvalidVoices(invalidVoices: List<VoiceConsistencyChecker.InvalidVoice>) {
        val checker = voiceConsistencyChecker ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fixed = checker.fixInvalidVoices(bookId, invalidVoices)
                withContext(Dispatchers.Main) {
                    if (isAdded && fixed > 0) {
                        view?.let {
                            Snackbar.make(it, "Fixed $fixed character voice(s)", Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
                AppLogger.i(tag, "VOICE-002: Fixed $fixed invalid voice assignments")
            } catch (e: Exception) {
                AppLogger.e(tag, "VOICE-002: Failed to fix invalid voices", e)
            }
        }
    }

    /** AUG-037: Track if pre-analysis has already been triggered for next chapter */
    private var nextChapterPreAnalysisTriggered = false

    /**
     * READ-003: Pre-analyze next chapter when user reaches 80% of current chapter.
     * Uses ChapterLookaheadManager for background LLM analysis.
     */
    private fun triggerNextChapterPreAnalysis() {
        if (nextChapterPreAnalysisTriggered) return
        nextChapterPreAnalysisTriggered = true

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Check if deep analysis is enabled
                val featureSettings = app.settingsRepository.featureSettings.first()
                val enableDeepAnalysis = featureSettings.enableDeepAnalysis

                if (!enableDeepAnalysis) {
                    AppLogger.d(tag, "READ-003: Deep analysis disabled, skipping lookahead")
                    return@launch
                }

                // Calculate progress for the lookahead manager
                val progress = if (novelPages.isNotEmpty()) {
                    (currentPageIndex + 1).toFloat() / novelPages.size
                } else {
                    0.8f // Already at 80% threshold if called
                }

                // Trigger lookahead through the manager
                chapterLookaheadManager?.checkAndTriggerLookahead(
                    currentProgress = progress,
                    bookId = bookId,
                    currentChapterId = loadedChapterId,
                    enableDeepAnalysis = enableDeepAnalysis
                )

                AppLogger.i(tag, "READ-003: Lookahead analysis triggered for next chapter")
            } catch (e: Exception) {
                AppLogger.e(tag, "READ-003: Lookahead trigger failed", e)
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
     * READ-002: Async wrapper for preGenerateAudioForPagesSync.
     * Called by AudioBufferManager to pre-load audio for upcoming pages.
     */
    private suspend fun preGenerateAudioForPagesAsync(startPage: Int, count: Int) {
        if (novelPages.isEmpty()) return
        AppLogger.d(tag, "AudioBufferManager: Pre-generating audio for pages $startPage to ${startPage + count - 1}")
        preGenerateAudioForPagesSync(startPage, count)

        // Add generated audio to buffer manager
        for (i in startPage until minOf(startPage + count, novelPages.size)) {
            preGeneratedAudio[i]?.let { file ->
                audioBufferManager?.addToBuffer(i, file)
            }
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

        // READ-002: Notify buffer manager that playback is starting
        if (novelPages.isNotEmpty()) {
            audioBufferManager?.onPlaybackStarted(pageIndex, novelPages.size)
        }

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
                    // SETTINGS-001: Apply saved playback speed
                    audioService?.setSpeed(app.settingsRepository.audioSettings.value.playbackSpeed)
                    btnPlay?.text = "Pause"
                    playerBottomSheet?.updatePlaybackState(true)
                    updateFabPlayCurrentPageVisibility()
                    // READ-002: Buffer manager handles pre-generation now
                    // Legacy: if (pageIndex + 1 < novelPages.size && !preGeneratedAudio.containsKey(pageIndex + 1)) {
                    //     preGenerateAudioForPages(pageIndex + 1, 1)
                    // }
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
                            // SETTINGS-001: Apply saved playback speed
                            audioService?.setSpeed(app.settingsRepository.audioSettings.value.playbackSpeed)
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

        // READ-003: Clean up chapter lookahead manager
        chapterLookaheadManager?.reset()
        chapterLookaheadManager = null

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
                        LlmService.retryLoadModel(requireContext())
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

    // ============== READ-001: Reading Mode Toggle Methods ==============

    /**
     * Update the mode toggle button UI based on current reading mode.
     */
    private fun updateModeButtonUI(button: com.google.android.material.button.MaterialButton) {
        button.text = "Mode: ${currentReadingMode.displayName}"
        // Update icon based on mode
        val iconRes = when (currentReadingMode) {
            ReadingMode.TEXT -> android.R.drawable.ic_menu_view
            ReadingMode.AUDIO -> android.R.drawable.ic_lock_silent_mode_off
            ReadingMode.MIXED -> android.R.drawable.ic_menu_sort_by_size
        }
        button.setIconResource(iconRes)
    }

    /**
     * Apply reading mode-specific behavior and UI adjustments.
     * - TEXT mode: Standard reading, no auto-play, full UI
     * - AUDIO mode: Auto-start playback, show minimal UI (hide text controls)
     * - MIXED mode: Text with synchronized audio and karaoke highlighting
     */
    private fun applyReadingMode() {
        val readerFooter = view?.findViewById<View>(R.id.reader_footer)
        val btnPlay = view?.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_play)
        val fabPlayCurrentPage = view?.findViewById<View>(R.id.fab_play_current_page)

        when (currentReadingMode) {
            ReadingMode.TEXT -> {
                // TEXT mode: Standard reading, no auto-play
                readerFooter?.visibility = View.VISIBLE
                btnPlay?.visibility = View.GONE  // Hide play button in text-only mode
                fabPlayCurrentPage?.visibility = View.GONE
                // Stop any playing audio
                audioService?.pause()
                playbackEngine?.pause()
                AppLogger.i(tag, "Applied TEXT mode: Audio controls hidden")
            }
            ReadingMode.AUDIO -> {
                // AUDIO mode: Auto-start playback, show minimal UI
                readerFooter?.visibility = View.VISIBLE
                btnPlay?.visibility = View.VISIBLE
                // Auto-start playback when switching to audio mode
                if (audioService?.isPlaying() != true && playbackEngine?.isPlaying() != true) {
                    playCurrentPage()
                }
                AppLogger.i(tag, "Applied AUDIO mode: Auto-play started")
            }
            ReadingMode.MIXED -> {
                // MIXED mode: Full experience with text and audio
                readerFooter?.visibility = View.VISIBLE
                btnPlay?.visibility = View.VISIBLE
                updateFabPlayCurrentPageVisibility()
                // Karaoke highlighting is handled by NovelPageAdapter when audio plays
                AppLogger.i(tag, "Applied MIXED mode: Full experience enabled")
            }
        }
    }

    /**
     * Load saved reading mode from the database.
     * Called during fragment initialization to restore user's preference.
     */
    private suspend fun loadSavedReadingMode() {
        val session = withContext(Dispatchers.IO) {
            app.db.readingSessionDao().getCurrent()
        }
        session?.let {
            currentReadingMode = ReadingMode.fromLegacyString(it.mode)
            AppLogger.d(tag, "Loaded saved reading mode: $currentReadingMode")
        }
    }

    // ============ LIBRARY-001: Reading progress tracking ============

    /**
     * LIBRARY-001: Update overall book reading progress based on current chapter and page position.
     * Calculates progress as: (completed chapters + current chapter progress) / total chapters
     * Also checks if book is finished and marks it accordingly.
     */
    private suspend fun updateBookReadingProgress(chapterProgress: Float) {
        try {
            val chapters = app.bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
            if (chapters.isEmpty()) return

            val currentChapterIndex = chapters.indexOfFirst { it.id == loadedChapterId }
            if (currentChapterIndex < 0) return

            val totalChapters = chapters.size
            // Overall progress = (completed chapters + current chapter progress) / total chapters
            val overallProgress = (currentChapterIndex + chapterProgress) / totalChapters

            app.bookRepository.updateReadingProgress(bookId, overallProgress)
            AppLogger.d(tag, "LIBRARY-001: Updated progress for book $bookId: ${(overallProgress * 100).toInt()}% (chapter ${currentChapterIndex + 1}/$totalChapters, page progress: ${(chapterProgress * 100).toInt()}%)")

            // Check if user finished the last chapter (at 100% of last chapter)
            if (currentChapterIndex == totalChapters - 1 && chapterProgress >= 0.99f) {
                app.bookRepository.markAsFinished(bookId)
                AppLogger.i(tag, "LIBRARY-001: Book $bookId marked as FINISHED!")
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "LIBRARY-001: Failed to update reading progress", e)
        }
    }
}

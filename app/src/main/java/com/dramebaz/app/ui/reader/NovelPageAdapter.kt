package com.dramebaz.app.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.R
import com.dramebaz.app.data.models.FontFamily
import com.dramebaz.app.data.models.ReadingSettings
import com.dramebaz.app.data.models.ReadingTheme
import com.dramebaz.app.pdf.PdfPageRenderer
import com.dramebaz.app.playback.engine.CharacterColorPalette
import com.dramebaz.app.playback.engine.ColoredUnderlineSpanCompat
import com.dramebaz.app.playback.engine.KaraokeHighlighter
import com.dramebaz.app.ui.theme.FontManager
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adapter for displaying novel pages in ViewPager2.
 * Each page contains a portion of the chapter text.
 */
data class NovelPage(
    val pageNumber: Int,              // Sequential page number (1, 2, 3...)
    val text: String,
    val lines: List<String>,
    val startOffset: Int = 0,         // Character offset in the original chapter text
    val pdfPageNumber: Int? = null,   // Original PDF page number (null for non-PDF or dynamically split pages)
    val usePdfRendering: Boolean = false  // True to use native PDF rendering (requires pdfPageNumber)
) {
    /**
     * Returns the 0-based PDF page index for rendering.
     * Returns null if PDF rendering is not applicable.
     */
    fun getPdfPageIndex(): Int? {
        return if (usePdfRendering && pdfPageNumber != null) {
            pdfPageNumber - 1  // Convert from 1-based to 0-based
        } else {
            null
        }
    }
}

class NovelPageAdapter(
    @Suppress("unused") private val highlightColor: Int,  // Kept for API compatibility, no longer used
    private val coroutineScope: CoroutineScope,
    private val onPageChanged: (Int) -> Unit = {}
) : ListAdapter<NovelPage, RecyclerView.ViewHolder>(PageDiffCallback()) {

    private val tag = "NovelPageAdapter"

    // UI-001: Karaoke highlighting state (uses colored underlines instead of background highlight)
    private var karaokePageIndex: Int = -1
    private var karaokeSegmentRange: KaraokeHighlighter.TextRange? = null
    private var karaokeCurrentWord: KaraokeHighlighter.WordSegment? = null
    private var karaokeSegmentColor: Int = 0
    private var karaokeWordColor: Int = 0

    // UI-001: Payload for partial karaoke updates to avoid flickering
    private data class KaraokePayload(
        val segmentRange: KaraokeHighlighter.TextRange,
        val currentWord: KaraokeHighlighter.WordSegment?
    )

    // PDF renderer for native PDF page display
    private var pdfRenderer: PdfPageRenderer? = null

    // Map to track rendering jobs for cancellation
    private val renderingJobs = mutableMapOf<Int, Job>()

    // SETTINGS-002: Reading settings for display customization
    private var currentReadingSettings: ReadingSettings = ReadingSettings()
    private val baseFontSizeSp = 18f // Base font size in SP (matches XML layout)

    // Context for loading custom fonts
    private lateinit var context: android.content.Context

    // BOLD-001: Character names for bolding on reading pages
    private var characterNames: Set<String> = emptySet()

    companion object {
        private const val VIEW_TYPE_TEXT = 0
        private const val VIEW_TYPE_PDF = 1
    }

    /**
     * SETTINGS-002: Update reading settings and refresh all visible pages.
     * Called when user changes display settings (font size, line height, theme, font family).
     */
    fun updateReadingSettings(settings: ReadingSettings) {
        if (currentReadingSettings != settings) {
            currentReadingSettings = settings
            AppLogger.d(tag, "Reading settings updated: fontSize=${settings.fontSize}, lineHeight=${settings.lineHeight}, theme=${settings.theme}, fontFamily=${settings.fontFamily}, boldCharacterNames=${settings.boldCharacterNames}")
            // Notify all items to rebind with new settings
            notifyItemRangeChanged(0, itemCount)
        }
    }

    /**
     * BOLD-001: Set the character names for this book.
     * Character names will be displayed in bold when boldCharacterNames setting is enabled.
     */
    fun setCharacterNames(names: List<String>) {
        val newNames = names.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (characterNames != newNames) {
            characterNames = newNames
            AppLogger.d(tag, "BOLD-001: Character names set: ${characterNames.size} names")
            // Refresh if bolding is enabled
            if (currentReadingSettings.boldCharacterNames) {
                notifyItemRangeChanged(0, itemCount)
            }
        }
    }

    /**
     * Set the PDF renderer for native PDF page rendering.
     * Must be called before submitting pages with usePdfRendering = true.
     */
    fun setPdfRenderer(renderer: PdfPageRenderer?) {
        pdfRenderer = renderer
        AppLogger.d(tag, "PDF renderer set: ${renderer != null}")
    }

    /**
     * SETTINGS-002: Get Typeface for the specified font family.
     * Uses FontManager for proper loading of custom fonts.
     */
    private fun getFontTypeface(fontFamily: FontFamily): Typeface {
        return FontManager.getTypeface(context, fontFamily)
    }

    /**
     * SETTINGS-002: Apply reading theme colors to the page.
     */
    private fun applyThemeColors(holder: TextPageViewHolder, theme: ReadingTheme) {
        val backgroundColor = Color.parseColor(theme.backgroundColor)
        val textColor = Color.parseColor(theme.textColor)

        // Apply to CardView background
        (holder.itemView as? CardView)?.setCardBackgroundColor(backgroundColor)

        // Apply to inner LinearLayout
        holder.itemView.findViewById<View>(R.id.page_text)?.parent?.let { parent ->
            (parent as? View)?.parent?.let { grandParent ->
                (grandParent as? View)?.setBackgroundColor(backgroundColor)
            }
        }

        // Apply text color
        holder.pageText.setTextColor(textColor)
        holder.pageNumber.setTextColor(textColor and 0x99FFFFFF.toInt()) // Slightly transparent
    }

    // ViewHolder for text-based pages (existing behavior)
    class TextPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val pageText: TextView = itemView.findViewById(R.id.page_text)
        val pageNumber: TextView = itemView.findViewById(R.id.page_number)
    }

    // ViewHolder for PDF-rendered pages
    class PdfPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val pdfPageImage: ImageView = itemView.findViewById(R.id.pdf_page_image)
        val pageNumber: TextView = itemView.findViewById(R.id.page_number)
        val loadingIndicator: ProgressBar = itemView.findViewById(R.id.loading_indicator)
        val highlightOverlay: View = itemView.findViewById(R.id.highlight_overlay)
        val errorContainer: View = itemView.findViewById(R.id.error_container)
        val errorText: TextView = itemView.findViewById(R.id.error_text)
        val fallbackScroll: ScrollView = itemView.findViewById(R.id.fallback_scroll)
        val fallbackText: TextView = itemView.findViewById(R.id.fallback_text)
    }

    override fun getItemViewType(position: Int): Int {
        val page = getItem(position)
        return if (page.usePdfRendering && page.pdfPageNumber != null && pdfRenderer != null) {
            VIEW_TYPE_PDF
        } else {
            VIEW_TYPE_TEXT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        // Initialize context for font loading
        if (!::context.isInitialized) {
            context = parent.context.applicationContext
        }

        return when (viewType) {
            VIEW_TYPE_PDF -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_novel_page_pdf, parent, false)
                PdfPageViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_novel_page, parent, false)
                TextPageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val page = getItem(position)
        // BUG-002-DEBUG: Log binding for all pages
        AppLogger.d(tag, "onBindViewHolder: position=$position, pageNumber=${page.pageNumber}, pdfPageNumber=${page.pdfPageNumber}, usePdfRendering=${page.usePdfRendering}, holderType=${holder.javaClass.simpleName}")

        when (holder) {
            is PdfPageViewHolder -> bindPdfPage(holder, page, position)
            is TextPageViewHolder -> bindTextPage(holder, page, position)
        }
    }

    /**
     * UI-001: Handle partial updates for karaoke highlighting to avoid flickering.
     * When payload is a KaraokePayload, only update the text spans without rebinding everything.
     */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            // No payload, do full bind
            onBindViewHolder(holder, position)
            return
        }

        // Handle karaoke payload for partial update
        if (holder is TextPageViewHolder) {
            val page = getItem(position)
            for (payload in payloads) {
                if (payload is KaraokePayload) {
                    // Only update the text with new spans - no other changes
                    val highlightedText = applyKaraokeSpans(page.text, payload.segmentRange, payload.currentWord)
                    holder.pageText.text = highlightedText
                    AppLogger.d(tag, "UI-001: Partial karaoke update for page $position, word=${payload.currentWord?.word}")
                    return
                }
            }
        }

        // Unknown payload, fall back to full bind
        onBindViewHolder(holder, position)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        // Cancel any pending rendering job when view is recycled
        @Suppress("DEPRECATION")
        val position = holder.adapterPosition
        if (position != RecyclerView.NO_POSITION) {
            renderingJobs.remove(position)?.cancel()
        }
        // Clear bitmap reference to help GC
        if (holder is PdfPageViewHolder) {
            holder.pdfPageImage.setImageBitmap(null)
        }
    }

    private fun bindPdfPage(holder: PdfPageViewHolder, page: NovelPage, position: Int) {
        // BUG-002-DEBUG: Log page binding details
        AppLogger.d(tag, "bindPdfPage: position=$position, pageNumber=${page.pageNumber}, pdfPageNumber=${page.pdfPageNumber}, pdfPageIndex=${page.getPdfPageIndex()}")

        // Set page number
        holder.pageNumber.text = "Page ${page.pdfPageNumber}"

        // Cancel any previous rendering job for this position
        renderingJobs.remove(position)?.cancel()

        // Show loading state
        holder.loadingIndicator.visibility = View.VISIBLE
        holder.pdfPageImage.visibility = View.GONE
        holder.errorContainer.visibility = View.GONE
        holder.highlightOverlay.visibility = View.GONE

        val renderer = pdfRenderer
        val pdfPageIndex = page.getPdfPageIndex()

        if (renderer == null || pdfPageIndex == null) {
            AppLogger.w(tag, "bindPdfPage: Falling back to text - renderer=${renderer != null}, pdfPageIndex=$pdfPageIndex")
            showFallbackText(holder, page)
            return
        }

        // Render PDF page asynchronously
        val job = coroutineScope.launch {
            try {
                val targetWidth = holder.itemView.width.takeIf { it > 0 }
                    ?: holder.itemView.resources.displayMetrics.widthPixels

                val bitmap = withContext(Dispatchers.IO) {
                    renderer.renderPage(pdfPageIndex, targetWidth)
                }

                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        holder.pdfPageImage.setImageBitmap(bitmap)
                        holder.pdfPageImage.visibility = View.VISIBLE
                        holder.loadingIndicator.visibility = View.GONE
                        holder.errorContainer.visibility = View.GONE
                        // Note: PDF highlighting has been removed - karaoke highlighting
                        // only applies to text pages via the karaoke observer
                    } else {
                        showFallbackText(holder, page)
                    }
                }

                // Prefetch adjacent pages in background for smoother scrolling
                // This runs after the current page is rendered to not block the UI
                prefetchAdjacentPages(renderer, pdfPageIndex, targetWidth)

            } catch (e: Exception) {
                AppLogger.e(tag, "Error rendering PDF page ${page.pdfPageNumber}", e)
                withContext(Dispatchers.Main) {
                    showFallbackText(holder, page)
                }
            }
        }
        renderingJobs[position] = job
    }

    /**
     * Prefetch adjacent pages (previous and next) to improve scrolling performance.
     * Runs on IO dispatcher to not block the main thread.
     */
    private suspend fun prefetchAdjacentPages(
        renderer: PdfPageRenderer,
        currentPageIndex: Int,
        targetWidth: Int
    ) {
        withContext(Dispatchers.IO) {
            // Prefetch previous page
            if (currentPageIndex > 0) {
                try {
                    renderer.renderPage(currentPageIndex - 1, targetWidth)
                    AppLogger.d(tag, "Prefetched page ${currentPageIndex - 1}")
                } catch (e: Exception) {
                    // Ignore prefetch errors - they're not critical
                }
            }

            // Prefetch next page
            if (currentPageIndex < renderer.getPageCount() - 1) {
                try {
                    renderer.renderPage(currentPageIndex + 1, targetWidth)
                    AppLogger.d(tag, "Prefetched page ${currentPageIndex + 1}")
                } catch (e: Exception) {
                    // Ignore prefetch errors - they're not critical
                }
            }
        }
    }

    private fun showFallbackText(holder: PdfPageViewHolder, page: NovelPage) {
        holder.loadingIndicator.visibility = View.GONE
        holder.pdfPageImage.visibility = View.GONE
        holder.errorContainer.visibility = View.VISIBLE
        holder.fallbackScroll.visibility = View.VISIBLE
        holder.fallbackText.text = page.text
    }

    private fun bindTextPage(holder: TextPageViewHolder, page: NovelPage, position: Int) {
        // Show PDF page number if available, otherwise show sequential page number
        holder.pageNumber.text = if (page.pdfPageNumber != null) {
            "Page ${page.pdfPageNumber}"
        } else {
            "Page ${page.pageNumber} of ${itemCount}"
        }

        // SETTINGS-002: Apply reading settings (font size, line height, font family, theme)
        applyReadingSettings(holder)

        // UI-001: Apply karaoke highlighting (word-by-word) if active for this page
        // This uses colored underline spans for character-specific highlighting
        if (position == karaokePageIndex && karaokeSegmentRange != null) {
            val highlightedText = applyKaraokeSpans(page.text, karaokeSegmentRange!!, karaokeCurrentWord)
            holder.pageText.text = highlightedText
        } else {
            // BOLD-001: Apply character name bolding if enabled
            if (currentReadingSettings.boldCharacterNames && characterNames.isNotEmpty()) {
                val boldedText = applyCharacterNameBolding(page.text)
                holder.pageText.text = boldedText
            } else {
                holder.pageText.text = page.text
            }
        }
    }

    /**
     * BOLD-001: Apply bold styling to character names in text.
     * Finds all occurrences of character names (case-insensitive, whole words) and applies bold span.
     */
    private fun applyCharacterNameBolding(text: String): SpannableString {
        val spannable = SpannableString(text)
        val textLower = text.lowercase()

        for (name in characterNames) {
            if (name.isBlank()) continue
            val nameLower = name.lowercase()
            var startIndex = 0

            while (true) {
                val foundIndex = textLower.indexOf(nameLower, startIndex)
                if (foundIndex == -1) break

                // Check for whole word boundaries
                val beforeOk = foundIndex == 0 || !text[foundIndex - 1].isLetterOrDigit()
                val afterIndex = foundIndex + name.length
                val afterOk = afterIndex >= text.length || !text[afterIndex].isLetterOrDigit()

                if (beforeOk && afterOk) {
                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD),
                        foundIndex,
                        afterIndex,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                startIndex = foundIndex + 1
            }
        }

        return spannable
    }

    /**
     * SETTINGS-002: Apply current reading settings to a text page view holder.
     */
    private fun applyReadingSettings(holder: TextPageViewHolder) {
        val settings = currentReadingSettings

        // Apply font size (base size * multiplier)
        val fontSizeSp = baseFontSizeSp * settings.fontSize
        holder.pageText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)

        // Apply line height (line spacing multiplier)
        holder.pageText.setLineSpacing(0f, settings.lineHeight)

        // Apply font family
        holder.pageText.typeface = getFontTypeface(settings.fontFamily)

        // Apply theme colors
        applyThemeColors(holder, settings.theme)
    }

    /**
     * UI-001: Apply karaoke highlighting spans to text.
     * Uses underline with character-specific colors instead of background highlight.
     */
    private fun applyKaraokeSpans(
        text: String,
        segmentRange: KaraokeHighlighter.TextRange,
        currentWord: KaraokeHighlighter.WordSegment?
    ): SpannableString {
        val spannable = SpannableString(text)

        // Get the underline color for the current speaker from CharacterColorPalette
        val underlineColor = CharacterColorPalette.getColorForCharacter(segmentRange.speaker)

        // Underline the entire segment with character-specific color
        if (!segmentRange.isEmpty && segmentRange.end <= text.length) {
            spannable.setSpan(
                ColoredUnderlineSpanCompat(underlineColor, 4f),
                segmentRange.start,
                segmentRange.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // Highlight the current word with bold
        currentWord?.let { word ->
            if (word.endIndex <= text.length && word.startIndex >= 0) {
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    word.startIndex,
                    word.endIndex,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        return spannable
    }

    /**
     * Clear all karaoke highlighting.
     * Called when playback stops or the reader is reset.
     */
    fun clearHighlighting() {
        val oldKaraokePage = karaokePageIndex
        karaokePageIndex = -1
        karaokeSegmentRange = null
        karaokeCurrentWord = null

        if (oldKaraokePage >= 0 && oldKaraokePage < itemCount) {
            notifyItemChanged(oldKaraokePage)
        }
    }

    /**
     * UI-001: Apply karaoke highlighting (word-by-word sync) to a specific page.
     * Uses payload-based updates to avoid flickering from full rebind.
     */
    fun applyKaraokeHighlighting(
        pageIndex: Int,
        segmentRange: KaraokeHighlighter.TextRange,
        currentWord: KaraokeHighlighter.WordSegment?,
        segmentHighlightColor: Int,
        wordHighlightColor: Int
    ) {
        val oldPage = karaokePageIndex
        val isSamePage = oldPage == pageIndex

        karaokePageIndex = pageIndex
        karaokeSegmentRange = segmentRange
        karaokeCurrentWord = currentWord
        karaokeSegmentColor = segmentHighlightColor
        karaokeWordColor = wordHighlightColor

        // Create payload for partial update
        val payload = KaraokePayload(segmentRange, currentWord)

        // Clear old page if we moved to a different page
        if (oldPage >= 0 && oldPage < itemCount && oldPage != pageIndex) {
            // Full rebind to clear highlighting on old page
            notifyItemChanged(oldPage)
        }

        if (pageIndex >= 0 && pageIndex < itemCount) {
            if (isSamePage) {
                // Same page - use payload for partial update (no flickering)
                notifyItemChanged(pageIndex, payload)
            } else {
                // Different page - full rebind needed for initial setup
                notifyItemChanged(pageIndex)
            }
        }
    }

    /**
     * Clean up resources. Should be called when the adapter is no longer needed.
     */
    fun cleanup() {
        // Cancel all rendering jobs
        renderingJobs.values.forEach { it.cancel() }
        renderingJobs.clear()

        // Don't close the PDF renderer here - it's managed externally
        pdfRenderer = null

        AppLogger.d(tag, "NovelPageAdapter cleaned up")
    }

    class PageDiffCallback : DiffUtil.ItemCallback<NovelPage>() {
        override fun areItemsTheSame(oldItem: NovelPage, newItem: NovelPage): Boolean {
            return oldItem.pageNumber == newItem.pageNumber
        }

        override fun areContentsTheSame(oldItem: NovelPage, newItem: NovelPage): Boolean {
            return oldItem == newItem
        }
    }
}

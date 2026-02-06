package com.dramebaz.app.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
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
    private val highlightColor: Int,
    private val coroutineScope: CoroutineScope,
    private val onPageChanged: (Int) -> Unit = {}
) : ListAdapter<NovelPage, RecyclerView.ViewHolder>(PageDiffCallback()) {

    private val tag = "NovelPageAdapter"

    private var currentHighlightedPage: Int = -1
    private var currentHighlightedLine: Int = -1

    // UI-001: Karaoke highlighting state
    private var karaokePageIndex: Int = -1
    private var karaokeSegmentRange: KaraokeHighlighter.TextRange? = null
    private var karaokeCurrentWord: KaraokeHighlighter.WordSegment? = null
    private var karaokeSegmentColor: Int = 0
    private var karaokeWordColor: Int = 0

    // PDF renderer for native PDF page display
    private var pdfRenderer: PdfPageRenderer? = null

    // Map to track rendering jobs for cancellation
    private val renderingJobs = mutableMapOf<Int, Job>()

    // SETTINGS-002: Reading settings for display customization
    private var currentReadingSettings: ReadingSettings = ReadingSettings()
    private val baseFontSizeSp = 18f // Base font size in SP (matches XML layout)

    // Context for loading custom fonts
    private lateinit var context: android.content.Context

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
            AppLogger.d(tag, "Reading settings updated: fontSize=${settings.fontSize}, lineHeight=${settings.lineHeight}, theme=${settings.theme}, fontFamily=${settings.fontFamily}")
            // Notify all items to rebind with new settings
            notifyItemRangeChanged(0, itemCount)
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

        when (holder) {
            is PdfPageViewHolder -> bindPdfPage(holder, page, position)
            is TextPageViewHolder -> bindTextPage(holder, page, position)
        }
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

                        // Apply highlighting if needed
                        if (position == currentHighlightedPage) {
                            applyPdfHighlight(holder, currentHighlightedLine, page.lines.size)
                        }
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

    private fun applyPdfHighlight(holder: PdfPageViewHolder, lineIndex: Int, totalLines: Int) {
        if (lineIndex < 0 || totalLines <= 0) {
            holder.highlightOverlay.visibility = View.GONE
            return
        }

        // Calculate approximate position of the highlighted line on the PDF page
        val imageHeight = holder.pdfPageImage.height
        val lineHeight = imageHeight.toFloat() / totalLines
        val lineTop = (lineIndex * lineHeight).toInt()

        val params = holder.highlightOverlay.layoutParams as? android.widget.FrameLayout.LayoutParams
        params?.let {
            it.topMargin = lineTop
            it.height = lineHeight.toInt().coerceAtLeast(20)
            holder.highlightOverlay.layoutParams = it
            holder.highlightOverlay.visibility = View.VISIBLE
        }
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
        if (position == karaokePageIndex && karaokeSegmentRange != null) {
            val highlightedText = applyKaraokeSpans(page.text, karaokeSegmentRange!!, karaokeCurrentWord)
            holder.pageText.text = highlightedText
        }
        // Apply line highlighting if this is the current page (fallback)
        else if (position == currentHighlightedPage && currentHighlightedLine >= 0) {
            val highlightedText = highlightLine(page.text, page.lines, currentHighlightedLine)
            holder.pageText.text = highlightedText
        } else {
            holder.pageText.text = page.text
        }
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
     */
    private fun applyKaraokeSpans(
        text: String,
        segmentRange: KaraokeHighlighter.TextRange,
        currentWord: KaraokeHighlighter.WordSegment?
    ): SpannableString {
        val spannable = SpannableString(text)

        // Highlight the entire segment with light background
        if (!segmentRange.isEmpty && segmentRange.end <= text.length) {
            spannable.setSpan(
                BackgroundColorSpan(karaokeSegmentColor),
                segmentRange.start,
                segmentRange.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // Highlight the current word with bold and stronger background
        currentWord?.let { word ->
            if (word.endIndex <= text.length && word.startIndex >= 0) {
                spannable.setSpan(
                    BackgroundColorSpan(karaokeWordColor),
                    word.startIndex,
                    word.endIndex,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
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
     * Highlight a specific line in a page.
     */
    fun highlightLine(pageIndex: Int, lineIndex: Int) {
        val oldPage = currentHighlightedPage
        currentHighlightedPage = pageIndex
        currentHighlightedLine = lineIndex

        // Notify both old and new pages to update
        if (oldPage >= 0 && oldPage < itemCount) {
            notifyItemChanged(oldPage)
        }
        if (pageIndex >= 0 && pageIndex < itemCount) {
            notifyItemChanged(pageIndex)
        }

        onPageChanged(pageIndex)
    }

    /**
     * Clear all highlighting.
     */
    fun clearHighlighting() {
        val oldPage = currentHighlightedPage
        currentHighlightedPage = -1
        currentHighlightedLine = -1
        // UI-001: Clear karaoke highlighting too
        val oldKaraokePage = karaokePageIndex
        karaokePageIndex = -1
        karaokeSegmentRange = null
        karaokeCurrentWord = null

        if (oldPage >= 0 && oldPage < itemCount) {
            notifyItemChanged(oldPage)
        }
        if (oldKaraokePage >= 0 && oldKaraokePage < itemCount && oldKaraokePage != oldPage) {
            notifyItemChanged(oldKaraokePage)
        }
    }

    /**
     * UI-001: Apply karaoke highlighting (word-by-word sync) to a specific page.
     */
    fun applyKaraokeHighlighting(
        pageIndex: Int,
        segmentRange: KaraokeHighlighter.TextRange,
        currentWord: KaraokeHighlighter.WordSegment?,
        segmentHighlightColor: Int,
        wordHighlightColor: Int
    ) {
        val oldPage = karaokePageIndex
        karaokePageIndex = pageIndex
        karaokeSegmentRange = segmentRange
        karaokeCurrentWord = currentWord
        karaokeSegmentColor = segmentHighlightColor
        karaokeWordColor = wordHighlightColor

        // Notify both old and new pages if different
        if (oldPage >= 0 && oldPage < itemCount && oldPage != pageIndex) {
            notifyItemChanged(oldPage)
        }
        if (pageIndex >= 0 && pageIndex < itemCount) {
            notifyItemChanged(pageIndex)
        }
    }

    private fun highlightLine(text: String, lines: List<String>, lineIndex: Int): SpannableString {
        val spannable = SpannableString(text)

        if (lineIndex >= 0 && lineIndex < lines.size) {
            // Calculate the start position by summing up all previous lines
            var startIndex = 0
            for (i in 0 until lineIndex) {
                startIndex += lines[i].length
                if (i < lines.size - 1) {
                    startIndex += 1 // Account for newline character
                }
            }

            val targetLine = lines[lineIndex]
            val endIndex = startIndex + targetLine.length

            // Ensure indices are within bounds
            if (startIndex >= 0 && endIndex <= text.length) {
                spannable.setSpan(
                    BackgroundColorSpan(highlightColor),
                    startIndex,
                    endIndex,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        return spannable
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

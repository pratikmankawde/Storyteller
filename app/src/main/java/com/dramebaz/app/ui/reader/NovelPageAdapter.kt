package com.dramebaz.app.ui.reader

import android.graphics.Bitmap
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.R
import com.dramebaz.app.pdf.PdfPageRenderer
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

    // PDF renderer for native PDF page display
    private var pdfRenderer: PdfPageRenderer? = null

    // Map to track rendering jobs for cancellation
    private val renderingJobs = mutableMapOf<Int, Job>()

    companion object {
        private const val VIEW_TYPE_TEXT = 0
        private const val VIEW_TYPE_PDF = 1
    }

    /**
     * Set the PDF renderer for native PDF page rendering.
     * Must be called before submitting pages with usePdfRendering = true.
     */
    fun setPdfRenderer(renderer: PdfPageRenderer?) {
        pdfRenderer = renderer
        AppLogger.d(tag, "PDF renderer set: ${renderer != null}")
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

        // Apply highlighting if this is the current page
        if (position == currentHighlightedPage && currentHighlightedLine >= 0) {
            val highlightedText = highlightLine(page.text, page.lines, currentHighlightedLine)
            holder.pageText.text = highlightedText
        } else {
            holder.pageText.text = page.text
        }
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

        if (oldPage >= 0 && oldPage < itemCount) {
            notifyItemChanged(oldPage)
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

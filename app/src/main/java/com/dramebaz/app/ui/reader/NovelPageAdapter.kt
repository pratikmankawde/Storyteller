package com.dramebaz.app.ui.reader

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.dramebaz.app.R

/**
 * Adapter for displaying novel pages in ViewPager2.
 * Each page contains a portion of the chapter text.
 */
data class NovelPage(
    val pageNumber: Int,
    val text: String,
    val lines: List<String>,
    val startOffset: Int = 0  // Character offset in the original chapter text
)

class NovelPageAdapter(
    private val highlightColor: Int,
    private val onPageChanged: (Int) -> Unit = {}
) : ListAdapter<NovelPage, NovelPageAdapter.PageViewHolder>(PageDiffCallback()) {
    
    private var currentHighlightedPage: Int = -1
    private var currentHighlightedLine: Int = -1
    
    class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val pageText: TextView = itemView.findViewById(R.id.page_text)
        val pageNumber: TextView = itemView.findViewById(R.id.page_number)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_novel_page, parent, false)
        return PageViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = getItem(position)
        // Show "Page X of Y" format so user knows there are multiple pages
        holder.pageNumber.text = "Page ${page.pageNumber} of ${itemCount}"
        
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
    
    class PageDiffCallback : DiffUtil.ItemCallback<NovelPage>() {
        override fun areItemsTheSame(oldItem: NovelPage, newItem: NovelPage): Boolean {
            return oldItem.pageNumber == newItem.pageNumber
        }
        
        override fun areContentsTheSame(oldItem: NovelPage, newItem: NovelPage): Boolean {
            return oldItem == newItem
        }
    }
}

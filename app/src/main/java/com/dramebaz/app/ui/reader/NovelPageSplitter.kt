package com.dramebaz.app.ui.reader

import android.content.Context
import android.graphics.Paint
import android.text.TextPaint
import android.util.DisplayMetrics
import android.view.WindowManager
import com.dramebaz.app.utils.AppLogger

/**
 * Utility class to split chapter text into pages for novel reading.
 * Calculates how much text fits on each page based on screen size and font metrics.
 */
object NovelPageSplitter {

    /**
     * Split text into pages for novel reading.
     *
     * @param text The full chapter text
     * @param context Context for getting screen dimensions
     * @param textSize Text size in sp
     * @param lineSpacing Line spacing multiplier
     * @param paddingDp Padding around text in dp
     * @return List of NovelPage objects
     */
    fun splitIntoPages(
        text: String,
        context: Context,
        textSize: Float = 18f,
        lineSpacing: Float = 1.5f,  // Reduced from 1.8 for more compact text (50%+ more content per page)
        paddingDp: Int = 32          // Reduced from 48 for larger text area
    ): List<NovelPage> {
        AppLogger.d("NovelPageSplitter", "splitIntoPages: text length=${text.length}, textSize=$textSize")

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        AppLogger.d("NovelPageSplitter", "Screen: ${screenWidth}x${screenHeight}, density=${displayMetrics.density}")

        // Convert dp to pixels
        val density = displayMetrics.density
        val paddingPx = (paddingDp * density).toInt()

        // Calculate available text area: subtract toolbar (~56dp), footer buttons (~200dp), padding, page number, and card margins
        // Optimized for more text per page while still fitting properly
        val toolbarFooterDp = 56 + 200 + 60 // Toolbar + footer + margin (reduced from 100)
        val toolbarFooterPx = (toolbarFooterDp * density).toInt()
        val cardMarginDp = 12 + 32 // CardView margin + padding from layout
        val cardMarginPx = (cardMarginDp * density).toInt() // Reduced - only top+bottom, not doubled
        val availableWidth = screenWidth - (paddingPx * 2) - cardMarginPx
        val availableHeight = screenHeight - (paddingPx * 2) - toolbarFooterPx - cardMarginPx
        AppLogger.d("NovelPageSplitter", "Available area: ${availableWidth}x${availableHeight}px (screen ${screenWidth}x${screenHeight})")

        // Create TextPaint to measure text
        val textSizePx = textSize * density
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSizePx
            isAntiAlias = true
        }

        // Preserve all lines including blank ones so chapter text and paragraphs display correctly
        val lines = text.split("\n")
        val pages = mutableListOf<NovelPage>()
        var currentPageLines = mutableListOf<String>()
        var currentPageText = StringBuilder()
        var pageNumber = 1
        var currentHeight = 0f

        val lineHeight = textPaint.fontMetrics.let { it.descent - it.ascent } * lineSpacing

        for (line in lines) {
            val lineHeightPx = lineHeight
            val wrappedLines = if (line.isBlank()) {
                listOf("") // Blank line takes one line height
            } else {
                val lineWidth = textPaint.measureText(line)
                if (lineWidth > availableWidth) wrapText(line, textPaint, availableWidth.toFloat()) else listOf(line)
            }

            for (wrappedLine in wrappedLines) {
                // Check if adding this line would exceed page height
                if (currentHeight + lineHeightPx > availableHeight && currentPageLines.isNotEmpty()) {
                    pages.add(
                        NovelPage(
                            pageNumber = pageNumber++,
                            text = currentPageText.toString().trimEnd(),
                            lines = currentPageLines.toList(),
                            startOffset = 0
                        )
                    )
                    currentPageLines.clear()
                    currentPageText.clear()
                    currentHeight = 0f
                }

                currentPageLines.add(wrappedLine)
                if (currentPageText.isNotEmpty()) currentPageText.append("\n")
                currentPageText.append(wrappedLine)
                currentHeight += lineHeightPx
            }
        }

        if (currentPageLines.isNotEmpty()) {
            pages.add(
                NovelPage(
                    pageNumber = pageNumber,
                    text = currentPageText.toString().trimEnd(),
                    lines = currentPageLines.toList(),
                    startOffset = 0
                )
            )
        }

        // Calculate start offsets for each page by finding their position in original text
        var cumulativeOffset = 0
        for (pageIndex in pages.indices) {
            val page = pages[pageIndex]
            val firstLine = page.lines.firstOrNull() ?: ""
            val pageStart = if (pageIndex == 0) {
                text.indexOf(firstLine).takeIf { it >= 0 } ?: 0
            } else {
                if (firstLine.isNotEmpty()) {
                    text.indexOf(firstLine, (cumulativeOffset - 50).coerceAtLeast(0)).takeIf { it >= 0 }
                } else null
            } ?: cumulativeOffset
            pages[pageIndex] = page.copy(startOffset = pageStart)
            cumulativeOffset = pageStart + page.text.length
        }

        AppLogger.i("NovelPageSplitter", "Split complete: ${pages.size} pages created from ${text.length} chars")
        if (pages.isNotEmpty()) {
            for ((idx, p) in pages.take(3).withIndex()) {
                val preview = p.text.take(100).replace("\n", " ").trim()
                AppLogger.d("NovelPageSplitter", "Page ${idx+1}: ${p.lines.size} lines, ${p.text.length} chars, preview=\"$preview...\"")
            }
        }

        return pages
    }

    /**
     * Wrap a long line of text to fit within the available width.
     */
    private fun wrapText(text: String, paint: TextPaint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val wrappedLines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val width = paint.measureText(testLine)

            if (width <= maxWidth) {
                if (currentLine.isNotEmpty()) {
                    currentLine.append(" ")
                }
                currentLine.append(word)
            } else {
                if (currentLine.isNotEmpty()) {
                    wrappedLines.add(currentLine.toString())
                }
                currentLine = StringBuilder(word)
            }
        }

        if (currentLine.isNotEmpty()) {
            wrappedLines.add(currentLine.toString())
        }

        return wrappedLines
    }
}

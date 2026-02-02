package com.dramebaz.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.dramebaz.app.utils.AppLogger
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Renders PDF pages as bitmaps using Android's native PdfRenderer.
 * Provides high-fidelity rendering that preserves:
 * - Embedded fonts (typefaces, sizes, weights)
 * - Layout (columns, margins, spacing, alignment)
 * - Styling (bold, italic, colors, decorations)
 * - Images, diagrams, and other non-text elements
 * - Page dimensions and aspect ratios
 *
 * Uses an LRU cache to manage memory for rendered bitmaps.
 */
class PdfPageRenderer(private val context: Context) {

    private val tag = "PdfPageRenderer"

    private var pdfRenderer: PdfRenderer? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var currentPdfPath: String? = null

    // LRU cache for rendered bitmaps (stores ~10 pages to balance memory vs performance)
    private val bitmapCache: LruCache<Int, Bitmap> = object : LruCache<Int, Bitmap>(
        // Use 1/8th of available memory for cache
        (Runtime.getRuntime().maxMemory() / 8).toInt()
    ) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && newValue == null) {
                // Don't recycle here - the bitmap might still be in use by ImageView
                AppLogger.d(tag, "Evicted bitmap for page $key from cache")
            }
        }
    }

    // Per-page locks to allow concurrent rendering of different pages
    private val pageLocks = ConcurrentHashMap<Int, ReentrantLock>()

    /**
     * Open a PDF file for rendering.
     * Must be called before renderPage().
     *
     * @param pdfFile The PDF file to open
     * @return true if opened successfully, false otherwise
     */
    @Synchronized
    fun openPdf(pdfFile: File): Boolean {
        // Check if already opened for this file
        if (currentPdfPath == pdfFile.absolutePath && pdfRenderer != null) {
            AppLogger.d(tag, "PDF already open: ${pdfFile.name}")
            return true
        }

        // Close any previously opened PDF
        close()

        return try {
            if (!pdfFile.exists()) {
                AppLogger.e(tag, "PDF file does not exist: ${pdfFile.absolutePath}")
                return false
            }

            parcelFileDescriptor = ParcelFileDescriptor.open(
                pdfFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            pdfRenderer = PdfRenderer(parcelFileDescriptor!!)
            currentPdfPath = pdfFile.absolutePath

            AppLogger.i(tag, "Opened PDF: ${pdfFile.name}, ${pdfRenderer!!.pageCount} pages")
            true
        } catch (e: IOException) {
            AppLogger.e(tag, "Failed to open PDF: ${e.message}", e)
            close()
            false
        } catch (e: SecurityException) {
            AppLogger.e(tag, "Security exception opening PDF: ${e.message}", e)
            close()
            false
        }
    }

    /**
     * Get the total number of pages in the PDF.
     * @return Page count, or 0 if not opened
     */
    fun getPageCount(): Int {
        return pdfRenderer?.pageCount ?: 0
    }

    /**
     * Render a PDF page as a bitmap.
     * Uses per-page locking to allow concurrent rendering of different pages.
     *
     * @param pageIndex 0-based page index
     * @param targetWidth Target width in pixels (height will be scaled proportionally)
     * @param useCache Whether to use bitmap cache (default true)
     * @return Rendered bitmap, or null if rendering failed
     */
    fun renderPage(pageIndex: Int, targetWidth: Int, useCache: Boolean = true): Bitmap? {
        val renderer = pdfRenderer
        if (renderer == null) {
            AppLogger.e(tag, "PdfRenderer not initialized. Call openPdf() first.")
            return null
        }

        if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
            AppLogger.e(tag, "Invalid page index: $pageIndex (total: ${renderer.pageCount})")
            return null
        }

        // Check cache first (before acquiring lock)
        val cacheKey = getCacheKey(pageIndex, targetWidth)
        if (useCache) {
            bitmapCache.get(cacheKey)?.let { cached ->
                if (!cached.isRecycled) {
                    AppLogger.d(tag, "Cache hit for page $pageIndex")
                    return cached
                }
            }
        }

        // Acquire per-page lock to allow concurrent rendering of different pages
        val pageLock = pageLocks.computeIfAbsent(pageIndex) { ReentrantLock() }
        pageLock.lock()
        try {
            // Double-check cache after acquiring lock (another thread may have rendered)
            if (useCache) {
                bitmapCache.get(cacheKey)?.let { cached ->
                    if (!cached.isRecycled) {
                        AppLogger.d(tag, "Cache hit for page $pageIndex (after lock)")
                        return cached
                    }
                }
            }

            // PdfRenderer.openPage requires synchronization on the renderer itself
            val page = synchronized(renderer) {
                renderer.openPage(pageIndex)
            }

            // Calculate dimensions preserving aspect ratio
            val aspectRatio = page.width.toFloat() / page.height.toFloat()
            val targetHeight = (targetWidth / aspectRatio).toInt()

            // Create bitmap with RGB_565 for 50% less memory (2 bytes vs 4 bytes per pixel)
            // RGB_565 is sufficient for PDF pages which are typically text/graphics on white
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)

            // Fill with white background (PDF pages are typically white)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            // Render the page with high quality
            page.render(
                bitmap,
                null, // Full page
                null, // No transform matrix (use default scaling)
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )

            page.close()

            // Cache the rendered bitmap
            if (useCache) {
                bitmapCache.put(cacheKey, bitmap)
            }

            AppLogger.d(tag, "Rendered page $pageIndex: ${targetWidth}x$targetHeight (RGB_565)")
            return bitmap
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to render page $pageIndex: ${e.message}", e)
            return null
        } finally {
            pageLock.unlock()
        }
    }

    /**
     * Get the dimensions of a PDF page without rendering.
     *
     * @param pageIndex 0-based page index
     * @return Pair of (width, height) in PDF points, or null if failed
     */
    @Synchronized
    fun getPageDimensions(pageIndex: Int): Pair<Int, Int>? {
        val renderer = pdfRenderer ?: return null

        if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
            return null
        }

        return try {
            val page = renderer.openPage(pageIndex)
            val dimensions = Pair(page.width, page.height)
            page.close()
            dimensions
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to get page dimensions: ${e.message}", e)
            null
        }
    }

    /**
     * Clear the bitmap cache to free memory.
     */
    fun clearCache() {
        bitmapCache.evictAll()
        AppLogger.d(tag, "Bitmap cache cleared")
    }

    /**
     * Close the PDF and release resources.
     * Should be called when the PDF is no longer needed.
     */
    @Synchronized
    fun close() {
        try {
            pdfRenderer?.close()
            parcelFileDescriptor?.close()
        } catch (e: Exception) {
            AppLogger.e(tag, "Error closing PDF: ${e.message}", e)
        } finally {
            pdfRenderer = null
            parcelFileDescriptor = null
            currentPdfPath = null
            clearCache()
            AppLogger.d(tag, "PdfPageRenderer closed")
        }
    }

    /**
     * Check if a PDF is currently open.
     */
    fun isOpen(): Boolean {
        return pdfRenderer != null
    }

    /**
     * Get the path of the currently opened PDF.
     */
    fun getCurrentPdfPath(): String? {
        return currentPdfPath
    }

    private fun getCacheKey(pageIndex: Int, targetWidth: Int): Int {
        // Combine page index and width into a unique cache key
        return pageIndex * 10000 + targetWidth
    }

    companion object {
        /**
         * Create a PdfPageRenderer and open the specified PDF file.
         * Returns null if the PDF cannot be opened.
         */
        fun create(context: Context, pdfFile: File): PdfPageRenderer? {
            val renderer = PdfPageRenderer(context)
            return if (renderer.openPdf(pdfFile)) {
                renderer
            } else {
                null
            }
        }
    }
}

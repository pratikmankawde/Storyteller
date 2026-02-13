package com.dramebaz.app.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import com.dramebaz.app.data.db.AnalysisState
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.domain.theme.GenreCoverMapper
import com.dramebaz.app.utils.AppLogger
import java.io.InputStream

/**
 * COVER-001: Helper for loading book cover images into ImageViews.
 *
 * Priority:
 * 1) embeddedCoverPath (from the book file itself)
 * 2) placeholderCoverPath (precomputed, genre-based asset path)
 * 3) detectedGenre mapped via GenreCoverMapper
 * 4) fall back to whatever srcCompat is defined in the layout
 *
 * COVER-SLIDESHOW: If book is being analyzed and has no cover/genre,
 * starts a slideshow of placeholder covers instead of showing a static image.
 */
object BookCoverLoader {

    private const val TAG = "BookCoverLoader"

    // Simple in-memory cache for small asset-based placeholder covers.
    private val assetCoverCache = mutableMapOf<String, Bitmap>()

    /**
     * Load cover into ImageView with optional slideshow support for analyzing books.
     *
     * @param imageView Target ImageView
     * @param book The book to load cover for
     * @param enableSlideshow If true, shows animated slideshow for books being analyzed
     *                        that don't have an embedded cover or detected genre yet
     */
    fun loadCoverInto(imageView: ImageView, book: Book, enableSlideshow: Boolean = false) {
        // COVER-SLIDESHOW: Check if we should show slideshow instead of static cover
        if (enableSlideshow && shouldShowSlideshow(book)) {
            // Start or join existing slideshow - supports multiple ImageViews for same book
            CoverSlideshowManager.startSlideshow(imageView, book.id)
            return
        }

        // Loading static cover - stop any slideshow for this book since analysis is complete
        CoverSlideshowManager.stopSlideshow(book.id)
        loadStaticCover(imageView, book)
    }

    /**
     * Determine if a book should show the cover slideshow.
     * Returns true if:
     * - Book is in PENDING or ANALYZING state
     * - Book has no embedded cover
     * - Book has no detected genre yet
     */
    fun shouldShowSlideshow(book: Book): Boolean {
        val state = book.getAnalysisState()
        val isAnalyzing = state == AnalysisState.PENDING || state == AnalysisState.ANALYZING
        val hasNoCover = book.embeddedCoverPath.isNullOrBlank()
        val hasNoGenre = book.detectedGenre.isNullOrBlank()
        val hasNoPlaceholder = book.placeholderCoverPath.isNullOrBlank()
        return isAnalyzing && hasNoCover && hasNoGenre && hasNoPlaceholder
    }

    /**
     * Load static cover following priority order.
     */
    private fun loadStaticCover(imageView: ImageView, book: Book) {
        // 1) Embedded cover from file system
        val embeddedPath = book.embeddedCoverPath
        if (!embeddedPath.isNullOrBlank()) {
            decodeFileSafely(embeddedPath)?.let { bitmap ->
                imageView.setImageBitmap(bitmap)
                return
            }
            AppLogger.w(TAG, "COVER-001: Failed to decode embedded cover at $embeddedPath, falling back")
        }

        val context = imageView.context

        // 2) Precomputed placeholder cover path (assets)
        val placeholderPath = book.placeholderCoverPath
        if (!placeholderPath.isNullOrBlank()) {
            loadAssetCover(context, placeholderPath)?.let { bitmap ->
                imageView.setImageBitmap(bitmap)
                return
            }
            AppLogger.w(TAG, "COVER-001: Failed to load placeholder cover asset $placeholderPath, falling back")
        }

        // 3) Map detected genre to a placeholder cover
        val genre = book.detectedGenre
        if (!genre.isNullOrBlank()) {
            val mappedPath = GenreCoverMapper.mapGenreToCoverPath(genre)
            loadAssetCover(context, mappedPath)?.let { bitmap ->
                imageView.setImageBitmap(bitmap)
                return
            }
            AppLogger.w(TAG, "COVER-001: Failed to load genre-mapped cover asset $mappedPath, falling back")
        }

        // 4) Fallback: keep existing ImageView srcCompat / background.
        // We intentionally do not override with a hard-coded drawable here to
        // respect whatever default the layout provides.
    }

    private fun decodeFileSafely(path: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            AppLogger.w(TAG, "COVER-001: Error decoding cover file: $path", e)
            null
        }
    }

    private fun loadAssetCover(context: Context, assetPath: String): Bitmap? {
        // Check cache first
        assetCoverCache[assetPath]?.let { return it }

        return try {
            context.assets.open(assetPath).use { input: InputStream ->
                val bitmap = BitmapFactory.decodeStream(input)
                if (bitmap != null) {
                    assetCoverCache[assetPath] = bitmap
                }
                bitmap
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "COVER-001: Error loading asset cover: $assetPath", e)
            null
        }
    }
}


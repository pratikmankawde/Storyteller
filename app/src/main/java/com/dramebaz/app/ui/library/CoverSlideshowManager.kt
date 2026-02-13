package com.dramebaz.app.ui.library

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import com.dramebaz.app.utils.AppLogger
import java.lang.ref.WeakReference

/**
 * COVER-SLIDESHOW: Manages animated slideshow of placeholder book covers.
 * 
 * Used when a book is being analyzed and doesn't have a detected genre yet.
 * Cycles through all available genre placeholder covers with crossfade animation.
 */
object CoverSlideshowManager {

    private const val TAG = "CoverSlideshowManager"

    /** Duration between cover transitions in milliseconds (slower for relaxed feel) */
    private const val SLIDE_INTERVAL_MS = 4000L

    /** Duration of crossfade animation in milliseconds (longer for smoother transitions) */
    private const val CROSSFADE_DURATION_MS = 1200L

    /** Interpolator for smooth easing effect */
    private val smoothInterpolator = AccelerateDecelerateInterpolator()

    /** All available placeholder cover asset paths */
    private val COVER_PATHS = listOf(
        "images/bookcovers/Fantasy.png",
        "images/bookcovers/Sci-Fi.png",
        "images/bookcovers/Romance.png",
        "images/bookcovers/Mystery.png",
        "images/bookcovers/Horror.png",
        "images/bookcovers/Comedy.png",
        "images/bookcovers/Childrens.png",
        "images/bookcovers/History.png",
        "images/bookcovers/Biographies.png",
        "images/bookcovers/Spiritual.png",
        "images/bookcovers/NonFiction.png",
        "images/bookcovers/Literature.png"
    )

    /** Cache for loaded cover bitmaps */
    private val coverCache = mutableMapOf<String, Bitmap>()

    /**
     * Active slideshows keyed by book ID.
     * Each book can have multiple ImageViews showing the slideshow (same book in different sections).
     */
    private val activeSlideshows = mutableMapOf<Long, SlideshowState>()

    /** Handler for scheduling slideshow transitions */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * State for an active slideshow on a specific book.
     * Supports multiple ImageViews for the same book (e.g., book appears in multiple sections).
     */
    private data class SlideshowState(
        val bookId: Long,
        val imageViewRefs: MutableList<WeakReference<ImageView>> = mutableListOf(),
        var currentIndex: Int = 0,
        var isRunning: Boolean = true
    )

    /**
     * Start a cover slideshow on the given ImageView for a book being analyzed.
     *
     * Supports multiple ImageViews for the same book (e.g., when the same book
     * appears in multiple sections like "Favourites" and "Recently Added").
     *
     * @param imageView The ImageView to animate
     * @param bookId Unique book ID to track this slideshow
     */
    fun startSlideshow(imageView: ImageView, bookId: Long) {
        // Preload covers if not cached
        preloadCovers(imageView.context)

        // Check if slideshow already exists for this book
        val existingState = activeSlideshows[bookId]
        if (existingState != null && existingState.isRunning) {
            // Add this ImageView to the existing slideshow
            // First, remove any stale references
            existingState.imageViewRefs.removeAll { it.get() == null }
            // Check if this ImageView is already registered
            val alreadyRegistered = existingState.imageViewRefs.any { it.get() === imageView }
            if (!alreadyRegistered) {
                existingState.imageViewRefs.add(WeakReference(imageView))
                // Show current cover on this new ImageView immediately
                showCoverOnImageView(imageView, existingState.currentIndex)
                AppLogger.d(TAG, "Added ImageView to existing slideshow for book $bookId (total: ${existingState.imageViewRefs.size})")
            }
            return
        }

        // Create new slideshow state
        val state = SlideshowState(
            bookId = bookId,
            currentIndex = (System.currentTimeMillis() % COVER_PATHS.size).toInt() // Random start
        )
        state.imageViewRefs.add(WeakReference(imageView))
        activeSlideshows[bookId] = state

        // Show first cover immediately
        showCover(state)

        // Schedule next transition
        scheduleNextTransition(state)

        AppLogger.d(TAG, "Started slideshow for book $bookId")
    }

    /**
     * Stop the slideshow for a specific book.
     */
    fun stopSlideshow(bookId: Long) {
        activeSlideshows.remove(bookId)?.let { state ->
            state.isRunning = false
            AppLogger.d(TAG, "Stopped slideshow for book $bookId")
        }
    }

    /**
     * Stop all active slideshows (e.g., when fragment is destroyed).
     */
    fun stopAllSlideshows() {
        activeSlideshows.values.forEach { it.isRunning = false }
        activeSlideshows.clear()
        handler.removeCallbacksAndMessages(null)
        AppLogger.d(TAG, "Stopped all slideshows")
    }

    /**
     * Check if a slideshow is active for a book.
     */
    fun isRunning(bookId: Long): Boolean = activeSlideshows[bookId]?.isRunning == true

    /**
     * Show cover on a single ImageView at the given index.
     */
    private fun showCoverOnImageView(imageView: ImageView, coverIndex: Int) {
        val coverPath = COVER_PATHS[coverIndex]
        val bitmap = coverCache[coverPath] ?: return
        imageView.setImageBitmap(bitmap)
    }

    /**
     * Show current cover on all registered ImageViews for this slideshow.
     */
    private fun showCover(state: SlideshowState) {
        val coverPath = COVER_PATHS[state.currentIndex]
        val bitmap = coverCache[coverPath] ?: return

        // Remove stale references and show cover on all valid ImageViews
        state.imageViewRefs.removeAll { it.get() == null }
        state.imageViewRefs.forEach { ref ->
            ref.get()?.setImageBitmap(bitmap)
        }
    }

    private fun scheduleNextTransition(state: SlideshowState) {
        handler.postDelayed({
            if (!state.isRunning) return@postDelayed

            // Clean up stale references
            state.imageViewRefs.removeAll { it.get() == null }

            // If no ImageViews left, stop the slideshow
            if (state.imageViewRefs.isEmpty()) {
                stopSlideshow(state.bookId)
                return@postDelayed
            }

            // Advance to next cover
            state.currentIndex = (state.currentIndex + 1) % COVER_PATHS.size
            val nextPath = COVER_PATHS[state.currentIndex]
            val nextBitmap = coverCache[nextPath]

            if (nextBitmap != null) {
                // Smooth crossfade animation on all ImageViews
                state.imageViewRefs.forEach { ref ->
                    val imageView = ref.get() ?: return@forEach
                    imageView.animate()
                        .alpha(0f)
                        .setDuration(CROSSFADE_DURATION_MS / 2)
                        .setInterpolator(smoothInterpolator)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (!state.isRunning) return
                                imageView.setImageBitmap(nextBitmap)
                                imageView.animate()
                                    .alpha(1f)
                                    .setDuration(CROSSFADE_DURATION_MS / 2)
                                    .setInterpolator(smoothInterpolator)
                                    .setListener(null)
                                    .start()
                            }
                        })
                        .start()
                }
            }

            // Schedule next transition
            if (state.isRunning) {
                scheduleNextTransition(state)
            }
        }, SLIDE_INTERVAL_MS)
    }

    private fun preloadCovers(context: Context) {
        if (coverCache.size == COVER_PATHS.size) return // Already loaded

        COVER_PATHS.forEach { path ->
            if (!coverCache.containsKey(path)) {
                try {
                    context.assets.open(path).use { input ->
                        BitmapFactory.decodeStream(input)?.let { bitmap ->
                            coverCache[path] = bitmap
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to preload cover: $path", e)
                }
            }
        }
        AppLogger.d(TAG, "Preloaded ${coverCache.size} cover images")
    }
}


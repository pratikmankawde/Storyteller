package com.dramebaz.app.ui

import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.dramebaz.app.ui.library.CoverSlideshowManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumentation tests for CoverSlideshowManager.
 * 
 * Tests slideshow lifecycle management with real Android components:
 * - ImageView, Handler, Bitmap loading from assets
 * - Multi-ImageView support for the same book
 * - Slideshow start/stop/isRunning state management
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class CoverSlideshowManagerTest {

    private lateinit var imageView1: ImageView
    private lateinit var imageView2: ImageView
    private lateinit var imageView3: ImageView

    @Before
    fun setUp() {
        // Create ImageViews on the main thread
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        imageView1 = ImageView(context)
        imageView2 = ImageView(context)
        imageView3 = ImageView(context)
        
        // Stop any existing slideshows
        CoverSlideshowManager.stopAllSlideshows()
    }

    @After
    fun tearDown() {
        CoverSlideshowManager.stopAllSlideshows()
    }

    // ==================== startSlideshow Tests ====================

    @Test
    fun startSlideshow_createsNewSlideshowForBook() {
        val bookId = 1L
        
        assertFalse(CoverSlideshowManager.isRunning(bookId))
        
        runOnMainThread {
            CoverSlideshowManager.startSlideshow(imageView1, bookId)
        }
        
        assertTrue(CoverSlideshowManager.isRunning(bookId))
    }

    @Test
    fun startSlideshow_setsImageOnImageView() {
        val bookId = 2L
        
        assertNull(imageView1.drawable)
        
        runOnMainThread {
            CoverSlideshowManager.startSlideshow(imageView1, bookId)
        }
        
        // Allow time for image to be set
        Thread.sleep(100)
        
        assertNotNull("ImageView should have a drawable set", imageView1.drawable)
    }

    @Test
    fun startSlideshow_addsImageViewToExistingSlideshowForSameBook() {
        val bookId = 3L
        
        runOnMainThread {
            CoverSlideshowManager.startSlideshow(imageView1, bookId)
        }
        
        assertTrue(CoverSlideshowManager.isRunning(bookId))
        
        runOnMainThread {
            CoverSlideshowManager.startSlideshow(imageView2, bookId)
        }
        
        // Both ImageViews should have images and slideshow should still be running
        Thread.sleep(100)
        assertTrue(CoverSlideshowManager.isRunning(bookId))
        assertNotNull(imageView1.drawable)
        assertNotNull(imageView2.drawable)
    }

    @Test
    fun startSlideshow_createsSeparateSlideshowsForDifferentBooks() {
        val bookId1 = 4L
        val bookId2 = 5L
        
        runOnMainThread {
            CoverSlideshowManager.startSlideshow(imageView1, bookId1)
            CoverSlideshowManager.startSlideshow(imageView2, bookId2)
        }
        
        assertTrue(CoverSlideshowManager.isRunning(bookId1))
        assertTrue(CoverSlideshowManager.isRunning(bookId2))
    }

    // ==================== stopSlideshow Tests ====================

    @Test
    fun stopSlideshow_stopsRunningSlideshow() {
        val bookId = 6L
        
        runOnMainThread {
            CoverSlideshowManager.startSlideshow(imageView1, bookId)
        }
        
        assertTrue(CoverSlideshowManager.isRunning(bookId))
        
        CoverSlideshowManager.stopSlideshow(bookId)
        
        assertFalse(CoverSlideshowManager.isRunning(bookId))
    }

    @Test
    fun stopSlideshow_doesNothingForNonExistentSlideshow() {
        val nonExistentBookId = 999L
        
        // Should not throw
        CoverSlideshowManager.stopSlideshow(nonExistentBookId)
        
        assertFalse(CoverSlideshowManager.isRunning(nonExistentBookId))
    }

    // ==================== stopAllSlideshows Tests ====================

    @Test
    fun stopAllSlideshows_stopsAllActiveSlideshows() {
        val bookId1 = 7L
        val bookId2 = 8L
        
        runOnMainThread {
            CoverSlideshowManager.startSlideshow(imageView1, bookId1)
            CoverSlideshowManager.startSlideshow(imageView2, bookId2)
        }
        
        assertTrue(CoverSlideshowManager.isRunning(bookId1))
        assertTrue(CoverSlideshowManager.isRunning(bookId2))
        
        CoverSlideshowManager.stopAllSlideshows()
        
        assertFalse(CoverSlideshowManager.isRunning(bookId1))
        assertFalse(CoverSlideshowManager.isRunning(bookId2))
    }

    // ==================== Helper Methods ====================

    private fun runOnMainThread(action: () -> Unit) {
        val latch = CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            action()
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
    }
}


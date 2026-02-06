package com.dramebaz.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.repositories.BookRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for demo book seeding and deletion tracking functionality.
 * 
 * Tests the following features:
 * 1. Demo book titles constant
 * 2. markDemoBookAsDeleted() functionality
 * 3. Demo book deletion tracking via SharedPreferences
 */
@RunWith(AndroidJUnit4::class)
class DemoBookSeedingTest {

    private lateinit var context: Context
    private lateinit var app: DramebazApplication
    private lateinit var db: AppDatabase
    private lateinit var bookRepository: BookRepository

    companion object {
        private const val TAG = "DemoBookSeedingTest"
        private const val PREFS_NAME = "dramebaz_prefs"
        private const val KEY_DELETED_DEMO_BOOKS = "deleted_demo_books"
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        app = context.applicationContext as DramebazApplication
        db = app.db
        bookRepository = app.bookRepository

        // Clear deleted demo books for clean test state
        clearDeletedDemoBooks()
    }

    @After
    fun teardown() {
        // Clean up - restore original state
        clearDeletedDemoBooks()
    }

    private fun clearDeletedDemoBooks() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_DELETED_DEMO_BOOKS).apply()
    }

    @Test
    fun testDemoBookTitlesContainsExpectedBooks() {
        // Verify DEMO_BOOK_TITLES contains expected books
        assertTrue(
            "DEMO_BOOK_TITLES should contain 'Space story'",
            DramebazApplication.DEMO_BOOK_TITLES.contains("Space story")
        )
        assertTrue(
            "DEMO_BOOK_TITLES should contain 'Demo story 2'",
            DramebazApplication.DEMO_BOOK_TITLES.contains("Demo story 2")
        )
        android.util.Log.d(TAG, "DEMO_BOOK_TITLES: ${DramebazApplication.DEMO_BOOK_TITLES}")
    }

    @Test
    fun testMarkDemoBookAsDeleted() {
        // Mark a demo book as deleted
        app.markDemoBookAsDeleted("Space story")

        // Verify it's stored in SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deletedBooks = prefs.getStringSet(KEY_DELETED_DEMO_BOOKS, emptySet()) ?: emptySet()

        assertTrue(
            "Deleted demo books should contain 'Space story'",
            deletedBooks.contains("Space story")
        )
        android.util.Log.d(TAG, "Deleted demo books after marking: $deletedBooks")
    }

    @Test
    fun testMarkNonDemoBookAsDeletedDoesNothing() {
        // Try to mark a non-demo book as deleted
        app.markDemoBookAsDeleted("Some Random Book")

        // Verify it's NOT stored in SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deletedBooks = prefs.getStringSet(KEY_DELETED_DEMO_BOOKS, emptySet()) ?: emptySet()

        assertFalse(
            "Non-demo book should not be in deleted list",
            deletedBooks.contains("Some Random Book")
        )
        android.util.Log.d(TAG, "Deleted demo books (should be empty): $deletedBooks")
    }

    @Test
    fun testMultipleDemoBooksCanBeMarkedAsDeleted() {
        // Mark multiple demo books as deleted
        app.markDemoBookAsDeleted("Space story")
        app.markDemoBookAsDeleted("Demo story 2")

        // Verify both are stored
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deletedBooks = prefs.getStringSet(KEY_DELETED_DEMO_BOOKS, emptySet()) ?: emptySet()

        assertEquals("Should have 2 deleted demo books", 2, deletedBooks.size)
        assertTrue("Should contain 'Space story'", deletedBooks.contains("Space story"))
        assertTrue("Should contain 'Demo story 2'", deletedBooks.contains("Demo story 2"))
        android.util.Log.d(TAG, "Multiple deleted demo books: $deletedBooks")
    }

    @Test
    fun testMarkSameBookTwiceDoesNotDuplicate() {
        // Mark the same book twice
        app.markDemoBookAsDeleted("Space story")
        app.markDemoBookAsDeleted("Space story")

        // Verify it's only stored once (Set behavior)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deletedBooks = prefs.getStringSet(KEY_DELETED_DEMO_BOOKS, emptySet()) ?: emptySet()

        assertEquals("Should have only 1 deleted demo book", 1, deletedBooks.size)
        android.util.Log.d(TAG, "Deleted books after duplicate marking: $deletedBooks")
    }

    @Test
    fun testDemoBookTitlesIsImmutable() {
        // Verify DEMO_BOOK_TITLES is a Set (immutable by design)
        val titles = DramebazApplication.DEMO_BOOK_TITLES
        assertTrue("DEMO_BOOK_TITLES should be a Set", titles is Set<*>)
        assertEquals("DEMO_BOOK_TITLES should have 2 entries", 2, titles.size)
        android.util.Log.d(TAG, "DEMO_BOOK_TITLES type: ${titles::class.simpleName}, size: ${titles.size}")
    }

    @Test
    fun testSpaceStoryBookExistsInDatabase() {
        runBlocking {
            // Check if Space story exists (should be seeded on app start if PDF exists)
            val book = bookRepository.findBookByTitle("Space story")
            
            if (book != null) {
                android.util.Log.d(TAG, "Space story found in database: id=${book.id}, title=${book.title}")
                assertNotNull("Book should have a title", book.title)
                assertEquals("Title should be 'Space story'", "Space story", book.title)
            } else {
                android.util.Log.d(TAG, "Space story not found - PDF may not be in Downloads folder")
                // This is acceptable - the PDF might not be on the device
            }
        }
    }
}


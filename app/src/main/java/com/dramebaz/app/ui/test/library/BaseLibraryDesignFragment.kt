package com.dramebaz.app.ui.test.library

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.data.db.AnalysisState
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.ui.library.LibrarySection
import com.dramebaz.app.ui.library.LibraryViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Base fragment for library design variations.
 * Provides common functionality: data collection, book clicks, delete dialogs.
 * Uses dummy data when no real books exist.
 */
abstract class BaseLibraryDesignFragment : Fragment() {

    protected val app get() = requireContext().applicationContext as DramebazApplication
    protected val vm: LibraryViewModel by activityViewModels {
        LibraryViewModel.Factory(app)
    }

    // Track expanded sections
    protected val expandedSections = mutableSetOf(
        LibrarySection.FAVOURITES,
        LibrarySection.LAST_READ,
        LibrarySection.FINISHED,
        LibrarySection.RECENTLY_ADDED,
        LibrarySection.UNREAD
    )

    protected var currentBooks: List<Book> = emptyList()

    // Dummy data for testing when no books exist
    private val dummyBooks: List<Book> by lazy { createDummyBooks() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectBooks()
    }

    private fun collectBooks() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.books.collectLatest { books ->
                    // Always use dummy data for test activity to showcase designs
                    // Merge real books with dummy books to ensure designs are populated
                    currentBooks = dummyBooks + books
                    onBooksUpdated(currentBooks)
                }
            }
        }
    }

    /** Create dummy books with varied properties for design testing */
    private fun createDummyBooks(): List<Book> {
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L

        return listOf(
            // Favorites - currently reading
            Book(
                id = 1, title = "Harry Potter and the Sorcerer's Stone", filePath = "/dummy/hp1.pdf",
                format = "pdf", createdAt = now - 30 * day, isFavorite = true,
                lastReadAt = now - 2 * 60 * 60 * 1000, readingProgress = 0.65f,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Fantasy", placeholderCoverPath = "images/bookcovers/Fantasy.png"
            ),
            Book(
                id = 2, title = "The Lord of the Rings", filePath = "/dummy/lotr.epub",
                format = "epub", createdAt = now - 45 * day, isFavorite = true,
                lastReadAt = now - day, readingProgress = 0.32f,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Fantasy", placeholderCoverPath = "images/bookcovers/Fantasy.png"
            ),
            // Last Read - in progress
            Book(
                id = 3, title = "Pride and Prejudice", filePath = "/dummy/pride.pdf",
                format = "pdf", createdAt = now - 20 * day,
                lastReadAt = now - 3 * day, readingProgress = 0.78f,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Romance", placeholderCoverPath = "images/bookcovers/Romance.png"
            ),
            Book(
                id = 4, title = "1984", filePath = "/dummy/1984.txt",
                format = "txt", createdAt = now - 15 * day,
                lastReadAt = now - 5 * day, readingProgress = 0.45f,
                analysisStatus = AnalysisState.ANALYZING.name, analysisProgress = 67,
                detectedGenre = "Sci-Fi", placeholderCoverPath = "images/bookcovers/Sci-Fi.png"
            ),
            // Finished books
            Book(
                id = 5, title = "To Kill a Mockingbird", filePath = "/dummy/mockingbird.pdf",
                format = "pdf", createdAt = now - 60 * day, isFavorite = true,
                lastReadAt = now - 10 * day, readingProgress = 1.0f, isFinished = true,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Literature", placeholderCoverPath = "images/bookcovers/Literature.png"
            ),
            Book(
                id = 6, title = "The Great Gatsby", filePath = "/dummy/gatsby.epub",
                format = "epub", createdAt = now - 90 * day,
                lastReadAt = now - 25 * day, readingProgress = 1.0f, isFinished = true,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Literature", placeholderCoverPath = "images/bookcovers/Literature.png"
            ),
            // Recently added - not started
            Book(
                id = 7, title = "Dune", filePath = "/dummy/dune.pdf",
                format = "pdf", createdAt = now - 2 * day,
                analysisStatus = AnalysisState.PENDING.name, analysisProgress = 0,
                detectedGenre = "Sci-Fi", placeholderCoverPath = "images/bookcovers/Sci-Fi.png"
            ),
            Book(
                id = 8, title = "The Hobbit", filePath = "/dummy/hobbit.epub",
                format = "epub", createdAt = now - 3 * day,
                analysisStatus = AnalysisState.ANALYZING.name, analysisProgress = 25,
                detectedGenre = "Fantasy", placeholderCoverPath = "images/bookcovers/Fantasy.png"
            ),
            Book(
                id = 9, title = "Sherlock Holmes", filePath = "/dummy/sherlock.pdf",
                format = "pdf", createdAt = now - 4 * day,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Mystery", placeholderCoverPath = "images/bookcovers/Mystery.png"
            ),
            // Unread
            Book(
                id = 10, title = "Brave New World", filePath = "/dummy/brave.txt",
                format = "txt", createdAt = now - 7 * day,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Sci-Fi", placeholderCoverPath = "images/bookcovers/Sci-Fi.png"
            ),
            Book(
                id = 11, title = "Jane Eyre", filePath = "/dummy/jane.pdf",
                format = "pdf", createdAt = now - 14 * day,
                analysisStatus = AnalysisState.FAILED.name, analysisProgress = 50,
                detectedGenre = "Romance", placeholderCoverPath = "images/bookcovers/Romance.png"
            ),
            Book(
                id = 12, title = "Dracula", filePath = "/dummy/dracula.epub",
                format = "epub", createdAt = now - 21 * day,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Horror", placeholderCoverPath = "images/bookcovers/Horror.png"
            ),
            Book(
                id = 13, title = "Frankenstein", filePath = "/dummy/frank.pdf",
                format = "pdf", createdAt = now - 28 * day,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Horror", placeholderCoverPath = "images/bookcovers/Horror.png"
            ),
            Book(
                id = 14, title = "The Hitchhiker's Guide", filePath = "/dummy/hitchhiker.epub",
                format = "epub", createdAt = now - 35 * day,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Comedy", placeholderCoverPath = "images/bookcovers/Comedy.png"
            ),
            Book(
                id = 15, title = "Steve Jobs Biography", filePath = "/dummy/jobs.pdf",
                format = "pdf", createdAt = now - 42 * day, isFavorite = true,
                lastReadAt = now - 15 * day, readingProgress = 0.55f,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Biographies", placeholderCoverPath = "images/bookcovers/Biographies.png"
            ),
            Book(
                id = 16, title = "World War II History", filePath = "/dummy/ww2.epub",
                format = "epub", createdAt = now - 50 * day,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "History", placeholderCoverPath = "images/bookcovers/History.png"
            ),
            Book(
                id = 17, title = "Charlotte's Web", filePath = "/dummy/charlotte.pdf",
                format = "pdf", createdAt = now - 55 * day, isFavorite = true,
                lastReadAt = now - 8 * day, readingProgress = 1.0f, isFinished = true,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Childrens", placeholderCoverPath = "images/bookcovers/Childrens.png"
            ),
            Book(
                id = 18, title = "The Art of War", filePath = "/dummy/artofwar.txt",
                format = "txt", createdAt = now - 60 * day,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "NonFiction", placeholderCoverPath = "images/bookcovers/NonFiction.png"
            ),
            Book(
                id = 19, title = "The Bhagavad Gita", filePath = "/dummy/gita.pdf",
                format = "pdf", createdAt = now - 65 * day,
                lastReadAt = now - 20 * day, readingProgress = 0.25f,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Spiritual", placeholderCoverPath = "images/bookcovers/Spiritual.png"
            ),
            Book(
                id = 20, title = "Agatha Christie Collection", filePath = "/dummy/agatha.epub",
                format = "epub", createdAt = now - 70 * day,
                analysisStatus = AnalysisState.COMPLETED.name, analysisProgress = 100,
                detectedGenre = "Mystery", placeholderCoverPath = "images/bookcovers/Mystery.png"
            )
        )
    }

    /** Called when books list changes. Override to update UI. */
    protected abstract fun onBooksUpdated(books: List<Book>)

    /** Handle book click - can be overridden for custom behavior */
    protected open fun onBookClick(book: Book, coverImageView: ImageView) {
        Toast.makeText(
            requireContext(),
            "Clicked: ${book.title}\n(Navigation disabled in test mode)",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Handle book long click - shows delete dialog */
    protected open fun onBookLongClick(book: Book): Boolean {
        showDeleteConfirmationDialog(book)
        return true
    }

    /** Handle favorite toggle */
    protected open fun onFavoriteClick(book: Book) {
        vm.toggleFavorite(book)
        val msg = if (book.isFavorite) "Removed from favorites" else "Added to favorites"
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    /** Handle section toggle */
    protected open fun onSectionToggle(section: LibrarySection) {
        if (expandedSections.contains(section)) {
            expandedSections.remove(section)
        } else {
            expandedSections.add(section)
        }
        onBooksUpdated(currentBooks) // Trigger UI update
    }

    private fun showDeleteConfirmationDialog(book: Book) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Book")
            .setMessage("Delete \"${book.title}\"?\n\nThis will remove the book and all associated data.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                vm.deleteBook(book)
                Toast.makeText(requireContext(), "Book deleted", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Build sectioned list for adapters */
    protected fun buildSectionedList(allBooks: List<Book>): List<LibraryItem> {
        val items = mutableListOf<LibraryItem>()

        // Favourites section
        val favourites = allBooks.filter { it.isFavorite }
        items.add(LibraryItem.Header(LibrarySection.FAVOURITES, favourites.size, expandedSections.contains(LibrarySection.FAVOURITES)))
        if (expandedSections.contains(LibrarySection.FAVOURITES)) {
            favourites.forEach { items.add(LibraryItem.BookItem(it, LibrarySection.FAVOURITES)) }
        }

        // Last Read section
        val lastRead = allBooks.filter { it.lastReadAt != null && !it.isFinished }
            .sortedByDescending { it.lastReadAt }
        items.add(LibraryItem.Header(LibrarySection.LAST_READ, lastRead.size, expandedSections.contains(LibrarySection.LAST_READ)))
        if (expandedSections.contains(LibrarySection.LAST_READ)) {
            lastRead.forEach { items.add(LibraryItem.BookItem(it, LibrarySection.LAST_READ)) }
        }

        // Finished section
        val finished = allBooks.filter { it.isFinished }
            .sortedByDescending { it.lastReadAt }
        items.add(LibraryItem.Header(LibrarySection.FINISHED, finished.size, expandedSections.contains(LibrarySection.FINISHED)))
        if (expandedSections.contains(LibrarySection.FINISHED)) {
            finished.forEach { items.add(LibraryItem.BookItem(it, LibrarySection.FINISHED)) }
        }

        // Recently Added section
        val recentlyAdded = allBooks.sortedByDescending { it.createdAt }
        items.add(LibraryItem.Header(LibrarySection.RECENTLY_ADDED, recentlyAdded.size, expandedSections.contains(LibrarySection.RECENTLY_ADDED)))
        if (expandedSections.contains(LibrarySection.RECENTLY_ADDED)) {
            recentlyAdded.forEach { items.add(LibraryItem.BookItem(it, LibrarySection.RECENTLY_ADDED)) }
        }

        // Unread section
        val unread = allBooks.filter { it.lastReadAt == null && !it.isFinished }
            .sortedByDescending { it.createdAt }
        items.add(LibraryItem.Header(LibrarySection.UNREAD, unread.size, expandedSections.contains(LibrarySection.UNREAD)))
        if (expandedSections.contains(LibrarySection.UNREAD)) {
            unread.forEach { items.add(LibraryItem.BookItem(it, LibrarySection.UNREAD)) }
        }

        return items
    }
}


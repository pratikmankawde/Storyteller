package com.dramebaz.app.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.data.db.AnalysisState
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.domain.usecases.AnalysisQueueManager
import com.dramebaz.app.ui.common.ErrorDialog
import com.dramebaz.app.ui.settings.SettingsBottomSheet
import com.dramebaz.app.utils.MemoryMonitor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * LIBRARY-001: Library fragment with collapsible sections for book organization.
 * Sections: Favourites, Last Read, Finished, Recently Added, Unread
 */
class LibraryFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val vm: LibraryViewModel by viewModels {
        LibraryViewModel.Factory(app)
    }

    private var memoryMonitor: MemoryMonitor? = null

    // LIBRARY-001: Sectioned adapter stored as property for section toggle callbacks
    private var sectionAdapter: LibrarySectionAdapter? = null

    // LIBRARY-001: Track which sections are expanded (all open by default)
    private val expandedSections = mutableSetOf(
        LibrarySection.FAVOURITES,
        LibrarySection.LAST_READ,
        LibrarySection.FINISHED,
        LibrarySection.RECENTLY_ADDED,
        LibrarySection.UNREAD
    )

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.importFromUri(requireContext(), it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)

        // Start memory monitoring on toolbar
        memoryMonitor = MemoryMonitor(requireContext(), toolbar)
        memoryMonitor?.start()

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.settings -> {
                    SettingsBottomSheet.newInstance().show(childFragmentManager, "settings")
                    true
                }
                R.id.test_activity -> {
                    startActivity(android.content.Intent(requireContext(), com.dramebaz.app.ui.test.TestActivity::class.java))
                    true
                }
                R.id.generate_story -> {
                    findNavController().navigate(R.id.action_library_to_storyGeneration)
                    true
                }
                else -> false
            }
        }

        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val emptyState = view.findViewById<View>(R.id.empty_state)

        // LIBRARY-001: Create sectioned adapter (stored as property for callbacks)
        sectionAdapter = LibrarySectionAdapter(
            onBookClick = { book, coverImageView ->
                navigateToBookDetail(book, coverImageView)
            },
            onBookLongClick = { book ->
                showDeleteConfirmationDialog(book)
                true
            },
            onSectionToggle = { section ->
                toggleSection(section, recycler)
            }
        )

        // LIBRARY-001: Configure GridLayoutManager with span lookup for headers
        val gridLayoutManager = GridLayoutManager(requireContext(), 2)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                // Headers span full width, books take 1 column
                return when (sectionAdapter?.getItemViewType(position)) {
                    0 -> 2 // Header spans full width
                    else -> 1 // Book takes 1 column
                }
            }
        }
        recycler.layoutManager = gridLayoutManager
        recycler.adapter = sectionAdapter
        recycler.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 300
            removeDuration = 300
        }

        // LIBRARY-001: Observe all books and build sectioned list
        viewLifecycleOwner.lifecycleScope.launch {
            vm.books.combine(AnalysisQueueManager.analysisStatus) { books, analysisStatusMap ->
                // Merge live analysis status into books
                books.map { book ->
                    val liveStatus = analysisStatusMap[book.id]
                    if (liveStatus != null) {
                        val mappedState = when (liveStatus.state) {
                            AnalysisQueueManager.AnalysisState.PENDING -> AnalysisState.PENDING
                            AnalysisQueueManager.AnalysisState.ANALYZING -> AnalysisState.ANALYZING
                            AnalysisQueueManager.AnalysisState.COMPLETE -> AnalysisState.COMPLETED
                            AnalysisQueueManager.AnalysisState.FAILED -> AnalysisState.FAILED
                        }
                        book.copy(
                            analysisStatus = mappedState.name,
                            analysisProgress = liveStatus.progress,
                            analyzedChapterCount = liveStatus.analyzedChapters,
                            totalChaptersToAnalyze = liveStatus.totalChapters,
                            analysisMessage = liveStatus.message
                        )
                    } else {
                        book
                    }
                }
            }.collectLatest { books ->
                val sectionedItems = buildSectionedList(books)
                sectionAdapter?.submitList(sectionedItems)

                emptyState.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        // AUG-039: Observe import errors and show dialog with retry option
        viewLifecycleOwner.lifecycleScope.launch {
            vm.importError.collect { errorMessage ->
                ErrorDialog.showWithRetry(
                    context = requireContext(),
                    title = "Import Failed",
                    message = errorMessage,
                    onRetry = { picker.launch("*/*") }
                )
            }
        }
        view.findViewById<View>(R.id.fab_import).setOnClickListener { picker.launch("*/*") }

        // UI-004: Set up exit/reenter transitions for returning from BookDetailFragment
        postponeEnterTransition()
        view.viewTreeObserver.addOnPreDrawListener {
            startPostponedEnterTransition()
            true
        }
    }

    /**
     * LIBRARY-001: Build the sectioned list from all books.
     * A book can appear in multiple sections (e.g., both Favourites and Last Read).
     */
    private fun buildSectionedList(allBooks: List<Book>): List<LibraryItem> {
        val items = mutableListOf<LibraryItem>()

        // Favourites section
        val favourites = allBooks.filter { it.isFavorite }
        items.add(LibraryItem.Header(LibrarySection.FAVOURITES, favourites.size, expandedSections.contains(LibrarySection.FAVOURITES)))
        if (expandedSections.contains(LibrarySection.FAVOURITES)) {
            favourites.forEach { items.add(LibraryItem.BookItem(it, LibrarySection.FAVOURITES)) }
        }

        // Last Read section (recently read but not finished)
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

        // Unread section (never opened)
        val unread = allBooks.filter { it.lastReadAt == null }
            .sortedByDescending { it.createdAt }
        items.add(LibraryItem.Header(LibrarySection.UNREAD, unread.size, expandedSections.contains(LibrarySection.UNREAD)))
        if (expandedSections.contains(LibrarySection.UNREAD)) {
            unread.forEach { items.add(LibraryItem.BookItem(it, LibrarySection.UNREAD)) }
        }

        return items
    }

    /**
     * LIBRARY-001: Toggle section expand/collapse.
     */
    private fun toggleSection(section: LibrarySection, recycler: RecyclerView) {
        if (expandedSections.contains(section)) {
            expandedSections.remove(section)
        } else {
            expandedSections.add(section)
        }
        // Trigger a re-observe by getting current list and rebuilding
        viewLifecycleOwner.lifecycleScope.launch {
            vm.books.combine(AnalysisQueueManager.analysisStatus) { books, statusMap ->
                books.map { book ->
                    val liveStatus = statusMap[book.id]
                    if (liveStatus != null) {
                        val mappedState = when (liveStatus.state) {
                            AnalysisQueueManager.AnalysisState.PENDING -> AnalysisState.PENDING
                            AnalysisQueueManager.AnalysisState.ANALYZING -> AnalysisState.ANALYZING
                            AnalysisQueueManager.AnalysisState.COMPLETE -> AnalysisState.COMPLETED
                            AnalysisQueueManager.AnalysisState.FAILED -> AnalysisState.FAILED
                        }
                        book.copy(
                            analysisStatus = mappedState.name,
                            analysisProgress = liveStatus.progress,
                            analyzedChapterCount = liveStatus.analyzedChapters,
                            totalChaptersToAnalyze = liveStatus.totalChapters,
                            analysisMessage = liveStatus.message
                        )
                    } else {
                        book
                    }
                }
            }.collectLatest { books ->
                sectionAdapter?.submitList(buildSectionedList(books))
            }
        }
    }

    /**
     * UI-004: Navigate to book detail with shared element transition.
     * The book cover morphs from the library grid into the book detail page.
     */
    private fun navigateToBookDetail(book: Book, coverImageView: ImageView) {
        // Set up exit transition for this fragment
        exitTransition = MaterialElevationScale(false).apply {
            duration = 300L
        }
        reenterTransition = MaterialElevationScale(true).apply {
            duration = 300L
        }

        // Get the transition name from the cover view
        val transitionName = ViewCompat.getTransitionName(coverImageView) ?: "book_cover_${book.id}"

        // Create the shared element extras
        val extras = FragmentNavigatorExtras(coverImageView to transitionName)

        // Navigate with shared element
        val bundle = Bundle().apply { putLong("bookId", book.id) }
        findNavController().navigate(R.id.bookDetailFragment, bundle, null, extras)
    }

    private fun showDeleteConfirmationDialog(book: Book) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Book")
            .setMessage("Are you sure you want to delete \"${book.title}\"? This will remove all chapters and analysis data.")
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton("Delete") { dialog, _ ->
                vm.deleteBook(book)
                Toast.makeText(requireContext(), "\"${book.title}\" deleted", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        memoryMonitor?.stop()
        memoryMonitor = null
        sectionAdapter = null
    }
}

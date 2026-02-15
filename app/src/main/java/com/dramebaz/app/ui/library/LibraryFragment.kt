package com.dramebaz.app.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
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
 * LIBRARY-GLASS: Library fragment with horizontal sections and glassmorphism styling.
 * Netflix/Spotify style layout with vertical scroll and horizontal book carousels.
 * Sections: Favourites, Last Read, Finished, Recently Added, Unread
 */
class LibraryFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val vm: LibraryViewModel by viewModels {
        LibraryViewModel.Factory(app)
    }

    private var memoryMonitor: MemoryMonitor? = null

    // Container for horizontal section views
    private var sectionsContainer: LinearLayout? = null
    private var emptyState: View? = null
    private var scrollView: View? = null

    // Track which sections are expanded (all open by default)
    private val expandedSections = mutableSetOf(
        LibrarySection.FAVOURITES,
        LibrarySection.LAST_READ,
        LibrarySection.FINISHED,
        LibrarySection.RECENTLY_ADDED,
        LibrarySection.UNREAD
    )

    // Cache current books list for section toggle rebuilding
    private var currentBooks: List<Book> = emptyList()

    // File picker configured to show only supported file types and allow multiple selection
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri -> vm.importFromUri(requireContext(), uri) }
    }

    // MIME types for supported file formats
    private val supportedMimeTypes = arrayOf(
        "application/pdf",                          // PDF
        "application/epub+zip",                     // EPUB
        "text/plain",                               // TXT
        "application/x-mobipocket-ebook",           // MOBI (for future support)
        "application/octet-stream"                  // Fallback for some file managers
    )

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

        // Initialize horizontal sections layout
        sectionsContainer = view.findViewById(R.id.sections_container)
        emptyState = view.findViewById(R.id.empty_state)
        scrollView = view.findViewById(R.id.scroll_view)

        // Observe all books and build horizontal sections
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
                currentBooks = books
                updateSections(books)
            }
        }

        // Observe import errors and show dialog with retry option
        viewLifecycleOwner.lifecycleScope.launch {
            vm.importError.collect { errorMessage ->
                ErrorDialog.showWithRetry(
                    context = requireContext(),
                    title = "Import Failed",
                    message = errorMessage,
                    onRetry = { picker.launch(supportedMimeTypes) }
                )
            }
        }
        view.findViewById<View>(R.id.fab_import).setOnClickListener { picker.launch(supportedMimeTypes) }

        // UI-004: Set up exit/reenter transitions for returning from BookDetailFragment
        postponeEnterTransition()
        view.viewTreeObserver.addOnPreDrawListener {
            startPostponedEnterTransition()
            true
        }
    }

    /**
     * Update the horizontal sections with current book data.
     * Each section has a glassmorphism header and horizontal book carousel.
     */
    private fun updateSections(allBooks: List<Book>) {
        sectionsContainer?.removeAllViews()

        if (allBooks.isEmpty()) {
            emptyState?.visibility = View.VISIBLE
            scrollView?.visibility = View.GONE
            return
        }
        emptyState?.visibility = View.GONE
        scrollView?.visibility = View.VISIBLE

        // Build section data map
        val sectionData = linkedMapOf(
            LibrarySection.FAVOURITES to allBooks.filter { it.isFavorite },
            LibrarySection.LAST_READ to allBooks.filter { it.lastReadAt != null && !it.isFinished }
                .sortedByDescending { it.lastReadAt },
            LibrarySection.FINISHED to allBooks.filter { it.isFinished }
                .sortedByDescending { it.lastReadAt },
            LibrarySection.RECENTLY_ADDED to allBooks.sortedByDescending { it.createdAt },
            LibrarySection.UNREAD to allBooks.filter { it.lastReadAt == null && !it.isFinished }
        )

        // Add each section with books
        sectionData.forEach { (section, sectionBooks) ->
            addSection(section, sectionBooks)
        }
    }

    /**
     * Add a section with glassmorphism header and horizontal RecyclerView.
     */
    private fun addSection(section: LibrarySection, books: List<Book>) {
        val inflater = LayoutInflater.from(context)
        val sectionView = inflater.inflate(R.layout.item_section_glass, sectionsContainer, false)

        // Configure header
        val header = sectionView.findViewById<View>(R.id.section_header)
        header.findViewById<ImageView>(R.id.section_icon).setImageResource(section.iconResId)
        header.findViewById<TextView>(R.id.section_title).setText(section.titleResId)
        header.findViewById<TextView>(R.id.section_count).text = books.size.toString()

        val arrow = header.findViewById<ImageView>(R.id.section_arrow)
        val isExpanded = expandedSections.contains(section)
        arrow.rotation = if (isExpanded) 180f else 0f

        // Configure horizontal RecyclerView
        val recycler = sectionView.findViewById<RecyclerView>(R.id.books_recycler)
        recycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        recycler.adapter = HorizontalBookAdapter(books, section)
        recycler.visibility = if (isExpanded && books.isNotEmpty()) View.VISIBLE else View.GONE

        // Toggle section on header click
        header.setOnClickListener {
            toggleSection(section, recycler, arrow)
        }

        sectionsContainer?.addView(sectionView)
    }

    /**
     * Toggle section expand/collapse with animation.
     */
    private fun toggleSection(section: LibrarySection, recycler: RecyclerView, arrow: ImageView) {
        val isExpanded = expandedSections.contains(section)
        if (isExpanded) {
            expandedSections.remove(section)
            recycler.visibility = View.GONE
            arrow.animate().rotation(0f).setDuration(200).start()
        } else {
            expandedSections.add(section)
            recycler.visibility = View.VISIBLE
            arrow.animate().rotation(180f).setDuration(200).start()
        }
    }

    /**
     * Horizontal book adapter for glassmorphism carousel.
     */
    private inner class HorizontalBookAdapter(
        private val books: List<Book>,
        private val section: LibrarySection
    ) : RecyclerView.Adapter<HorizontalBookAdapter.BookViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_book_horizontal_glass, parent, false)
            return BookViewHolder(view)
        }

        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            holder.bind(books[position])
        }

        override fun getItemCount() = books.size

        inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cover: ImageView = itemView.findViewById(R.id.book_cover)
            private val title: TextView = itemView.findViewById(R.id.book_title)
            private val favoriteIndicator: ImageView = itemView.findViewById(R.id.favorite_indicator)
            private val analysisStatus: TextView = itemView.findViewById(R.id.analysis_status)

            fun bind(book: Book) {
                title.text = book.title

                // Set unique transition name for shared element transition
                val transitionName = "book_cover_${book.id}"
                ViewCompat.setTransitionName(cover, transitionName)

                // Load cover with slideshow enabled for analyzing books
                BookCoverLoader.loadCoverInto(cover, book, enableSlideshow = true)

                // Show favorite indicator
                favoriteIndicator.visibility = if (book.isFavorite) View.VISIBLE else View.GONE

                // Show analysis status
                bindAnalysisStatus(book)

                // Click handlers
                itemView.setOnClickListener { navigateToBookDetail(book, cover) }
                itemView.setOnLongClickListener {
                    showDeleteConfirmationDialog(book)
                    true
                }
            }

            private fun bindAnalysisStatus(book: Book) {
                when (book.getAnalysisState()) {
                    AnalysisState.ANALYZING -> {
                        analysisStatus.visibility = View.VISIBLE
                        analysisStatus.text = "Analyzing ${book.analyzedChapterCount}/${book.totalChaptersToAnalyze}..."
                    }
                    AnalysisState.PENDING -> {
                        analysisStatus.visibility = View.VISIBLE
                        analysisStatus.text = "Pending..."
                    }
                    else -> analysisStatus.visibility = View.GONE
                }
            }
        }
    }

    /**
     * UI-004: Navigate to book detail with shared element transition.
     * The book cover morphs from the library into the book detail page.
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
        sectionsContainer = null
        emptyState = null
        scrollView = null
    }
}

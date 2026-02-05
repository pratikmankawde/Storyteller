package com.dramebaz.app.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.ui.common.ErrorDialog
import com.dramebaz.app.utils.MemoryMonitor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val vm: LibraryViewModel by viewModels {
        LibraryViewModel.Factory(app.bookRepository)
    }

    private var memoryMonitor: MemoryMonitor? = null

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
                R.id.settings -> { findNavController().navigate(R.id.settingsFragment); true }
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
        val gridLayoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
        recycler.layoutManager = gridLayoutManager
        val adapter = BookAdapter(
            onBookClick = { book ->
                findNavController().navigate(R.id.bookDetailFragment, Bundle().apply { putLong("bookId", book.id) })
            },
            onBookLongClick = { book ->
                showDeleteConfirmationDialog(book)
                true
            }
        )
        recycler.adapter = adapter
        recycler.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 300
            removeDuration = 300
        }
        val emptyState = view.findViewById<View>(R.id.empty_state)
        viewLifecycleOwner.lifecycleScope.launch {
            vm.books.collectLatest { books ->
                adapter.submitList(books)
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
    }
}

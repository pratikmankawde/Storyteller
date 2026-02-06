package com.dramebaz.app.ui.reader

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.ai.llm.pipeline.ChapterAnalysisInput
import com.dramebaz.app.ai.llm.pipeline.ChapterAnalysisPass
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CHAP-001: Chapter Manager Dialog.
 * 
 * Full-screen dialog for managing chapters:
 * - Reorder chapters (move up/down)
 * - Rename chapters inline
 * - Merge chapters with previous
 * - Split chapters in half
 * - Delete chapters with confirmation
 * 
 * From NovelReaderWeb chapter-manager.component.ts
 */
class ChapterManagerDialog : DialogFragment() {
    
    companion object {
        private const val TAG = "ChapterManagerDialog"
        private const val ARG_BOOK_ID = "bookId"
        
        fun newInstance(bookId: Long): ChapterManagerDialog {
            return ChapterManagerDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_BOOK_ID, bookId)
                }
            }
        }
    }
    
    /**
     * Listener for chapter changes.
     */
    interface ChapterChangeListener {
        fun onChaptersChanged()
    }
    
    private var listener: ChapterChangeListener? = null
    private var bookId: Long = 0L
    
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerChapters: RecyclerView
    private lateinit var emptyState: View
    private lateinit var batchActionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var btnDeselectAll: MaterialButton
    private lateinit var btnReanalyzeSelected: MaterialButton
    private lateinit var btnDeleteSelected: MaterialButton
    private lateinit var chapterCountInfo: TextView

    private lateinit var adapter: ChapterAdapter
    private val selectedChapters = mutableSetOf<Long>()
    
    private val app: DramebazApplication
        get() = requireActivity().application as DramebazApplication
    
    fun setListener(listener: ChapterChangeListener) {
        this.listener = listener
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        bookId = arguments?.getLong(ARG_BOOK_ID) ?: 0L
        AppLogger.d(TAG, "ChapterManagerDialog created for bookId=$bookId")
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_chapter_manager, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupViews(view)
        setupToolbar()
        setupRecyclerView()
        setupBatchActions()
        loadChapters()
    }
    
    private fun setupViews(view: View) {
        toolbar = view.findViewById(R.id.toolbar)
        recyclerChapters = view.findViewById(R.id.recycler_chapters)
        emptyState = view.findViewById(R.id.empty_state)
        batchActionBar = view.findViewById(R.id.batch_action_bar)
        selectionCount = view.findViewById(R.id.selection_count)
        btnDeselectAll = view.findViewById(R.id.btn_deselect_all)
        btnReanalyzeSelected = view.findViewById(R.id.btn_reanalyze_selected)
        btnDeleteSelected = view.findViewById(R.id.btn_delete_selected)
        chapterCountInfo = view.findViewById(R.id.chapter_count_info)
    }
    
    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            dismiss()
        }
    }
    
    private fun setupRecyclerView() {
        adapter = ChapterAdapter(
            onSelectionChanged = { chapterId, isSelected ->
                if (isSelected) selectedChapters.add(chapterId) else selectedChapters.remove(chapterId)
                updateBatchActionBar()
            },
            onTitleChanged = { chapter, newTitle -> updateChapterTitle(chapter, newTitle) },
            onMoveUp = { chapter -> moveChapterUp(chapter) },
            onMoveDown = { chapter -> moveChapterDown(chapter) },
            onMerge = { chapter -> showMergeConfirmation(chapter) },
            onSplit = { chapter -> showSplitConfirmation(chapter) },
            onDelete = { chapter -> showDeleteConfirmation(chapter) }
        )
        recyclerChapters.layoutManager = LinearLayoutManager(requireContext())
        recyclerChapters.adapter = adapter
    }

    private fun setupBatchActions() {
        btnDeselectAll.setOnClickListener {
            selectedChapters.clear()
            adapter.clearSelections()
            updateBatchActionBar()
        }

        // CHAP-002: Batch re-analysis
        btnReanalyzeSelected.setOnClickListener {
            if (selectedChapters.isNotEmpty()) {
                batchReanalyzeSelected()
            }
        }

        // CHAP-002: Batch delete
        btnDeleteSelected.setOnClickListener {
            if (selectedChapters.isNotEmpty()) {
                showDeleteSelectedConfirmation()
            }
        }
    }

    private fun updateBatchActionBar() {
        val count = selectedChapters.size
        if (count > 0) {
            batchActionBar.visibility = View.VISIBLE
            selectionCount.text = getString(R.string.chapters_selected_count, count)
        } else {
            batchActionBar.visibility = View.GONE
        }
    }

    private fun loadChapters() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val chapters = app.bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
            withContext(Dispatchers.Main) {
                if (chapters.isEmpty()) {
                    recyclerChapters.visibility = View.GONE
                    emptyState.visibility = View.VISIBLE
                } else {
                    recyclerChapters.visibility = View.VISIBLE
                    emptyState.visibility = View.GONE
                    adapter.submitList(chapters)
                    chapterCountInfo.text = getString(R.string.chapter_count_format, chapters.size)
                }
            }
        }
    }

    private fun updateChapterTitle(chapter: Chapter, newTitle: String) {
        if (newTitle.isBlank() || newTitle == chapter.title) return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val updated = chapter.copy(title = newTitle)
            app.bookRepository.updateChapter(updated)
            AppLogger.d(TAG, "Chapter ${chapter.id} title updated to: $newTitle")
            listener?.onChaptersChanged()
            loadChapters()
        }
    }

    private fun moveChapterUp(chapter: Chapter) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val chapters = app.bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
            val currentIndex = chapters.indexOfFirst { it.id == chapter.id }
            if (currentIndex <= 0) return@launch

            val prevChapter = chapters[currentIndex - 1]
            // Swap order indices
            app.bookRepository.updateChapter(chapter.copy(orderIndex = prevChapter.orderIndex))
            app.bookRepository.updateChapter(prevChapter.copy(orderIndex = chapter.orderIndex))
            AppLogger.d(TAG, "Moved chapter ${chapter.id} up")
            listener?.onChaptersChanged()
            loadChapters()
        }
    }

    private fun moveChapterDown(chapter: Chapter) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val chapters = app.bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
            val currentIndex = chapters.indexOfFirst { it.id == chapter.id }
            if (currentIndex < 0 || currentIndex >= chapters.size - 1) return@launch

            val nextChapter = chapters[currentIndex + 1]
            // Swap order indices
            app.bookRepository.updateChapter(chapter.copy(orderIndex = nextChapter.orderIndex))
            app.bookRepository.updateChapter(nextChapter.copy(orderIndex = chapter.orderIndex))
            AppLogger.d(TAG, "Moved chapter ${chapter.id} down")
            listener?.onChaptersChanged()
            loadChapters()
        }
    }

    private fun showMergeConfirmation(chapter: Chapter) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val chapters = app.bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
            val currentIndex = chapters.indexOfFirst { it.id == chapter.id }
            if (currentIndex <= 0) {
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.cannot_merge)
                        .setMessage(R.string.no_previous_chapter)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                return@launch
            }

            val prevChapter = chapters[currentIndex - 1]
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.merge_chapters)
                    .setMessage(getString(R.string.merge_chapters_message, prevChapter.title, chapter.title))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.merge) { _, _ ->
                        mergeChapters(prevChapter, chapter)
                    }
                    .show()
            }
        }
    }

    private fun mergeChapters(targetChapter: Chapter, sourceChapter: Chapter) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Merge body content
            val mergedBody = targetChapter.body + "\n\n" + sourceChapter.body
            val merged = targetChapter.copy(
                body = mergedBody,
                // Clear analysis since content changed
                analysisJson = null,
                fullAnalysisJson = null
            )
            app.bookRepository.updateChapter(merged)

            // Delete source chapter and reorder remaining
            val chapters = app.bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
            val remaining = chapters.filter { it.id != sourceChapter.id }
            remaining.forEachIndexed { index, ch ->
                if (ch.orderIndex != index) {
                    app.bookRepository.updateChapter(ch.copy(orderIndex = index))
                }
            }

            // Delete the source chapter
            app.db.chapterDao().deleteChapter(sourceChapter.id)
            AppLogger.d(TAG, "Merged chapter ${sourceChapter.id} into ${targetChapter.id}")
            listener?.onChaptersChanged()
            loadChapters()
        }
    }

    private fun showSplitConfirmation(chapter: Chapter) {
        if (chapter.body.length < 100) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cannot_split)
                .setMessage(R.string.chapter_too_short)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.split_chapter)
            .setMessage(getString(R.string.split_chapter_message, chapter.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.split) { _, _ ->
                splitChapter(chapter)
            }
            .show()
    }

    private fun splitChapter(chapter: Chapter) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Split at paragraph boundary near middle
            val body = chapter.body
            val midpoint = body.length / 2

            // Find paragraph break near midpoint
            val paragraphBreak = findParagraphBreak(body, midpoint)
            val splitPoint = if (paragraphBreak > 0) paragraphBreak else midpoint

            val firstHalf = body.substring(0, splitPoint).trim()
            val secondHalf = body.substring(splitPoint).trim()

            // Update original chapter
            val updatedOriginal = chapter.copy(
                body = firstHalf,
                analysisJson = null,
                fullAnalysisJson = null
            )
            app.bookRepository.updateChapter(updatedOriginal)

            // Create new chapter
            val chapters = app.bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
            val currentIndex = chapters.indexOfFirst { it.id == chapter.id }

            // Shift all following chapters up by 1
            chapters.filter { it.orderIndex > chapter.orderIndex }.forEach { ch ->
                app.bookRepository.updateChapter(ch.copy(orderIndex = ch.orderIndex + 1))
            }

            // Insert new chapter
            val newChapter = Chapter(
                bookId = bookId,
                title = "${chapter.title} (Part 2)",
                body = secondHalf,
                orderIndex = chapter.orderIndex + 1
            )
            app.db.chapterDao().insert(newChapter)

            AppLogger.d(TAG, "Split chapter ${chapter.id} into two parts")
            listener?.onChaptersChanged()
            loadChapters()
        }
    }

    private fun findParagraphBreak(text: String, nearPosition: Int): Int {
        // Search for double newline (paragraph break) near the position
        val searchRange = minOf(500, text.length / 4)
        val start = maxOf(0, nearPosition - searchRange)
        val end = minOf(text.length, nearPosition + searchRange)

        // Look for paragraph break
        val section = text.substring(start, end)
        val relativeBreak = section.indexOf("\n\n")
        if (relativeBreak >= 0) {
            return start + relativeBreak + 2
        }

        // Fall back to single newline
        val singleBreak = section.indexOf("\n")
        if (singleBreak >= 0) {
            return start + singleBreak + 1
        }

        return -1
    }

    private fun showDeleteConfirmation(chapter: Chapter) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_chapter)
            .setMessage(getString(R.string.delete_chapter_message, chapter.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteChapter(chapter)
            }
            .show()
    }

    private fun deleteChapter(chapter: Chapter) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Reorder remaining chapters
            val chapters = app.bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
            chapters.filter { it.orderIndex > chapter.orderIndex }.forEach { ch ->
                app.bookRepository.updateChapter(ch.copy(orderIndex = ch.orderIndex - 1))
            }

            // Delete the chapter
            app.db.chapterDao().deleteChapter(chapter.id)
            AppLogger.d(TAG, "Deleted chapter ${chapter.id}")
            listener?.onChaptersChanged()
            loadChapters()
        }
    }

    // ==================== CHAP-002: Batch Chapter Re-Analysis ====================

    /**
     * CHAP-002: Batch re-analyze selected chapters with LLM.
     * Shows progress dialog and updates chapter summaries after analysis.
     */
    private fun batchReanalyzeSelected() {
        val selectedIds = selectedChapters.toList()
        if (selectedIds.isEmpty()) return

        AppLogger.d(TAG, "Starting batch re-analysis for ${selectedIds.size} chapters")

        // Show progress dialog
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reanalyzing_chapters)
            .setView(LayoutInflater.from(requireContext()).inflate(
                android.R.layout.simple_list_item_1, null
            ).apply {
                findViewById<TextView>(android.R.id.text1)?.text =
                    getString(R.string.reanalyzing_progress, 0, selectedIds.size)
            })
            .setCancelable(false)
            .create()
        progressDialog.show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val chapters = app.bookRepository.chapters(bookId).first()
                .filter { it.id in selectedIds }
                .sortedBy { it.orderIndex }

            var successCount = 0
            chapters.forEachIndexed { index, chapter ->
                withContext(Dispatchers.Main) {
                    progressDialog.findViewById<TextView>(android.R.id.text1)?.text =
                        getString(R.string.reanalyzing_progress, index + 1, chapters.size)
                }

                try {
                    // Ensure LLM is initialized
                    LlmService.ensureInitialized()
                    val model = LlmService.getModel()

                    val analysisJson = if (model != null) {
                        // Use ChapterAnalysisPass for chapter analysis
                        val chapterPass = ChapterAnalysisPass()
                        val output = chapterPass.execute(
                            model = model,
                            input = ChapterAnalysisInput(chapter.body),
                            config = ChapterAnalysisPass.DEFAULT_CONFIG
                        )
                        com.google.gson.Gson().toJson(output)
                    } else {
                        // Fallback to LlmService method (handles stubs internally)
                        val analysisResponse = LlmService.analyzeChapter(chapter.body)
                        com.google.gson.Gson().toJson(analysisResponse)
                    }

                    // Update chapter with new analysis
                    val updatedChapter = chapter.copy(
                        fullAnalysisJson = analysisJson,
                        analysisJson = null // Clear extended analysis, will be regenerated if needed
                    )
                    app.bookRepository.updateChapter(updatedChapter)
                    successCount++
                    AppLogger.d(TAG, "Re-analyzed chapter ${chapter.id}: ${chapter.title}")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to re-analyze chapter ${chapter.id}", e)
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()

                // Show completion message
                view?.let { rootView ->
                    Snackbar.make(
                        rootView,
                        getString(R.string.reanalysis_complete_message, successCount),
                        Snackbar.LENGTH_LONG
                    ).show()
                }

                // Clear selection and reload
                selectedChapters.clear()
                adapter.clearSelections()
                updateBatchActionBar()
                listener?.onChaptersChanged()
            }

            loadChapters()
        }
    }

    /**
     * CHAP-002: Show confirmation dialog before deleting selected chapters.
     */
    private fun showDeleteSelectedConfirmation() {
        val count = selectedChapters.size
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_selected_chapters)
            .setMessage(getString(R.string.delete_selected_chapters_message, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteSelectedChapters()
            }
            .show()
    }

    /**
     * CHAP-002: Delete all selected chapters and reorder remaining.
     */
    private fun deleteSelectedChapters() {
        val selectedIds = selectedChapters.toList()
        if (selectedIds.isEmpty()) return

        AppLogger.d(TAG, "Deleting ${selectedIds.size} selected chapters")

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Get all chapters for the book
            val allChapters = app.bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }

            // Delete selected chapters
            selectedIds.forEach { chapterId ->
                app.db.chapterDao().deleteChapter(chapterId)
            }

            // Reorder remaining chapters
            val remaining = allChapters.filter { it.id !in selectedIds }
            remaining.forEachIndexed { index, chapter ->
                if (chapter.orderIndex != index) {
                    app.bookRepository.updateChapter(chapter.copy(orderIndex = index))
                }
            }

            val deletedCount = selectedIds.size
            AppLogger.d(TAG, "Deleted $deletedCount chapters, reordered ${remaining.size} remaining")

            withContext(Dispatchers.Main) {
                // Show confirmation
                view?.let { rootView ->
                    Snackbar.make(
                        rootView,
                        getString(R.string.chapters_deleted, deletedCount),
                        Snackbar.LENGTH_LONG
                    ).show()
                }

                // Clear selection
                selectedChapters.clear()
                adapter.clearSelections()
                updateBatchActionBar()
                listener?.onChaptersChanged()
            }

            loadChapters()
        }
    }
}

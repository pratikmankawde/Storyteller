package com.dramebaz.app.ui.reader

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
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Chapter
import com.google.android.material.card.MaterialCardView

/**
 * CHAP-001: RecyclerView adapter for chapter management.
 * Supports selection, inline editing, and action buttons.
 */
class ChapterAdapter(
    private val onSelectionChanged: (Long, Boolean) -> Unit,
    private val onTitleChanged: (Chapter, String) -> Unit,
    private val onMoveUp: (Chapter) -> Unit,
    private val onMoveDown: (Chapter) -> Unit,
    private val onMerge: (Chapter) -> Unit,
    private val onSplit: (Chapter) -> Unit,
    private val onDelete: (Chapter) -> Unit
) : ListAdapter<Chapter, ChapterAdapter.ChapterViewHolder>(ChapterDiffCallback()) {
    
    private val selectedIds = mutableSetOf<Long>()
    
    fun clearSelections() {
        selectedIds.clear()
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chapter_manager, parent, false)
        return ChapterViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ChapterViewHolder, position: Int) {
        val chapter = getItem(position)
        holder.bind(chapter, position, itemCount)
    }
    
    inner class ChapterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView as MaterialCardView
        private val checkboxSelect: CheckBox = itemView.findViewById(R.id.checkbox_select)
        private val chapterOrder: TextView = itemView.findViewById(R.id.chapter_order)
        private val editTitle: EditText = itemView.findViewById(R.id.edit_chapter_title)
        private val chapterStats: TextView = itemView.findViewById(R.id.chapter_stats)
        private val btnMoveUp: ImageButton = itemView.findViewById(R.id.btn_move_up)
        private val btnMoveDown: ImageButton = itemView.findViewById(R.id.btn_move_down)
        private val btnMoreActions: ImageButton = itemView.findViewById(R.id.btn_more_actions)
        
        private var currentChapter: Chapter? = null
        private var isBindingTitle = false
        
        init {
            // Handle checkbox selection
            checkboxSelect.setOnCheckedChangeListener { _, isChecked ->
                currentChapter?.let { chapter ->
                    if (isChecked) selectedIds.add(chapter.id) else selectedIds.remove(chapter.id)
                    card.isChecked = isChecked
                    onSelectionChanged(chapter.id, isChecked)
                }
            }
            
            // Handle title editing
            editTitle.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    currentChapter?.let { chapter ->
                        val newTitle = editTitle.text.toString().trim()
                        if (newTitle.isNotBlank() && newTitle != chapter.title) {
                            onTitleChanged(chapter, newTitle)
                        }
                    }
                    editTitle.clearFocus()
                    true
                } else false
            }
            
            editTitle.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && !isBindingTitle) {
                    currentChapter?.let { chapter ->
                        val newTitle = editTitle.text.toString().trim()
                        if (newTitle.isNotBlank() && newTitle != chapter.title) {
                            onTitleChanged(chapter, newTitle)
                        }
                    }
                }
            }
            
            // Action buttons
            btnMoveUp.setOnClickListener {
                currentChapter?.let { onMoveUp(it) }
            }
            
            btnMoveDown.setOnClickListener {
                currentChapter?.let { onMoveDown(it) }
            }
            
            btnMoreActions.setOnClickListener { view ->
                currentChapter?.let { chapter ->
                    showPopupMenu(view, chapter)
                }
            }
        }
        
        fun bind(chapter: Chapter, position: Int, totalCount: Int) {
            currentChapter = chapter
            
            // Order badge
            chapterOrder.text = (position + 1).toString()
            
            // Title
            isBindingTitle = true
            editTitle.setText(chapter.title)
            isBindingTitle = false
            
            // Stats
            val wordCount = chapter.body.split("\\s+".toRegex()).size
            val analyzed = if (!chapter.fullAnalysisJson.isNullOrBlank()) "Analyzed" else "Not analyzed"
            chapterStats.text = itemView.context.getString(R.string.chapter_stats_format, wordCount, analyzed)
            
            // Selection state
            checkboxSelect.isChecked = selectedIds.contains(chapter.id)
            card.isChecked = selectedIds.contains(chapter.id)
            
            // Disable move buttons at edges
            btnMoveUp.isEnabled = position > 0
            btnMoveUp.alpha = if (position > 0) 1f else 0.3f
            btnMoveDown.isEnabled = position < totalCount - 1
            btnMoveDown.alpha = if (position < totalCount - 1) 1f else 0.3f
        }
        
        private fun showPopupMenu(anchor: View, chapter: Chapter) {
            PopupMenu(anchor.context, anchor).apply {
                menuInflater.inflate(R.menu.menu_chapter_actions, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_merge -> { onMerge(chapter); true }
                        R.id.action_split -> { onSplit(chapter); true }
                        R.id.action_delete -> { onDelete(chapter); true }
                        else -> false
                    }
                }
                show()
            }
        }
    }
}

class ChapterDiffCallback : DiffUtil.ItemCallback<Chapter>() {
    override fun areItemsTheSame(oldItem: Chapter, newItem: Chapter) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Chapter, newItem: Chapter) = oldItem == newItem
}


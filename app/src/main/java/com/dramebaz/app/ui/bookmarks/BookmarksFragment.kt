package com.dramebaz.app.ui.bookmarks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Bookmark
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** T7.3: Smart bookmark UI – list with smart description, resume from bookmark. */
class BookmarksFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val vm: BookmarksViewModel by viewModels { BookmarksViewModel.Factory(app.db) }
    private var bookId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = arguments?.getLong("bookId", 0L) ?: 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_bookmarks, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        val adapter = BookmarkAdapter(
            onItemClick = { b ->
                findNavController().navigate(R.id.readerFragment, Bundle().apply {
                    putLong("bookId", b.bookId)
                    putLong("chapterId", b.chapterId)
                })
            }
        )
        recycler.adapter = adapter
        recycler.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 300
            removeDuration = 300
        }
        val emptyState = view.findViewById<View>(R.id.empty_state)
        viewLifecycleOwner.lifecycleScope.launch {
            vm.bookmarks(bookId).collectLatest { bookmarks ->
                adapter.submitList(bookmarks)
                emptyState.visibility = if (bookmarks.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility = if (bookmarks.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }
}

class BookmarkAdapter(private val onItemClick: (Bookmark) -> Unit) : RecyclerView.Adapter<BookmarkAdapter.VH>() {
    private var list = listOf<Bookmark>()
    fun submitList(l: List<Bookmark>) { list = l; notifyDataSetChanged() }
    
    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val context: TextView = itemView.findViewById(R.id.bookmark_context)
        val preview: TextView = itemView.findViewById(R.id.bookmark_preview)
        val timestamp: TextView = itemView.findViewById(R.id.bookmark_timestamp)
    }
    
    override fun getItemCount() = list.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark_card, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = list[position]
        holder.context.text = "Chapter ${b.chapterId}"
        holder.preview.text = b.contextSummary.ifEmpty { "Bookmark at paragraph ${b.paragraphIndex}" }
        
        // Format timestamp
        val timeAgo = formatTimeAgo(b.timestamp)
        holder.timestamp.text = timeAgo
        
        holder.itemView.setOnClickListener { onItemClick(b) }
        
        // Add animation
        holder.itemView.alpha = 0f
        holder.itemView.animate()
            .alpha(1f)
            .setDuration(300)
            .setStartDelay(position * 50L)
            .start()
    }
    
    private fun formatTimeAgo(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        
        return when {
            days > 0 -> "$days day${if (days > 1) "s" else ""} ago"
            hours > 0 -> "$hours hour${if (hours > 1) "s" else ""} ago"
            minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""} ago"
            else -> "Just now"
        }
    }
}

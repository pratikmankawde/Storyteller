package com.dramebaz.app.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Book

class BookAdapter(
    private val onBookClick: (Book) -> Unit,
    private val onBookLongClick: (Book) -> Boolean = { false }
) : ListAdapter<Book, BookAdapter.VH>(Diff) {

    class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val cover: ImageView = itemView.findViewById(R.id.book_cover)
        val title: TextView = itemView.findViewById(R.id.book_title)
        val metadata: TextView = itemView.findViewById(R.id.book_metadata)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_book_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = getItem(position)
        holder.title.text = b.title
        
        // Set metadata (format info)
        holder.metadata.text = b.format.uppercase()
        
        holder.itemView.setOnClickListener { onBookClick(b) }
        holder.itemView.setOnLongClickListener { onBookLongClick(b) }
        
        // Add animation
        holder.itemView.alpha = 0f
        holder.itemView.animate()
            .alpha(1f)
            .setDuration(300)
            .setStartDelay(position * 50L)
            .start()
    }

    object Diff : DiffUtil.ItemCallback<Book>() {
        override fun areItemsTheSame(a: Book, b: Book) = a.id == b.id
        override fun areContentsTheSame(a: Book, b: Book) = a == b
    }
}

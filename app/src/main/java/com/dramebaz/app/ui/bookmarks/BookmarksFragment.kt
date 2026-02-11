package com.dramebaz.app.ui.bookmarks

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** T7.3 + BOOKMARK-UI: Smart bookmark UI with emotion indicators and character chips. */
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

/**
 * BOOKMARK-UI: Enhanced adapter displaying smart context with emotion and characters.
 */
class BookmarkAdapter(private val onItemClick: (Bookmark) -> Unit) : RecyclerView.Adapter<BookmarkAdapter.VH>() {
    private var list = listOf<Bookmark>()
    fun submitList(l: List<Bookmark>) { list = l; notifyDataSetChanged() }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val context: TextView = itemView.findViewById(R.id.bookmark_context)
        val preview: TextView = itemView.findViewById(R.id.bookmark_preview)
        val timestamp: TextView = itemView.findViewById(R.id.bookmark_timestamp)
        val emotionStrip: View = itemView.findViewById(R.id.emotion_strip)
        val emotionBadge: TextView = itemView.findViewById(R.id.emotion_badge)
        val charactersScroll: HorizontalScrollView = itemView.findViewById(R.id.characters_scroll)
        val charactersChipGroup: ChipGroup = itemView.findViewById(R.id.characters_chip_group)
        val paragraphIndicator: TextView = itemView.findViewById(R.id.paragraph_indicator)
    }

    override fun getItemCount() = list.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = list[position]
        val ctx = holder.itemView.context

        // Chapter title (extract from context summary or use fallback)
        val chapterTitle = extractChapterTitle(b.contextSummary) ?: "Chapter ${b.chapterId}"
        holder.context.text = chapterTitle

        // Context summary
        holder.preview.text = b.contextSummary.ifEmpty { "Bookmark at paragraph ${b.paragraphIndex}" }

        // Timestamp
        holder.timestamp.text = formatTimeAgo(b.timestamp)

        // Paragraph indicator
        if (b.paragraphIndex > 0) {
            holder.paragraphIndicator.visibility = View.VISIBLE
            holder.paragraphIndicator.text = "¶ ${b.paragraphIndex}"
        } else {
            holder.paragraphIndicator.visibility = View.GONE
        }

        // Emotion handling
        val emotionData = parseEmotion(b.emotionSnapshot)
        if (emotionData != null) {
            val emotionColor = getEmotionColor(emotionData.first)

            // Color strip on left
            holder.emotionStrip.setBackgroundColor(emotionColor)

            // Emotion badge
            holder.emotionBadge.visibility = View.VISIBLE
            holder.emotionBadge.text = emotionData.first.replaceFirstChar { it.uppercase() }
            val badgeBg = holder.emotionBadge.background as? GradientDrawable
                ?: GradientDrawable().also { holder.emotionBadge.background = it }
            badgeBg.setColor(emotionColor)
            badgeBg.cornerRadius = 24f
        } else {
            holder.emotionStrip.setBackgroundColor(Color.parseColor("#9E9E9E"))
            holder.emotionBadge.visibility = View.GONE
        }

        // Characters involved (as chips)
        holder.charactersChipGroup.removeAllViews()
        val characters = b.charactersInvolved.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (characters.isNotEmpty()) {
            holder.charactersScroll.visibility = View.VISIBLE
            characters.take(4).forEach { charName ->
                val chip = Chip(ctx).apply {
                    text = charName.replaceFirstChar { it.uppercase() }
                    isClickable = false
                    isCheckable = false
                    chipMinHeight = 28f
                    textSize = 11f
                    setChipBackgroundColorResource(R.color.primary_light)
                    setTextColor(ctx.getColor(R.color.primary))
                    chipStrokeWidth = 0f
                }
                holder.charactersChipGroup.addView(chip)
            }
            if (characters.size > 4) {
                val moreChip = Chip(ctx).apply {
                    text = "+${characters.size - 4}"
                    isClickable = false
                    isCheckable = false
                    chipMinHeight = 28f
                    textSize = 11f
                    setChipBackgroundColorResource(R.color.surface_variant)
                    setTextColor(ctx.getColor(R.color.text_secondary))
                    chipStrokeWidth = 0f
                }
                holder.charactersChipGroup.addView(moreChip)
            }
        } else {
            holder.charactersScroll.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onItemClick(b) }

        // Add animation
        holder.itemView.alpha = 0f
        holder.itemView.animate()
            .alpha(1f)
            .setDuration(300)
            .setStartDelay(position * 50L)
            .start()
    }

    /** Extract chapter title from context summary like "You stopped at Chapter 5..." */
    private fun extractChapterTitle(summary: String): String? {
        val regex = Regex("You stopped at ([^.]+)")
        return regex.find(summary)?.groupValues?.getOrNull(1)?.let {
            it.replace(" with.*".toRegex(), "").trim()
        }
    }

    /** Parse emotion snapshot like "tension (75%)" into Pair(emotion, intensity) */
    private fun parseEmotion(snapshot: String): Pair<String, Int>? {
        if (snapshot.isBlank() || snapshot == "neutral") return null
        val regex = Regex("([a-zA-Z]+)\\s*\\((\\d+)%\\)")
        val match = regex.find(snapshot)
        return if (match != null) {
            val emotion = match.groupValues[1].lowercase()
            val intensity = match.groupValues[2].toIntOrNull() ?: 50
            Pair(emotion, intensity)
        } else {
            // Just emotion name without percentage
            val emotion = snapshot.trim().lowercase()
            if (emotion.isNotEmpty() && emotion != "neutral") Pair(emotion, 50) else null
        }
    }

    /** Get color for emotion type */
    private fun getEmotionColor(emotion: String): Int {
        return when (emotion.lowercase()) {
            "tension", "suspense", "anxious", "anxiety" -> Color.parseColor("#FF5722") // Deep Orange
            "fear", "scared", "horror" -> Color.parseColor("#9C27B0") // Purple
            "sad", "sadness", "melancholy", "grief" -> Color.parseColor("#607D8B") // Blue Grey
            "happy", "joy", "joyful", "excited" -> Color.parseColor("#4CAF50") // Green
            "angry", "anger", "rage" -> Color.parseColor("#F44336") // Red
            "love", "romantic", "affection" -> Color.parseColor("#E91E63") // Pink
            "curious", "curiosity", "wonder" -> Color.parseColor("#2196F3") // Blue
            "peaceful", "calm", "serene" -> Color.parseColor("#00BCD4") // Cyan
            "dramatic", "intense" -> Color.parseColor("#FF9800") // Orange
            "mysterious", "mystery" -> Color.parseColor("#673AB7") // Deep Purple
            "hopeful", "hope" -> Color.parseColor("#8BC34A") // Light Green
            "contemplative", "thoughtful" -> Color.parseColor("#795548") // Brown
            else -> Color.parseColor("#9E9E9E") // Grey for unknown
        }
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

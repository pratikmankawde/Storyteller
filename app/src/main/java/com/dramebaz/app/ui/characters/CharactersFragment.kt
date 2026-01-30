package com.dramebaz.app.ui.characters

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Character
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CharactersFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val vm: CharactersViewModel by viewModels { CharactersViewModel.Factory(app.db) }
    private var bookId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = arguments?.getLong("bookId", 0L) ?: 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_characters, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        // Use GridLayoutManager with 2 columns for better visual layout
        val spanCount = resources.getInteger(R.integer.character_grid_span_count).takeIf { it > 0 } ?: 2
        recycler.layoutManager = GridLayoutManager(requireContext(), spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = 1
            }
        }
        val adapter = CharacterAdapter { c -> findNavController().navigate(R.id.characterDetailFragment, Bundle().apply { putLong("characterId", c.id) }) }
        recycler.adapter = adapter
        recycler.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 300
            removeDuration = 300
            changeDuration = 300
        }
        val emptyState = view.findViewById<View>(R.id.empty_state)
        viewLifecycleOwner.lifecycleScope.launch {
            vm.characters(bookId).collectLatest { characters ->
                adapter.submitList(characters) {
                    // Trigger animations after list is updated
                    recycler.scheduleLayoutAnimation()
                }
                emptyState.visibility = if (characters.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility = if (characters.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }
}

class CharacterAdapter(private val onItemClick: (Character) -> Unit) : RecyclerView.Adapter<CharacterAdapter.VH>() {
    private var list = listOf<Character>()
    
    fun submitList(l: List<Character>, onComplete: (() -> Unit)? = null) { 
        list = l
        notifyDataSetChanged()
        onComplete?.invoke()
    }
    
    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.character_name)
        val traits: TextView = itemView.findViewById(R.id.character_traits)
    }
    
    override fun getItemCount() = list.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_character_card, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = list[position]
        holder.name.text = c.name
        holder.traits.text = c.traits.takeIf { it.isNotBlank() } ?: "No traits available"
        
        // Set up click listener with ripple effect
        holder.itemView.setOnClickListener { 
            onItemClick(c)
            // Add click animation
            holder.itemView.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction {
                    holder.itemView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .start()
                }
                .start()
        }
        
        // Fade-in animation for items
        holder.itemView.alpha = 0f
        holder.itemView.translationY = 20f
        holder.itemView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setStartDelay(position * 30L)
            .start()
    }
}

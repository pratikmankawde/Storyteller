package com.dramebaz.app.ui.characters

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Character
import com.dramebaz.app.ui.reader.VoiceSelectorDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AUG-026: Character list with search and filtering
 */
class CharactersFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val vm: CharactersViewModel by viewModels { CharactersViewModel.Factory(app.db) }
    private var bookId: Long = 0L

    // AUG-026: Full list and current filter state
    private var allCharacters: List<Character> = emptyList()
    private var dialogCountsBySpeaker: Map<String, Int> = emptyMap()
    private var currentSearchQuery = ""
    private var currentSortMode = SortMode.NAME_ASC

    enum class SortMode { NAME_ASC, NAME_DESC, DIALOG_COUNT }

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
        val searchView = view.findViewById<SearchView>(R.id.search_view)
        val filterChips = view.findViewById<ChipGroup>(R.id.filter_chips)

        // Use GridLayoutManager with 2 columns for better visual layout
        val spanCount = resources.getInteger(R.integer.character_grid_span_count).takeIf { it > 0 } ?: 2
        recycler.layoutManager = GridLayoutManager(requireContext(), spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = 1
            }
        }
        val adapter = CharacterAdapter(
            dialogCountsBySpeaker = { dialogCountsBySpeaker },
            onItemClick = { c ->
                if (c.id == VoiceSelectorDialog.NARRATOR_ID) {
                    // NARRATOR-001: Open voice selector for Narrator
                    val dialog = VoiceSelectorDialog.newInstanceForNarrator(bookId)
                    dialog.show(childFragmentManager, "NarratorVoiceSelector")
                } else if (c.id > 0) {
                    // Show character detail as bottom sheet dialog (similar to Narrator's voice selector)
                    val dialog = CharacterDetailDialog.newInstance(c.id, bookId)
                    dialog.show(childFragmentManager, "CharacterDetail")
                }
            }
        )
        recycler.adapter = adapter
        recycler.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 300
            removeDuration = 300
            changeDuration = 300
        }
        val emptyState = view.findViewById<View>(R.id.empty_state)

        // AUG-026: Setup search
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                currentSearchQuery = newText ?: ""
                applyFilters(adapter, emptyState, recycler)
                return true
            }
        })

        // AUG-026: Setup filter chips
        filterChips?.setOnCheckedStateChangeListener { _, checkedIds ->
            currentSortMode = when {
                checkedIds.contains(R.id.chip_sort_name_desc) -> SortMode.NAME_DESC
                checkedIds.contains(R.id.chip_sort_dialogs) -> SortMode.DIALOG_COUNT
                else -> SortMode.NAME_ASC
            }
            applyFilters(adapter, emptyState, recycler)
        }

        // Load dialog counts from chapter analysis
        viewLifecycleOwner.lifecycleScope.launch {
            dialogCountsBySpeaker = withContext(Dispatchers.IO) {
                vm.getDialogCountsBySpeaker(bookId)
            }
            // Refresh adapter after dialog counts are loaded
            applyFilters(adapter, emptyState, recycler)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.characters(bookId).collectLatest { characters ->
                allCharacters = characters
                applyFilters(adapter, emptyState, recycler)
            }
        }
    }

    /**
     * Create a pseudo-character for Narrator to display in the list.
     * NARRATOR-001: Uses narrator settings from SettingsRepository.
     */
    private fun createNarratorCharacter(): Character {
        val narratorSettings = app.settingsRepository.narratorSettings.value
        return Character(
            id = VoiceSelectorDialog.NARRATOR_ID,  // Special ID for Narrator (not in database)
            bookId = bookId,
            name = "Narrator",
            traits = "Story narrator • Tap to change voice",
            personalitySummary = "The voice that tells the story",
            speakerId = narratorSettings.speakerId
        )
    }

    // AUG-026: Apply search and sort filters
    private fun applyFilters(adapter: CharacterAdapter, emptyState: View, recycler: RecyclerView) {
        // Create list with Narrator at the top
        val narratorCharacter = createNarratorCharacter()
        var filtered = listOf(narratorCharacter) + allCharacters

        // Apply search filter
        if (currentSearchQuery.isNotBlank()) {
            filtered = filtered.filter {
                it.name.contains(currentSearchQuery, ignoreCase = true) ||
                it.traits.contains(currentSearchQuery, ignoreCase = true)
            }
        }

        // Apply sort (keep Narrator at top unless sorting by dialogs)
        filtered = when (currentSortMode) {
            SortMode.NAME_ASC -> {
                val narrator = filtered.find { it.id == -1L }
                val others = filtered.filter { it.id != -1L }.sortedBy { it.name.lowercase() }
                listOfNotNull(narrator) + others
            }
            SortMode.NAME_DESC -> {
                val narrator = filtered.find { it.id == -1L }
                val others = filtered.filter { it.id != -1L }.sortedByDescending { it.name.lowercase() }
                listOfNotNull(narrator) + others
            }
            SortMode.DIALOG_COUNT -> {
                // Sort all by dialog count (including Narrator)
                filtered.sortedByDescending { c ->
                    dialogCountsBySpeaker[c.name.lowercase()] ?: 0
                }
            }
        }

        adapter.submitList(filtered) {
            recycler.scheduleLayoutAnimation()
        }
        emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }
}

class CharacterAdapter(
    private val dialogCountsBySpeaker: () -> Map<String, Int>,
    private val onItemClick: (Character) -> Unit
) : RecyclerView.Adapter<CharacterAdapter.VH>() {
    private var list = listOf<Character>()

    fun submitList(l: List<Character>, onComplete: (() -> Unit)? = null) {
        list = l
        notifyDataSetChanged()
        onComplete?.invoke()
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.character_name)
        val traits: TextView = itemView.findViewById(R.id.character_traits)
        // AUG-031: Voice warning container (CHARACTER-001: now a container with icon and text)
        val voiceWarningContainer: View? = itemView.findViewById(R.id.voice_warning_container)
        // Dialog count display
        val dialogCount: TextView? = itemView.findViewById(R.id.dialog_count)
        // CHARACTER-001: Main clickable card
        val card: View? = itemView.findViewById(R.id.character_card)
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

        // AUG-031: Show voice warning container if inconsistency detected (CHARACTER-001: updated for new layout)
        val hasVoiceWarning = c.personalitySummary.contains("⚠️") || c.personalitySummary.contains("inconsistenc", ignoreCase = true)
        holder.voiceWarningContainer?.visibility = if (hasVoiceWarning) View.VISIBLE else View.GONE

        // Display dialog count from chapter analysis (primary source)
        val dialogCount = dialogCountsBySpeaker()[c.name.lowercase()] ?: getDialogCountFromJson(c.dialogsJson)
        if (dialogCount > 0) {
            holder.dialogCount?.text = "$dialogCount dialog${if (dialogCount > 1) "s" else ""}"
            holder.dialogCount?.visibility = View.VISIBLE
        } else {
            holder.dialogCount?.visibility = View.GONE
        }

        // CHARACTER-001: Set up click listener on the card (or itemView as fallback)
        val clickTarget = holder.card ?: holder.itemView
        clickTarget.setOnClickListener {
            onItemClick(c)
            // Add click animation for all characters including Narrator
            clickTarget.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction {
                    clickTarget.animate()
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

    /**
     * Parse dialogsJson and return the count of dialogs (fallback).
     */
    private fun getDialogCountFromJson(dialogsJson: String?): Int {
        if (dialogsJson.isNullOrBlank()) return 0
        return try {
            val dialogs = Gson().fromJson(dialogsJson, Array<Any>::class.java)
            dialogs?.size ?: 0
        } catch (e: JsonSyntaxException) {
            0
        }
    }
}

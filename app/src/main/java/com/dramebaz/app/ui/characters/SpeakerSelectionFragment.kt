package com.dramebaz.app.ui.characters

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Character
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T11.1: Fragment for selecting speaker ID for a character.
 * Shows all available speakers (0-108 for VCTK model) and allows user to pick one.
 */
class SpeakerSelectionFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private var characterId: Long = 0L
    private var characterName: String = ""
    private var currentSpeakerId: Int? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        characterId = arguments?.getLong("characterId", 0L) ?: 0L
        characterName = arguments?.getString("characterName", "") ?: ""
    }
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_speaker_selection, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val title = view.findViewById<TextView>(R.id.title)
        title.text = "Select Speaker for $characterName"
        
        val recycler = view.findViewById<RecyclerView>(R.id.speaker_list)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        
        // Load current character to get current speaker ID
        viewLifecycleOwner.lifecycleScope.launch {
            val character = app.db.characterDao().getById(characterId)
            currentSpeakerId = character?.speakerId
            
            val adapter = SpeakerAdapter(currentSpeakerId) { selectedSpeakerId ->
                saveSpeakerSelection(selectedSpeakerId)
            }
            recycler.adapter = adapter
        }
    }
    
    private fun saveSpeakerSelection(speakerId: Int) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val character = app.db.characterDao().getById(characterId)
                if (character != null) {
                    val updatedCharacter = character.copy(speakerId = speakerId)
                    app.db.characterDao().update(updatedCharacter)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Speaker selected: $speakerId", Toast.LENGTH_SHORT).show()
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Character not found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SpeakerSelection", "Error saving speaker selection", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private class SpeakerAdapter(
        private val currentSelection: Int?,
        private val onSpeakerSelected: (Int) -> Unit
    ) : RecyclerView.Adapter<SpeakerAdapter.ViewHolder>() {
        
        // VCTK model has 109 speakers (0-108)
        private val speakers = (0..108).toList()
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val speakerId = speakers[position]
            holder.textView.text = "Speaker $speakerId"
            holder.textView.isSelected = speakerId == currentSelection
            
            holder.itemView.setOnClickListener {
                onSpeakerSelected(speakerId)
            }
        }
        
        override fun getItemCount() = speakers.size
        
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(android.R.id.text1)
        }
    }
}

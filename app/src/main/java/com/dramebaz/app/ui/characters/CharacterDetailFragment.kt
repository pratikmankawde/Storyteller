package com.dramebaz.app.ui.characters

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import kotlinx.coroutines.launch

class CharacterDetailFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val vm: CharacterDetailViewModel by viewModels { CharacterDetailViewModel.Factory(app.db) }
    private var characterId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        characterId = arguments?.getLong("characterId", 0L) ?: 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_character_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val name = view.findViewById<TextView>(R.id.name)
        val traits = view.findViewById<TextView>(R.id.traits)
        val summary = view.findViewById<TextView>(R.id.summary)
        viewLifecycleOwner.lifecycleScope.launch {
            val c = vm.getCharacter(characterId)
            c?.let {
                name.text = it.name
                traits.text = "Traits: ${it.traits}"
                summary.text = it.personalitySummary.ifEmpty { "-" }
            }
        }
        view.findViewById<Button>(R.id.btn_voice_preview).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val c = vm.getCharacter(characterId)
                c?.let { character ->
                    val args = Bundle().apply {
                        putLong("characterId", character.id)
                        putString("characterName", character.name)
                    }
                    val voicePreviewFragment = VoicePreviewFragment().apply {
                        arguments = args
                    }
                    // Navigate to voice preview using navigation
                    findNavController().navigate(
                        R.id.action_characterDetailFragment_to_voicePreviewFragment,
                        args
                    )
                }
            }
        }
        
        view.findViewById<Button>(R.id.btn_select_speaker).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val c = vm.getCharacter(characterId)
                c?.let { character ->
                    val args = Bundle().apply {
                        putLong("characterId", character.id)
                        putString("characterName", character.name)
                    }
                    // Navigate to speaker selection
                    findNavController().navigate(
                        R.id.action_characterDetailFragment_to_speakerSelectionFragment,
                        args
                    )
                }
            }
        }
    }
}

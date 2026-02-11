package com.dramebaz.app.ui.characters

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.google.android.material.card.MaterialCardView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch

/**
 * AUG-023: Character detail with relationships display
 * AUG-024: Character detail with key moments timeline
 */
class CharacterDetailFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val vm: CharacterDetailViewModel by viewModels { CharacterDetailViewModel.Factory(app.db) }
    private var characterId: Long = 0L
    private val gson = Gson()

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
        val relationshipsCard = view.findViewById<MaterialCardView>(R.id.relationships_card)
        val relationshipsText = view.findViewById<TextView>(R.id.relationships)
        val keyMomentsCard = view.findViewById<MaterialCardView>(R.id.key_moments_card)
        val keyMomentsText = view.findViewById<TextView>(R.id.key_moments)
        val btnViewDialogs = view.findViewById<Button>(R.id.btn_view_dialogs)

        viewLifecycleOwner.lifecycleScope.launch {
            val c = vm.getCharacter(characterId)
            c?.let {
                name.text = it.name
                traits.text = "Traits: ${it.traits}"
                summary.text = it.personalitySummary.ifEmpty { "-" }

                // AUG-023: Display relationships
                displayRelationships(it.relationships, relationshipsCard, relationshipsText)

                // AUG-024: Display key moments
                displayKeyMoments(it.keyMoments, keyMomentsCard, keyMomentsText)

                // Show View Dialogs button if character has dialogs
                val dialogs = parseDialogs(it.dialogsJson)
                if (dialogs.isNotEmpty()) {
                    btnViewDialogs.text = "View Dialogs (${dialogs.size})"
                    btnViewDialogs.visibility = View.VISIBLE
                } else {
                    btnViewDialogs.visibility = View.GONE
                }
            }
        }

        // View Dialogs button click handler
        btnViewDialogs.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val c = vm.getCharacter(characterId)
                c?.let { character ->
                    showDialogsDialog(character.name, character.dialogsJson)
                }
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

    // AUG-023: Parse and display relationships
    private fun displayRelationships(relationshipsJson: String, card: MaterialCardView, textView: TextView) {
        if (relationshipsJson.isBlank() || relationshipsJson == "[]") {
            card.visibility = View.GONE
            return
        }

        try {
            val type = object : TypeToken<List<Map<String, String>>>() {}.type
            val relationships: List<Map<String, String>> = gson.fromJson(relationshipsJson, type) ?: emptyList()

            if (relationships.isEmpty()) {
                card.visibility = View.GONE
                return
            }

            val formattedText = relationships.joinToString("\n") { rel ->
                val character = rel["character"] ?: rel["name"] ?: "Unknown"
                val relType = rel["relationship"] ?: rel["type"] ?: ""
                val nature = rel["nature"] ?: rel["description"] ?: ""
                buildString {
                    append("• $character")
                    if (relType.isNotBlank()) append(" ($relType)")
                    if (nature.isNotBlank()) append(": $nature")
                }
            }

            textView.text = formattedText
            card.visibility = View.VISIBLE
        } catch (e: Exception) {
            card.visibility = View.GONE
        }
    }

    // AUG-024: Parse and display key moments
    private fun displayKeyMoments(keyMomentsJson: String, card: MaterialCardView, textView: TextView) {
        if (keyMomentsJson.isBlank() || keyMomentsJson == "[]") {
            card.visibility = View.GONE
            return
        }

        try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val moments: List<Map<String, Any>> = gson.fromJson(keyMomentsJson, type) ?: emptyList()

            if (moments.isEmpty()) {
                card.visibility = View.GONE
                return
            }

            val formattedText = moments.mapIndexed { index, moment ->
                val chapter = moment["chapter"]?.toString() ?: "Ch. ${index + 1}"
                val description = moment["description"]?.toString() ?: moment["moment"]?.toString() ?: ""
                val significance = moment["significance"]?.toString() ?: ""
                buildString {
                    append("📍 $chapter")
                    if (description.isNotBlank()) append("\n   $description")
                    if (significance.isNotBlank()) append("\n   ★ $significance")
                }
            }.joinToString("\n\n")

            textView.text = formattedText
            card.visibility = View.VISIBLE
        } catch (e: Exception) {
            card.visibility = View.GONE
        }
    }

    /**
     * Parse dialogs JSON into a list of dialog maps.
     * Expected format: [{segmentIndex/pageNumber, text, emotion, intensity}, ...]
     */
    private fun parseDialogs(dialogsJson: String?): List<Map<String, Any>> {
        if (dialogsJson.isNullOrBlank() || dialogsJson == "[]") {
            return emptyList()
        }
        return try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            gson.fromJson(dialogsJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Show a dialog with the list of character's dialogs.
     */
    private fun showDialogsDialog(characterName: String, dialogsJson: String?) {
        val dialogs = parseDialogs(dialogsJson)
        if (dialogs.isEmpty()) {
            return
        }

        val formattedDialogs = dialogs.mapIndexed { index, dialog ->
            val text = dialog["text"]?.toString() ?: ""
            val emotion = dialog["emotion"]?.toString() ?: ""
            val intensity = dialog["intensity"]?.toString() ?: ""
            val pageNumber = dialog["pageNumber"]?.toString()?.toDoubleOrNull()?.toInt()
                ?: dialog["segmentIndex"]?.toString()?.toDoubleOrNull()?.toInt()
                ?: (index + 1)

            buildString {
                append("${index + 1}. ")
                if (pageNumber > 0) append("[Page $pageNumber] ")
                append("\"$text\"")
                if (emotion.isNotBlank()) {
                    append("\n   ")
                    if (intensity.isNotBlank()) append("$intensity ")
                    append(emotion)
                }
            }
        }.joinToString("\n\n")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("$characterName's Dialogs (${dialogs.size})")
            .setMessage(formattedDialogs)
            .setPositiveButton("Close", null)
            .show()
    }
}

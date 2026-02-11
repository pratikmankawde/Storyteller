package com.dramebaz.app.ui.characters

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Character
import com.dramebaz.app.ui.reader.VoiceSelectorDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Character Detail Bottom Sheet Dialog - similar to VoiceSelectorDialog.
 * Shows character info, personality summary, relationships, key moments.
 * Actions: View Dialogs, Change Voice
 */
class CharacterDetailDialog : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_CHARACTER_ID = "characterId"
        private const val ARG_BOOK_ID = "bookId"

        fun newInstance(characterId: Long, bookId: Long): CharacterDetailDialog {
            return CharacterDetailDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHARACTER_ID, characterId)
                    putLong(ARG_BOOK_ID, bookId)
                }
            }
        }
    }

    private val app get() = requireContext().applicationContext as DramebazApplication
    private var characterId: Long = 0L
    private var bookId: Long = 0L
    private var character: Character? = null
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        characterId = arguments?.getLong(ARG_CHARACTER_ID, 0L) ?: 0L
        bookId = arguments?.getLong(ARG_BOOK_ID, 0L) ?: 0L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_character_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI(view)
        loadCharacter(view)
    }

    private fun setupUI(view: View) {
        // Change Voice button
        view.findViewById<MaterialButton>(R.id.btn_change_voice)?.setOnClickListener {
            character?.let { c ->
                dismiss()
                val voiceDialog = VoiceSelectorDialog.newInstance(c.id, bookId)
                voiceDialog.show(parentFragmentManager, "VoiceSelector")
            }
        }

        // View Dialogs button
        view.findViewById<MaterialButton>(R.id.btn_view_dialogs)?.setOnClickListener {
            character?.let { c ->
                showDialogsDialog(c.name, c.dialogsJson)
            }
        }
    }

    private fun loadCharacter(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            val c = withContext(Dispatchers.IO) {
                app.db.characterDao().getById(characterId)
            }
            character = c
            c?.let { displayCharacter(view, it) }
        }
    }

    private fun displayCharacter(view: View, c: Character) {
        // Header
        view.findViewById<TextView>(R.id.character_name)?.text = c.name
        view.findViewById<TextView>(R.id.character_traits)?.text = c.traits.ifBlank { "No traits available" }

        // Personality Summary
        view.findViewById<TextView>(R.id.summary)?.text = c.personalitySummary.ifBlank { "No personality summary available." }

        // Relationships
        displayRelationships(view, c.relationships)

        // Key Moments
        displayKeyMoments(view, c.keyMoments)

        // Dialog Count Badge and View Dialogs button
        val dialogs = parseDialogs(c.dialogsJson)
        val dialogCountView = view.findViewById<TextView>(R.id.dialog_count)
        val btnViewDialogs = view.findViewById<MaterialButton>(R.id.btn_view_dialogs)
        if (dialogs.isNotEmpty()) {
            dialogCountView?.text = "${dialogs.size}"
            dialogCountView?.visibility = View.VISIBLE
            btnViewDialogs?.text = "Dialogs (${dialogs.size})"
            btnViewDialogs?.visibility = View.VISIBLE
        } else {
            dialogCountView?.visibility = View.GONE
            btnViewDialogs?.visibility = View.GONE
        }
    }

    private fun displayRelationships(view: View, relationshipsJson: String) {
        val label = view.findViewById<TextView>(R.id.relationships_label)
        val textView = view.findViewById<TextView>(R.id.relationships)

        if (relationshipsJson.isBlank() || relationshipsJson == "[]") {
            label?.visibility = View.GONE
            textView?.visibility = View.GONE
            return
        }

        try {
            val type = object : TypeToken<List<Map<String, String>>>() {}.type
            val relationships: List<Map<String, String>> = gson.fromJson(relationshipsJson, type) ?: emptyList()

            if (relationships.isEmpty()) {
                label?.visibility = View.GONE
                textView?.visibility = View.GONE
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

            textView?.text = formattedText
            label?.visibility = View.VISIBLE
            textView?.visibility = View.VISIBLE
        } catch (e: Exception) {
            label?.visibility = View.GONE
            textView?.visibility = View.GONE
        }
    }

    private fun displayKeyMoments(view: View, keyMomentsJson: String) {
        val label = view.findViewById<TextView>(R.id.key_moments_label)
        val textView = view.findViewById<TextView>(R.id.key_moments)

        if (keyMomentsJson.isBlank() || keyMomentsJson == "[]") {
            label?.visibility = View.GONE
            textView?.visibility = View.GONE
            return
        }

        try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val moments: List<Map<String, Any>> = gson.fromJson(keyMomentsJson, type) ?: emptyList()

            if (moments.isEmpty()) {
                label?.visibility = View.GONE
                textView?.visibility = View.GONE
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

            textView?.text = formattedText
            label?.visibility = View.VISIBLE
            textView?.visibility = View.VISIBLE
        } catch (e: Exception) {
            label?.visibility = View.GONE
            textView?.visibility = View.GONE
        }
    }

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

    private fun showDialogsDialog(characterName: String, dialogsJson: String?) {
        val dialogs = parseDialogs(dialogsJson)
        if (dialogs.isEmpty()) return

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

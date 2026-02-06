package com.dramebaz.app.ui.characters

import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.tts.SpeakerMatcher
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T11.1: Fragment for selecting speaker ID for a character.
 * AUG-014: Shows filtered speakers sorted by match score.
 * AUG-015: Includes voice preview functionality.
 */
class SpeakerSelectionFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private var characterId: Long = 0L
    private var characterName: String = ""
    private var currentSpeakerId: Int? = null

    // AUG-015: Voice preview state
    private var mediaPlayer: MediaPlayer? = null
    private var currentPreviewJob: Job? = null
    private var currentPreviewingPosition: Int = -1

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

        // AUG-014: Load character and show speakers sorted by match score
        viewLifecycleOwner.lifecycleScope.launch {
            val character = withContext(Dispatchers.IO) { app.db.characterDao().getById(characterId) }
            currentSpeakerId = character?.speakerId
            val traits = character?.traits
            val personalitySummary = character?.personalitySummary
            val name = character?.name ?: characterName

            // AUG-014: Use getSimilarSpeakers for scored results
            // Filter to top 30 matches for better UX - shows only most relevant speakers
            val scoredSpeakers = SpeakerMatcher.getSimilarSpeakers(traits, personalitySummary, name, topN = 30)

            val adapter = SpeakerAdapter(
                scoredSpeakers,
                currentSpeakerId,
                onSpeakerSelected = { selectedSpeakerId -> saveSpeakerSelection(selectedSpeakerId) },
                onPreviewClicked = { position, speakerId -> previewVoice(position, speakerId) }
            )
            recycler.adapter = adapter
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // AUG-015: Release media player resources
        stopPreview()
    }

    /**
     * AUG-015: Preview voice for a speaker.
     */
    private fun previewVoice(position: Int, speakerId: Int) {
        // Stop any current preview
        stopPreview()

        currentPreviewingPosition = position
        val recycler = view?.findViewById<RecyclerView>(R.id.speaker_list) ?: return

        // Show loading indicator
        (recycler.adapter as? SpeakerAdapter)?.setPreviewingPosition(position, isLoading = true)

        currentPreviewJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Generate TTS audio using sample text
                val sampleText = "Hello, this is how I sound. Nice to meet you!"

                val result = withContext(Dispatchers.IO) {
                    app.ttsEngine.speak(sampleText, voiceProfile = null, onComplete = null, speakerId = speakerId)
                }

                val audioFile = result.getOrNull()
                if (audioFile != null && audioFile.exists()) {
                    // Play the audio
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(audioFile.absolutePath)
                        prepare()
                        setOnCompletionListener {
                            stopPreview()
                            (recycler.adapter as? SpeakerAdapter)?.clearPreviewingPosition()
                        }
                        start()
                    }

                    // Update UI to show playing state
                    (recycler.adapter as? SpeakerAdapter)?.setPreviewingPosition(position, isLoading = false)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Failed to generate preview", Toast.LENGTH_SHORT).show()
                        (recycler.adapter as? SpeakerAdapter)?.clearPreviewingPosition()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("SpeakerSelection", "Error previewing voice", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Preview error: ${e.message}", Toast.LENGTH_SHORT).show()
                    (recycler.adapter as? SpeakerAdapter)?.clearPreviewingPosition()
                }
            }
        }
    }

    private fun stopPreview() {
        currentPreviewJob?.cancel()
        currentPreviewJob = null
        mediaPlayer?.release()
        mediaPlayer = null
        currentPreviewingPosition = -1
    }

    private fun saveSpeakerSelection(speakerId: Int) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val character = app.db.characterDao().getById(characterId)
                if (character != null) {
                    val updatedCharacter = character.copy(speakerId = speakerId)
                    app.db.characterDao().update(updatedCharacter)

                    // AUG-043: Update speaker ID for all segments of this character and invalidate audio cache
                    val bookId = character.bookId
                    val characterPageMappingDao = app.db.characterPageMappingDao()

                    // Update speaker ID in CharacterPageMapping table (marks audio as needing regeneration)
                    characterPageMappingDao.updateSpeakerForCharacter(bookId, character.name, speakerId)
                    AppLogger.d("SpeakerSelection", "Updated speaker for ${character.name} to $speakerId in CharacterPageMapping")

                    // Delete old audio files for this character
                    app.pageAudioStorage.deleteSegmentsForCharacter(bookId, character.name)
                    AppLogger.d("SpeakerSelection", "Deleted old audio files for ${character.name}")

                    // Get segments that need regeneration (on current page if available)
                    val segmentsToRegenerate = characterPageMappingDao.getSegmentsForCharacter(bookId, character.name)

                    if (segmentsToRegenerate.isNotEmpty()) {
                        // Regenerate audio for affected segments in background
                        val regenerated = app.segmentAudioGenerator.regenerateSegments(segmentsToRegenerate, speakerId)
                        AppLogger.i("SpeakerSelection", "Regenerated $regenerated segments for ${character.name}")
                    }

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
                AppLogger.e("SpeakerSelection", "Error saving speaker selection", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * AUG-014 & AUG-015: Enhanced speaker adapter with match scores and voice preview.
     */
    private class SpeakerAdapter(
        private val scoredSpeakers: List<SpeakerMatcher.ScoredSpeaker>,
        private val currentSelection: Int?,
        private val onSpeakerSelected: (Int) -> Unit,
        private val onPreviewClicked: (Int, Int) -> Unit
    ) : RecyclerView.Adapter<SpeakerAdapter.ViewHolder>() {

        private var previewingPosition: Int = -1
        private var isPreviewLoading: Boolean = false

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_speaker, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val scored = scoredSpeakers[position]
            val traits = scored.speaker
            val speakerId = traits.speakerId
            val score = scored.score

            // Speaker name
            holder.speakerName.text = "Speaker #$speakerId"

            // Speaker traits
            val ageText = traits.ageYears?.toString() ?: "?"
            holder.speakerTraits.text = "${traits.gender}, $ageText yrs, ${traits.accent}, ${traits.region}"

            // Pitch level
            holder.speakerPitch.text = "Pitch: ${traits.pitchLevel.name.lowercase().replaceFirstChar { it.uppercase() }}"

            // Match score badge (show if score > 0)
            if (score > 0) {
                holder.matchScore.visibility = View.VISIBLE
                holder.matchScore.text = "+$score"
            } else {
                holder.matchScore.visibility = View.GONE
            }

            // Selected indicator
            holder.selectedIndicator.visibility = if (speakerId == currentSelection) View.VISIBLE else View.GONE

            // Preview button state
            val isPreviewing = position == previewingPosition
            if (isPreviewing && isPreviewLoading) {
                holder.previewButton.visibility = View.GONE
                holder.previewLoading.visibility = View.VISIBLE
            } else if (isPreviewing) {
                holder.previewButton.visibility = View.VISIBLE
                holder.previewButton.setImageResource(R.drawable.ic_stop_circle)
                holder.previewLoading.visibility = View.GONE
            } else {
                holder.previewButton.visibility = View.VISIBLE
                holder.previewButton.setImageResource(R.drawable.ic_play_circle)
                holder.previewLoading.visibility = View.GONE
            }

            // Click handlers
            holder.itemView.setOnClickListener {
                onSpeakerSelected(speakerId)
            }

            holder.previewButton.setOnClickListener {
                onPreviewClicked(position, speakerId)
            }
        }

        override fun getItemCount() = scoredSpeakers.size

        fun setPreviewingPosition(position: Int, isLoading: Boolean) {
            val oldPosition = previewingPosition
            previewingPosition = position
            isPreviewLoading = isLoading
            if (oldPosition >= 0) notifyItemChanged(oldPosition)
            notifyItemChanged(position)
        }

        fun clearPreviewingPosition() {
            val oldPosition = previewingPosition
            previewingPosition = -1
            isPreviewLoading = false
            if (oldPosition >= 0) notifyItemChanged(oldPosition)
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val speakerName: TextView = view.findViewById(R.id.speaker_name)
            val speakerTraits: TextView = view.findViewById(R.id.speaker_traits)
            val speakerPitch: TextView = view.findViewById(R.id.speaker_pitch)
            val matchScore: TextView = view.findViewById(R.id.match_score)
            val selectedIndicator: ImageView = view.findViewById(R.id.selected_indicator)
            val previewButton: ImageButton = view.findViewById(R.id.preview_button)
            val previewLoading: ProgressBar = view.findViewById(R.id.preview_loading)
        }
    }
}

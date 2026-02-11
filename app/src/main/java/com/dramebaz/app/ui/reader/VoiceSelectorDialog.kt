package com.dramebaz.app.ui.reader

import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.tts.SpeakerMatcher
import com.dramebaz.app.data.db.Character
import com.dramebaz.app.data.models.NarratorSettings
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.domain.usecases.AudioRegenerationManager
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * VOICE-001: Voice Selector Dialog for character voice customization.
 * 
 * From NovelReaderWeb voice-selector.component.ts:
 * - Character info header with name and traits
 * - Gender filter buttons (All, Female, Male)
 * - Voice list with selection highlighting
 * - Speed/Energy sliders for tuning
 * - Test button with preview and Save button
 */
class VoiceSelectorDialog : BottomSheetDialogFragment() {
    
    companion object {
        private const val TAG = "VoiceSelectorDialog"
        private const val ARG_CHARACTER_ID = "characterId"
        private const val ARG_BOOK_ID = "bookId"
        private const val ARG_IS_NARRATOR = "isNarrator"

        /** Special ID to indicate Narrator (not in database) */
        const val NARRATOR_ID = -1L

        fun newInstance(characterId: Long, bookId: Long): VoiceSelectorDialog {
            return VoiceSelectorDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHARACTER_ID, characterId)
                    putLong(ARG_BOOK_ID, bookId)
                    putBoolean(ARG_IS_NARRATOR, characterId == NARRATOR_ID)
                }
            }
        }

        /** Create dialog for Narrator voice selection */
        fun newInstanceForNarrator(bookId: Long): VoiceSelectorDialog {
            return VoiceSelectorDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHARACTER_ID, NARRATOR_ID)
                    putLong(ARG_BOOK_ID, bookId)
                    putBoolean(ARG_IS_NARRATOR, true)
                }
            }
        }
    }
    
    interface VoiceChangeListener {
        fun onVoiceChanged(characterId: Long, speakerId: Int)
    }
    
    private val app get() = requireContext().applicationContext as DramebazApplication
    private var characterId: Long = 0L
    private var bookId: Long = 0L
    private var isNarrator: Boolean = false
    private var character: Character? = null
    private var selectedSpeakerId: Int? = null
    private var currentSpeed = 1.0f
    private var currentEnergy = 1.0f
    
    // Voice preview
    private var mediaPlayer: MediaPlayer? = null
    private var previewJob: Job? = null
    
    // Gender filter
    private var currentGenderFilter: String = "all" // "all", "female", "male"
    private var allSpeakers: List<SpeakerMatcher.ScoredSpeaker> = emptyList()
    
    private var voiceAdapter: VoiceListAdapter? = null
    private var listener: VoiceChangeListener? = null
    
    fun setListener(listener: VoiceChangeListener) {
        this.listener = listener
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        characterId = arguments?.getLong(ARG_CHARACTER_ID, 0L) ?: 0L
        bookId = arguments?.getLong(ARG_BOOK_ID, 0L) ?: 0L
        isNarrator = arguments?.getBoolean(ARG_IS_NARRATOR, false) ?: false
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_voice_selector, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLogger.d(TAG, "Voice selector dialog created for character: $characterId, isNarrator: $isNarrator")

        setupUI(view)
        if (isNarrator) {
            loadNarrator()
        } else {
            loadCharacter()
        }
    }
    
    private fun setupUI(view: View) {
        // Gender filter buttons
        val genderGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.gender_filter_group)
        val btnAll = view.findViewById<MaterialButton>(R.id.btn_all)
        val btnFemale = view.findViewById<MaterialButton>(R.id.btn_female)
        val btnMale = view.findViewById<MaterialButton>(R.id.btn_male)
        
        btnAll.isChecked = true
        genderGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentGenderFilter = when (checkedId) {
                    R.id.btn_female -> "female"
                    R.id.btn_male -> "male"
                    else -> "all"
                }
                filterVoices()
            }
        }
        
        // Voice list
        val voiceList = view.findViewById<RecyclerView>(R.id.voice_list)
        voiceList.layoutManager = LinearLayoutManager(requireContext())
        
        // Sliders
        val speedSlider = view.findViewById<Slider>(R.id.speed_slider)
        val speedValue = view.findViewById<TextView>(R.id.speed_value)
        val energySlider = view.findViewById<Slider>(R.id.energy_slider)
        val energyValue = view.findViewById<TextView>(R.id.energy_value)
        
        speedSlider.addOnChangeListener { _, value, _ ->
            currentSpeed = value
            speedValue.text = String.format("%.1f", value)
        }
        
        energySlider.addOnChangeListener { _, value, _ ->
            currentEnergy = value
            energyValue.text = String.format("%.1f", value)
        }
        
        // Action buttons
        view.findViewById<MaterialButton>(R.id.btn_test).setOnClickListener { testVoice() }
        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { saveVoice() }
    }
    
    private fun loadCharacter() {
        viewLifecycleOwner.lifecycleScope.launch {
            character = withContext(Dispatchers.IO) {
                app.db.characterDao().getById(characterId)
            }

            val char = character ?: return@launch
            selectedSpeakerId = char.speakerId

            // Update UI with character info
            view?.apply {
                findViewById<TextView>(R.id.character_name).text = char.name

                // Parse traits for display
                val traitsDisplay = parseTraitsForDisplay(char.traits, char.voiceProfileJson)
                findViewById<TextView>(R.id.character_traits).text = traitsDisplay

                // Current voice label - use active model's catalog for correct gender info
                val currentVoiceText = char.speakerId?.let { sid ->
                    val traits = SpeakerMatcher.getActiveCatalog().getTraits(sid)
                    "Current: Speaker #$sid (${traits?.genderLabel ?: "?"})"
                } ?: "No voice assigned"
                findViewById<TextView>(R.id.current_voice_label).text = currentVoiceText

                // Load voice profile settings
                char.voiceProfileJson?.let { json ->
                    try {
                        val vp = Gson().fromJson(json, VoiceProfile::class.java)
                        currentSpeed = vp.speed
                        currentEnergy = vp.energy
                        findViewById<Slider>(R.id.speed_slider).value = currentSpeed.coerceIn(0.5f, 2.0f)
                        findViewById<Slider>(R.id.energy_slider).value = currentEnergy.coerceIn(0.5f, 1.5f)
                        findViewById<TextView>(R.id.speed_value).text = String.format("%.1f", currentSpeed)
                        findViewById<TextView>(R.id.energy_value).text = String.format("%.1f", currentEnergy)
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Failed to parse voice profile", e)
                    }
                }
            }

            // Load speakers sorted by match score
            loadSpeakers(char)
        }
    }

    /**
     * NARRATOR-002: Load narrator settings from Book entity (per-book settings).
     */
    private fun loadNarrator() {
        viewLifecycleOwner.lifecycleScope.launch {
            AppLogger.d(TAG, "Loading narrator settings for bookId=$bookId")
            val book = withContext(Dispatchers.IO) {
                app.db.bookDao().getById(bookId)
            }
            AppLogger.d(TAG, "Loaded book: id=${book?.id}, narratorSpeakerId=${book?.narratorSpeakerId}, narratorSpeed=${book?.narratorSpeed}")

            // Get narrator settings from book, with defaults
            val speakerId = book?.narratorSpeakerId ?: 0
            val speed = book?.narratorSpeed ?: NarratorSettings.DEFAULT_SPEED
            val energy = book?.narratorEnergy ?: 1.0f

            // Create a pseudo-character for Narrator
            character = Character(
                id = NARRATOR_ID,
                bookId = bookId,
                name = "Narrator",
                traits = "Story narrator",
                personalitySummary = "The voice that tells the story",
                speakerId = speakerId
            )

            selectedSpeakerId = speakerId
            currentSpeed = speed
            currentEnergy = energy

            // Update UI with narrator info
            view?.apply {
                findViewById<TextView>(R.id.character_name).text = "Narrator"
                findViewById<TextView>(R.id.character_traits).text = "Story narrator"

                // Current voice label
                val currentVoiceText = if (speakerId > 0) "Current: Speaker #$speakerId" else "No voice assigned"
                findViewById<TextView>(R.id.current_voice_label).text = currentVoiceText

                // Load voice profile settings
                findViewById<Slider>(R.id.speed_slider).value = currentSpeed.coerceIn(0.5f, 2.0f)
                findViewById<Slider>(R.id.energy_slider).value = currentEnergy.coerceIn(0.5f, 1.5f)
                findViewById<TextView>(R.id.speed_value).text = String.format("%.1f", currentSpeed)
                findViewById<TextView>(R.id.energy_value).text = String.format("%.1f", currentEnergy)
            }

            // Load all speakers (no character-based scoring for narrator)
            loadSpeakersForNarrator()
        }
    }

    /**
     * Load all speakers without character-based scoring for narrator.
     */
    private suspend fun loadSpeakersForNarrator() {
        allSpeakers = withContext(Dispatchers.IO) {
            SpeakerMatcher.getSimilarSpeakers(
                traits = "",
                personalitySummary = "Story narrator",
                name = "Narrator",
                topN = 100
            )
        }
        filterVoices()
    }

    private fun parseTraitsForDisplay(traits: String, voiceProfileJson: String?): String {
        // Try to extract gender and age from voice profile
        voiceProfileJson?.let { json ->
            try {
                val map = Gson().fromJson(json, Map::class.java)
                val gender = map["gender"] as? String
                val age = map["age"] as? String
                if (gender != null || age != null) {
                    return listOfNotNull(gender?.replaceFirstChar { it.uppercase() }, age).joinToString(", ")
                }
            } catch (_: Exception) {}
        }

        // Fallback to first few traits
        return if (traits.isBlank()) "Unknown"
        else traits.take(30) + if (traits.length > 30) "..." else ""
    }

    private suspend fun loadSpeakers(char: Character) {
        allSpeakers = withContext(Dispatchers.IO) {
            SpeakerMatcher.getSimilarSpeakers(
                char.traits,
                char.personalitySummary,
                char.name,
                topN = 100
            )
        }
        filterVoices()
    }

    private fun filterVoices() {
        val filtered = when (currentGenderFilter) {
            "female" -> allSpeakers.filter { it.speaker.isFemale }
            "male" -> allSpeakers.filter { it.speaker.isMale }
            else -> allSpeakers
        }

        val voiceList = view?.findViewById<RecyclerView>(R.id.voice_list) ?: return
        voiceAdapter = VoiceListAdapter(filtered, selectedSpeakerId) { speakerId ->
            selectedSpeakerId = speakerId
            voiceAdapter?.updateSelection(speakerId)
        }
        voiceList.adapter = voiceAdapter
    }

    private fun testVoice() {
        val speakerId = selectedSpeakerId
        if (speakerId == null) {
            Toast.makeText(context, "Select a voice first", Toast.LENGTH_SHORT).show()
            return
        }

        stopPreview()

        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val speakerName = if (isNarrator) "Narrator" else (character?.name ?: "a character")
                val sampleText = "Hello, I am $speakerName. This is how I sound."
                val voiceProfile = VoiceProfile(speed = currentSpeed, energy = currentEnergy)

                val result = withContext(Dispatchers.IO) {
                    app.ttsEngine.speak(sampleText, voiceProfile, null, speakerId)
                }

                result.onSuccess { audioFile ->
                    audioFile?.let {
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(it.absolutePath)
                            prepare()
                            start()
                        }
                    }
                }.onFailure { error ->
                    Toast.makeText(context, "Preview failed: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error testing voice", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun saveVoice() {
        val speakerId = selectedSpeakerId
        if (speakerId == null) {
            Toast.makeText(context, "Select a voice first", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (isNarrator) {
                    // NARRATOR-002: Save narrator settings to Book entity (per-book)
                    AppLogger.d(TAG, "Saving narrator settings: bookId=$bookId, speakerId=$speakerId, speed=$currentSpeed, energy=$currentEnergy")
                    app.db.bookDao().updateNarratorSettings(
                        bookId = bookId,
                        speakerId = speakerId,
                        speed = currentSpeed,
                        energy = currentEnergy
                    )

                    // Verify the save worked by reading back
                    val savedBook = app.db.bookDao().getById(bookId)
                    AppLogger.d(TAG, "After save - bookId=$bookId, narratorSpeakerId=${savedBook?.narratorSpeakerId}, narratorSpeed=${savedBook?.narratorSpeed}")

                    // Invalidate all narrator audio segments for this book
                    app.pageAudioStorage.deleteSegmentsForCharacter(bookId, "Narrator")

                    // AUDIO-REGEN-001: Enqueue background regeneration for current page
                    val session = app.db.readingSessionDao().getCurrent()
                    val pageNumber = session?.lastPageIndex ?: 1
                    var chapterId = session?.chapterId ?: 0L

                    // Fallback: if no valid session, get the first chapter of the book
                    if (chapterId == 0L || (session != null && session.bookId != bookId)) {
                        val chapters = app.db.chapterDao().getChaptersList(bookId)
                        if (chapters.isNotEmpty()) {
                            chapterId = chapters.first().id
                            AppLogger.d(TAG, "No valid session, using first chapter: $chapterId")
                        }
                    }

                    if (chapterId > 0) {
                        AudioRegenerationManager.enqueueRegeneration(
                            bookId = bookId,
                            chapterId = chapterId,
                            pageNumber = pageNumber,
                            characterName = "Narrator",
                            newSpeakerId = speakerId,
                            speed = currentSpeed,
                            energy = currentEnergy
                        )
                        AppLogger.i(TAG, "Enqueued narrator audio regeneration for page $pageNumber, chapterId=$chapterId")
                    } else {
                        AppLogger.w(TAG, "No chapters found for book $bookId, skipping audio regeneration")
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Narrator voice saved", Toast.LENGTH_SHORT).show()
                        listener?.onVoiceChanged(NARRATOR_ID, speakerId)
                        dismiss()
                    }
                    return@launch
                }

                val char = character ?: return@launch

                // Update voice profile with new speed/energy
                val existingProfile = char.voiceProfileJson?.let {
                    try { Gson().fromJson(it, VoiceProfile::class.java) } catch (_: Exception) { null }
                } ?: VoiceProfile()

                val updatedProfile = existingProfile.copy(speed = currentSpeed, energy = currentEnergy)
                val profileJson = Gson().toJson(updatedProfile)

                // Update character
                val updatedChar = char.copy(speakerId = speakerId, voiceProfileJson = profileJson)
                app.db.characterDao().update(updatedChar)

                // Update CharacterPageMapping and invalidate audio cache
                app.db.characterPageMappingDao().updateSpeakerForCharacter(bookId, char.name, speakerId)
                app.pageAudioStorage.deleteSegmentsForCharacter(bookId, char.name)

                // AUDIO-REGEN-001: Enqueue background regeneration for current page
                val session = app.db.readingSessionDao().getCurrent()
                val pageNumber = session?.lastPageIndex ?: 1
                var charChapterId = session?.chapterId ?: 0L

                // Fallback: if no valid session, get the first chapter of the book
                if (charChapterId == 0L || (session != null && session.bookId != bookId)) {
                    val chapters = app.db.chapterDao().getChaptersList(bookId)
                    if (chapters.isNotEmpty()) {
                        charChapterId = chapters.first().id
                        AppLogger.d(TAG, "No valid session for character, using first chapter: $charChapterId")
                    }
                }

                if (charChapterId > 0) {
                    AudioRegenerationManager.enqueueRegeneration(
                        bookId = bookId,
                        chapterId = charChapterId,
                        pageNumber = pageNumber,
                        characterName = char.name,
                        newSpeakerId = speakerId,
                        speed = currentSpeed,
                        energy = currentEnergy
                    )
                    AppLogger.i(TAG, "Enqueued character audio regeneration for ${char.name} on page $pageNumber, chapterId=$charChapterId")
                } else {
                    AppLogger.w(TAG, "No chapters found for book $bookId, skipping audio regeneration for ${char.name}")
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Voice saved for ${char.name}", Toast.LENGTH_SHORT).show()
                    listener?.onVoiceChanged(characterId, speakerId)
                    dismiss()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error saving voice", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPreview()
    }

    /**
     * VOICE-001: Adapter for voice list with selection highlighting.
     */
    private class VoiceListAdapter(
        private val voices: List<SpeakerMatcher.ScoredSpeaker>,
        private var selectedId: Int?,
        private val onVoiceSelected: (Int) -> Unit
    ) : RecyclerView.Adapter<VoiceListAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_speaker, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val scored = voices[position]
            val traits = scored.speaker
            val speakerId = traits.speakerId

            holder.speakerName.text = "Speaker #$speakerId"
            holder.speakerTraits.text = "${traits.genderLabel}, ${traits.ageYears ?: "?"}yrs, ${traits.region}"
            holder.speakerPitch.text = "Pitch: ${traits.pitchLevel.name.lowercase().replaceFirstChar { it.uppercase() }}"

            // Match score badge
            if (scored.score > 0) {
                holder.matchScore.visibility = View.VISIBLE
                holder.matchScore.text = "+${scored.score}"
            } else {
                holder.matchScore.visibility = View.GONE
            }

            // Selection indicator
            val isSelected = speakerId == selectedId
            holder.selectedIndicator.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.itemView.alpha = if (isSelected) 1.0f else 0.8f

            // Hide preview button/loading in this compact mode
            holder.previewButton.visibility = View.GONE
            holder.previewLoading.visibility = View.GONE

            holder.itemView.setOnClickListener {
                onVoiceSelected(speakerId)
            }
        }

        override fun getItemCount() = voices.size

        fun updateSelection(speakerId: Int) {
            val oldPos = voices.indexOfFirst { it.speaker.speakerId == selectedId }
            val newPos = voices.indexOfFirst { it.speaker.speakerId == speakerId }
            selectedId = speakerId
            if (oldPos >= 0) notifyItemChanged(oldPos)
            if (newPos >= 0) notifyItemChanged(newPos)
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val speakerName: TextView = view.findViewById(R.id.speaker_name)
            val speakerTraits: TextView = view.findViewById(R.id.speaker_traits)
            val speakerPitch: TextView = view.findViewById(R.id.speaker_pitch)
            val matchScore: TextView = view.findViewById(R.id.match_score)
            val selectedIndicator: android.widget.ImageView = view.findViewById(R.id.selected_indicator)
            val previewButton: android.widget.ImageButton = view.findViewById(R.id.preview_button)
            val previewLoading: android.widget.ProgressBar = view.findViewById(R.id.preview_loading)
        }
    }
}

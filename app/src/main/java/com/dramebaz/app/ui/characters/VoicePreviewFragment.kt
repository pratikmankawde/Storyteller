package com.dramebaz.app.ui.characters

import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.data.models.VoiceProfile
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T1.5: Voice Preview screen - play sample, pitch/speed sliders, save overrides.
 */
class VoicePreviewFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private var characterId: Long = 0L
    private var characterName: String = ""
    private var characterTraits: String = ""
    private var speakerId: Int? = null
    private var baseVoiceProfile: VoiceProfile? = null
    
    private var currentPitch = 1.0f
    private var currentSpeed = 1.0f
    private var mediaPlayer: MediaPlayer? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        characterId = arguments?.getLong("characterId", 0L) ?: 0L
        characterName = arguments?.getString("characterName", "Character") ?: "Character"
    }
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_voice_preview, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val characterNameView = view.findViewById<TextView>(R.id.character_name)
        val sampleTextView = view.findViewById<TextView>(R.id.sample_text)
        val pitchSlider = view.findViewById<SeekBar>(R.id.pitch_slider)
        val speedSlider = view.findViewById<SeekBar>(R.id.speed_slider)
        val pitchValue = view.findViewById<TextView>(R.id.pitch_value)
        val speedValue = view.findViewById<TextView>(R.id.speed_value)
        val btnPlayPreview = view.findViewById<MaterialButton>(R.id.btn_play_preview)
        val btnSave = view.findViewById<MaterialButton>(R.id.btn_save)
        
        characterNameView.text = characterName
        sampleTextView.text = "" // Set after character load with "Hello, I am <name>. <traits>."
        
        // Load character (voice profile, traits, speaker) and set preview text
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val appInstance = context?.applicationContext as? DramebazApplication ?: return@launch
                if (characterId == 0L) {
                    android.util.Log.w("VoicePreview", "Invalid characterId: 0")
                    withContext(Dispatchers.Main) {
                        if (isAdded) Toast.makeText(context, "Invalid character", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                val character = withContext(Dispatchers.IO) { appInstance.db.characterDao().getById(characterId) }
                if (character == null) {
                    android.util.Log.w("VoicePreview", "Character not found for id: $characterId")
                    withContext(Dispatchers.Main) {
                        if (isAdded) Toast.makeText(context, "Character not found", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                characterName = character.name
                characterTraits = character.traits.trim()
                speakerId = character.speakerId
                android.util.Log.d("VoicePreview", "Loaded character: ${character.name}, speakerId=$speakerId, voiceProfileJson=${character.voiceProfileJson?.take(100)}")
                
                if (!character.voiceProfileJson.isNullOrBlank()) {
                    try {
                        val gson = Gson()
                        baseVoiceProfile = gson.fromJson(character.voiceProfileJson, VoiceProfile::class.java)
                        currentPitch = baseVoiceProfile?.pitch ?: 1.0f
                        currentSpeed = baseVoiceProfile?.speed ?: 1.0f
                    } catch (e: Exception) {
                        android.util.Log.e("VoicePreview", "Error parsing voice profile", e)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    characterNameView.text = characterName
                    val previewText = if (characterTraits.isBlank()) {
                        "Hello, I am $characterName."
                    } else {
                        "Hello, I am $characterName. $characterTraits"
                    }
                    sampleTextView.text = previewText
                    pitchSlider.progress = ((currentPitch - 0.5f) / 1.0f * 200).toInt().coerceIn(0, 200)
                    speedSlider.progress = ((currentSpeed - 0.5f) / 1.0f * 200).toInt().coerceIn(0, 200)
                    updateSliderValues(pitchValue, speedValue)
                }
            } catch (e: Exception) {
                android.util.Log.e("VoicePreview", "Error loading character", e)
                withContext(Dispatchers.Main) {
                    if (isAdded) Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        // Pitch slider
        pitchSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentPitch = 0.5f + (progress / 200f) * 1.0f
                    updateSliderValues(pitchValue, speedValue)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Speed slider
        speedSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentSpeed = 0.5f + (progress / 200f) * 1.0f
                    updateSliderValues(pitchValue, speedValue)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Play preview button
        btnPlayPreview.setOnClickListener {
            playPreview()
        }
        
        // Save button
        btnSave.setOnClickListener {
            saveOverrides()
        }
    }
    
    private fun updateSliderValues(pitchValue: TextView, speedValue: TextView) {
        pitchValue.text = String.format("%.2f", currentPitch)
        speedValue.text = String.format("%.2f", currentSpeed)
    }
    
    private fun buildPreviewText(): String {
        return if (characterTraits.isBlank()) {
            "Hello, I am $characterName."
        } else {
            "Hello, I am $characterName. $characterTraits"
        }
    }
    
    private fun playPreview() {
        viewLifecycleOwner.lifecycleScope.launch {
            val appInstance = context?.applicationContext as? DramebazApplication ?: return@launch
            val sampleText = view?.findViewById<TextView>(R.id.sample_text)?.text?.toString()
                ?: buildPreviewText()
            if (sampleText.isBlank()) {
                if (isAdded) Toast.makeText(context, "No preview text", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            val updatedProfile = VoiceProfile(
                pitch = currentPitch,
                speed = currentSpeed,
                energy = baseVoiceProfile?.energy ?: 1.0f,
                emotionBias = baseVoiceProfile?.emotionBias ?: emptyMap()
            )
            
            val result = withContext(Dispatchers.IO) {
                appInstance.ttsEngine.speak(sampleText, updatedProfile, null, speakerId)
            }
            
            result.onSuccess { audioFile ->
                audioFile?.let {
                    withContext(Dispatchers.Main) {
                        if (isAdded) playAudioFile(it.absolutePath)
                    }
                } ?: run {
                    if (isAdded) Toast.makeText(context, "Failed to generate audio", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                android.util.Log.e("VoicePreview", "TTS synthesis failed", error)
                if (isAdded) Toast.makeText(context, "TTS failed: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun playAudioFile(filePath: String) {
        try {
            mediaPlayer?.release()
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                setOnCompletionListener {
                    release()
                    mediaPlayer = null
                }
                setOnErrorListener { _, what, extra ->
                    android.util.Log.e("VoicePreview", "MediaPlayer error: what=$what, extra=$extra")
                    release()
                    mediaPlayer = null
                    true
                }
                setOnPreparedListener {
                    start()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            android.util.Log.e("VoicePreview", "Error playing audio", e)
            if (isAdded) Toast.makeText(context, "Error playing audio: ${e.message}", Toast.LENGTH_SHORT).show()
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
    
    private fun saveOverrides() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val appInstance = context?.applicationContext as? DramebazApplication ?: return@launch
            try {
                val character = appInstance.db.characterDao().getById(characterId)
                if (character != null) {
                    val updatedProfile = VoiceProfile(
                        pitch = currentPitch,
                        speed = currentSpeed,
                        energy = baseVoiceProfile?.energy ?: 1.0f,
                        emotionBias = baseVoiceProfile?.emotionBias ?: emptyMap()
                    )
                    
                    val gson = Gson()
                    val profileJson = gson.toJson(updatedProfile)
                    
                    val updatedCharacter = character.copy(voiceProfileJson = profileJson)
                    appInstance.db.characterDao().update(updatedCharacter)
                    
                    withContext(Dispatchers.Main) {
                        if (isAdded) Toast.makeText(context, "Voice settings saved", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VoicePreview", "Error saving overrides", e)
                withContext(Dispatchers.Main) {
                    if (isAdded) Toast.makeText(context, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

package com.dramebaz.app.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import com.dramebaz.app.playback.mixer.PlaybackTheme
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.concurrent.TimeUnit

/**
 * T2.4 / T8.3: PlayerBottomSheet - Play/pause, seek, theme selection.
 */
class PlayerBottomSheet : BottomSheetDialogFragment() {
    
    interface PlayerControlsListener {
        fun onPlayPause()
        fun onSeek(position: Long)
        fun onRewind()
        fun onForward()
        fun onThemeChanged(theme: PlaybackTheme)
    }
    
    private var listener: PlayerControlsListener? = null
    private var currentPosition = 0L
    private var totalDuration = 0L
    private var isPlaying = false
    private var currentTheme = PlaybackTheme.CLASSIC
    
    fun setListener(listener: PlayerControlsListener) {
        this.listener = listener
    }
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(com.dramebaz.app.R.layout.bottom_sheet_player, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val seekBar = view.findViewById<SeekBar>(com.dramebaz.app.R.id.seek_bar)
        val btnPlayPause = view.findViewById<MaterialButton>(com.dramebaz.app.R.id.btn_play_pause)
        val btnRewind = view.findViewById<MaterialButton>(com.dramebaz.app.R.id.btn_rewind)
        val btnForward = view.findViewById<MaterialButton>(com.dramebaz.app.R.id.btn_forward)
        val currentTime = view.findViewById<TextView>(com.dramebaz.app.R.id.current_time)
        val totalTime = view.findViewById<TextView>(com.dramebaz.app.R.id.total_time)
        val themeGroup = view.findViewById<MaterialButtonToggleGroup>(com.dramebaz.app.R.id.theme_group)
        
        // Initialize seek bar
        seekBar.max = 1000
        seekBar.progress = 0
        
        // Play/Pause button
        btnPlayPause.setOnClickListener {
            listener?.onPlayPause()
            updatePlayPauseButton(btnPlayPause)
        }
        
        // Rewind button
        btnRewind.setOnClickListener {
            listener?.onRewind()
        }
        
        // Forward button
        btnForward.setOnClickListener {
            listener?.onForward()
        }
        
        // Seek bar
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val position = (progress / 1000f * totalDuration).toLong()
                    listener?.onSeek(position)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Theme selection
        val themeButtons = mapOf(
            com.dramebaz.app.R.id.theme_cinematic to PlaybackTheme.CINEMATIC,
            com.dramebaz.app.R.id.theme_relaxed to PlaybackTheme.RELAXED,
            com.dramebaz.app.R.id.theme_immersive to PlaybackTheme.IMMERSIVE,
            com.dramebaz.app.R.id.theme_classic to PlaybackTheme.CLASSIC
        )
        
        themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                themeButtons[checkedId]?.let { theme ->
                    currentTheme = theme
                    listener?.onThemeChanged(theme)
                }
            }
        }
        
        // Set initial theme selection
        when (currentTheme) {
            PlaybackTheme.CINEMATIC -> themeGroup.check(com.dramebaz.app.R.id.theme_cinematic)
            PlaybackTheme.RELAXED -> themeGroup.check(com.dramebaz.app.R.id.theme_relaxed)
            PlaybackTheme.IMMERSIVE -> themeGroup.check(com.dramebaz.app.R.id.theme_immersive)
            PlaybackTheme.CLASSIC -> themeGroup.check(com.dramebaz.app.R.id.theme_classic)
        }
        
        // Update UI
        updateProgress(currentPosition, totalDuration)
        updatePlayPauseButton(btnPlayPause)
    }
    
    fun updateProgress(position: Long, duration: Long) {
        currentPosition = position
        totalDuration = duration
        
        view?.let {
            val seekBar = it.findViewById<SeekBar>(com.dramebaz.app.R.id.seek_bar)
            val currentTime = it.findViewById<TextView>(com.dramebaz.app.R.id.current_time)
            val totalTime = it.findViewById<TextView>(com.dramebaz.app.R.id.total_time)
            
            if (duration > 0) {
                seekBar.progress = ((position.toFloat() / duration) * 1000).toInt()
            }
            
            currentTime.text = formatTime(position)
            totalTime.text = formatTime(duration)
        }
    }
    
    fun updatePlaybackState(playing: Boolean) {
        isPlaying = playing
        view?.let {
            val btnPlayPause = it.findViewById<MaterialButton>(com.dramebaz.app.R.id.btn_play_pause)
            updatePlayPauseButton(btnPlayPause)
        }
    }
    
    private fun updatePlayPauseButton(button: MaterialButton) {
        button.text = if (isPlaying) "⏸" else "▶"
    }
    
    private fun formatTime(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}

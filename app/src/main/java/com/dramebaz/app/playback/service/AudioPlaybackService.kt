package com.dramebaz.app.playback.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.dramebaz.app.R
import com.dramebaz.app.ui.main.MainActivity
import com.dramebaz.app.utils.AppLogger
import java.io.File

/**
 * Foreground service for audio playback with media notification.
 * Allows playback to continue when app is minimized.
 */
class AudioPlaybackService : Service() {
    private val tag = "AudioPlaybackService"
    private val binder = LocalBinder()
    
    private var mediaSession: MediaSessionCompat? = null
    private var mediaPlayer: MediaPlayer? = null
    private var notificationManager: NotificationManager? = null
    
    // Playback state
    private var isPlaying = false
    private var currentPosition: Long = 0L
    private var totalDuration: Long = 0L
    private var currentBookTitle: String = "Storyteller"
    private var currentChapterTitle: String = "Chapter"
    private var bookCoverBitmap: Bitmap? = null
    
    // Callbacks
    private var onProgressCallback: ((Long, Long) -> Unit)? = null
    private var onCompleteCallback: (() -> Unit)? = null
    private var onPlayStateChangeCallback: ((Boolean) -> Unit)? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }
    
    override fun onCreate() {
        super.onCreate()
        AppLogger.i(tag, "AudioPlaybackService created")
        
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        initMediaSession()
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.d(tag, "onStartCommand: action=${intent?.action}")
        
        when (intent?.action) {
            ACTION_PLAY -> resume()
            ACTION_PAUSE -> pause()
            ACTION_STOP -> {
                stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_REWIND -> rewind()
            ACTION_FORWARD -> forward()
        }
        
        return START_NOT_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager?.createNotificationChannel(channel)
            AppLogger.d(tag, "Notification channel created")
        }
    }
    
    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "StorytellerMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resume()
                }
                
                override fun onPause() {
                    pause()
                }
                
                override fun onStop() {
                    stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                
                override fun onSeekTo(pos: Long) {
                    seekTo(pos)
                }
                
                override fun onRewind() {
                    rewind()
                }
                
                override fun onFastForward() {
                    forward()
                }
            })
            
            isActive = true
        }
        AppLogger.d(tag, "MediaSession initialized")
    }
    
    /**
     * Start playback of an audio file.
     */
    fun playAudioFile(audioFile: File, bookTitle: String, chapterTitle: String, coverBitmap: Bitmap? = null) {
        AppLogger.i(tag, "Playing audio file: ${audioFile.name}, book=$bookTitle, chapter=$chapterTitle")
        
        currentBookTitle = bookTitle
        currentChapterTitle = chapterTitle
        bookCoverBitmap = coverBitmap
        
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                
                this@AudioPlaybackService.totalDuration = duration.toLong()
                
                setOnCompletionListener {
                    AppLogger.d(tag, "Audio playback completed")
                    this@AudioPlaybackService.isPlaying = false
                    onCompleteCallback?.invoke()
                    onPlayStateChangeCallback?.invoke(false)
                    updateNotification()
                    updateMediaSessionState()
                }
                
                setOnErrorListener { _, what, extra ->
                    AppLogger.e(tag, "MediaPlayer error: what=$what, extra=$extra")
                    this@AudioPlaybackService.isPlaying = false
                    onPlayStateChangeCallback?.invoke(false)
                    true
                }
                
                start()
                this@AudioPlaybackService.isPlaying = true
            }
            
            onPlayStateChangeCallback?.invoke(true)
            startProgressUpdates()
            startForeground(NOTIFICATION_ID, buildNotification())
            updateMediaSessionMetadata()
            updateMediaSessionState()
            
        } catch (e: Exception) {
            AppLogger.e(tag, "Error playing audio file", e)
        }
    }
    
    fun resume() {
        if (mediaPlayer != null && !isPlaying) {
            AppLogger.d(tag, "Resuming playback")
            mediaPlayer?.start()
            isPlaying = true
            onPlayStateChangeCallback?.invoke(true)
            startProgressUpdates()
            updateNotification()
            updateMediaSessionState()
        }
    }
    
    fun pause() {
        if (mediaPlayer != null && isPlaying) {
            AppLogger.d(tag, "Pausing playback")
            mediaPlayer?.pause()
            isPlaying = false
            onPlayStateChangeCallback?.invoke(false)
            updateNotification()
            updateMediaSessionState()
        }
    }
    
    fun stop() {
        AppLogger.i(tag, "Stopping playback")
        isPlaying = false
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        currentPosition = 0L
        totalDuration = 0L
        onPlayStateChangeCallback?.invoke(false)
    }
    
    fun seekTo(position: Long) {
        val posInt = position.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        AppLogger.d(tag, "Seeking to: ${position}ms")
        mediaPlayer?.seekTo(posInt)
        currentPosition = position
        updateMediaSessionState()
    }
    
    private fun rewind() {
        val newPos = (currentPosition - 10000).coerceAtLeast(0L)
        seekTo(newPos)
    }
    
    private fun forward() {
        val newPos = (currentPosition + 10000).coerceAtMost(totalDuration)
        seekTo(newPos)
    }
    
    private fun startProgressUpdates() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isPlaying && mediaPlayer != null) {
                    try {
                        currentPosition = mediaPlayer?.currentPosition?.toLong() ?: 0L
                        totalDuration = mediaPlayer?.duration?.toLong() ?: 0L
                        onProgressCallback?.invoke(currentPosition, totalDuration)
                        handler.postDelayed(this, 100)
                    } catch (e: Exception) {
                        // Player was released
                    }
                }
            }
        }
        handler.post(updateRunnable)
    }
    
    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause,
                "Pause",
                createActionIntent(ACTION_PAUSE)
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                "Play",
                createActionIntent(ACTION_PLAY)
            ).build()
        }
        
        val rewindAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_rew,
            "Rewind",
            createActionIntent(ACTION_REWIND)
        ).build()
        
        val forwardAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_ff,
            "Forward",
            createActionIntent(ACTION_FORWARD)
        ).build()
        
        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            createActionIntent(ACTION_STOP)
        ).build()
        
        // Use book cover or default icon
        val largeIcon = bookCoverBitmap ?: BitmapFactory.decodeResource(resources, R.drawable.ic_launcher)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentBookTitle)
            .setContentText(currentChapterTitle)
            .setSubText(if (isPlaying) "Playing" else "Paused")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(largeIcon)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .addAction(rewindAction)
            .addAction(playPauseAction)
            .addAction(forwardAction)
            .addAction(stopAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2) // rewind, play/pause, forward
            )
            .build()
    }
    
    private fun createActionIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
    
    private fun updateNotification() {
        notificationManager?.notify(NOTIFICATION_ID, buildNotification())
    }
    
    private fun updateMediaSessionMetadata() {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentChapterTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentBookTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Storyteller")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, totalDuration)
            .apply {
                bookCoverBitmap?.let {
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                }
            }
            .build()
        mediaSession?.setMetadata(metadata)
    }
    
    private fun updateMediaSessionState() {
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_FAST_FORWARD
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                currentPosition,
                1.0f
            )
            .build()
        mediaSession?.setPlaybackState(state)
    }
    
    // Public getters and setters for callbacks
    fun setOnProgressListener(callback: (Long, Long) -> Unit) {
        onProgressCallback = callback
    }
    
    fun setOnCompleteListener(callback: () -> Unit) {
        onCompleteCallback = callback
    }
    
    fun setOnPlayStateChangeListener(callback: (Boolean) -> Unit) {
        onPlayStateChangeCallback = callback
    }
    
    fun isPlaying(): Boolean = isPlaying
    fun getCurrentPosition(): Long = currentPosition
    fun getDuration(): Long = totalDuration
    
    override fun onDestroy() {
        AppLogger.i(tag, "AudioPlaybackService destroyed")
        stop()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
    
    companion object {
        const val CHANNEL_ID = "storyteller_playback"
        const val NOTIFICATION_ID = 1001
        
        const val ACTION_PLAY = "com.dramebaz.app.ACTION_PLAY"
        const val ACTION_PAUSE = "com.dramebaz.app.ACTION_PAUSE"
        const val ACTION_STOP = "com.dramebaz.app.ACTION_STOP"
        const val ACTION_REWIND = "com.dramebaz.app.ACTION_REWIND"
        const val ACTION_FORWARD = "com.dramebaz.app.ACTION_FORWARD"
    }
}

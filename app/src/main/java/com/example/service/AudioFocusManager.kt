package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages audio focus transitions with intelligent camera and transient focus loss handling.
 *
 * When a user opens the phone's camera, Android camera apps request short transient audio focus.
 * By default, this causes media players to pause playback. With [ignoreTransientFocusLoss] enabled,
 * music continues playing smoothly while taking photos or browsing camera apps, while still
 * immediately pausing on actual phone calls or permanent focus loss (e.g. YouTube, Spotify).
 */
class AudioFocusManager(
    private val context: Context,
    var isPlayingProvider: () -> Boolean = { false },
    var onPauseRequested: () -> Unit = {},
    var onResumeRequested: () -> Unit = {},
    var onDuckRequested: (Float) -> Unit = {},
    var onRestoreVolumeRequested: () -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioFocusManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile var hasAudioFocus: Boolean = false
        private set
    @Volatile private var wasPausedByTransientFocus: Boolean = false
    @Volatile private var wasDuckedByTransientFocus: Boolean = false

    var ignoreTransientFocusLoss: Boolean = false
    var reduceVolumeOnNotification: Boolean = true

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Permanent audio focus loss, pausing playback")
                hasAudioFocus = false
                wasPausedByTransientFocus = false
                wasDuckedByTransientFocus = false
                onRestoreVolumeRequested()
                onPauseRequested()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val isPhoneCall = audioManager?.mode != AudioManager.MODE_NORMAL
                val shouldPause = isPhoneCall || !ignoreTransientFocusLoss
                if (shouldPause) {
                    val currentlyPlaying = isPlayingProvider()
                    if (currentlyPlaying) {
                        Log.d(TAG, "Transient focus loss (camera, call, or external request), cleanly pausing playback")
                        wasPausedByTransientFocus = true
                        onPauseRequested()
                    } else {
                        Log.d(TAG, "Transient focus loss received while already paused, preserving paused state")
                        wasPausedByTransientFocus = false
                    }
                } else {
                    Log.d(TAG, "Attempting to preserve playback during transient focus loss")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (reduceVolumeOnNotification) {
                    Log.d(TAG, "Ducking volume for notification alert")
                    wasDuckedByTransientFocus = true
                    onDuckRequested(0.25f)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus restored")
                hasAudioFocus = true
                if (wasDuckedByTransientFocus) {
                    wasDuckedByTransientFocus = false
                    onRestoreVolumeRequested()
                }
                if (wasPausedByTransientFocus) {
                    wasPausedByTransientFocus = false
                    Log.d(TAG, "Resuming playback automatically after camera / transient focus")
                    onResumeRequested()
                }
            }
        }
    }

    fun requestAudioFocus(): Boolean {
        val mgr = audioManager ?: return true
        if (hasAudioFocus) return true

        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    val attrs = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setAcceptsDelayedFocusGain(true)
                        .setOnAudioFocusChangeListener(focusChangeListener, Handler(Looper.getMainLooper()))
                        .build()
                }
                mgr.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                mgr.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            hasAudioFocus
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting audio focus", e)
            true
        }
    }

    fun abandonAudioFocus() {
        val mgr = audioManager ?: return
        if (!hasAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { mgr.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                mgr.abandonAudioFocus(focusChangeListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus", e)
        } finally {
            hasAudioFocus = false
            wasPausedByTransientFocus = false
            wasDuckedByTransientFocus = false
        }
    }
}

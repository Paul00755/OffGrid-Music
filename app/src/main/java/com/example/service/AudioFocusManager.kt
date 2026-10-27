package com.example.service

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages audio focus and camera interruptions with independent user-configurable policies:
 * - [autoPauseCamera]: Pause playback when a camera opens (default OFF), and resume when closed.
 * - [autoPauseSystemAudio]: Pause playback on system/app audio interruptions (default ON), and resume when ended.
 */
class AudioFocusManager(
    private val context: Context,
    var isPlayingProvider: () -> Boolean = { false },
    var isSleepTimerStoppedProvider: () -> Boolean = { false },
    var onPauseRequested: () -> Unit = {},
    var onResumeRequested: () -> Unit = {},
    var onDuckRequested: (Float) -> Unit = {},
    var onRestoreVolumeRequested: () -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioFocusManager"
    }

    enum class InterruptionSource {
        CAMERA,
        SYSTEM_AUDIO_TRANSIENT,
        SYSTEM_AUDIO_LOSS,
        PHONE_CALL
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile var hasAudioFocus: Boolean = false
        private set

    private val activeInterruptions = mutableSetOf<InterruptionSource>()
    private val activeCameraIds = mutableSetOf<String>()

    @Volatile var wasPlayingBeforeInterruption: Boolean = false
        private set
    @Volatile var userManuallyPausedOrStopped: Boolean = false
        private set
    @Volatile private var wasDuckedByTransientFocus: Boolean = false
    @Volatile private var cameraCallbackRegistered: Boolean = false
    @Volatile private var cameraRegistrationReady: Boolean = false

    @Volatile var autoPauseCamera: Boolean = false
        set(value) {
            field = value
            if (!value) {
                synchronized(this) {
                    if (activeInterruptions.contains(InterruptionSource.CAMERA)) {
                        endInterruptionLocked(InterruptionSource.CAMERA)
                    }
                }
            }
        }

    @Volatile var autoPauseSystemAudio: Boolean = true
        set(value) {
            field = value
            if (!value) {
                synchronized(this) {
                    if (activeInterruptions.contains(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)) {
                        endInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                    }
                }
            }
        }

    /** Legacy compatibility property mapped to [!autoPauseSystemAudio]. */
    var ignoreTransientFocusLoss: Boolean
        get() = !autoPauseSystemAudio
        set(value) {
            autoPauseSystemAudio = !value
        }

    @Volatile var reduceVolumeOnNotification: Boolean = true

    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            if (!cameraRegistrationReady) return
            onCameraOpened(cameraId)
        }

        override fun onCameraAvailable(cameraId: String) {
            if (!cameraRegistrationReady) return
            onCameraClosed(cameraId)
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        dispatchFocusChange(focusChange)
    }

    init {
        registerCameraCallbackIfNeeded()
    }

    private fun registerCameraCallbackIfNeeded() {
        if (cameraCallbackRegistered) return
        val mgr = cameraManager ?: return
        try {
            mgr.registerAvailabilityCallback(cameraAvailabilityCallback, mainHandler)
            cameraCallbackRegistered = true
            mainHandler.post {
                cameraRegistrationReady = true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to register camera availability callback", e)
        }
    }

    fun unregisterCameraCallback() {
        if (!cameraCallbackRegistered) return
        try {
            cameraManager?.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        } catch (_: Throwable) {
        } finally {
            cameraCallbackRegistered = false
            cameraRegistrationReady = false
        }
    }

    /**
     * Invoked when the system reports a camera device has been opened (become unavailable).
     */
    @Synchronized
    fun onCameraOpened(cameraId: String = "0") {
        activeCameraIds.add(cameraId)
        if (autoPauseCamera) {
            Log.d(TAG, "Camera opened ($cameraId) with autoPauseCamera=true, pausing if playing")
            beginInterruptionLocked(InterruptionSource.CAMERA)
        } else {
            Log.d(TAG, "Camera opened ($cameraId) with autoPauseCamera=false, continuing playback")
        }
    }

    /**
     * Invoked when the system reports a camera device has been closed (become available).
     */
    @Synchronized
    fun onCameraClosed(cameraId: String = "0") {
        activeCameraIds.remove(cameraId)
        if (activeCameraIds.isEmpty()) {
            Log.d(TAG, "All active cameras closed ($cameraId)")
            endInterruptionLocked(InterruptionSource.CAMERA)
        }
    }

    /**
     * Handles Android AudioManager focus change events.
     */
    @Synchronized
    fun dispatchFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                if (wasDuckedByTransientFocus) {
                    wasDuckedByTransientFocus = false
                    try { onRestoreVolumeRequested() } catch (_: Throwable) {}
                }
                if (autoPauseSystemAudio) {
                    Log.d(TAG, "Audio focus loss with autoPauseSystemAudio=true, pausing and awaiting potential focus return")
                    beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_LOSS)
                } else {
                    Log.d(TAG, "Mandatory permanent audio focus loss with autoPauseSystemAudio=false")
                    val isCurrentlyPlaying = try { isPlayingProvider() } catch (_: Throwable) { false }
                    activeInterruptions.clear()
                    wasPlayingBeforeInterruption = false
                    if (isCurrentlyPlaying) {
                        try { onPauseRequested() } catch (_: Throwable) {}
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val mode = try { audioManager?.mode ?: AudioManager.MODE_NORMAL } catch (_: Throwable) { AudioManager.MODE_NORMAL }
                val isPhoneCall = mode == AudioManager.MODE_IN_CALL ||
                    mode == AudioManager.MODE_IN_COMMUNICATION ||
                    mode == AudioManager.MODE_RINGTONE
                val isCameraActive = activeCameraIds.isNotEmpty()

                when {
                    isPhoneCall -> {
                        Log.d(TAG, "Phone call audio focus loss, pausing playback")
                        beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                    }
                    isCameraActive && !autoPauseSystemAudio -> {
                        if (autoPauseCamera) {
                            Log.d(TAG, "Camera-correlated transient focus loss with autoPauseCamera=true")
                            beginInterruptionLocked(InterruptionSource.CAMERA)
                        } else {
                            Log.d(TAG, "Ignoring camera transient focus loss (both autoPauseCamera and autoPauseSystemAudio are false)")
                        }
                    }
                    autoPauseSystemAudio -> {
                        Log.d(TAG, "Transient system audio focus loss with autoPauseSystemAudio=true, pausing playback")
                        beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                    }
                    else -> {
                        Log.d(TAG, "Ignoring transient system audio focus loss (autoPauseSystemAudio=false)")
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (reduceVolumeOnNotification) {
                    Log.d(TAG, "Ducking volume for notification alert")
                    wasDuckedByTransientFocus = true
                    try { onDuckRequested(0.25f) } catch (_: Throwable) {}
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus restored")
                hasAudioFocus = true
                if (wasDuckedByTransientFocus) {
                    wasDuckedByTransientFocus = false
                    try { onRestoreVolumeRequested() } catch (_: Throwable) {}
                }
                endAudioFocusInterruptionsLocked()
            }
        }
    }

    private fun beginInterruptionLocked(source: InterruptionSource) {
        val currentlyPlaying = try { isPlayingProvider() } catch (_: Throwable) { false }
        val sleepStopped = try { isSleepTimerStoppedProvider() } catch (_: Throwable) { false }

        if (activeInterruptions.isEmpty()) {
            // First interruption in a potential sequence: record whether we were genuinely playing
            wasPlayingBeforeInterruption = currentlyPlaying && !userManuallyPausedOrStopped && !sleepStopped
        }
        activeInterruptions.add(source)

        if (currentlyPlaying) {
            try { onPauseRequested() } catch (_: Throwable) {}
        } else if (!wasPlayingBeforeInterruption) {
            Log.d(TAG, "Interruption ($source) received while already paused; preserving paused state")
        }
    }

    private fun endInterruptionLocked(source: InterruptionSource) {
        val removed = activeInterruptions.remove(source)
        if (!removed) return
        maybeResumeAfterInterruptionsLocked()
    }

    private fun endAudioFocusInterruptionsLocked() {
        val hadFocusInterruption =
            activeInterruptions.remove(InterruptionSource.SYSTEM_AUDIO_TRANSIENT) ||
            activeInterruptions.remove(InterruptionSource.SYSTEM_AUDIO_LOSS) ||
            activeInterruptions.remove(InterruptionSource.PHONE_CALL)
        if (!hadFocusInterruption) return
        maybeResumeAfterInterruptionsLocked()
    }

    private fun maybeResumeAfterInterruptionsLocked() {
        if (activeInterruptions.isNotEmpty()) {
            Log.d(TAG, "Deferring auto-resume; remaining active interruptions: $activeInterruptions")
            return
        }
        val sleepStopped = try { isSleepTimerStoppedProvider() } catch (_: Throwable) { false }
        val shouldResume = wasPlayingBeforeInterruption && !userManuallyPausedOrStopped && !sleepStopped
        wasPlayingBeforeInterruption = false

        if (shouldResume) {
            val alreadyPlaying = try { isPlayingProvider() } catch (_: Throwable) { false }
            if (!alreadyPlaying) {
                Log.d(TAG, "All interruptions ended; automatically resuming playback once")
                try { onResumeRequested() } catch (_: Throwable) {}
            }
        }
    }

    @Synchronized
    fun hasActiveInterruption(): Boolean = activeInterruptions.isNotEmpty()

    @Synchronized
    fun getActiveInterruptions(): Set<InterruptionSource> = activeInterruptions.toSet()

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
                        .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
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

    @Synchronized
    fun abandonAudioFocus() {
        val mgr = audioManager ?: return
        if (!hasAudioFocus && activeInterruptions.isEmpty()) return

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
            activeInterruptions.clear()
            wasPlayingBeforeInterruption = false
            wasDuckedByTransientFocus = false
        }
    }

    /** A user pause, explicit stop, or sleep-timer stop supersedes any pending automatic resume. */
    @Synchronized
    fun cancelPendingResume() {
        userManuallyPausedOrStopped = true
        activeInterruptions.clear()
        wasPlayingBeforeInterruption = false
    }

    /** A direct user play request clears manual-pause state and supersedes any pending focus-loss resume. */
    @Synchronized
    fun onUserPlayRequested() {
        userManuallyPausedOrStopped = false
        activeInterruptions.clear()
        wasPlayingBeforeInterruption = false
    }
}

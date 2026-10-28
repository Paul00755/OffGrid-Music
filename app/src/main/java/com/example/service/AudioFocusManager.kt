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
    var ownActiveMediaPlayerCountProvider: () -> Int = { 0 },
    var externalVideoOrMediaActiveProvider: (() -> Boolean)? = null,
    var callActiveProvider: (() -> Boolean)? = null,
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
    private val pendingTransientCheckRunnables = mutableListOf<Runnable>()

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
        set(value) {
            field = value
            if (!value && wasDuckedByTransientFocus) {
                synchronized(this) {
                    if (wasDuckedByTransientFocus) {
                        wasDuckedByTransientFocus = false
                        try { onRestoreVolumeRequested() } catch (_: Throwable) {}
                    }
                }
            }
        }

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
        cancelTransientVerificationChecksLocked()
        if (!cameraCallbackRegistered) return
        try {
            cameraManager?.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        } catch (_: Throwable) {
        } finally {
            cameraCallbackRegistered = false
            cameraRegistrationReady = false
        }
    }

    private fun cancelTransientVerificationChecksLocked() {
        if (pendingTransientCheckRunnables.isEmpty()) return
        for (runnable in pendingTransientCheckRunnables) {
            mainHandler.removeCallbacks(runnable)
        }
        pendingTransientCheckRunnables.clear()
    }

    private fun isCallOrRingtoneActive(): Boolean {
        try {
            if (callActiveProvider?.invoke() == true) return true
        } catch (_: Throwable) {}

        val mode = try { audioManager?.mode ?: AudioManager.MODE_NORMAL } catch (_: Throwable) { AudioManager.MODE_NORMAL }
        if (mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            mode == 4 // AudioManager.MODE_CALL_SCREENING
        ) {
            return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val configs = try { audioManager?.activePlaybackConfigurations.orEmpty() } catch (_: Throwable) { emptyList() }
            for (config in configs) {
                val usage = config.audioAttributes?.usage ?: continue
                if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION ||
                    usage == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING ||
                    usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE ||
                    usage == AudioAttributes.USAGE_ALARM
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun hasActiveNotificationOrSystemStream(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val configs = try { audioManager?.activePlaybackConfigurations.orEmpty() } catch (_: Throwable) { emptyList() }
            for (config in configs) {
                val attrs = config.audioAttributes ?: continue
                if (attrs.contentType == AudioAttributes.CONTENT_TYPE_SONIFICATION) {
                    return true
                }
                when (attrs.usage) {
                    AudioAttributes.USAGE_NOTIFICATION,
                    AudioAttributes.USAGE_NOTIFICATION_EVENT,
                    AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
                    AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
                    AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
                    AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> return true
                }
            }
        }
        return false
    }

    private fun hasExternalVideoOrMediaPlaying(): Boolean {
        try {
            externalVideoOrMediaActiveProvider?.let { return it.invoke() }
        } catch (_: Throwable) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val configs = try { audioManager?.activePlaybackConfigurations.orEmpty() } catch (_: Throwable) { emptyList() }
            if (configs.isEmpty()) return false

            val ownMediaCount = try { ownActiveMediaPlayerCountProvider().coerceAtLeast(0) } catch (_: Throwable) { 0 }
            var musicMediaCount = 0

            for (config in configs) {
                val attrs = config.audioAttributes ?: continue
                val usage = attrs.usage
                val contentType = attrs.contentType

                // Any movie or game stream is definitely external video/game playback
                if (contentType == AudioAttributes.CONTENT_TYPE_MOVIE || usage == AudioAttributes.USAGE_GAME) {
                    return true
                }

                if (usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_UNKNOWN) {
                    if (contentType == AudioAttributes.CONTENT_TYPE_SONIFICATION) {
                        continue
                    }
                    // Our ExoPlayer exclusively sets USAGE_MEDIA + CONTENT_TYPE_MUSIC.
                    // Any other content type on USAGE_MEDIA/USAGE_UNKNOWN is an external media/video player.
                    if (usage == AudioAttributes.USAGE_MEDIA && contentType == AudioAttributes.CONTENT_TYPE_MUSIC) {
                        musicMediaCount++
                    } else {
                        return true
                    }
                }
            }

            if (musicMediaCount > ownMediaCount) {
                return true
            }
        }
        return false
    }

    /**
     * Determines whether a transient audio focus loss was triggered by a notification / hotspot / system alert
     * rather than a video or call.
     */
    private fun isNotificationOrSystemAlertTransientLoss(): Boolean {
        if (isCallOrRingtoneActive()) return false
        if (hasExternalVideoOrMediaPlaying()) return false
        if (hasActiveNotificationOrSystemStream()) return true
        val ownMediaCount = try { ownActiveMediaPlayerCountProvider() } catch (_: Throwable) { 0 }
        return ownMediaCount > 0
    }

    private fun handleNotificationDuckOrContinueLocked() {
        if (reduceVolumeOnNotification) {
            Log.d(TAG, "Notification/hotspot focus loss detected: ducking volume without pausing")
            wasDuckedByTransientFocus = true
            try { onDuckRequested(0.25f) } catch (_: Throwable) {}
        } else {
            Log.d(TAG, "Notification/hotspot focus loss detected with ducking disabled: continuing playback without pause")
        }
    }

    private fun scheduleTransientVideoOrCallVerificationLocked() {
        cancelTransientVerificationChecksLocked()

        val checkDelaysMs = longArrayOf(250L, 600L, 1100L)
        for (delayMs in checkDelaysMs) {
            val checkRunnable = Runnable {
                synchronized(this@AudioFocusManager) {
                    if (hasAudioFocus || activeInterruptions.isNotEmpty()) return@synchronized
                    val callNowActive = isCallOrRingtoneActive()
                    val videoNowActive = hasExternalVideoOrMediaPlaying()
                    if (callNowActive) {
                        if (wasDuckedByTransientFocus) {
                            wasDuckedByTransientFocus = false
                            try { onRestoreVolumeRequested() } catch (_: Throwable) {}
                        }
                        cancelTransientVerificationChecksLocked()
                        Log.d(TAG, "Deferred check detected active call; pausing playback")
                        beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                    } else if (videoNowActive && autoPauseSystemAudio) {
                        if (wasDuckedByTransientFocus) {
                            wasDuckedByTransientFocus = false
                            try { onRestoreVolumeRequested() } catch (_: Throwable) {}
                        }
                        cancelTransientVerificationChecksLocked()
                        Log.d(TAG, "Deferred check detected external video/media stream; pausing playback")
                        beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                    }
                }
            }
            pendingTransientCheckRunnables.add(checkRunnable)
            mainHandler.postDelayed(checkRunnable, delayMs)
        }

        // Safety fallback: if a hotspot/system notification never dispatches AUDIOFOCUS_GAIN after its chime ends,
        // restore ducked volume once no notification stream remains active.
        val restoreSafetyRunnable = Runnable {
            synchronized(this@AudioFocusManager) {
                if (wasDuckedByTransientFocus && activeInterruptions.isEmpty() && !hasActiveNotificationOrSystemStream()) {
                    wasDuckedByTransientFocus = false
                    try { onRestoreVolumeRequested() } catch (_: Throwable) {}
                }
            }
        }
        pendingTransientCheckRunnables.add(restoreSafetyRunnable)
        mainHandler.postDelayed(restoreSafetyRunnable, 2800L)
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
                cancelTransientVerificationChecksLocked()
                hasAudioFocus = false
                if (hasActiveNotificationOrSystemStream() && !hasExternalVideoOrMediaPlaying() && !isCallOrRingtoneActive()) {
                    handleNotificationDuckOrContinueLocked()
                    scheduleTransientVideoOrCallVerificationLocked()
                    return
                }
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
                cancelTransientVerificationChecksLocked()
                val isPhoneCall = isCallOrRingtoneActive()
                val isCameraActive = activeCameraIds.isNotEmpty()

                when {
                    isPhoneCall -> {
                        if (wasDuckedByTransientFocus) {
                            wasDuckedByTransientFocus = false
                            try { onRestoreVolumeRequested() } catch (_: Throwable) {}
                        }
                        Log.d(TAG, "Phone call audio focus loss, pausing playback")
                        beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                    }
                    isCameraActive && !autoPauseCamera -> {
                        Log.d(TAG, "Ignoring camera-correlated transient focus loss because autoPauseCamera=false")
                    }
                    isCameraActive && !autoPauseSystemAudio -> {
                        Log.d(TAG, "Camera-correlated transient focus loss with autoPauseCamera=true")
                        beginInterruptionLocked(InterruptionSource.CAMERA)
                    }
                    isNotificationOrSystemAlertTransientLoss() -> {
                        hasAudioFocus = false
                        handleNotificationDuckOrContinueLocked()
                        scheduleTransientVideoOrCallVerificationLocked()
                    }
                    autoPauseSystemAudio -> {
                        if (wasDuckedByTransientFocus) {
                            wasDuckedByTransientFocus = false
                            try { onRestoreVolumeRequested() } catch (_: Throwable) {}
                        }
                        Log.d(TAG, "Transient video/system audio focus loss with autoPauseSystemAudio=true, pausing playback")
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
                cancelTransientVerificationChecksLocked()
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
                        .setWillPauseWhenDucked(false)
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
        cancelTransientVerificationChecksLocked()
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
        cancelTransientVerificationChecksLocked()
        userManuallyPausedOrStopped = true
        activeInterruptions.clear()
        wasPlayingBeforeInterruption = false
    }

    /** A direct user play request clears manual-pause state and supersedes any pending focus-loss resume. */
    @Synchronized
    fun onUserPlayRequested() {
        cancelTransientVerificationChecksLocked()
        userManuallyPausedOrStopped = false
        activeInterruptions.clear()
        wasPlayingBeforeInterruption = false
    }
}

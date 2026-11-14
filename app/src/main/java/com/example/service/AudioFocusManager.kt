package com.example.service

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
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
        /**
         * Duck volume multiplier: reduces 40% of the user's current speaker/stream volume
         * (leaving 60% of the current volume so the song remains gently audible during notifications
         * and then restores to 100% of the user's set volume).
         */
        const val DEFAULT_NOTIFICATION_DUCK_VOLUME = 0.60f
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
    @Volatile private var focusCallbackThread: HandlerThread? = null
    @Volatile private var focusCallbackHandler: Handler? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile var hasAudioFocus: Boolean = false
        private set

    private val activeInterruptions = mutableSetOf<InterruptionSource>()
    private val activeCameraIds = mutableSetOf<String>()
    private val hardwareUnavailableCameraIds = mutableSetOf<String>()
    private val pendingTransientCheckRunnables = mutableListOf<Runnable>()
    private var pendingPlaybackDuckRestoreRunnable: Runnable? = null

    @Volatile var wasPlayingBeforeInterruption: Boolean = false
        private set
    @Volatile var userManuallyPausedOrStopped: Boolean = false
        private set
    @Volatile private var wasDuckedByTransientFocus: Boolean = false
    @Volatile private var duckStartedViaPlaybackObserver: Boolean = false
    @Volatile private var duckStartedAtMs: Long = 0L
    @Volatile private var systemHandledDuckingForNotification: Boolean = false
    @Volatile private var baselineMusicStreamVolume: Int = -1
    @Volatile private var baselineMusicStreamObservedAtMs: Long = 0L
    @Volatile private var recentMusicStreamDropAtMs: Long = 0L
    @Volatile private var musicStreamVolumeBeforeDrop: Int = -1
    @Volatile private var cameraCallbackRegistered: Boolean = false
    @Volatile private var cameraRegistrationReady: Boolean = false
    @Volatile private var playbackCallbackRegistered: Boolean = false
    @Volatile private var recordingCallbackRegistered: Boolean = false
    @Volatile private var modeListenerRegistered: Boolean = false
    @Volatile private var screenReceiverRegistered: Boolean = false
    @Volatile private var lastCameraOrTransientInterruptionAtMs: Long = 0L

    private val pendingCameraOpenRunnables = mutableMapOf<String, Runnable>()

    private fun getOrCreateFocusCallbackHandler(): Handler {
        val existing = focusCallbackHandler
        if (existing != null && focusCallbackThread?.isAlive == true) {
            return existing
        }
        synchronized(this) {
            val recheck = focusCallbackHandler
            if (recheck != null && focusCallbackThread?.isAlive == true) {
                return recheck
            }
            return try {
                val thread = HandlerThread("OffGridAudioFocus", Process.THREAD_PRIORITY_URGENT_AUDIO).apply {
                    isDaemon = true
                    start()
                }
                val handler = Handler(thread.looper)
                focusCallbackThread = thread
                focusCallbackHandler = handler
                handler
            } catch (_: Throwable) {
                mainHandler
            }
        }
    }

    private fun runUrgentOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { block() } catch (_: Throwable) {}
        } else {
            mainHandler.postAtFrontOfQueue {
                try { block() } catch (_: Throwable) {}
            }
        }
    }

    @Volatile var autoPauseCamera: Boolean = false
        set(value) {
            field = value
            if (value) {
                registerCameraCallbackIfNeeded()
            } else {
                synchronized(this) {
                    cancelPendingCameraOpenRunnablesLocked()
                    activeCameraIds.clear()
                    hardwareUnavailableCameraIds.clear()
                    if (activeInterruptions.contains(InterruptionSource.CAMERA)) {
                        endInterruptionLocked(InterruptionSource.CAMERA)
                    }
                }
                unregisterOnlyCameraAvailabilityCallback()
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
                        clearDuckStateAndRestoreLocked()
                    }
                }
            }
        }

    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            synchronized(this@AudioFocusManager) {
                hardwareUnavailableCameraIds.add(cameraId)
            }
            if (!cameraRegistrationReady || !autoPauseCamera) return
            // Ignore background system camera checks (e.g. Android Adaptive Timeout / Screen Attention
            // 1–2s before screen sleep, lockscreen Face Unlock, or when OffGrid Music itself is in the foreground).
            if (isDeviceIdleOrSleepingOrLocking()) return
            if (PlaybackManager.activeInstance?.takeIf { it.audioFocusManager === this@AudioFocusManager }?.isUiInForeground == true) return
            val confirmDelayMs = if (isFrontFacingCamera(cameraId)) 2400L else 900L
            synchronized(this@AudioFocusManager) {
                pendingCameraOpenRunnables.remove(cameraId)?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    synchronized(this@AudioFocusManager) {
                        pendingCameraOpenRunnables.remove(cameraId)
                        if (autoPauseCamera &&
                            hardwareUnavailableCameraIds.contains(cameraId) &&
                            !isDeviceIdleOrSleepingOrLocking() &&
                            PlaybackManager.activeInstance?.takeIf { it.audioFocusManager === this@AudioFocusManager }?.isUiInForeground != true
                        ) {
                            onCameraOpened(cameraId)
                        }
                    }
                }
                pendingCameraOpenRunnables[cameraId] = runnable
                mainHandler.postDelayed(runnable, confirmDelayMs)
            }
        }

        override fun onCameraAvailable(cameraId: String) {
            synchronized(this@AudioFocusManager) {
                hardwareUnavailableCameraIds.remove(cameraId)
                pendingCameraOpenRunnables.remove(cameraId)?.let { mainHandler.removeCallbacks(it) }
            }
            if (!cameraRegistrationReady) return
            onCameraClosed(cameraId)
        }
    }

    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) {
                synchronized(this@AudioFocusManager) {
                    cancelPendingCameraOpenRunnablesLocked()
                    hardwareUnavailableCameraIds.clear()
                    activeCameraIds.clear()
                    val now = android.os.SystemClock.elapsedRealtime()
                    val wasRecentPreSleepTrigger = (now - lastCameraOrTransientInterruptionAtMs) in 0L..4000L
                    if (activeInterruptions.contains(InterruptionSource.CAMERA)) {
                        endInterruptionLocked(InterruptionSource.CAMERA)
                    }
                    if (wasRecentPreSleepTrigger &&
                        activeInterruptions.contains(InterruptionSource.SYSTEM_AUDIO_TRANSIENT) &&
                        !isCallOrRingtoneActive()
                    ) {
                        endInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                    }
                }
            }
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        dispatchFocusChange(focusChange)
    }

    private val audioPlaybackCallback: AudioManager.AudioPlaybackCallback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    onActivePlaybackConfigurationsChanged(configs)
                }
            }
        } else null

    private val audioRecordingCallback: AudioManager.AudioRecordingCallback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                    onActiveRecordingConfigurationsChanged()
                }
            }
        } else null

    private val audioModeChangedListener: AudioManager.OnModeChangedListener? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnModeChangedListener { mode ->
                mainHandler.post {
                    onAudioModeChanged(mode)
                }
            }
        } else null

    init {
        if (autoPauseCamera) {
            registerCameraCallbackIfNeeded()
        }
        registerPlaybackCallbackIfNeeded()
        registerRecordingCallbackIfNeeded()
        registerModeChangedListenerIfNeeded()
        registerScreenReceiverIfNeeded()
    }

    private fun registerScreenReceiverIfNeeded() {
        if (screenReceiverRegistered) return
        try {
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(screenStateReceiver, filter)
            }
            screenReceiverRegistered = true
        } catch (_: Throwable) {}
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        try {
            context.unregisterReceiver(screenStateReceiver)
        } catch (_: Throwable) {
        } finally {
            screenReceiverRegistered = false
        }
    }

    private fun registerPlaybackCallbackIfNeeded() {
        if (playbackCallbackRegistered) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = audioManager ?: return
        val cb = audioPlaybackCallback ?: return
        try {
            mgr.registerAudioPlaybackCallback(cb, getOrCreateFocusCallbackHandler())
            playbackCallbackRegistered = true
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to register audio playback callback", e)
        }
    }

    private fun unregisterPlaybackCallback() {
        if (!playbackCallbackRegistered) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val cb = audioPlaybackCallback ?: return
        try {
            audioManager?.unregisterAudioPlaybackCallback(cb)
        } catch (_: Throwable) {
        } finally {
            playbackCallbackRegistered = false
        }
    }

    private fun registerRecordingCallbackIfNeeded() {
        if (recordingCallbackRegistered) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val mgr = audioManager ?: return
        val cb = audioRecordingCallback ?: return
        try {
            mgr.registerAudioRecordingCallback(cb, getOrCreateFocusCallbackHandler())
            recordingCallbackRegistered = true
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to register audio recording callback", e)
        }
    }

    private fun unregisterRecordingCallback() {
        if (!recordingCallbackRegistered) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cb = audioRecordingCallback ?: return
        try {
            audioManager?.unregisterAudioRecordingCallback(cb)
        } catch (_: Throwable) {
        } finally {
            recordingCallbackRegistered = false
        }
    }

    private fun registerModeChangedListenerIfNeeded() {
        if (modeListenerRegistered) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mgr = audioManager ?: return
        val listener = audioModeChangedListener ?: return
        try {
            mgr.addOnModeChangedListener(context.mainExecutor, listener)
            modeListenerRegistered = true
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to register audio mode changed listener", e)
        }
    }

    private fun unregisterModeChangedListener() {
        if (!modeListenerRegistered) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val listener = audioModeChangedListener ?: return
        try {
            audioManager?.removeOnModeChangedListener(listener)
        } catch (_: Throwable) {
        } finally {
            modeListenerRegistered = false
        }
    }

    @Synchronized
    private fun onAudioModeChanged(mode: Int) {
        val isCallMode = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            mode == 4 || // AudioManager.MODE_CALL_SCREENING
            mode == 5    // AudioManager.MODE_COMMUNICATION_REDIRECT
        if (isCallMode) {
            if (!activeInterruptions.contains(InterruptionSource.PHONE_CALL)) {
                clearDuckStateAndRestoreLocked()
                cancelTransientVerificationChecksLocked()
                Log.d(TAG, "AudioModeChangedListener detected call/ringtone mode ($mode); pausing playback")
                beginInterruptionLocked(InterruptionSource.PHONE_CALL)
            }
        } else if (mode == AudioManager.MODE_NORMAL && activeInterruptions.contains(InterruptionSource.PHONE_CALL)) {
            mainHandler.postDelayed({
                synchronized(this@AudioFocusManager) {
                    if (activeInterruptions.contains(InterruptionSource.PHONE_CALL) && !isCallOrRingtoneActive()) {
                        Log.d(TAG, "Call mode returned to MODE_NORMAL; ending phone call interruption")
                        endInterruptionLocked(InterruptionSource.PHONE_CALL)
                    }
                }
            }, 350L)
        }
    }

    @Synchronized
    private fun onActiveRecordingConfigurationsChanged() {
        val callNowActive = isCallOrRingtoneActive()
        if (callNowActive && !activeInterruptions.contains(InterruptionSource.PHONE_CALL)) {
            clearDuckStateAndRestoreLocked()
            cancelTransientVerificationChecksLocked()
            Log.d(TAG, "RecordingCallback detected active call/VoIP mic; pausing playback")
            beginInterruptionLocked(InterruptionSource.PHONE_CALL)
        } else if (!callNowActive && activeInterruptions.contains(InterruptionSource.PHONE_CALL) && hasAudioFocus) {
            Log.d(TAG, "RecordingCallback detected call mic closed with focus held; resuming")
            endInterruptionLocked(InterruptionSource.PHONE_CALL)
        }
    }

    @Synchronized
    internal fun onActivePlaybackConfigurationsChanged(configs: List<AudioPlaybackConfiguration>? = null) {
        val callNowActive = isCallOrRingtoneActive()
        if (callNowActive) {
            if (!activeInterruptions.contains(InterruptionSource.PHONE_CALL)) {
                clearDuckStateAndRestoreLocked()
                cancelTransientVerificationChecksLocked()
                Log.d(TAG, "PlaybackCallback detected active call/ringtone; pausing playback")
                beginInterruptionLocked(InterruptionSource.PHONE_CALL)
            }
            return
        } else if (activeInterruptions.contains(InterruptionSource.PHONE_CALL) && hasAudioFocus) {
            endInterruptionLocked(InterruptionSource.PHONE_CALL)
            return
        }

        val notificationStreamActive = hasNotificationConfigActive(configs)
        if (notificationStreamActive && activeInterruptions.isEmpty()) {
            cancelPendingPlaybackDuckRestoreLocked()
            val isCurrentlyPlaying = try { isPlayingProvider() } catch (_: Throwable) { false }
            if (isCurrentlyPlaying && !wasDuckedByTransientFocus) {
                handleNotificationDuckOrContinueLocked(
                    isExplicitDuckableFocus = true,
                    viaPlaybackObserver = true
                )
            }
            return
        }

        if (!notificationStreamActive &&
            wasDuckedByTransientFocus &&
            duckStartedViaPlaybackObserver &&
            hasAudioFocus &&
            activeInterruptions.isEmpty()
        ) {
            scheduleQuickPlaybackObserverRestoreLocked()
        }

        if (hasAudioFocus || activeInterruptions.isNotEmpty()) return
        val anyNotificationActive = notificationStreamActive || isSystemNotificationOrRingStreamActive()
        val videoNowActive = !anyNotificationActive && hasExternalVideoOrMediaPlaying()
        if (videoNowActive && autoPauseSystemAudio) {
            clearDuckStateAndRestoreLocked()
            cancelTransientVerificationChecksLocked()
            Log.d(TAG, "PlaybackCallback detected external video/media stream; pausing playback")
            beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
        }
    }

    /**
     * Lightweight poll invoked from PlaybackManager's active playback loop to guarantee
     * that incoming/active cellular or WhatsApp/VoIP calls pause music even on OEM ROMs
     * that delay or suppress audio focus callbacks when switching into call/ringtone mode,
     * and to keep the device's baseline speaker volume calibrated for OEM ducking detection.
     */
    @Synchronized
    fun pollActiveCallStateWhilePlaying() {
        val currentVol = try { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1 } catch (_: Throwable) { -1 }
        if (currentVol >= 0) {
            recordMusicStreamVolumeLocked(currentVol)
        }
        if (activeInterruptions.contains(InterruptionSource.PHONE_CALL)) return
        if (isCallOrRingtoneActive()) {
            clearDuckStateAndRestoreLocked()
            cancelTransientVerificationChecksLocked()
            Log.d(TAG, "Active call/ringtone detected during playback poll; pausing playback")
            beginInterruptionLocked(InterruptionSource.PHONE_CALL)
        }
    }

    /**
     * Called when STREAM_MUSIC changes while playing so built-in OEM notification ducking
     * that transiently lowers STREAM_MUSIC won't stack with in-app volume attenuation.
     */
    @Synchronized
    fun onMusicStreamVolumeObserved(streamVolume: Int) {
        if (streamVolume < 0) return
        recordMusicStreamVolumeLocked(streamVolume)
    }

    private fun recordMusicStreamVolumeLocked(currentVol: Int) {
        val now = SystemClock.elapsedRealtime()
        if (wasDuckedByTransientFocus) {
            if (!systemHandledDuckingForNotification &&
                baselineMusicStreamVolume > 0 &&
                currentVol in 1 until baselineMusicStreamVolume &&
                (now - duckStartedAtMs) <= 1800L
            ) {
                // OEM built-in ducking lowered STREAM_MUSIC right after our in-app duck started;
                // yield attenuation to the OS so both settings do not double-duck playback.
                systemHandledDuckingForNotification = true
                Log.d(TAG, "System STREAM_MUSIC dropped ($baselineMusicStreamVolume -> $currentVol) during notification; yielding ducking to OS")
                runUrgentOnMain { onRestoreVolumeRequested() }
            }
            return
        }
        if (activeInterruptions.isNotEmpty()) return

        if (baselineMusicStreamVolume > 0 &&
            currentVol in 1 until baselineMusicStreamVolume &&
            (now - baselineMusicStreamObservedAtMs) <= 1500L
        ) {
            musicStreamVolumeBeforeDrop = baselineMusicStreamVolume
            recentMusicStreamDropAtMs = now
        } else if (recentMusicStreamDropAtMs > 0L && (now - recentMusicStreamDropAtMs) > 1500L) {
            musicStreamVolumeBeforeDrop = -1
            recentMusicStreamDropAtMs = 0L
        }
        baselineMusicStreamVolume = currentVol
        baselineMusicStreamObservedAtMs = now
        if (musicStreamVolumeBeforeDrop > 0 && currentVol >= musicStreamVolumeBeforeDrop) {
            musicStreamVolumeBeforeDrop = -1
            recentMusicStreamDropAtMs = 0L
        }
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

    private fun unregisterOnlyCameraAvailabilityCallback() {
        if (!cameraCallbackRegistered) return
        try {
            cameraManager?.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        } catch (_: Throwable) {
        } finally {
            cameraCallbackRegistered = false
            cameraRegistrationReady = false
        }
    }

    private fun cancelPendingCameraOpenRunnablesLocked() {
        if (pendingCameraOpenRunnables.isEmpty()) return
        for (runnable in pendingCameraOpenRunnables.values) {
            mainHandler.removeCallbacks(runnable)
        }
        pendingCameraOpenRunnables.clear()
    }

    fun unregisterCameraCallback() {
        cancelTransientVerificationChecksLocked()
        cancelPendingPlaybackDuckRestoreLocked()
        cancelPendingCameraOpenRunnablesLocked()
        unregisterPlaybackCallback()
        unregisterRecordingCallback()
        unregisterModeChangedListener()
        unregisterScreenReceiver()
        unregisterOnlyCameraAvailabilityCallback()
        try {
            focusCallbackThread?.quitSafely()
        } catch (_: Throwable) {}
        focusCallbackThread = null
        focusCallbackHandler = null
    }

    private fun cancelTransientVerificationChecksLocked() {
        if (pendingTransientCheckRunnables.isEmpty()) return
        for (runnable in pendingTransientCheckRunnables) {
            mainHandler.removeCallbacks(runnable)
        }
        pendingTransientCheckRunnables.clear()
    }

    private fun cancelPendingPlaybackDuckRestoreLocked() {
        pendingPlaybackDuckRestoreRunnable?.let {
            mainHandler.removeCallbacks(it)
            focusCallbackHandler?.removeCallbacks(it)
        }
        pendingPlaybackDuckRestoreRunnable = null
    }

    private fun clearDuckStateAndRestoreLocked() {
        cancelPendingPlaybackDuckRestoreLocked()
        if (!wasDuckedByTransientFocus) return
        val wasSystemHandled = systemHandledDuckingForNotification
        wasDuckedByTransientFocus = false
        duckStartedViaPlaybackObserver = false
        systemHandledDuckingForNotification = false
        if (!wasSystemHandled) {
            runUrgentOnMain { onRestoreVolumeRequested() }
        }
    }

    private fun isCallOrRingtoneActive(): Boolean {
        try {
            if (callActiveProvider?.invoke() == true) return true
        } catch (_: Throwable) {}

        val mode = try { audioManager?.mode ?: AudioManager.MODE_NORMAL } catch (_: Throwable) { AudioManager.MODE_NORMAL }
        if (mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            mode == 4 || // AudioManager.MODE_CALL_SCREENING
            mode == 5    // AudioManager.MODE_COMMUNICATION_REDIRECT
        ) {
            return true
        }

        // When AudioManager is in MODE_NORMAL and we actively hold audio focus, brief background
        // VOICE_COMMUNICATION / SIGNALLING streams or VOICE_COMMUNICATION mic probes from Bluetooth
        // earbuds (e.g. HFP keepalive), IMS, or voice assistants are not active phone calls.
        val allowVoiceCommInNormalMode = !hasAudioFocus

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val configs = try { audioManager?.activePlaybackConfigurations.orEmpty() } catch (_: Throwable) { emptyList() }
            for (config in configs) {
                val usage = config.audioAttributes?.usage ?: continue
                if (usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE ||
                    usage == AudioAttributes.USAGE_ALARM
                ) {
                    return true
                }
                if (allowVoiceCommInNormalMode && (
                        usage == AudioAttributes.USAGE_VOICE_COMMUNICATION ||
                        usage == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING ||
                        usage == AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST
                    )
                ) {
                    return true
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val recConfigs = try { audioManager?.activeRecordingConfigurations.orEmpty() } catch (_: Throwable) { emptyList() }
            for (rec in recConfigs) {
                val src = try { rec.clientAudioSource } catch (_: Throwable) { -1 }
                if (src == android.media.MediaRecorder.AudioSource.VOICE_CALL ||
                    src == android.media.MediaRecorder.AudioSource.VOICE_DOWNLINK ||
                    src == android.media.MediaRecorder.AudioSource.VOICE_UPLINK
                ) {
                    return true
                }
                if (allowVoiceCommInNormalMode &&
                    src == android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun isSystemNotificationOrRingStreamActive(lookbackMs: Int = 350): Boolean {
        return try {
            val audioSystemClass = Class.forName("android.media.AudioSystem")
            val isStreamActiveMethod = audioSystemClass.getMethod(
                "isStreamActive",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val notificationActive = (isStreamActiveMethod.invoke(null, AudioManager.STREAM_NOTIFICATION, lookbackMs) as? Boolean) == true
            val systemActive = (isStreamActiveMethod.invoke(null, AudioManager.STREAM_SYSTEM, lookbackMs) as? Boolean) == true
            notificationActive || systemActive
        } catch (_: Throwable) {
            false
        }
    }

    private fun isPlaybackConfigurationActive(config: AudioPlaybackConfiguration): Boolean {
        return try {
            val stateMethod = config.javaClass.getMethod("getPlayerState")
            val state = (stateMethod.invoke(config) as? Int) ?: -1
            // Exclude explicit RELEASED (0), PAUSED (3), STOPPED (4); allow STARTED (2) and redacted/test UNKNOWN (-1)
            state != 0 && state != 3 && state != 4
        } catch (_: Throwable) {
            true
        }
    }

    private fun hasNotificationConfigActive(overrideConfigs: List<AudioPlaybackConfiguration>? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val configs = overrideConfigs ?: try { audioManager?.activePlaybackConfigurations.orEmpty() } catch (_: Throwable) { emptyList() }
        for (config in configs) {
            if (!isPlaybackConfigurationActive(config)) continue
            val attrs = config.audioAttributes ?: continue
            when (attrs.usage) {
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
                AudioAttributes.USAGE_ALARM -> continue
                AudioAttributes.USAGE_NOTIFICATION,
                AudioAttributes.USAGE_NOTIFICATION_EVENT,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> return true
            }
            if (attrs.contentType == AudioAttributes.CONTENT_TYPE_SONIFICATION) {
                return true
            }
        }
        return false
    }

    private fun hasActiveNotificationOrSystemStream(): Boolean {
        if (isCallOrRingtoneActive()) return false
        if (isSystemNotificationOrRingStreamActive()) return true
        return hasNotificationConfigActive()
    }

    private fun isDeviceScreenInteractive(): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.isInteractive ?: true
        } catch (_: Throwable) {
            true
        }
    }

    private fun isDefaultDisplayOn(): Boolean {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            val display = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            display == null || display.state == android.view.Display.STATE_ON
        } catch (_: Throwable) {
            true
        }
    }

    private fun isKeyguardLocked(): Boolean {
        return try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            km?.isKeyguardLocked == true || km?.isDeviceLocked == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isDeviceIdleOrSleepingOrLocking(): Boolean {
        return !isDeviceScreenInteractive() || !isDefaultDisplayOn() || isKeyguardLocked()
    }

    private fun isFrontFacingCamera(cameraId: String): Boolean {
        return try {
            val characteristics = cameraManager?.getCameraCharacteristics(cameraId)
            val facing = characteristics?.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
            facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasExternalVideoOrMediaPlaying(): Boolean {
        try {
            externalVideoOrMediaActiveProvider?.let { return it.invoke() }
        } catch (_: Throwable) {}

        // When the phone screen is off / idle and no explicit test provider is set, the user is not
        // watching a video or playing a foreground game. Background focus losses while idle come from
        // notifications, hotspot client events, or download alerts.
        if (!isDeviceScreenInteractive() || !isDefaultDisplayOn()) {
            return false
        }

        // If a notification or system alert stream is currently active, do not misclassify short
        // notification audio tracks as external video/media playback.
        if (hasActiveNotificationOrSystemStream()) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val configs = try { audioManager?.activePlaybackConfigurations.orEmpty() } catch (_: Throwable) { emptyList() }
            if (configs.isEmpty()) return false

            val ownMediaCount = try { ownActiveMediaPlayerCountProvider() } catch (_: Throwable) { 0 }
            var totalMediaPlayers = 0

            for (config in configs) {
                val attrs = config.audioAttributes ?: continue
                val usage = attrs.usage
                val contentType = attrs.contentType

                // Ignore notification, ringtone, alarm, call, and sonification streams here
                // (calls/ringtones/alarms are handled by isCallOrRingtoneActive(),
                // and notifications are handled by hasActiveNotificationOrSystemStream()).
                if (contentType == AudioAttributes.CONTENT_TYPE_SONIFICATION) {
                    continue
                }
                when (usage) {
                    AudioAttributes.USAGE_NOTIFICATION,
                    AudioAttributes.USAGE_NOTIFICATION_EVENT,
                    AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
                    AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
                    AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
                    AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                    AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                    AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
                    AudioAttributes.USAGE_ALARM,
                    AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> continue
                }

                // OffGrid Music only uses USAGE_MEDIA + CONTENT_TYPE_MUSIC.
                // Any movie, speech, or game stream is guaranteed to be an external video/voice/game app.
                if (contentType == AudioAttributes.CONTENT_TYPE_MOVIE ||
                    contentType == AudioAttributes.CONTENT_TYPE_SPEECH ||
                    usage == AudioAttributes.USAGE_GAME
                ) {
                    return true
                }

                // Count explicit USAGE_MEDIA players (do not count USAGE_UNKNOWN, which OEM RingtonePlayer
                // and SoundPool notification chimes frequently emit).
                if (usage == AudioAttributes.USAGE_MEDIA) {
                    totalMediaPlayers++
                }
            }

            if (totalMediaPlayers > ownMediaCount) {
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
        if (hasActiveNotificationOrSystemStream()) return true
        if (hasExternalVideoOrMediaPlaying()) return false

        val explicitVideoProvider = externalVideoOrMediaActiveProvider
        if (explicitVideoProvider != null) {
            val ownMediaCount = try { ownActiveMediaPlayerCountProvider() } catch (_: Throwable) { 0 }
            return !explicitVideoProvider.invoke() && ownMediaCount > 0
        }

        // When the device screen is off / turning off / non-interactive, or keyguard is locking/locked,
        // or OffGrid Music itself is in the foreground (with no external video/media or phone call active),
        // any transient focus loss comes from a background system service (e.g. pre-sleep AttentionService /
        // Screen Off dim transition / SystemUI lock) or notification and must never pause music.
        if (isDeviceIdleOrSleepingOrLocking()) {
            return true
        }
        if (PlaybackManager.activeInstance?.takeIf { it.audioFocusManager === this@AudioFocusManager }?.isUiInForeground == true) {
            return true
        }

        return false
    }

    private fun hasSystemAlreadyDuckedMusicStreamLocked(): Boolean {
        val currentVol = try { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1 } catch (_: Throwable) { -1 }
        if (currentVol <= 0) return false
        val now = SystemClock.elapsedRealtime()
        if (recentMusicStreamDropAtMs > 0L &&
            (now - recentMusicStreamDropAtMs) <= 1200L &&
            musicStreamVolumeBeforeDrop > currentVol
        ) {
            return true
        }
        return baselineMusicStreamVolume > 0 &&
            currentVol < baselineMusicStreamVolume &&
            (now - baselineMusicStreamObservedAtMs) <= 1500L
    }

    private fun handleNotificationDuckOrContinueLocked(
        isExplicitDuckableFocus: Boolean = false,
        viaPlaybackObserver: Boolean = false
    ) {
        val shouldDuckVolume = reduceVolumeOnNotification && !isDeviceIdleOrSleepingOrLocking() && (
            isExplicitDuckableFocus ||
            hasActiveNotificationOrSystemStream() ||
            externalVideoOrMediaActiveProvider != null
        )
        if (shouldDuckVolume) {
            if (!viaPlaybackObserver) {
                // Focus loss supersedes playback-observer auto-restore until AUDIOFOCUS_GAIN or safety check
                duckStartedViaPlaybackObserver = false
                cancelPendingPlaybackDuckRestoreLocked()
            } else if (!wasDuckedByTransientFocus) {
                duckStartedViaPlaybackObserver = true
            }

            if (wasDuckedByTransientFocus) {
                // Already ducked for this notification episode (e.g. AudioPlaybackCallback caught it before
                // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK arrived): avoid redundant callbacks or double-ducking.
                scheduleDuckRestoreSafetyFallbackLocked()
                return
            }

            duckStartedAtMs = SystemClock.elapsedRealtime()
            wasDuckedByTransientFocus = true

            if (hasSystemAlreadyDuckedMusicStreamLocked()) {
                systemHandledDuckingForNotification = true
                Log.d(TAG, "Built-in OS notification ducking already active on speaker stream; skipping redundant in-app duck")
            } else {
                systemHandledDuckingForNotification = false
                Log.d(TAG, "Notification/hotspot detected (playbackObserver=$viaPlaybackObserver): immediately ducking volume to 60% without pausing")
                runUrgentOnMain { onDuckRequested(DEFAULT_NOTIFICATION_DUCK_VOLUME) }
            }
            scheduleDuckRestoreSafetyFallbackLocked()
        } else {
            clearDuckStateAndRestoreLocked()
            Log.d(TAG, "Notification focus loss with ducking OFF or silent system event: continuing playback at 100% volume without pause")
        }
    }

    private fun scheduleQuickPlaybackObserverRestoreLocked() {
        cancelPendingPlaybackDuckRestoreLocked()
        val elapsed = (SystemClock.elapsedRealtime() - duckStartedAtMs).coerceAtLeast(0L)
        val delayMs = maxOf(160L, 320L - elapsed)
        val handler = focusCallbackHandler?.takeIf { focusCallbackThread?.isAlive == true } ?: mainHandler
        val runnable = Runnable {
            synchronized(this@AudioFocusManager) {
                pendingPlaybackDuckRestoreRunnable = null
                if (!wasDuckedByTransientFocus || !duckStartedViaPlaybackObserver || activeInterruptions.isNotEmpty()) {
                    return@synchronized
                }
                if (isCallOrRingtoneActive()) {
                    clearDuckStateAndRestoreLocked()
                    cancelTransientVerificationChecksLocked()
                    beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                    return@synchronized
                }
                if (hasNotificationConfigActive() || isSystemNotificationOrRingStreamActive(lookbackMs = 120)) {
                    // Reschedule briefly while notification sound is still finishing
                    scheduleQuickPlaybackObserverRestoreLocked()
                    return@synchronized
                }
                clearDuckStateAndRestoreLocked()
            }
        }
        pendingPlaybackDuckRestoreRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun scheduleDuckRestoreSafetyFallbackLocked() {
        val checkDelaysMs = longArrayOf(650L, 1300L, 2200L)
        for (delayMs in checkDelaysMs) {
            val restoreSafetyRunnable = Runnable {
                synchronized(this@AudioFocusManager) {
                    if (wasDuckedByTransientFocus && activeInterruptions.isEmpty() && !hasActiveNotificationOrSystemStream()) {
                        if (isCallOrRingtoneActive()) {
                            clearDuckStateAndRestoreLocked()
                            beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                        } else {
                            clearDuckStateAndRestoreLocked()
                        }
                    }
                }
            }
            pendingTransientCheckRunnables.add(restoreSafetyRunnable)
            mainHandler.postDelayed(restoreSafetyRunnable, delayMs)
        }
    }

    private fun scheduleTransientCallVerificationOnlyLocked() {
        cancelTransientVerificationChecksLocked()

        val checkDelaysMs = longArrayOf(80L, 250L, 600L, 1200L)
        for (delayMs in checkDelaysMs) {
            val checkRunnable = Runnable {
                synchronized(this@AudioFocusManager) {
                    if (hasAudioFocus || activeInterruptions.isNotEmpty()) return@synchronized
                    if (isCallOrRingtoneActive()) {
                        clearDuckStateAndRestoreLocked()
                        cancelTransientVerificationChecksLocked()
                        Log.d(TAG, "Deferred check detected active call; pausing playback")
                        beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                    }
                }
            }
            pendingTransientCheckRunnables.add(checkRunnable)
            mainHandler.postDelayed(checkRunnable, delayMs)
        }

        // Safety fallback: if a notification never dispatches AUDIOFOCUS_GAIN after its chime ends,
        // restore ducked volume once no notification stream remains active.
        scheduleDuckRestoreSafetyFallbackLocked()
    }

    private fun scheduleTransientVideoOrCallVerificationLocked() {
        cancelTransientVerificationChecksLocked()

        val checkDelaysMs = longArrayOf(80L, 200L, 400L, 750L, 1200L, 1800L, 2500L)
        for (delayMs in checkDelaysMs) {
            val checkRunnable = Runnable {
                synchronized(this@AudioFocusManager) {
                    if (hasAudioFocus || activeInterruptions.isNotEmpty()) return@synchronized
                    val callNowActive = isCallOrRingtoneActive()
                    val notificationStreamActive = hasActiveNotificationOrSystemStream()
                    val videoNowActive = !notificationStreamActive && hasExternalVideoOrMediaPlaying()
                    if (callNowActive) {
                        clearDuckStateAndRestoreLocked()
                        cancelTransientVerificationChecksLocked()
                        Log.d(TAG, "Deferred check detected active call; pausing playback")
                        beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                    } else if (videoNowActive && autoPauseSystemAudio) {
                        clearDuckStateAndRestoreLocked()
                        cancelTransientVerificationChecksLocked()
                        Log.d(TAG, "Deferred check detected external video/media stream; pausing playback")
                        beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                    }
                }
            }
            pendingTransientCheckRunnables.add(checkRunnable)
            mainHandler.postDelayed(checkRunnable, delayMs)
        }

        scheduleDuckRestoreSafetyFallbackLocked()
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
                if (isCallOrRingtoneActive()) {
                    clearDuckStateAndRestoreLocked()
                    Log.d(TAG, "Audio focus loss during call/ringtone, pausing playback")
                    beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                    return
                }
                if (hasActiveNotificationOrSystemStream() && !hasExternalVideoOrMediaPlaying()) {
                    handleNotificationDuckOrContinueLocked()
                    scheduleTransientCallVerificationOnlyLocked()
                    return
                }
                clearDuckStateAndRestoreLocked()
                if (autoPauseSystemAudio) {
                    Log.d(TAG, "Audio focus loss with autoPauseSystemAudio=true, pausing and awaiting potential focus return")
                    beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_LOSS)
                } else {
                    Log.d(TAG, "Mandatory permanent audio focus loss with autoPauseSystemAudio=false")
                    val isCurrentlyPlaying = try { isPlayingProvider() } catch (_: Throwable) { false }
                    activeInterruptions.clear()
                    wasPlayingBeforeInterruption = false
                    if (isCurrentlyPlaying) {
                        runUrgentOnMain { onPauseRequested() }
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                cancelTransientVerificationChecksLocked()
                hasAudioFocus = false
                val isPhoneCall = isCallOrRingtoneActive()
                val isCameraActive = activeCameraIds.isNotEmpty() ||
                    (!autoPauseCamera && hardwareUnavailableCameraIds.isNotEmpty())
                val externalVideoPlayingNow = hasExternalVideoOrMediaPlaying()

                when {
                    isPhoneCall -> {
                        clearDuckStateAndRestoreLocked()
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
                        // Notification / system alert transient focus loss: never pause via autoPauseSystemAudio!
                        // Only duck by 40% (if reduceVolumeOnNotification is ON) or continue at 100% volume.
                        handleNotificationDuckOrContinueLocked()
                        scheduleTransientCallVerificationOnlyLocked()
                    }
                    autoPauseSystemAudio -> {
                        val liveManager = PlaybackManager.activeInstance?.takeIf { it.audioFocusManager === this@AudioFocusManager }
                        val justLeftForeground = liveManager != null &&
                            (android.os.SystemClock.elapsedRealtime() - liveManager.lastBackgroundTransitionAtMs) in 0L..2500L
                        val shouldConfirmNotPreSleep = liveManager != null &&
                            externalVideoOrMediaActiveProvider == null &&
                            !externalVideoPlayingNow
                        if (shouldConfirmNotPreSleep) {
                            val confirmDelayMs = if (justLeftForeground) 550L else 300L
                            val confirmRunnable = Runnable {
                                synchronized(this@AudioFocusManager) {
                                    if (hasAudioFocus || activeInterruptions.isNotEmpty() || !autoPauseSystemAudio) {
                                        return@synchronized
                                    }
                                    if (isCallOrRingtoneActive()) {
                                        clearDuckStateAndRestoreLocked()
                                        beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                                        return@synchronized
                                    }
                                    if (isDeviceIdleOrSleepingOrLocking() || PlaybackManager.activeInstance?.takeIf { it.audioFocusManager === this@AudioFocusManager }?.isUiInForeground == true) {
                                        Log.d(TAG, "Suppressed pre-sleep/idle transient focus loss without interrupting playback")
                                        scheduleTransientVideoOrCallVerificationLocked()
                                        return@synchronized
                                    }
                                    clearDuckStateAndRestoreLocked()
                                    Log.d(TAG, "Confirmed external transient audio focus loss, pausing playback")
                                    beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                                }
                            }
                            pendingTransientCheckRunnables.add(confirmRunnable)
                            mainHandler.postDelayed(confirmRunnable, confirmDelayMs)
                        } else {
                            clearDuckStateAndRestoreLocked()
                            Log.d(TAG, "Transient video/system/call audio focus loss with autoPauseSystemAudio=true, pausing playback")
                            beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                        }
                    }
                    else -> {
                        // Even if autoPauseSystemAudio is false, verify if a phone/WhatsApp call is starting up
                        scheduleTransientCallVerificationOnlyLocked()
                        Log.d(TAG, "Ignoring transient system audio focus loss (autoPauseSystemAudio=false)")
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                cancelTransientVerificationChecksLocked()
                hasAudioFocus = false
                val isPhoneCall = isCallOrRingtoneActive()
                val isCameraActive = activeCameraIds.isNotEmpty() ||
                    (!autoPauseCamera && hardwareUnavailableCameraIds.isNotEmpty())
                val explicitExternalVideo = try { externalVideoOrMediaActiveProvider?.invoke() == true } catch (_: Throwable) { false }

                when {
                    isPhoneCall -> {
                        clearDuckStateAndRestoreLocked()
                        beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                    }
                    isCameraActive && !autoPauseCamera -> {
                        Log.d(TAG, "Ignoring camera-correlated duckable focus loss because autoPauseCamera=false")
                    }
                    explicitExternalVideo && autoPauseSystemAudio -> {
                        clearDuckStateAndRestoreLocked()
                        beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                    }
                    else -> {
                        // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK is requested by notifications and short alerts.
                        // Never pause playback via autoPauseSystemAudio here — only duck by 40% (to 60% of song volume)
                        // when reduceVolumeOnNotification is enabled, or continue at full volume when disabled.
                        handleNotificationDuckOrContinueLocked(isExplicitDuckableFocus = true)
                        scheduleTransientCallVerificationOnlyLocked()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus restored")
                cancelTransientVerificationChecksLocked()
                hasAudioFocus = true
                clearDuckStateAndRestoreLocked()
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
        if (source == InterruptionSource.CAMERA || source == InterruptionSource.SYSTEM_AUDIO_TRANSIENT) {
            lastCameraOrTransientInterruptionAtMs = android.os.SystemClock.elapsedRealtime()
        }

        if (currentlyPlaying) {
            runUrgentOnMain { onPauseRequested() }
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
                runUrgentOnMain { onResumeRequested() }
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
                        .setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(focusChangeListener, getOrCreateFocusCallbackHandler())
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
        cancelPendingPlaybackDuckRestoreLocked()
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
            duckStartedViaPlaybackObserver = false
            systemHandledDuckingForNotification = false
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

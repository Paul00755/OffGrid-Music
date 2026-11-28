package com.example.service

import android.content.Context
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
 * Manages audio focus and external system/app playback interruptions:
 * - [autoPauseSystemAudio]: Pause playback on system/app audio interruptions (videos, Reels, Shorts, alarms, timers, calls, camera video recording) and auto-resume when ended.
 * - [reduceVolumeOnNotification]: Duck music volume smoothly during notifications without stacking with built-in OEM ducking.
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
    var onResumeAfterCallRequested: (() -> Unit)? = null,
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
        SYSTEM_AUDIO_TRANSIENT,
        SYSTEM_AUDIO_LOSS,
        PHONE_CALL
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var focusCallbackThread: HandlerThread? = null
    @Volatile private var focusCallbackHandler: Handler? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile var hasAudioFocus: Boolean = false
        private set

    private val activeInterruptions = mutableSetOf<InterruptionSource>()
    private val pendingTransientCheckRunnables = mutableListOf<Runnable>()
    private var pendingPlaybackDuckRestoreRunnable: Runnable? = null
    private var pendingInterruptionMonitorRunnable: Runnable? = null
    private var pendingPostCallResumeRunnable: Runnable? = null
    private var postCallResumePollCount: Int = 0
    private var interruptionMonitorPollCount: Int = 0

    @Volatile var wasPlayingBeforeInterruption: Boolean = false
        private set
    @Volatile private var wasInterruptedByPhoneCall: Boolean = false
    @Volatile var userManuallyPausedOrStopped: Boolean = false
        private set
    @Volatile var userOverrodeInterruption: Boolean = false
        private set
    @Volatile private var lastUserPlayRequestAtMs: Long = -10_000L
    @Volatile private var observedExternalAudioDuringInterruption: Boolean = false
    @Volatile private var externalAudioSilentSinceMs: Long = 0L
    @Volatile private var wasDuckedByTransientFocus: Boolean = false
    @Volatile private var duckStartedViaPlaybackObserver: Boolean = false
    @Volatile private var duckStartedAtMs: Long = 0L
    @Volatile private var systemHandledDuckingForNotification: Boolean = false
    @Volatile private var baselineMusicStreamVolume: Int = -1
    @Volatile private var baselineMusicStreamObservedAtMs: Long = 0L
    @Volatile private var recentMusicStreamDropAtMs: Long = 0L
    @Volatile private var musicStreamVolumeBeforeDrop: Int = -1
    @Volatile private var playbackCallbackRegistered: Boolean = false
    @Volatile private var recordingCallbackRegistered: Boolean = false
    @Volatile private var modeListenerRegistered: Boolean = false
    @Volatile private var screenReceiverRegistered: Boolean = false
    @Volatile private var lastTransientInterruptionAtMs: Long = 0L

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
            mainHandler.post {
                try { block() } catch (_: Throwable) {}
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
                        clearDuckStateAndRestoreLocked()
                    }
                }
            }
        }

    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) {
                synchronized(this@AudioFocusManager) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val wasRecentPreSleepTrigger = (now - lastTransientInterruptionAtMs) in 0L..4000L
                    if (wasRecentPreSleepTrigger &&
                        !observedExternalAudioDuringInterruption &&
                        activeInterruptions.contains(InterruptionSource.SYSTEM_AUDIO_TRANSIENT) &&
                        !isCallOrRingtoneActive() &&
                        !hasExternalVideoOrMediaPlaying()
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
                    // Pass null so real OS callbacks never get treated as explicit test overrideConfigs.
                    onActivePlaybackConfigurationsChanged(null)
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
        if (userOverrodeInterruption) {
            if (!isCallMode && !isCallOrRingtoneActive() && !hasExternalVideoOrMediaPlaying()) {
                userOverrodeInterruption = false
            } else {
                return
            }
        }
        if (isCallMode) {
            observedExternalAudioDuringInterruption = true
            externalAudioSilentSinceMs = 0L
            cancelInterruptionMonitorLocked()
            if (!activeInterruptions.contains(InterruptionSource.PHONE_CALL)) {
                clearDuckStateAndRestoreLocked()
                cancelTransientVerificationChecksLocked()
                Log.d(TAG, "AudioModeChangedListener detected call/ringtone mode ($mode); pausing playback")
                beginInterruptionLocked(InterruptionSource.PHONE_CALL)
            }
            scheduleInterruptionMonitorLocked(600L)
        } else if (mode == AudioManager.MODE_NORMAL && activeInterruptions.contains(InterruptionSource.PHONE_CALL)) {
            mainHandler.postDelayed({
                synchronized(this@AudioFocusManager) {
                    if (activeInterruptions.contains(InterruptionSource.PHONE_CALL) &&
                        !isCallOrRingtoneActive() &&
                        !hasExternalVideoOrMediaPlaying()
                    ) {
                        if (hasAudioFocus || requestAudioFocus()) {
                            Log.d(TAG, "Call mode returned to MODE_NORMAL; ending phone call interruption")
                            endAudioFocusInterruptionsLocked()
                        } else {
                            scheduleInterruptionMonitorLocked(450L)
                        }
                    }
                }
            }, 350L)
        }
    }

    @Synchronized
    private fun onActiveRecordingConfigurationsChanged() {
        val callNowActive = isCallOrRingtoneActive()
        if (userOverrodeInterruption) {
            if (!callNowActive && !hasExternalVideoOrMediaPlaying()) {
                userOverrodeInterruption = false
            } else {
                return
            }
        }
        if (callNowActive) {
            observedExternalAudioDuringInterruption = true
            externalAudioSilentSinceMs = 0L
            if (!activeInterruptions.contains(InterruptionSource.PHONE_CALL)) {
                clearDuckStateAndRestoreLocked()
                cancelTransientVerificationChecksLocked()
                Log.d(TAG, "RecordingCallback detected active call/VoIP mic; pausing playback")
                beginInterruptionLocked(InterruptionSource.PHONE_CALL)
            }
            scheduleInterruptionMonitorLocked(600L)
        } else if (activeInterruptions.contains(InterruptionSource.PHONE_CALL)) {
            if (hasAudioFocus && !hasExternalVideoOrMediaPlaying()) {
                Log.d(TAG, "RecordingCallback detected call mic closed with focus held; resuming")
                endAudioFocusInterruptionsLocked()
            } else {
                scheduleInterruptionMonitorLocked(450L)
            }
        }
    }

    @Synchronized
    internal fun onActivePlaybackConfigurationsChanged(configs: List<AudioPlaybackConfiguration>? = null) {
        val callNowActive = isCallOrRingtoneActive(configs)
        val videoNowActive = hasExternalVideoOrMediaPlaying(configs)
        if (userOverrodeInterruption) {
            val withinPlayGrace = (SystemClock.elapsedRealtime() - lastUserPlayRequestAtMs) in 0L..1500L
            if (!callNowActive && !videoNowActive && !withinPlayGrace) {
                userOverrodeInterruption = false
            } else if (callNowActive || videoNowActive) {
                return
            }
        }
        if (callNowActive) {
            observedExternalAudioDuringInterruption = true
            externalAudioSilentSinceMs = 0L
            if (!activeInterruptions.contains(InterruptionSource.PHONE_CALL)) {
                clearDuckStateAndRestoreLocked()
                cancelTransientVerificationChecksLocked()
                Log.d(TAG, "PlaybackCallback detected active call/ringtone/alarm; pausing playback")
                beginInterruptionLocked(InterruptionSource.PHONE_CALL)
            }
            scheduleInterruptionMonitorLocked(600L)
            return
        } else if (activeInterruptions.contains(InterruptionSource.PHONE_CALL)) {
            if (hasAudioFocus && !videoNowActive) {
                endAudioFocusInterruptionsLocked()
                return
            } else {
                scheduleInterruptionMonitorLocked(450L)
            }
        }

        if (videoNowActive) {
            observedExternalAudioDuringInterruption = true
            externalAudioSilentSinceMs = 0L
            if (activeInterruptions.isNotEmpty()) {
                scheduleInterruptionMonitorLocked(600L)
                return
            }
            if (autoPauseSystemAudio) {
                clearDuckStateAndRestoreLocked()
                cancelTransientVerificationChecksLocked()
                Log.d(TAG, "PlaybackCallback detected external video/media stream; pausing playback")
                beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                scheduleInterruptionMonitorLocked(600L)
                return
            }
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

        if (activeInterruptions.isNotEmpty() &&
            observedExternalAudioDuringInterruption &&
            (activeInterruptions.contains(InterruptionSource.SYSTEM_AUDIO_LOSS) ||
                activeInterruptions.contains(InterruptionSource.SYSTEM_AUDIO_TRANSIENT))
        ) {
            scheduleInterruptionMonitorLocked(850L)
        }
    }

    /**
     * Lightweight poll invoked from PlaybackManager's active playback loop to guarantee
     * that calls, alarms, or external video/Reels/Shorts streams pause music promptly even on
     * OEM ROMs or apps that start/unmute playback without re-dispatching audio focus callbacks,
     * and to keep baseline speaker volume calibrated for OEM ducking detection.
     */
    @Synchronized
    fun pollActiveCallStateWhilePlaying() {
        val currentVol = try { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1 } catch (_: Throwable) { -1 }
        if (currentVol >= 0) {
            recordMusicStreamVolumeLocked(currentVol)
        }
        val callNowActive = isCallOrRingtoneActive()
        val videoNowActive = hasExternalVideoOrMediaPlaying()
        if (userOverrodeInterruption) {
            val withinPlayGrace = (SystemClock.elapsedRealtime() - lastUserPlayRequestAtMs) in 0L..1500L
            if (!callNowActive && !videoNowActive && !withinPlayGrace) {
                userOverrodeInterruption = false
            } else if (callNowActive || videoNowActive) {
                return
            }
        }
        if (activeInterruptions.contains(InterruptionSource.PHONE_CALL)) return
        if (callNowActive) {
            clearDuckStateAndRestoreLocked()
            cancelTransientVerificationChecksLocked()
            observedExternalAudioDuringInterruption = true
            externalAudioSilentSinceMs = 0L
            Log.d(TAG, "Active call/ringtone detected during playback poll; pausing playback")
            beginInterruptionLocked(InterruptionSource.PHONE_CALL)
            scheduleInterruptionMonitorLocked(600L)
            return
        }
        if (autoPauseSystemAudio &&
            !activeInterruptions.contains(InterruptionSource.SYSTEM_AUDIO_TRANSIENT) &&
            !activeInterruptions.contains(InterruptionSource.SYSTEM_AUDIO_LOSS) &&
            videoNowActive
        ) {
            clearDuckStateAndRestoreLocked()
            cancelTransientVerificationChecksLocked()
            observedExternalAudioDuringInterruption = true
            externalAudioSilentSinceMs = 0L
            Log.d(TAG, "External video/media playback detected during playback poll; pausing playback")
            beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
            scheduleInterruptionMonitorLocked(600L)
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

    fun unregisterCameraCallback() {
        cancelTransientVerificationChecksLocked()
        cancelInterruptionMonitorLocked()
        cancelPostCallSettledResumeLocked()
        cancelPendingPlaybackDuckRestoreLocked()
        unregisterPlaybackCallback()
        unregisterRecordingCallback()
        unregisterModeChangedListener()
        unregisterScreenReceiver()
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

    private fun cancelInterruptionMonitorLocked() {
        pendingInterruptionMonitorRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingInterruptionMonitorRunnable = null
        interruptionMonitorPollCount = 0
    }

    private fun cancelPostCallSettledResumeLocked() {
        pendingPostCallResumeRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingPostCallResumeRunnable = null
        postCallResumePollCount = 0
    }

    private fun isBluetoothScoOrCommRouteActive(): Boolean {
        val mgr = audioManager ?: return false
        try {
            @Suppress("DEPRECATION")
            if (mgr.isBluetoothScoOn) return true
        } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val commType = mgr.communicationDevice?.type
                if (commType == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    commType == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    commType == android.media.AudioDeviceInfo.TYPE_HEARING_AID
                ) {
                    return true
                }
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun isBluetoothAudioConnected(): Boolean {
        val mgr = audioManager ?: return false
        try {
            @Suppress("DEPRECATION")
            if (mgr.isBluetoothA2dpOn || mgr.isBluetoothScoOn) return true
        } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val outputs = mgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                if (outputs.any {
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
                    }
                ) {
                    return true
                }
            } catch (_: Throwable) {}
        }
        return false
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

    private fun isCallOrRingtoneActive(overrideConfigs: List<AudioPlaybackConfiguration>? = null): Boolean {
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

        if (overrideConfigs == null && isSystemAlarmOrRingStreamActive()) {
            return true
        }

        // Only treat USAGE_ALARM and USAGE_NOTIFICATION_RINGTONE playback configs or cellular
        // VOICE_CALL recording sources as call/alarm interruptions when AudioManager.mode is MODE_NORMAL.
        // Background browsers (Brave, Chrome WebRTC) and social apps hold USAGE_VOICE_COMMUNICATION
        // audio/mic configurations in MODE_NORMAL when no call is active.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val configs = overrideConfigs ?: try { audioManager?.activePlaybackConfigurations.orEmpty() } catch (_: Throwable) { emptyList() }
            for (config in configs) {
                if (!isPlaybackConfigurationActive(config)) continue
                val usage = config.audioAttributes?.usage ?: continue
                if (usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE ||
                    usage == AudioAttributes.USAGE_ALARM
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
            }
        }
        return false
    }

    private fun isSystemAlarmOrRingStreamActive(lookbackMs: Int = 250): Boolean {
        return try {
            val audioSystemClass = Class.forName("android.media.AudioSystem")
            val isStreamActiveMethod = audioSystemClass.getMethod(
                "isStreamActive",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val alarmActive = (isStreamActiveMethod.invoke(null, AudioManager.STREAM_ALARM, lookbackMs) as? Boolean) == true
            val ringActive = (isStreamActiveMethod.invoke(null, AudioManager.STREAM_RING, lookbackMs) as? Boolean) == true
            alarmActive || ringActive
        } catch (_: Throwable) {
            false
        }
    }

    private fun isSystemNotificationOrRingStreamActive(lookbackMs: Int = 350): Boolean {
        return try {
            val audioSystemClass = Class.forName("android.media.AudioSystem")
            val isStreamActiveMethod = audioSystemClass.getMethod(
                "isStreamActive",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            // Check STREAM_NOTIFICATION only (not STREAM_SYSTEM, which fires on every screen/unmute touch tap).
            (isStreamActiveMethod.invoke(null, AudioManager.STREAM_NOTIFICATION, lookbackMs) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun getPlaybackConfigurationPlayerState(config: AudioPlaybackConfiguration): Int {
        try {
            val stateMethod = config.javaClass.getMethod("getPlayerState")
            val state = stateMethod.invoke(config) as? Int
            if (state != null) return state
        } catch (_: Throwable) {}
        return try {
            val stateField = config.javaClass.getDeclaredField("mPlayerState")
            stateField.isAccessible = true
            stateField.getInt(config)
        } catch (_: Throwable) {
            -1
        }
    }

    private fun isPlaybackConfigurationActive(config: AudioPlaybackConfiguration): Boolean {
        // Require explicit PLAYER_STATE_STARTED (2).
        // On Android devices where getPlayerState() is a hidden @SystemApi (returning -1 or throwing)
        // or when a browser tab (e.g. Chrome/AI Studio) holds an idle (1) or paused (3) AudioTrack,
        // treating unknown (-1) or idle (1) states as active causes false auto-pauses while holding audio focus.
        return getPlaybackConfigurationPlayerState(config) == 2
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

    private fun hasExternalVideoOrMediaPlaying(overrideConfigs: List<AudioPlaybackConfiguration>? = null): Boolean {
        try {
            externalVideoOrMediaActiveProvider?.let { return it.invoke() }
        } catch (_: Throwable) {}

        // On real devices (when no explicit test overrideConfigs are supplied), rely on standard
        // Android AudioFocus callbacks rather than guessing from activePlaybackConfigurations,
        // because background browsers (e.g. Brave, Chrome) keep active USAGE_MEDIA / WebAudio
        // player configurations even when silent or in the background.
        if (overrideConfigs == null) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val configs = overrideConfigs
            if (configs.isEmpty()) return false

            val ownMediaCount = try { ownActiveMediaPlayerCountProvider() } catch (_: Throwable) { 0 }
            val currentlyPlaying = try { isPlayingProvider() } catch (_: Throwable) { false }
            val effectiveOwnMediaCount = maxOf(ownMediaCount, if (currentlyPlaying) 1 else 0)
            var totalMediaPlayers = 0

            for (config in configs) {
                if (!isPlaybackConfigurationActive(config)) continue
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

            if (totalMediaPlayers > effectiveOwnMediaCount) {
                return true
            }
        }
        return false
    }

    /**
     * Determines whether a transient audio focus loss was triggered by a notification / idle sleep blip
     * rather than an external video, reel/short, alarm/timer, or call.
     */
    private fun isNotificationOrSystemAlertTransientLoss(): Boolean {
        if (isCallOrRingtoneActive()) return false
        if (hasExternalVideoOrMediaPlaying()) return false
        if (hasActiveNotificationOrSystemStream()) return true

        val explicitVideoProvider = externalVideoOrMediaActiveProvider
        if (explicitVideoProvider != null) {
            val ownMediaCount = try { ownActiveMediaPlayerCountProvider() } catch (_: Throwable) { 0 }
            return !explicitVideoProvider.invoke() && ownMediaCount > 0
        }

        // Only when the device screen is genuinely off / turning off / locked AND no call, alarm,
        // or external media is active should a transient focus loss be ignored as a pre-sleep/lockscreen
        // system blip (while still arming deferred call/alarm/video verification).
        if (isDeviceIdleOrSleepingOrLocking()) {
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
                        observedExternalAudioDuringInterruption = true
                        externalAudioSilentSinceMs = 0L
                        Log.d(TAG, "Deferred check detected active call; pausing playback")
                        beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                        scheduleInterruptionMonitorLocked(600L)
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
                    val videoNowActive = hasExternalVideoOrMediaPlaying()
                    if (callNowActive) {
                        clearDuckStateAndRestoreLocked()
                        cancelTransientVerificationChecksLocked()
                        observedExternalAudioDuringInterruption = true
                        externalAudioSilentSinceMs = 0L
                        Log.d(TAG, "Deferred check detected active call; pausing playback")
                        beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                        scheduleInterruptionMonitorLocked(600L)
                    } else if (videoNowActive && autoPauseSystemAudio) {
                        clearDuckStateAndRestoreLocked()
                        cancelTransientVerificationChecksLocked()
                        observedExternalAudioDuringInterruption = true
                        externalAudioSilentSinceMs = 0L
                        Log.d(TAG, "Deferred check detected external video/media stream; pausing playback")
                        beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                        scheduleInterruptionMonitorLocked(600L)
                    }
                }
            }
            pendingTransientCheckRunnables.add(checkRunnable)
            mainHandler.postDelayed(checkRunnable, delayMs)
        }

        scheduleDuckRestoreSafetyFallbackLocked()
    }

    private fun scheduleInterruptionMonitorLocked(delayMs: Long = 500L) {
        pendingInterruptionMonitorRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingInterruptionMonitorRunnable = null

        val hasAudioInterruption =
            activeInterruptions.contains(InterruptionSource.PHONE_CALL) ||
                activeInterruptions.contains(InterruptionSource.SYSTEM_AUDIO_LOSS) ||
                activeInterruptions.contains(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
        if (!hasAudioInterruption || !wasPlayingBeforeInterruption || userManuallyPausedOrStopped) {
            interruptionMonitorPollCount = 0
            return
        }
        if (try { isSleepTimerStoppedProvider() } catch (_: Throwable) { false }) {
            interruptionMonitorPollCount = 0
            return
        }

        val runnable = Runnable {
            synchronized(this@AudioFocusManager) {
                pendingInterruptionMonitorRunnable = null
                val stillHasAudioInterruption =
                    activeInterruptions.contains(InterruptionSource.PHONE_CALL) ||
                        activeInterruptions.contains(InterruptionSource.SYSTEM_AUDIO_LOSS) ||
                        activeInterruptions.contains(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                if (!stillHasAudioInterruption || !wasPlayingBeforeInterruption || userManuallyPausedOrStopped) {
                    interruptionMonitorPollCount = 0
                    return@synchronized
                }
                if (try { isSleepTimerStoppedProvider() } catch (_: Throwable) { false }) {
                    interruptionMonitorPollCount = 0
                    return@synchronized
                }

                val now = SystemClock.elapsedRealtime()
                val callNowActive = isCallOrRingtoneActive()
                val videoNowActive = hasExternalVideoOrMediaPlaying()
                val notificationNowActive = !videoNowActive && hasActiveNotificationOrSystemStream()

                if (callNowActive) {
                    observedExternalAudioDuringInterruption = true
                    externalAudioSilentSinceMs = 0L
                    interruptionMonitorPollCount = 0
                    if (!activeInterruptions.contains(InterruptionSource.PHONE_CALL)) {
                        activeInterruptions.add(InterruptionSource.PHONE_CALL)
                    }
                    wasInterruptedByPhoneCall = true
                    scheduleInterruptionMonitorLocked(600L)
                    return@synchronized
                }

                if (videoNowActive) {
                    observedExternalAudioDuringInterruption = true
                    externalAudioSilentSinceMs = 0L
                    interruptionMonitorPollCount = 0
                    scheduleInterruptionMonitorLocked(600L)
                    return@synchronized
                }

                val isPhoneOrAlarm = activeInterruptions.contains(InterruptionSource.PHONE_CALL)
                val settleMs = when {
                    isPhoneOrAlarm -> 450L
                    observedExternalAudioDuringInterruption -> 850L
                    else -> -1L
                }

                if (settleMs < 0L) {
                    // External audio hasn't appeared yet (e.g. delayed AudioTrack startup or permanent loss to another app).
                    // Poll briefly up to ~7.5s, after which onActivePlaybackConfigurationsChanged / AUDIOFOCUS_GAIN re-arms.
                    interruptionMonitorPollCount++
                    if (interruptionMonitorPollCount <= 15) {
                        scheduleInterruptionMonitorLocked(500L)
                    } else {
                        interruptionMonitorPollCount = 0
                    }
                    return@synchronized
                }

                if (notificationNowActive) {
                    externalAudioSilentSinceMs = 0L
                    scheduleInterruptionMonitorLocked(400L)
                    return@synchronized
                }

                if (externalAudioSilentSinceMs == 0L) {
                    externalAudioSilentSinceMs = now
                    scheduleInterruptionMonitorLocked(settleMs)
                    return@synchronized
                }

                val elapsedSilent = now - externalAudioSilentSinceMs
                if (elapsedSilent < settleMs) {
                    scheduleInterruptionMonitorLocked((settleMs - elapsedSilent).coerceAtLeast(100L))
                    return@synchronized
                }

                // External audio / call / alarm has genuinely stopped and settled -> re-acquire focus and resume once
                if (!hasAudioFocus) {
                    requestAudioFocus()
                }
                if (hasAudioFocus) {
                    Log.d(TAG, "Interruption monitor confirmed external audio/call ended; resuming playback")
                    endAudioFocusInterruptionsLocked()
                } else {
                    externalAudioSilentSinceMs = now
                    scheduleInterruptionMonitorLocked(700L)
                }
            }
        }
        pendingInterruptionMonitorRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs.coerceAtLeast(50L))
    }

    /**
     * Handles Android AudioManager focus change events.
     */
    @Synchronized
    fun dispatchFocusChange(focusChange: Int) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            val now = SystemClock.elapsedRealtime()
            if (userOverrodeInterruption && (now - lastUserPlayRequestAtMs) in 0L..1500L && !isCallOrRingtoneActive()) {
                Log.d(TAG, "Ignoring immediate focus loss bounce-back ($focusChange) right after manual user play")
                hasAudioFocus = true
                return
            }
            userOverrodeInterruption = false
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                cancelTransientVerificationChecksLocked()
                cancelInterruptionMonitorLocked()
                hasAudioFocus = false
                if (isCallOrRingtoneActive()) {
                    clearDuckStateAndRestoreLocked()
                    Log.d(TAG, "Audio focus loss during call/ringtone/alarm, pausing playback")
                    beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                    scheduleInterruptionMonitorLocked(600L)
                    return
                }
                if (hasActiveNotificationOrSystemStream() && !hasExternalVideoOrMediaPlaying()) {
                    handleNotificationDuckOrContinueLocked()
                    scheduleTransientVideoOrCallVerificationLocked()
                    return
                }
                clearDuckStateAndRestoreLocked()
                if (autoPauseSystemAudio) {
                    Log.d(TAG, "Audio focus loss with autoPauseSystemAudio=true, pausing and monitoring for end of interruption")
                    beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_LOSS)
                    scheduleInterruptionMonitorLocked(500L)
                } else {
                    Log.d(TAG, "Mandatory permanent audio focus loss with autoPauseSystemAudio=false")
                    val isCurrentlyPlaying = try { isPlayingProvider() } catch (_: Throwable) { false }
                    activeInterruptions.clear()
                    wasPlayingBeforeInterruption = false
                    observedExternalAudioDuringInterruption = false
                    externalAudioSilentSinceMs = 0L
                    if (isCurrentlyPlaying) {
                        runUrgentOnMain { onPauseRequested() }
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                cancelTransientVerificationChecksLocked()
                cancelInterruptionMonitorLocked()
                hasAudioFocus = false
                val isPhoneCall = isCallOrRingtoneActive()

                when {
                    isPhoneCall -> {
                        clearDuckStateAndRestoreLocked()
                        Log.d(TAG, "Phone call / alarm audio focus loss, pausing playback")
                        beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                        scheduleInterruptionMonitorLocked(600L)
                    }
                    isNotificationOrSystemAlertTransientLoss() -> {
                        // Notification / idle pre-sleep transient focus loss: never pause upfront!
                        // Still verify deferred call/alarm/video start in case playback follows focus acquisition.
                        handleNotificationDuckOrContinueLocked()
                        scheduleTransientVideoOrCallVerificationLocked()
                    }
                    autoPauseSystemAudio -> {
                        clearDuckStateAndRestoreLocked()
                        Log.d(TAG, "Transient video/system/call/camera-recording audio focus loss with autoPauseSystemAudio=true, pausing playback")
                        beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                        scheduleInterruptionMonitorLocked(500L)
                    }
                    else -> {
                        // Even if autoPauseSystemAudio is false, verify if a phone/WhatsApp call or alarm is starting up
                        scheduleTransientCallVerificationOnlyLocked()
                        Log.d(TAG, "Ignoring transient system audio focus loss (autoPauseSystemAudio=false)")
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                cancelTransientVerificationChecksLocked()
                cancelInterruptionMonitorLocked()
                hasAudioFocus = false
                val isPhoneCall = isCallOrRingtoneActive()
                val externalVideoNow = try {
                    externalVideoOrMediaActiveProvider?.invoke() == true ||
                        hasExternalVideoOrMediaPlaying()
                } catch (_: Throwable) {
                    false
                }

                when {
                    isPhoneCall -> {
                        clearDuckStateAndRestoreLocked()
                        beginInterruptionLocked(InterruptionSource.PHONE_CALL)
                        scheduleInterruptionMonitorLocked(600L)
                    }
                    externalVideoNow && autoPauseSystemAudio -> {
                        clearDuckStateAndRestoreLocked()
                        beginInterruptionLocked(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                        scheduleInterruptionMonitorLocked(600L)
                    }
                    else -> {
                        // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK is requested by notifications and short alerts.
                        // Never pause playback via autoPauseSystemAudio here — only duck by 40% (to 60% of song volume)
                        // when reduceVolumeOnNotification is enabled, or continue at full volume when disabled.
                        handleNotificationDuckOrContinueLocked(isExplicitDuckableFocus = true)
                        if (autoPauseSystemAudio) {
                            scheduleTransientVideoOrCallVerificationLocked()
                        } else {
                            scheduleTransientCallVerificationOnlyLocked()
                        }
                    }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus restored")
                cancelTransientVerificationChecksLocked()
                cancelInterruptionMonitorLocked()
                hasAudioFocus = true
                userOverrodeInterruption = false
                clearDuckStateAndRestoreLocked()
                if (isCallOrRingtoneActive()) {
                    if (!activeInterruptions.contains(InterruptionSource.PHONE_CALL)) {
                        activeInterruptions.add(InterruptionSource.PHONE_CALL)
                    }
                    wasInterruptedByPhoneCall = true
                    activeInterruptions.remove(InterruptionSource.SYSTEM_AUDIO_TRANSIENT)
                    activeInterruptions.remove(InterruptionSource.SYSTEM_AUDIO_LOSS)
                    scheduleInterruptionMonitorLocked(600L)
                } else if (autoPauseSystemAudio && activeInterruptions.isNotEmpty() && hasExternalVideoOrMediaPlaying()) {
                    scheduleInterruptionMonitorLocked(600L)
                } else {
                    endAudioFocusInterruptionsLocked()
                }
            }
        }
    }

    private fun beginInterruptionLocked(source: InterruptionSource) {
        cancelPostCallSettledResumeLocked()
        val currentlyPlaying = try { isPlayingProvider() } catch (_: Throwable) { false }
        val sleepStopped = try { isSleepTimerStoppedProvider() } catch (_: Throwable) { false }

        if (activeInterruptions.isEmpty()) {
            // First interruption in a potential sequence: record whether we were genuinely playing
            wasPlayingBeforeInterruption = currentlyPlaying && !userManuallyPausedOrStopped && !sleepStopped
            wasInterruptedByPhoneCall = (source == InterruptionSource.PHONE_CALL)
            observedExternalAudioDuringInterruption = isCallOrRingtoneActive() || hasExternalVideoOrMediaPlaying()
            externalAudioSilentSinceMs = 0L
        } else {
            if (source == InterruptionSource.PHONE_CALL) {
                wasInterruptedByPhoneCall = true
            }
            if (isCallOrRingtoneActive() || hasExternalVideoOrMediaPlaying()) {
                observedExternalAudioDuringInterruption = true
                externalAudioSilentSinceMs = 0L
            }
        }
        activeInterruptions.add(source)
        if (source == InterruptionSource.SYSTEM_AUDIO_TRANSIENT) {
            lastTransientInterruptionAtMs = android.os.SystemClock.elapsedRealtime()
        }

        if (currentlyPlaying) {
            runUrgentOnMain {
                val stillInterrupted = synchronized(this@AudioFocusManager) {
                    activeInterruptions.isNotEmpty()
                }
                if (stillInterrupted) {
                    onPauseRequested()
                }
            }
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
            activeInterruptions.remove(InterruptionSource.SYSTEM_AUDIO_TRANSIENT) or
                activeInterruptions.remove(InterruptionSource.SYSTEM_AUDIO_LOSS) or
                activeInterruptions.remove(InterruptionSource.PHONE_CALL)
        if (!hadFocusInterruption) return
        maybeResumeAfterInterruptionsLocked()
    }

    private fun maybeResumeAfterInterruptionsLocked() {
        if (activeInterruptions.isNotEmpty()) {
            Log.d(TAG, "Deferring auto-resume; remaining active interruptions: $activeInterruptions")
            return
        }
        cancelInterruptionMonitorLocked()
        observedExternalAudioDuringInterruption = false
        externalAudioSilentSinceMs = 0L
        val sleepStopped = try { isSleepTimerStoppedProvider() } catch (_: Throwable) { false }
        val shouldResume = wasPlayingBeforeInterruption && !userManuallyPausedOrStopped && !sleepStopped
        val resumeAfterPhoneCall = wasInterruptedByPhoneCall
        wasPlayingBeforeInterruption = false
        wasInterruptedByPhoneCall = false

        if (shouldResume) {
            val alreadyPlaying = try { isPlayingProvider() } catch (_: Throwable) { false }
            if (!alreadyPlaying) {
                if (resumeAfterPhoneCall && onResumeAfterCallRequested != null) {
                    // Phone/VoIP call teardown takes 350-900ms across Android Telecom HAL & Bluetooth SCO->A2DP.
                    // Wait for route settlement before resuming so audio never flickers/stutters.
                    val settleDelayMs = if (isBluetoothAudioConnected()) 850L else 550L
                    Log.d(TAG, "Phone call ended; scheduling route-settled smooth resume in ${settleDelayMs}ms")
                    schedulePostCallSettledResumeLocked(settleDelayMs)
                } else {
                    Log.d(TAG, "All interruptions ended; automatically resuming playback once")
                    runUrgentOnMain {
                        val allowed = synchronized(this@AudioFocusManager) {
                            activeInterruptions.isEmpty() && !userManuallyPausedOrStopped &&
                                !(try { isSleepTimerStoppedProvider() } catch (_: Throwable) { false })
                        }
                        if (allowed) {
                            onResumeRequested()
                        }
                    }
                }
            }
        }
    }

    private fun schedulePostCallSettledResumeLocked(delayMs: Long) {
        cancelPostCallSettledResumeLocked()
        val runnable = object : Runnable {
            override fun run() {
                val shouldDispatch = synchronized(this@AudioFocusManager) {
                    pendingPostCallResumeRunnable = null
                    val sleepStopped = try { isSleepTimerStoppedProvider() } catch (_: Throwable) { false }
                    val alreadyPlaying = try { isPlayingProvider() } catch (_: Throwable) { false }
                    if (activeInterruptions.isNotEmpty() || userManuallyPausedOrStopped || sleepStopped || alreadyPlaying) {
                        postCallResumePollCount = 0
                        return@synchronized false
                    }

                    val modeNow = try { audioManager?.mode ?: AudioManager.MODE_NORMAL } catch (_: Throwable) { AudioManager.MODE_NORMAL }
                    val routeStillTearingDown =
                        modeNow != AudioManager.MODE_NORMAL ||
                            isCallOrRingtoneActive() ||
                            isBluetoothScoOrCommRouteActive()

                    if (routeStillTearingDown && postCallResumePollCount < 6) {
                        postCallResumePollCount++
                        Log.d(TAG, "Post-call audio route still settling (mode=$modeNow, poll=$postCallResumePollCount); deferring 250ms")
                        pendingPostCallResumeRunnable = this
                        mainHandler.postDelayed(this, 250L)
                        return@synchronized false
                    }

                    postCallResumePollCount = 0
                    if (!hasAudioFocus) {
                        requestAudioFocus()
                    }
                    true
                }

                if (shouldDispatch) {
                    try {
                        val callback = onResumeAfterCallRequested ?: onResumeRequested
                        callback.invoke()
                    } catch (_: Throwable) {}
                }
            }
        }
        pendingPostCallResumeRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs.coerceAtLeast(100L))
    }

    @Synchronized
    fun hasPendingPostCallResume(): Boolean = pendingPostCallResumeRunnable != null

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
                        .setOnAudioFocusChangeListener(focusChangeListener, getOrCreateFocusCallbackHandler())
                        .build()
                }
                mgr.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                mgr.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ||
                result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
            hasAudioFocus
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting audio focus", e)
            hasAudioFocus = true
            true
        }
    }

    @Synchronized
    fun abandonAudioFocus() {
        cancelTransientVerificationChecksLocked()
        cancelInterruptionMonitorLocked()
        cancelPostCallSettledResumeLocked()
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
            userOverrodeInterruption = false
            activeInterruptions.clear()
            wasPlayingBeforeInterruption = false
            wasInterruptedByPhoneCall = false
            observedExternalAudioDuringInterruption = false
            externalAudioSilentSinceMs = 0L
            wasDuckedByTransientFocus = false
            duckStartedViaPlaybackObserver = false
            systemHandledDuckingForNotification = false
        }
    }

    /** A user pause, explicit stop, or sleep-timer stop supersedes any pending automatic resume. */
    @Synchronized
    fun cancelPendingResume() {
        cancelTransientVerificationChecksLocked()
        cancelInterruptionMonitorLocked()
        cancelPostCallSettledResumeLocked()
        userManuallyPausedOrStopped = true
        userOverrodeInterruption = false
        activeInterruptions.clear()
        wasPlayingBeforeInterruption = false
        wasInterruptedByPhoneCall = false
        observedExternalAudioDuringInterruption = false
        externalAudioSilentSinceMs = 0L
    }

    /**
     * A direct user play/resume request clears manual-pause state, supersedes any pending focus-loss
     * auto-resume, and allows playback to proceed even if an external system sound/video/call is currently active.
     */
    @Synchronized
    fun onUserPlayRequested() {
        val hadActiveInterruptionOrExternalAudio =
            activeInterruptions.isNotEmpty() ||
                isCallOrRingtoneActive() ||
                hasExternalVideoOrMediaPlaying()
        cancelTransientVerificationChecksLocked()
        cancelInterruptionMonitorLocked()
        cancelPostCallSettledResumeLocked()
        clearDuckStateAndRestoreLocked()
        userManuallyPausedOrStopped = false
        userOverrodeInterruption = hadActiveInterruptionOrExternalAudio
        lastUserPlayRequestAtMs = SystemClock.elapsedRealtime()
        activeInterruptions.clear()
        wasPlayingBeforeInterruption = false
        wasInterruptedByPhoneCall = false
        observedExternalAudioDuringInterruption = false
        externalAudioSilentSinceMs = 0L
    }
}

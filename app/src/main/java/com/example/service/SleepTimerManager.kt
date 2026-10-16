package com.example.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TrackSleepTimerPhase {
    INACTIVE,
    ARMED,
    COUNTING,
    FINAL_TRACK,
    WAITING_FOR_COMPLETION,
    STOPPED
}

data class SleepTimerState(
    val isActive: Boolean = false,
    val remainingSeconds: Long = 0L,
    val remainingSongs: Int = 0,
    val totalSongs: Int = 0,
    val elapsedSongs: Int = 0,
    val initialMinutes: Int = 0,
    val mode: String = "TIME", // "TIME", "SONGS"
    val finishLastSong: Boolean = false,
    val isWaitingForCurrentTrackToEnd: Boolean = false,
    val initialSongId: Long? = null,
    val isFinalSleepTrack: Boolean = false,

    // Explicit state properties for track-based sleep timer
    val isTrackTimerActive: Boolean = false,
    val targetTrackCount: Int = 0,
    val countedTrackCount: Int = 0,
    val baselineTrackIdentity: String? = null,
    val lastProcessedTrackIdentity: String? = null,
    val finalTrackIdentity: String? = null,
    val isFinalTrack: Boolean = false,
    val stopPending: Boolean = false,
    val phase: TrackSleepTimerPhase = TrackSleepTimerPhase.INACTIVE
)

class SleepTimerManager(
    externalScope: CoroutineScope? = null,
    var isPlaybackActive: (() -> Boolean)? = null,
    var onTimerExpired: () -> Unit = {}
) {
    companion object {
        private const val TAG = "SleepTimer"
    }

    // Use a persistent supervisor scope so background playback timer survives Activity lifecycles
    private val scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var initialSongId: Long? = null
    private var hasPassedInitialSong = false
    private var completedEligibleTracks = 0
    private val processedSessionIds = mutableSetOf<String>()
    private val countedOccurrences = mutableSetOf<String>()

    fun startTimerMinutes(minutes: Int, finishLastSong: Boolean = false) {
        cancelTimer()
        val durationSec = minutes * 60L
        val targetEndTimeMs = android.os.SystemClock.elapsedRealtime() + durationSec * 1000L

        _state.value = SleepTimerState(
            isActive = true,
            remainingSeconds = durationSec,
            initialMinutes = minutes,
            mode = "TIME",
            finishLastSong = finishLastSong,
            isWaitingForCurrentTrackToEnd = false
        )

        timerJob = scope.launch(Dispatchers.Main) {
            while (true) {
                delay(500)
                val remainingMs = targetEndTimeMs - android.os.SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) {
                    break
                }
                val secLeft = (remainingMs / 1000L).coerceAtLeast(0L)
                if (_state.value.remainingSeconds != secLeft) {
                    _state.value = _state.value.copy(remainingSeconds = secLeft)
                }
            }

            val playing = isPlaybackActive?.invoke() ?: false
            android.util.Log.d(TAG, "TIME sleep timer countdown reached 0. finishLastSong=$finishLastSong, isPlaying=$playing")

            if (!finishLastSong || !playing) {
                // Scenario 1: Stop immediately
                android.util.Log.d(TAG, "Stopping playback immediately (Scenario 1)")
                _state.value = SleepTimerState()
                timerJob = null
                onTimerExpired()
            } else {
                // Scenario 2: Wait for current track to finish, then transit to next song and stop at 0:00
                android.util.Log.d(TAG, "Waiting for current track to complete before stopping (Scenario 2)")
                _state.value = _state.value.copy(
                    remainingSeconds = 0L,
                    isWaitingForCurrentTrackToEnd = true
                )
            }
        }
    }

    @Synchronized
    fun startSongCount(count: Int, baselineIdentity: String?, currentSongId: Long? = null) {
        cancelTimer()
        val validCount = count.coerceAtLeast(1)
        countedOccurrences.clear()
        val parsedSongId = currentSongId ?: when {
            baselineIdentity == null -> null
            baselineIdentity.startsWith("song_") -> baselineIdentity.removePrefix("song_").toLongOrNull()
            baselineIdentity.startsWith("baseline_") -> baselineIdentity.substringAfterLast('_').toLongOrNull()
            else -> null
        }
        initialSongId = parsedSongId
        hasPassedInitialSong = (baselineIdentity == null && parsedSongId == null)
        completedEligibleTracks = 0

        _state.value = SleepTimerState(
            isActive = true,
            remainingSongs = validCount,
            totalSongs = validCount,
            elapsedSongs = 0,
            mode = "SONGS",
            finishLastSong = true,
            isWaitingForCurrentTrackToEnd = false,
            initialSongId = parsedSongId,
            isTrackTimerActive = true,
            targetTrackCount = validCount,
            countedTrackCount = 0,
            baselineTrackIdentity = baselineIdentity,
            lastProcessedTrackIdentity = null,
            finalTrackIdentity = null,
            isFinalTrack = false,
            stopPending = false,
            phase = TrackSleepTimerPhase.ARMED
        )
        android.util.Log.d(TAG, "Timer enabled")
        android.util.Log.d(TAG, "Baseline = ${baselineIdentity ?: "None"}")
    }

    fun startSongCount(count: Int, currentSongId: Long? = null) {
        startSongCount(count, currentSongId?.let { "song_$it" }, currentSongId)
    }

    @Synchronized
    fun setBaselineIdentity(identity: String) {
        val curr = _state.value
        if (curr.isTrackTimerActive && curr.mode == "SONGS") {
            val parsedSongId = when {
                identity.startsWith("song_") -> identity.removePrefix("song_").toLongOrNull()
                identity.startsWith("baseline_") -> identity.substringAfterLast('_').toLongOrNull()
                else -> null
            }
            if (parsedSongId != null) {
                initialSongId = parsedSongId
            }
            _state.value = curr.copy(
                baselineTrackIdentity = identity,
                initialSongId = parsedSongId ?: curr.initialSongId
            )
            android.util.Log.d(TAG, "Baseline = $identity")
        }
    }

    /**
     * Evaluates track eligibility and increments the counter exactly once per eligible occurrence.
     * Marks the track as final track when targetTrackCount is reached.
     */
    @Synchronized
    fun onTrackOccurrenceEligible(occurrenceId: String, trackName: String? = null): Boolean {
        val curr = _state.value
        if (!curr.isTrackTimerActive || curr.mode != "SONGS") return false

        // Baseline track is ignored and must never be counted
        if (occurrenceId == curr.baselineTrackIdentity || (initialSongId != null && occurrenceId == "song_$initialSongId")) {
            return false
        }

        // Guarantee that one track occurrence can consume only one counter increment
        if (countedOccurrences.contains(occurrenceId)) {
            return curr.isFinalTrack
        }

        countedOccurrences.add(occurrenceId)
        val newCounted = curr.countedTrackCount + 1
        completedEligibleTracks = newCounted
        val isFinal = (newCounted >= curr.targetTrackCount)
        val remaining = (curr.targetTrackCount - newCounted).coerceAtLeast(0)

        _state.value = curr.copy(
            countedTrackCount = newCounted,
            elapsedSongs = newCounted,
            remainingSongs = remaining,
            lastProcessedTrackIdentity = occurrenceId,
            finalTrackIdentity = if (isFinal) occurrenceId else curr.finalTrackIdentity,
            isFinalTrack = isFinal,
            isFinalSleepTrack = isFinal,
            stopPending = isFinal
        )

        val name = trackName ?: occurrenceId
        if (isFinal) {
            android.util.Log.d(TAG, "$name transition → eligible → $newCounted/${curr.targetTrackCount} → FINAL_TRACK")
        } else {
            android.util.Log.d(TAG, "$name transition → eligible → $newCounted/${curr.targetTrackCount}")
        }
        return isFinal
    }

    @Synchronized
    fun isStopPendingForOccurrence(occurrenceId: String?): Boolean {
        val curr = _state.value
        if (!curr.isTrackTimerActive || curr.mode != "SONGS") return false
        return curr.isFinalTrack && (curr.finalTrackIdentity == occurrenceId || curr.stopPending)
    }

    @Synchronized
    fun onFinalTrackCompleted(occurrenceId: String?): Boolean {
        val curr = _state.value
        if (!curr.isTrackTimerActive || curr.mode != "SONGS") return false
        if (curr.isFinalTrack && (curr.finalTrackIdentity == occurrenceId || curr.stopPending)) {
            android.util.Log.d(TAG, "F completion → FINAL_TRACK_COMPLETED")
            return true
        }
        return false
    }

    /**
     * Handles track transitions specifically for SONGS mode.
     */
    @Synchronized
    fun onTrackTransition(
        oldTrackId: Long?,
        isOldQualified: Boolean = true,
        oldNaturalEnd: Boolean = true,
        newTrackId: Long?,
        oldSessionId: String? = null
    ): Boolean {
        val curr = _state.value
        if (!curr.isTrackTimerActive || curr.mode != "SONGS") return false

        val oldOccurrence = oldSessionId ?: oldTrackId?.let { "song_$it" }
        val newOccurrence = newTrackId?.let { "song_$it" }

        val isOldEligible = isOldQualified || oldNaturalEnd

        // 1. If old track was marked as the final counted track (F)
        if (curr.isFinalTrack || curr.isFinalSleepTrack) {
            if (isOldEligible) {
                // F completed -> new track is G -> STOP on G at 0:00!
                _state.value = curr.copy(phase = TrackSleepTimerPhase.STOPPED)
                cancelTimer()
                android.util.Log.d(TAG, "Final track completed → stopping on next track at 0:00")
                return true
            } else {
                // Ineligible skip of final track: current track remains final track candidate
                android.util.Log.d(TAG, "Ineligible skip of final track; new track $newOccurrence is final track candidate")
                _state.value = curr.copy(
                    lastProcessedTrackIdentity = newOccurrence,
                    finalTrackIdentity = newOccurrence
                )
                return false
            }
        }

        // 2. Check if baseline track (A) is finishing
        val isOldBaseline = !hasPassedInitialSong && (
            (curr.baselineTrackIdentity != null && (oldOccurrence == curr.baselineTrackIdentity || oldSessionId == curr.baselineTrackIdentity)) ||
            (initialSongId != null && oldTrackId == initialSongId)
        )

        if (isOldBaseline) {
            hasPassedInitialSong = true
            // Baseline track A has completed without being counted.
            // New track (B) is starting as Count 1.
            completedEligibleTracks = 1
        } else if (isOldEligible) {
            hasPassedInitialSong = true
            completedEligibleTracks++
        }

        val currentTrackNumber = completedEligibleTracks.coerceIn(1, curr.targetTrackCount)
        val isFinal = (currentTrackNumber >= curr.targetTrackCount)
        val remaining = (curr.targetTrackCount - currentTrackNumber + 1).coerceIn(1, curr.targetTrackCount)
        val nextPhase = if (isFinal) TrackSleepTimerPhase.FINAL_TRACK else TrackSleepTimerPhase.COUNTING

        _state.value = curr.copy(
            elapsedSongs = currentTrackNumber,
            countedTrackCount = currentTrackNumber,
            remainingSongs = remaining,
            lastProcessedTrackIdentity = newOccurrence,
            finalTrackIdentity = if (isFinal) newOccurrence else curr.finalTrackIdentity,
            isFinalTrack = isFinal,
            isFinalSleepTrack = isFinal,
            stopPending = isFinal,
            phase = nextPhase
        )

        val name = newTrackId?.let { "Song $it" } ?: (newOccurrence ?: "Track")
        if (isFinal) {
            android.util.Log.d(TAG, "$name transition → count $currentTrackNumber/${curr.targetTrackCount} → FINAL_TRACK")
        } else {
            android.util.Log.d(TAG, "$name transition → count $currentTrackNumber/${curr.targetTrackCount}")
        }

        return false
    }

    @Synchronized
    fun markWaitingForCompletion() {
        val curr = _state.value
        if (curr.isTrackTimerActive && curr.mode == "SONGS" && curr.isFinalTrack) {
            _state.value = curr.copy(phase = TrackSleepTimerPhase.WAITING_FOR_COMPLETION)
            android.util.Log.d(TAG, "Final track waiting for completion")
        }
    }

    /**
     * Called when the player reaches STATE_ENDED (playlist ended).
     */
    @Synchronized
    fun onPlaybackEnded(finishedSongId: Long?, isQualified: Boolean = true, sessionId: String? = null): Boolean {
        val curr = _state.value
        if (!curr.isActive) return false

        if (curr.mode == "TIME") {
            if (curr.finishLastSong && (curr.isWaitingForCurrentTrackToEnd || curr.remainingSeconds <= 0L)) {
                cancelTimer()
                scope.launch(Dispatchers.Main) {
                    onTimerExpired()
                }
            }
            return false
        } else if (curr.mode == "SONGS") {
            val wasEligible = isQualified
            if (wasEligible && (curr.isFinalSleepTrack || curr.isFinalTrack || (completedEligibleTracks >= curr.targetTrackCount) || (curr.countedTrackCount >= curr.targetTrackCount))) {
                _state.value = curr.copy(phase = TrackSleepTimerPhase.STOPPED)
                cancelTimer()
                android.util.Log.d(TAG, "F completion → FINAL_TRACK_COMPLETED")
                return true
            }
        }
        return false
    }

    fun onSongQualified(songId: Long) {
        // Analytics hook
    }

    fun onSongFinished(finishedSongId: Long? = null, isQualified: Boolean = false, sessionId: String? = null) {
        val curr = _state.value
        if (!curr.isActive) return

        if (sessionId != null && processedSessionIds.contains(sessionId)) {
            return
        }

        if (curr.mode == "TIME") {
            if (curr.finishLastSong && (curr.isWaitingForCurrentTrackToEnd || curr.remainingSeconds <= 0L)) {
                if (sessionId != null) processedSessionIds.add(sessionId)
                cancelTimer()
                scope.launch(Dispatchers.Main) {
                    onTimerExpired()
                }
            }
        }
    }

    fun onSongEnded(finishedSongId: Long? = null, isQualified: Boolean = true, sessionId: String? = null) {
        onSongFinished(finishedSongId, isQualified, sessionId)
    }

    fun addMinutes(minutes: Int) {
        val curr = _state.value
        if (curr.isActive && curr.mode == "TIME") {
            val currentSec = if (curr.remainingSeconds > 0) curr.remainingSeconds else 0L
            val addedSec = minutes * 60L
            val totalSec = currentSec + addedSec
            val totalMins = (totalSec / 60).toInt().coerceAtLeast(1)
            startTimerMinutes(totalMins, curr.finishLastSong)
        }
    }

    @Synchronized
    fun addSongs(count: Int) {
        val curr = _state.value
        if (curr.isTrackTimerActive && curr.mode == "SONGS") {
            val newTotal = curr.targetTrackCount + count
            val newRemaining = (newTotal - curr.countedTrackCount).coerceAtLeast(0)
            val isFinal = (curr.countedTrackCount >= newTotal)
            _state.value = curr.copy(
                totalSongs = newTotal,
                targetTrackCount = newTotal,
                remainingSongs = newRemaining,
                isFinalSleepTrack = isFinal,
                isFinalTrack = isFinal,
                stopPending = isFinal
            )
            android.util.Log.d(TAG, "Added $count songs, new target = $newTotal")
        }
    }

    fun toggleFinishLastSong() {
        val curr = _state.value
        if (curr.isActive) {
            val newFinish = !curr.finishLastSong
            _state.value = curr.copy(finishLastSong = newFinish)
            if (!newFinish && (curr.isWaitingForCurrentTrackToEnd || curr.remainingSeconds <= 0L)) {
                _state.value = SleepTimerState()
                timerJob?.cancel()
                timerJob = null
                onTimerExpired()
            }
        }
    }

    @Synchronized
    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        initialSongId = null
        hasPassedInitialSong = false
        completedEligibleTracks = 0
        processedSessionIds.clear()
        countedOccurrences.clear()
        _state.value = SleepTimerState()
        android.util.Log.d(TAG, "Timer cancelled/reset")
    }
}




package com.example.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var initialSongId: Long? = null
    private var hasPassedInitialSong = false
    private var completedEligibleTracks = 0
    private val processedSessionIds = mutableSetOf<String>()
    private val countedOccurrences = mutableSetOf<String>()

    @Volatile
    private var defaultFinishLastSong: Boolean = false

    fun startTimerMinutes(minutes: Int, finishLastSong: Boolean = defaultFinishLastSong) {
        defaultFinishLastSong = finishLastSong
        cancelTimer()
        val validMinutes = minutes.coerceAtLeast(1)
        val durationSec = validMinutes * 60L
        val targetEndTimeMs = android.os.SystemClock.elapsedRealtime() + durationSec * 1000L

        _state.value = SleepTimerState(
            isActive = true,
            remainingSeconds = durationSec,
            initialMinutes = validMinutes,
            mode = "TIME",
            finishLastSong = finishLastSong,
            isWaitingForCurrentTrackToEnd = false
        )

        timerJob = scope.launch(Dispatchers.Main) {
            while (true) {
                delay(400)
                val remainingMs = targetEndTimeMs - android.os.SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) {
                    break
                }
                val secLeft = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
                if (_state.value.remainingSeconds != secLeft) {
                    _state.value = _state.value.copy(remainingSeconds = secLeft)
                }
            }

            val playing = isPlaybackActive?.invoke() ?: false
            val shouldFinishLastSong = _state.value.finishLastSong
            android.util.Log.d(TAG, "TIME sleep timer countdown reached 0. finishLastSong=$shouldFinishLastSong, isPlaying=$playing")

            if (!shouldFinishLastSong || !playing) {
                // Scenario 1: Normal sleep timer (without finish last track) -> stop immediately
                android.util.Log.d(TAG, "Stopping playback immediately (Scenario 1)")
                _state.value = SleepTimerState(finishLastSong = defaultFinishLastSong)
                timerJob = null
                onTimerExpired()
            } else {
                // Scenario 2: Normal sleep timer with finish last track enabled ->
                // Let current Song D finish completely, then transition UI to Song E at 0:00 and stop.
                android.util.Log.d(TAG, "Waiting for current track to complete before transitioning to next track at 0:00 (Scenario 2)")
                _state.value = _state.value.copy(
                    remainingSeconds = 0L,
                    isWaitingForCurrentTrackToEnd = true,
                    isFinalSleepTrack = true
                )
            }
        }
    }

    /**
     * Starts a track-based sleep timer:
     * Example: Song A is currently playing and user sets 5 songs as track count.
     * Since the timer is set during Song A, the count begins from Song B (Song B = track 1),
     * then Song C (2), Song D (3), Song E (4), Song F (5th track).
     * After Song F finishes, playback transitions to Song G screen and stops at 0:00.
     */
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
        // If no song is currently loaded/playing when the timer is set, the very first song played is Track 1.
        // If Song A is currently loaded/playing, Song A is the baseline and Song B will be Track 1.
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
            isFinalSleepTrack = false,
            stopPending = false,
            phase = TrackSleepTimerPhase.ARMED
        )
        android.util.Log.d(TAG, "Track sleep timer enabled: target=$validCount, baseline=${baselineIdentity ?: parsedSongId ?: "None"}")
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
            android.util.Log.d(TAG, "Baseline updated = $identity")
        }
    }

    @Synchronized
    fun onTrackOccurrenceEligible(occurrenceId: String, trackName: String? = null): Boolean {
        val curr = _state.value
        if (!curr.isTrackTimerActive || curr.mode != "SONGS") return false

        // Baseline track (Song A) is ignored and must never be counted as Track 1
        if (!hasPassedInitialSong && (occurrenceId == curr.baselineTrackIdentity || (initialSongId != null && occurrenceId == "song_$initialSongId"))) {
            return false
        }

        if (countedOccurrences.contains(occurrenceId)) {
            return curr.isFinalTrack
        }

        countedOccurrences.add(occurrenceId)
        val newCounted = if (completedEligibleTracks > 0) completedEligibleTracks else (curr.countedTrackCount + 1)
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
            android.util.Log.d(TAG, "Final track completed -> FINAL_TRACK_COMPLETED")
            return true
        }
        return false
    }

    /**
     * Handles track transitions for SONGS mode.
     * Example trace (target = 5, set during Song A):
     * - Transition A -> B: Song A (baseline) finished -> Song B starts as Track 1 (1/5).
     * - Transition B -> C: Song B (Track 1) finished -> Song C starts as Track 2 (2/5).
     * - Transition C -> D: Song C (Track 2) finished -> Song D starts as Track 3 (3/5).
     * - Transition D -> E: Song D (Track 3) finished -> Song E starts as Track 4 (4/5).
     * - Transition E -> F: Song E (Track 4) finished -> Song F starts as Track 5 (5/5, isFinalTrack = true).
     * - Transition F -> G: Song F (Track 5) finished -> returns true so PlaybackManager transitions UI to Song G at 0:00 and pauses!
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

        val oldWasEligible = isOldQualified || oldNaturalEnd

        // 1. If the finishing track was the Nth (final) counted track (Song F),
        // and it completed or qualified, transitioning to Song G completes the sleep timer and stops at 0:00 on Song G!
        // If the user skipped the final track before qualification, the new track becomes the final track candidate instead.
        if (curr.isFinalTrack || curr.isFinalSleepTrack) {
            if (oldWasEligible) {
                _state.value = curr.copy(phase = TrackSleepTimerPhase.STOPPED)
                cancelTimer()
                android.util.Log.d(TAG, "Final counted track ($oldTrackId) finished -> transitioning to next track ($newTrackId) at 0:00 and stopping")
                return true
            } else {
                _state.value = curr.copy(
                    lastProcessedTrackIdentity = newOccurrence,
                    finalTrackIdentity = newOccurrence
                )
                return false
            }
        }

        // 2. Check if we are transitioning out of the initial baseline track (Song A) into Track 1 (Song B)
        if (!hasPassedInitialSong) {
            hasPassedInitialSong = true
            // Baseline track A finished; new track B is Track 1
            completedEligibleTracks = 1
        } else if (oldWasEligible) {
            // Subsequent eligible transition (B -> C, C -> D, D -> E, E -> F)
            completedEligibleTracks++
        }

        val currentTrackNumber = completedEligibleTracks.coerceIn(1, curr.targetTrackCount)
        val isFinal = (currentTrackNumber >= curr.targetTrackCount)
        val remaining = (curr.targetTrackCount - currentTrackNumber + 1).coerceAtLeast(1)
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
            android.util.Log.d(TAG, "$name started -> Track $currentTrackNumber/${curr.targetTrackCount} (FINAL_TRACK)")
        } else {
            android.util.Log.d(TAG, "$name started -> Track $currentTrackNumber/${curr.targetTrackCount} ($remaining tracks remaining)")
        }

        return false
    }

    @Synchronized
    fun markWaitingForCompletion() {
        val curr = _state.value
        if (curr.isTrackTimerActive && curr.mode == "SONGS" && curr.isFinalTrack) {
            _state.value = curr.copy(phase = TrackSleepTimerPhase.WAITING_FOR_COMPLETION)
        }
    }

    /**
     * Called when the player reaches STATE_ENDED (end of queue).
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
                return true
            }
            return false
        } else if (curr.mode == "SONGS") {
            if (curr.isFinalSleepTrack || curr.isFinalTrack || (completedEligibleTracks >= curr.targetTrackCount) || (curr.countedTrackCount >= curr.targetTrackCount)) {
                _state.value = curr.copy(phase = TrackSleepTimerPhase.STOPPED)
                cancelTimer()
                android.util.Log.d(TAG, "Final track ended at end of queue -> FINAL_TRACK_COMPLETED")
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
            val totalMins = ((totalSec + 59L) / 60L).toInt().coerceAtLeast(1)
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

    fun setFinishLastSong(enabled: Boolean) {
        defaultFinishLastSong = enabled
        val curr = _state.value
        _state.value = curr.copy(finishLastSong = enabled)
        if (curr.isActive && curr.mode == "TIME" && !enabled && (curr.isWaitingForCurrentTrackToEnd || curr.remainingSeconds <= 0L)) {
            _state.value = SleepTimerState(finishLastSong = defaultFinishLastSong)
            timerJob?.cancel()
            timerJob = null
            onTimerExpired()
        }
    }

    fun toggleFinishLastSong() {
        setFinishLastSong(!_state.value.finishLastSong)
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
        _state.value = SleepTimerState(finishLastSong = defaultFinishLastSong)
        android.util.Log.d(TAG, "Timer cancelled/reset")
    }
}
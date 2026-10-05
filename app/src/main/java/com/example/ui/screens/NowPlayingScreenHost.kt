package com.example.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.example.data.model.Lyrics
import com.example.data.model.Song
import com.example.service.PlaybackState
import com.example.service.SleepTimerState
import com.example.ui.viewmodel.MusicViewModel
import com.example.util.AudioRouteInfo

@Composable
fun NowPlayingScreenHost(
    viewModel: MusicViewModel,
    activity: Activity,
    playbackState: PlaybackState,
    currentLyrics: Lyrics?,
    currentAudioRoute: AudioRouteInfo,
    sleepTimerState: SleepTimerState,
    dynamicArtworkColor: Color,
    nowPlayingLayout: String,
    showNowPlaying: Boolean,
    onDismiss: () -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onDeleteSong: (Song) -> Unit,
    onEditTags: (Song) -> Unit
) {
    AnimatedVisibility(
        visible = showNowPlaying,
        enter = slideInVertically { it },
        exit = slideOutVertically { it }
    ) {
        val waveformStyle by viewModel.waveformStyle.collectAsState()
        val availableAudioDevices by viewModel.availableAudioDevices.collectAsState()
        val keepScreenAwakeSetting by viewModel.keepScreenAwake.collectAsState()
        val gesturePlaybackEnabled by viewModel.gesturePlaybackEnabled.collectAsState()
        val lyricsAutoScroll by viewModel.lyricsAutoScroll.collectAsState()
        val isTranscribeTranslateEnabled by viewModel.isTranscribeTranslateEnabled.collectAsState()
        val audioTranscribeMode by viewModel.audioTranscribeMode.collectAsState()
        val audioTranscribeTargetLang by viewModel.audioTranscribeTargetLang.collectAsState()
        val isTranscribingLyrics by viewModel.isTranscribingLyrics.collectAsState()
        val transcribeStatusMessage by viewModel.transcribeStatusMessage.collectAsState()
        val transcribeErrorMessage by viewModel.transcribeErrorMessage.collectAsState()
        val canUndoLyrics by viewModel.canUndoLyricsForCurrentSong.collectAsState()

        NowPlayingScreen(
            playbackState = playbackState,
            currentLyrics = currentLyrics,
            audioRouteInfo = currentAudioRoute,
            availableAudioDevices = availableAudioDevices,
            keepScreenAwake = keepScreenAwakeSetting,
            gesturePlaybackEnabled = gesturePlaybackEnabled,
            lyricsAutoScroll = lyricsAutoScroll,
            onSaveLyricsOffset = { songId, offsetMs ->
                viewModel.setLyricsSyncOffset(songId, offsetMs)
            },
            onGetLyricsOffset = { songId ->
                viewModel.getLyricsSyncOffset(songId)
            },
            onSelectAudioDevice = { viewModel.selectAudioRoute(it) },
            onLaunchSystemOutputSwitcher = { viewModel.launchSystemOutputSwitcher(activity) },
            sleepTimerState = sleepTimerState,
            dynamicArtworkColor = dynamicArtworkColor,
            waveformStyle = waveformStyle,
            nowPlayingLayout = nowPlayingLayout,
            playbackPositionFlow = viewModel.playbackPositionMs,
            onDismiss = onDismiss,
            onTogglePlayPause = { viewModel.togglePlayPause() },
            onSkipNext = { viewModel.skipToNext() },
            onSkipPrevious = { viewModel.skipToPrevious() },
            onSeekTo = { viewModel.seekTo(it) },
            onFavoriteToggle = {
                playbackState.currentSong?.let { viewModel.toggleFavorite(it) }
            },
            onToggleShuffle = { viewModel.toggleShuffle() },
            onToggleRepeat = { viewModel.toggleRepeat() },
            onStartSleepTimer = { mins, finishLastSong -> viewModel.startSleepTimerMinutes(mins, finishLastSong) },
            onStartSongCountTimer = { songs -> viewModel.startSleepTimerSongs(songs) },
            onAddSleepTimerMinutes = { viewModel.addSleepTimerMinutes(it) },
            onCancelSleepTimer = { viewModel.cancelSleepTimer() },
            onCustomLrcSelected = { lrcText ->
                playbackState.currentSong?.let { viewModel.associateCustomLrc(it, lrcText) }
            },
            onReorderQueue = { from, to -> viewModel.reorderQueue(from, to) },
            onUpdateQueue = { viewModel.updateQueueOrder(it) },
            onRemoveFromQueue = { index -> viewModel.removeFromQueue(index) },
            onRemoveDuplicatesFromQueue = {
                val removed = viewModel.removeDuplicateSongsFromQueue()
                if (removed > 0) {
                    Toast.makeText(activity, "Removed $removed duplicate song${if (removed > 1) "s" else ""} from queue", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "No duplicate songs in queue", Toast.LENGTH_SHORT).show()
                }
            },
            onJumpToQueueItem = { index ->
                viewModel.jumpToQueueIndex(index)
            },
            onPlayNext = { viewModel.playNext(it) },
            onAddToPlaylist = onAddToPlaylist,
            onDeleteSong = onDeleteSong,
            onEditTags = onEditTags,
            onForceFetchLyrics = { viewModel.forceFetchLyrics(it) },
            onSearchLyricsCandidates = { query, force -> viewModel.searchLyricsCandidates(query, force) },
            lyricsSearchResults = viewModel.lyricsSearchResults.collectAsState().value,
            isSearchingLyrics = viewModel.isSearchingLyrics.collectAsState().value,
            onApplyLyricsCandidate = { viewModel.applySelectedLyrics(it) },
            isTranscribeTranslateEnabled = isTranscribeTranslateEnabled,
            audioTranscribeMode = audioTranscribeMode,
            audioTranscribeTargetLang = audioTranscribeTargetLang,
            isTranscribingLyrics = isTranscribingLyrics,
            transcribeStatusMessage = transcribeStatusMessage,
            transcribeErrorMessage = transcribeErrorMessage,
            canUndoLyrics = canUndoLyrics,
            transcribeLyricsPreview = viewModel.transcribeLyricsPreview.collectAsState().value,
            onSaveTranscribedLyrics = { viewModel.saveTranscribedLyrics(it) },
            onDiscardTranscribedLyrics = { viewModel.discardTranscribedLyrics() },
            onTranslateLyrics = { song, lang -> viewModel.translateLyricsForSong(song, lang) },
            onTranscribeAudio = { song, lang -> viewModel.transcribeAudioForSong(song, lang) },
            onUndoLyricsChanges = { songId -> viewModel.undoLyricsChanges(songId) },
            onClearTranscribeError = { viewModel.clearTranscribeError() }
        )
    }
}

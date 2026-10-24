package com.example.data.repository

import com.example.data.local.*
import com.example.data.model.*
import com.example.scanner.LibraryScanner
import com.example.scanner.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class MusicRepository(
    private val songDao: SongDao,
    private val folderDao: FolderDao,
    private val playlistDao: PlaylistDao,
    private val historyDao: HistoryDao,
    private val lyricsDao: LyricsDao,
    private val recentSearchDao: RecentSearchDao,
    private val smartPlaylistDao: SmartPlaylistDao,
    private val libraryScanner: LibraryScanner
) {
    var cloudSyncRepository: com.example.data.sync.CloudSyncRepository? = null

    val allSongs: Flow<List<Song>> = songDao.getAllSongs().map { list -> list.map { it.toDomainModel() } }
    val favoriteSongs: Flow<List<Song>> = songDao.getFavoriteSongs().map { list -> list.map { it.toDomainModel() } }
    val recentlyAddedSongs: Flow<List<Song>> = songDao.getRecentlyAddedSongs().map { list -> list.map { it.toDomainModel() } }
    val recentlyPlayedSongs: Flow<List<Song>> = combine(
        historyDao.getRecentlyPlayedHistory(200),
        songDao.getRecentlyPlayedSongs()
    ) { historyList, legacySongs ->
        if (historyList.isNotEmpty()) {
            val seenKeys = mutableSetOf<String>()
            val uniqueList = mutableListOf<Song>()
            for (item in historyList) {
                val key = if (item.song.id > 0) "id_${item.song.id}" else "path_${item.song.path}"
                if (seenKeys.add(key)) {
                    val playCount = item.song.playCount.coerceAtLeast(1)
                    uniqueList.add(
                        item.song.toDomainModel().copy(
                            lastPlayed = item.historyStartedAt,
                            playCount = playCount
                        )
                    )
                }
                if (uniqueList.size >= 100) break
            }
            uniqueList
        } else {
            legacySongs.take(100).map { it.toDomainModel() }
        }
    }
    val mostPlayedSongs: Flow<List<Song>> = songDao.getMostPlayedSongs().map { list -> list.map { it.toDomainModel() } }
    val scanProgress: Flow<ScanProgress> = libraryScanner.scanProgress
    val allFolders: Flow<List<FolderInfo>> = folderDao.getAllFolders().map { list -> list.map { it.toDomainModel() } }
    val recentSearches: Flow<List<String>> = recentSearchDao.getRecentSearches()
    val smartPlaylists: Flow<List<SmartPlaylist>> = smartPlaylistDao.getAllSmartPlaylists().map { list -> list.map { it.toDomainModel() } }

    suspend fun createSmartPlaylist(smartPlaylist: SmartPlaylist): Long {
        return smartPlaylistDao.insertSmartPlaylist(SmartPlaylistEntity.fromDomainModel(smartPlaylist))
    }

    suspend fun deleteSmartPlaylist(id: Long) {
        smartPlaylistDao.deleteSmartPlaylist(id)
    }

    val allPlaylists: Flow<List<Playlist>> = playlistDao.getAllPlaylistsWithDetails().map { list ->
        list.map { item ->
            Playlist(
                id = item.id,
                name = item.name,
                songCount = item.songCount,
                totalDurationMs = item.totalDurationMs,
                createdAt = item.createdAt,
                isPinned = item.isPinned,
                artworkUri = item.artworkUri
            )
        }
    }

    suspend fun scanLibrary(
        ignoreAudioDurationMs: Long = 0L,
        maxAudioDurationMs: Long = Long.MAX_VALUE,
        forceFullScan: Boolean = false
    ) {
        val included = if (forceFullScan) emptyList() else folderDao.getIncludedFoldersList()
        val excluded = folderDao.getExcludedFoldersList()
        libraryScanner.scanLibrary(included, excluded, ignoreAudioDurationMs, maxAudioDurationMs)
    }

    suspend fun quickScanAndCleanLibrary(
        ignoreAudioDurationMs: Long = 0L,
        maxAudioDurationMs: Long = Long.MAX_VALUE
    ) {
        val included = folderDao.getIncludedFoldersList()
        val excluded = folderDao.getExcludedFoldersList()
        libraryScanner.quickScanAndCleanLibrary(included, excluded, ignoreAudioDurationMs, maxAudioDurationMs)
    }

    suspend fun detectAndImportNewSongs(): Int {
        return libraryScanner.detectAndImportNewSongs()
    }

    val mostPlayedPlaylists: Flow<List<Playlist>> = playlistDao.getMostPlayedPlaylistsWithDetails(10).map { list ->
        list.map { item ->
            Playlist(
                id = item.id,
                name = item.name,
                songCount = item.songCount,
                totalDurationMs = item.totalDurationMs,
                createdAt = item.createdAt,
                isPinned = item.isPinned,
                artworkUri = item.artworkUri
            )
        }
    }

    suspend fun recordPlaylistPlay(playlistId: Long) {
        if (playlistId <= 0) return
        try {
            playlistDao.recordPlaylistPlay(
                com.example.data.local.PlaylistPlayEventEntity(
                    playlistId = playlistId,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {}
    }

    suspend fun checkAndSeedInitialData() {
        try {
            // Clean up any remaining legacy sample songs from previous runs
            songDao.deleteSampleSongs()
        } catch (_: Exception) {}
    }

    suspend fun setFavorite(songId: Long, isFavorite: Boolean, path: String = "") {
        if (path.isNotBlank()) {
            songDao.setFavoriteBySong(songId, path, isFavorite)
        } else {
            songDao.setFavorite(songId, isFavorite)
        }
        try {
            val song = if (songId > 0) songDao.getSongById(songId)?.toDomainModel() else if (path.isNotBlank()) songDao.getSongByPath(path)?.toDomainModel() else null
            if (song != null) {
                cloudSyncRepository?.onFavoriteChangedAsync(song, isFavorite)
            }
        } catch (_: Exception) {}
    }

    suspend fun clearAllFavorites() {
        songDao.clearAllFavorites()
    }

    suspend fun recordPlaybackSession(
        sessionId: String,
        trackId: Long,
        startedAt: Long,
        endedAt: Long,
        listenedDurationMs: Long,
        sourceContext: String,
        totalTrackDurationMs: Long,
        scrobbleThresholdType: String = "SECONDS",
        scrobbleThresholdValue: Int = 30
    ) {
        if (listenedDurationMs < 1000L) return
        val completionPercentage = if (totalTrackDurationMs > 0) {
            (listenedDurationMs.toFloat() / totalTrackDurationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

        val existing = historyDao.getHistoryBySessionId(sessionId)
        val wasAlreadyQualified = existing?.isQualified == true

        val qualifies = if (scrobbleThresholdType == "PERCENTAGE") {
            val ratio = (scrobbleThresholdValue.coerceIn(1, 100) / 100f)
            completionPercentage >= ratio || (totalTrackDurationMs <= 0L && listenedDurationMs >= 15000L)
        } else {
            val thresholdMs = (scrobbleThresholdValue.coerceAtLeast(1) * 1000L)
            listenedDurationMs >= thresholdMs
        }

        val isNowQualified = wasAlreadyQualified || qualifies

        val entity = PlaybackHistoryEntity(
            id = existing?.id ?: 0L,
            trackId = trackId,
            startedAt = startedAt,
            endedAt = endedAt,
            listenedDurationMs = listenedDurationMs,
            sourceContext = sourceContext,
            completionPercentage = completionPercentage,
            sessionId = sessionId,
            isQualified = isNowQualified,
            createdAt = existing?.createdAt ?: startedAt
        )
        historyDao.insertPlaybackHistory(entity)

        if (isNowQualified && !wasAlreadyQualified) {
            songDao.recordPlay(trackId, startedAt)
        }

        try {
            val song = songDao.getSongById(trackId)?.toDomainModel()
            cloudSyncRepository?.onPlaybackSessionRecordedAsync(
                sessionId = sessionId,
                trackId = trackId,
                startedAt = startedAt,
                endedAt = endedAt,
                listenedDurationMs = listenedDurationMs,
                sourceContext = sourceContext,
                completionPercentage = completionPercentage,
                isQualified = isNowQualified,
                song = song
            )
        } catch (_: Exception) {}
    }

    suspend fun clearRecentlyPlayedHistory() {
        historyDao.clearHistory()
        songDao.clearRecentlyPlayedHistory()
    }

    suspend fun deleteSong(song: Song, context: android.content.Context): Pair<Boolean, String?> {
        var fileDeleted = false
        var errorMsg: String? = null

        try {
            val file = java.io.File(song.path)
            if (file.exists()) {
                fileDeleted = file.delete()
                val lrcFile = java.io.File(file.parentFile, "${file.nameWithoutExtension}.lrc")
                if (lrcFile.exists()) {
                    lrcFile.delete()
                }
            }
            if (!fileDeleted && song.contentUriString.isNotEmpty()) {
                val uri = android.net.Uri.parse(song.contentUriString)
                val rows = context.contentResolver.delete(uri, null, null)
                fileDeleted = rows > 0
            }
            if (!fileDeleted && song.id > 0) {
                val mediaStoreUri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    song.id
                )
                val rows = context.contentResolver.delete(mediaStoreUri, null, null)
                fileDeleted = rows > 0
            }

            try {
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(song.path),
                    null
                ) { _, _ -> }
            } catch (_: Exception) {}
        } catch (e: SecurityException) {
            errorMsg = "Storage permission denied for ${song.displayTitle}"
            e.printStackTrace()
        } catch (e: Exception) {
            errorMsg = e.localizedMessage ?: "Deletion failed"
            e.printStackTrace()
        }

        val fileNoLongerExists = !java.io.File(song.path).exists()
        if (fileDeleted || fileNoLongerExists) {
            songDao.deleteSong(song.id)
            lyricsDao.deleteLyrics(song.id)
            playlistDao.removeSongFromAllPlaylists(song.id)
            return Pair(true, null)
        }
        return Pair(false, errorMsg ?: "File could not be removed from storage")
    }

    suspend fun updateSongArtwork(songId: Long, artworkUri: String?) {
        songDao.updateSongArtwork(songId, artworkUri)
    }

    suspend fun updatePlaylistArtwork(playlistId: Long, artworkUri: String?) {
        playlistDao.updatePlaylistArtwork(playlistId, artworkUri)
    }

    fun getTotalDurationPlayedBetween(startTime: Long, endTime: Long = 9223372036854775807L): Flow<Long> {
        return historyDao.getTotalDurationPlayedBetween(startTime, endTime)
            .map { it ?: 0L }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    fun getDailyListeningStats(days: Int = 7): Flow<List<com.example.data.model.DailyListeningStat>> {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.DAY_OF_YEAR, -(days - 1))
        }
        val startTime = cal.timeInMillis

        val dayFormat = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        val fullDateFormat = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())

        return historyDao.getPlaybackHistorySince(startTime).map { historyRecords ->
            val daySlots = mutableListOf<com.example.data.model.DailyListeningStat>()
            val currentCal = java.util.Calendar.getInstance()
            val todayDayOfYear = currentCal.get(java.util.Calendar.DAY_OF_YEAR)
            val todayYear = currentCal.get(java.util.Calendar.YEAR)

            for (i in 0 until days) {
                val dayCal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    add(java.util.Calendar.DAY_OF_YEAR, -(days - 1 - i))
                }
                val dayStart = dayCal.timeInMillis
                val dayEnd = dayStart + 24L * 60 * 60 * 1000

                val isToday = (dayCal.get(java.util.Calendar.DAY_OF_YEAR) == todayDayOfYear && dayCal.get(java.util.Calendar.YEAR) == todayYear)
                val label = if (isToday) "Today" else dayFormat.format(dayCal.time)
                val fullDate = fullDateFormat.format(dayCal.time)

                val dayHistory = historyRecords.filter { it.startedAt in dayStart until dayEnd }
                val totalDuration = dayHistory.sumOf { it.listenedDurationMs }
                val count = dayHistory.count { it.isQualified }

                daySlots.add(
                    com.example.data.model.DailyListeningStat(
                        dayStartMs = dayStart,
                        dayLabel = label,
                        fullDate = fullDate,
                        durationMs = totalDuration,
                        trackCount = count,
                        isToday = isToday
                    )
                )
            }
            daySlots
        }
    }

    fun getTimeOfDayDistribution(startTime: Long): Flow<com.example.data.model.TimeOfDayDistribution> {
        return historyDao.getPlaybackHistorySince(startTime).map { records ->
            var morning = 0L
            var afternoon = 0L
            var evening = 0L
            var night = 0L

            val cal = java.util.Calendar.getInstance()
            for (rec in records) {
                cal.timeInMillis = rec.startedAt
                val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                when (hour) {
                    in 5..11 -> morning += rec.listenedDurationMs
                    in 12..16 -> afternoon += rec.listenedDurationMs
                    in 17..21 -> evening += rec.listenedDurationMs
                    else -> night += rec.listenedDurationMs
                }
            }
            com.example.data.model.TimeOfDayDistribution(
                morningDurationMs = morning,
                afternoonDurationMs = afternoon,
                eveningDurationMs = evening,
                nightDurationMs = night
            )
        }
    }


    fun getTrackCountBetween(startTime: Long, endTime: Long = 9223372036854775807L): Flow<Int> {
        return historyDao.getTrackCountBetween(startTime, endTime)
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    fun getUniqueTrackCountBetween(startTime: Long, endTime: Long = 9223372036854775807L): Flow<Int> {
        return historyDao.getUniqueTrackCountBetween(startTime, endTime)
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    fun getMostPlayedSongsBetween(startTime: Long, endTime: Long = 9223372036854775807L, limit: Int = 20): Flow<List<Song>> {
        return historyDao.getMostPlayedSongsBetween(startTime, endTime, limit).map { list -> list.map { it.toDomainModel() } }
    }

    fun getTopArtistsBetween(startTime: Long, endTime: Long = 9223372036854775807L, limit: Int = 10): Flow<List<ArtistStat>> {
        return historyDao.getTopArtistsBetween(startTime, endTime, limit).map { list ->
            list.map { ArtistStat(name = it.name, playCount = it.playCount, totalDurationMs = it.totalDurationMs) }
        }
    }

    fun getTopAlbumsBetween(startTime: Long, endTime: Long = 9223372036854775807L, limit: Int = 10): Flow<List<AlbumStat>> {
        return historyDao.getTopAlbumsBetween(startTime, endTime, limit).map { list ->
            list.map { AlbumStat(name = it.name, artist = it.artist, artworkUri = it.artworkUri, playCount = it.playCount) }
        }
    }

    fun getMostPlayedPlaylistsBetween(startTime: Long, endTime: Long = 9223372036854775807L, limit: Int = 10): Flow<List<Playlist>> {
        return historyDao.getMostPlayedPlaylistsBetween(startTime, endTime, limit).map { list ->
            list.map { item ->
                Playlist(
                    id = item.id,
                    name = item.name,
                    songCount = item.songCount,
                    totalDurationMs = item.totalDurationMs,
                    createdAt = item.createdAt,
                    isPinned = item.isPinned,
                    artworkUri = item.artworkUri
                )
            }
        }
    }

    fun getPlaybackHistoryBetween(startTime: Long, endTime: Long = 9223372036854775807L): Flow<List<PlaybackHistoryEntity>> {
        return historyDao.getPlaybackHistoryBetween(startTime, endTime)
    }

    suspend fun getWrappedDataForPeriod(startTime: Long, endTime: Long = 9223372036854775807L): WrappedData = withContext(Dispatchers.IO) {
        val totalDurationMs = historyDao.getTotalDurationPlayedBetween(startTime, endTime).first() ?: 0L
        val trackCount = historyDao.getTrackCountBetween(startTime, endTime).first()
        val uniqueCount = historyDao.getUniqueTrackCountBetween(startTime, endTime).first()
        val topSongs = historyDao.getMostPlayedSongsBetween(startTime, endTime, 20).first().map { it.toDomainModel() }
        val topArtists = historyDao.getTopArtistsBetween(startTime, endTime, 10).first().map {
            ArtistStat(name = it.name, playCount = it.playCount, totalDurationMs = it.totalDurationMs)
        }
        val topAlbums = historyDao.getTopAlbumsBetween(startTime, endTime, 10).first().map {
            AlbumStat(name = it.name, artist = it.artist, artworkUri = it.artworkUri, playCount = it.playCount)
        }
        val history = historyDao.getPlaybackHistoryBetweenOnce(startTime, endTime)

        var morning = 0L
        var afternoon = 0L
        var evening = 0L
        var night = 0L
        val cal = java.util.Calendar.getInstance()
        val distinctDays = mutableSetOf<String>()

        for (rec in history) {
            cal.timeInMillis = rec.startedAt
            val year = cal.get(java.util.Calendar.YEAR)
            val day = cal.get(java.util.Calendar.DAY_OF_YEAR)
            distinctDays.add("${year}_$day")

            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            when (hour) {
                in 5..11 -> morning += rec.listenedDurationMs
                in 12..16 -> afternoon += rec.listenedDurationMs
                in 17..21 -> evening += rec.listenedDurationMs
                else -> night += rec.listenedDurationMs
            }
        }

        val timeOfDay = TimeOfDayDistribution(
            morningDurationMs = morning,
            afternoonDurationMs = afternoon,
            eveningDurationMs = evening,
            nightDurationMs = night
        )

        val dominantVibe = timeOfDay.dominantVibe
        val (archetype, desc) = when (dominantVibe) {
            "Night Owl" -> Pair("Night Owl Explorer", "Your peak hours are late at night with immersive, uninterrupted listening sessions.")
            "Early Bird" -> Pair("Dawn Melodist", "You start your mornings energized with music that sets the rhythm for your day.")
            "Daytime Groover" -> Pair("Midday Flow Master", "Music fuels your daytime focus, keeping your productivity high and spirits upbeat.")
            else -> Pair("Sonic Harmonizer", "You unwind in the evenings with rich soundscapes and eclectic favorites.")
        }

        val topSong = topSongs.firstOrNull()
        val topSongPlays = topSong?.playCount ?: 0
        val topSongDuration = if (topSong != null) topSongPlays * (if (topSong.durationMs > 0) topSong.durationMs else 180000L) else 0L

        WrappedData(
            totalMinutes = totalDurationMs / (1000L * 60L),
            totalTracks = trackCount,
            uniqueTracks = uniqueCount,
            activeDaysCount = distinctDays.size,
            topSong = topSong,
            topSongPlayCount = topSongPlays,
            topSongDurationMs = topSongDuration,
            topSongs = topSongs,
            topArtists = topArtists,
            topAlbums = topAlbums,
            timeOfDay = timeOfDay,
            listenerArchetype = archetype,
            archetypeDescription = desc
        )
    }

    suspend fun addFolder(path: String, name: String, isExcluded: Boolean) {
        folderDao.insertFolder(
            FolderEntity(
                path = path,
                name = name,
                uriString = path,
                isExcluded = isExcluded
            )
        )
        libraryScanner.refreshDirectoryObservers()
        libraryScanner.detectAndImportNewSongs()
    }

    suspend fun removeFolder(path: String) {
        folderDao.deleteFolder(path)
        libraryScanner.refreshDirectoryObservers()
    }

    private fun notifyPlaylistUpdated(playlistId: Long) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val pl = playlistDao.getPlaylistById(playlistId)
                if (pl != null) {
                    val songs = playlistDao.getPlaylistSongs(playlistId).firstOrNull()?.map { it.toDomainModel() } ?: emptyList()
                    cloudSyncRepository?.onPlaylistUpdatedAsync(playlistId, pl.name, pl.isPinned, songs)
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun createPlaylist(name: String): Long {
        val id = playlistDao.insertPlaylist(PlaylistEntity(name = name))
        notifyPlaylistUpdated(id)
        return id
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) {
        playlistDao.renamePlaylist(playlistId, newName)
        notifyPlaylistUpdated(playlistId)
    }

    suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deletePlaylist(playlistId)
        playlistDao.clearPlaylistSongs(playlistId)
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long, addAtTop: Boolean = false): Boolean {
        if (playlistDao.isSongInPlaylist(playlistId, songId) > 0) {
            return false
        }
        if (addAtTop) {
            val existingSongs = playlistDao.getPlaylistSongs(playlistId).firstOrNull() ?: emptyList()
            val crossRefs = mutableListOf<PlaylistSongCrossRef>()
            crossRefs.add(PlaylistSongCrossRef(playlistId, songId, 0))
            existingSongs.forEachIndexed { index, song ->
                crossRefs.add(PlaylistSongCrossRef(playlistId, song.id, index + 1))
            }
            playlistDao.updatePlaylistOrder(playlistId, crossRefs)
        } else {
            val count = playlistDao.getPlaylistSongCount(playlistId)
            playlistDao.insertPlaylistSong(PlaylistSongCrossRef(playlistId, songId, count))
        }
        notifyPlaylistUpdated(playlistId)
        return true
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        playlistDao.deletePlaylistSong(playlistId, songId)
        notifyPlaylistUpdated(playlistId)
    }

    suspend fun getPlaylistsContainingSong(songId: Long): List<Playlist> {
        return playlistDao.getPlaylistsContainingSong(songId).map { entity ->
            val count = playlistDao.getPlaylistSongCount(entity.id)
            val duration = playlistDao.getPlaylistTotalDuration(entity.id) ?: 0L
            entity.toDomainModel(count, duration)
        }
    }

    suspend fun removeDuplicateSongsFromPlaylist(playlistId: Long): Int {
        val songsList = playlistDao.getPlaylistSongs(playlistId).firstOrNull() ?: emptyList()
        if (songsList.size <= 1) return 0

        val seenKeys = mutableSetOf<String>()
        val uniqueSongIds = mutableListOf<Long>()
        var removedCount = 0

        for (song in songsList) {
            val key = "${song.title.lowercase().trim()}___${song.artist.lowercase().trim()}"
            if (seenKeys.add(key)) {
                uniqueSongIds.add(song.id)
            } else {
                removedCount++
            }
        }

        if (removedCount > 0) {
            playlistDao.clearPlaylistSongs(playlistId)
            val crossRefs = uniqueSongIds.mapIndexed { index, songId ->
                PlaylistSongCrossRef(playlistId, songId, index)
            }
            playlistDao.insertPlaylistSongs(crossRefs)
            notifyPlaylistUpdated(playlistId)
        }

        return removedCount
    }

    fun getPlaylistSongs(playlistId: Long): Flow<List<Song>> {
        return playlistDao.getPlaylistSongs(playlistId).map { list -> list.map { it.toDomainModel() } }
    }

    suspend fun updatePlaylistSongOrder(playlistId: Long, songIds: List<Long>) {
        val seen = mutableSetOf<Long>()
        val crossRefs = mutableListOf<PlaylistSongCrossRef>()
        var pos = 0
        for (songId in songIds) {
            if (seen.add(songId)) {
                crossRefs.add(PlaylistSongCrossRef(playlistId, songId, pos++))
            }
        }
        playlistDao.updatePlaylistOrder(playlistId, crossRefs)
        notifyPlaylistUpdated(playlistId)
    }

    fun searchSongs(query: String): Flow<List<Song>> {
        return songDao.searchSongs(query).map { list -> list.map { it.toDomainModel() } }
    }

    suspend fun addRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            recentSearchDao.insertSearch(RecentSearchEntity(query = trimmed, timestamp = System.currentTimeMillis()))
        }
    }

    suspend fun deleteRecentSearch(query: String) {
        recentSearchDao.deleteSearch(query)
    }

    suspend fun clearRecentSearches() {
        recentSearchDao.clearAll()
    }

    suspend fun updateSongTags(
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newArtworkUri: String?,
        newLyrics: String?,
        context: android.content.Context,
        newGenre: String? = null,
        newYear: Int? = null,
        newTrackNumber: Int? = null
    ): Song {
        var finalArtworkUri = newArtworkUri
        if (!newArtworkUri.isNullOrBlank()) {
            val persistentUri = com.example.util.ArtworkManager.persistSongCover(context, song.id, newArtworkUri, song.path)
            if (!persistentUri.isNullOrBlank()) {
                finalArtworkUri = persistentUri
            }
        } else if (finalArtworkUri.isNullOrBlank()) {
            // Treat artwork as independent metadata: Preserve existing artwork across title/tag changes
            finalArtworkUri = com.example.ui.components.ArtworkResolutionCache.getManualCoverUri(context, song.id, song.path)
                ?: song.artworkUri
                ?: com.example.ui.components.ArtworkResolutionCache.getOrCheckDisk(context, song.id, song.path)
                ?: com.example.util.ArtworkManager.getOrExtractArtworkUri(context, song.id, song.path, song.albumId, song.album)
        }

        if (!finalArtworkUri.isNullOrBlank()) {
            com.example.ui.components.ArtworkResolutionCache.cache[song.id] = finalArtworkUri
        }

        val updatedSong = song.copy(
            title = newTitle.ifBlank { song.title },
            artist = newArtist.ifBlank { song.artist },
            album = newAlbum.ifBlank { song.album },
            genre = newGenre?.ifBlank { song.genre } ?: song.genre,
            year = if (newYear != null && newYear > 0) newYear else song.year,
            trackNumber = if (newTrackNumber != null && newTrackNumber > 0) newTrackNumber else song.trackNumber,
            artworkUri = finalArtworkUri,
            isManuallyEdited = true
        )

        com.example.util.ManualTagStore.markManuallyEdited(
            context = context,
            songId = song.id,
            path = song.path,
            title = updatedSong.title,
            artist = updatedSong.artist,
            album = updatedSong.album,
            genre = updatedSong.genre,
            year = updatedSong.year,
            trackNumber = updatedSong.trackNumber,
            artworkUri = finalArtworkUri,
            lyrics = newLyrics
        )

        // Keep online artwork cache synchronized with the new metadata
        com.example.util.OnlineArtistImageFetcher.updateSongMetadataCacheKeys(
            context = context,
            songId = song.id,
            newTitle = updatedSong.title,
            newArtist = updatedSong.artist,
            newAlbum = updatedSong.album
        )

        val entity = SongEntity.fromDomainModel(updatedSong)
        songDao.updateSong(entity)
        if (!finalArtworkUri.isNullOrBlank()) {
            songDao.updateSongArtwork(song.id, finalArtworkUri)
        }

        val existingLyrics = lyricsDao.getLyricsForSong(song.id)

        if (newLyrics != null) {
            val (parsedPlain, parsedSynced) = com.example.util.LrcParser.parseLrcContent(newLyrics)

            val finalSynced = if (!parsedSynced.isNullOrBlank()) {
                parsedSynced
            } else if (existingLyrics != null && !existingLyrics.syncedLyricsJson.isNullOrBlank()) {
                val existingFormatted = com.example.util.LrcParser.formatSyncedJsonToLrc(existingLyrics.syncedLyricsJson)
                val isUnchanged = newLyrics.isBlank() ||
                        newLyrics.trim() == existingFormatted?.trim() ||
                        newLyrics.trim() == existingLyrics.plainLyrics?.trim()
                if (isUnchanged) {
                    existingLyrics.syncedLyricsJson
                } else {
                    null
                }
            } else {
                null
            }

            val finalPlain = if (!parsedPlain.isNullOrBlank()) parsedPlain else existingLyrics?.plainLyrics

            if (finalPlain != null || finalSynced != null) {
                val lyricsEntity = LyricsEntity(
                    songId = song.id,
                    plainLyrics = finalPlain,
                    syncedLyricsJson = finalSynced,
                    isCached = true,
                    source = if (finalSynced == existingLyrics?.syncedLyricsJson) (existingLyrics?.source ?: "Tag Editor") else "Tag Editor"
                )
                lyricsDao.insertLyrics(lyricsEntity)
            }
        }

        // Write tags directly to local device storage audio file (ID3v2 for MP3, .lrc companion, MediaStore, and MediaScanner)
        try {
            com.example.util.AudioTagWriter.writeAudioTags(
                context = context,
                songPath = song.path,
                contentUriString = song.contentUriString,
                songId = song.id,
                newTitle = updatedSong.title,
                newArtist = updatedSong.artist,
                newAlbum = updatedSong.album,
                newArtworkUri = finalArtworkUri,
                newLyrics = newLyrics,
                newGenre = updatedSong.genre,
                newYear = updatedSong.year,
                newTrackNumber = updatedSong.trackNumber
            )
        } catch (_: Exception) {
            // Gracefully handle file tag writing if restricted
        }

        return updatedSong
    }
}

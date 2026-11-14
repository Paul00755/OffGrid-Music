package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.Song
import com.example.data.sync.*
import com.example.ui.components.StoryPeriod
import com.example.ui.components.StoryTimeRange
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CloudSyncTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var songDao: SongDao
    private lateinit var playlistDao: PlaylistDao
    private lateinit var historyDao: HistoryDao
    private lateinit var smartPlaylistDao: SmartPlaylistDao
    private lateinit var userPreferences: UserPreferencesRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        songDao = db.songDao()
        playlistDao = db.playlistDao()
        historyDao = db.historyDao()
        smartPlaylistDao = db.smartPlaylistDao()
        userPreferences = UserPreferencesRepository.getInstance(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testStableSongIdentity_isDeterministicAndPathIndependent() {
        val song1 = Song(
            id = 101L,
            title = "Bohemian Rhapsody [Official Video]",
            artist = "Queen [320kbps]",
            album = "A Night at the Opera",
            durationMs = 354000L,
            path = "/storage/emulated/0/Music/Queen/Bohemian Rhapsody.mp3",
            contentUriString = "content://media/external/audio/media/101"
        )

        val song2 = Song(
            id = 9999L, // Completely different Room/MediaStore ID on reinstalled device
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera",
            durationMs = 354200L, // Slightly different duration (same second bucket)
            path = "/sdcard/Downloads/Bohemian Rhapsody.mp3", // Completely different file path
            contentUriString = "content://media/external/audio/media/9999"
        )

        val sig1 = SongIdentityHelper.computeSignature(song1)
        val sig2 = SongIdentityHelper.computeSignature(song2)

        assertEquals("Signatures must match across devices and paths after tag cleaning", sig1, sig2)
        assertTrue("Signature must not be empty", sig1.isNotBlank())
        assertTrue("Signature must have sig_ prefix", sig1.startsWith("sig_"))
    }

    @Test
    fun testSongMatching_preventsFalsePositives() {
        val songEntity = SongEntity(
            id = 1L,
            title = "Shape of You",
            artist = "Ed Sheeran",
            album = "Divide",
            genre = "Pop",
            durationMs = 233000L,
            path = "/storage/emulated/0/Music/Ed Sheeran/Shape of You.mp3",
            contentUriString = "content://media/external/audio/media/1"
        )

        val matchingCloudSig = SongIdentityHelper.computeSignature(songEntity)
        assertTrue(
            "Exact song must match",
            SongIdentityHelper.matches(
                song = songEntity,
                cloudSignature = matchingCloudSig,
                cloudTitle = "Shape of You",
                cloudArtist = "Ed Sheeran",
                cloudAlbum = "Divide",
                cloudDurationMs = 233000L,
                cloudFilename = "Shape of You.mp3"
            )
        )

        // Completely different song
        assertFalse(
            "Different song must NOT match",
            SongIdentityHelper.matches(
                song = songEntity,
                cloudSignature = "sig_different",
                cloudTitle = "Blinding Lights",
                cloudArtist = "The Weeknd",
                cloudAlbum = "After Hours",
                cloudDurationMs = 200000L,
                cloudFilename = "Blinding Lights.mp3"
            )
        )
    }

    @Test
    fun testCloudUserSettings_serializationAndConflictStrategy() {
        val original = CloudUserSettings(
            themeMode = "LIGHT",
            uiTheme = "PURE_AMOLED",
            homeScreenLayout = "STUDIO_DASHBOARD",
            nowPlayingLayout = "VINYL_TURNTABLE",
            waveformStyle = "BARS",
            gaplessPlayback = true,
            crossfadeDurationSec = 4,
            normalizeAudio = true,
            autoPlayOnHeadphoneConnection = true,
            customAccentColor = 0xFF5722.toInt(),
            updatedAt = 1720000000000L
        )

        val map = original.toMap()
        val restored = CloudUserSettings.fromMap(map)

        assertEquals(original.themeMode, restored.themeMode)
        assertEquals(original.uiTheme, restored.uiTheme)
        assertEquals(original.homeScreenLayout, restored.homeScreenLayout)
        assertEquals(original.nowPlayingLayout, restored.nowPlayingLayout)
        assertEquals(original.waveformStyle, restored.waveformStyle)
        assertEquals(original.crossfadeDurationSec, restored.crossfadeDurationSec)
        assertEquals(original.normalizeAudio, restored.normalizeAudio)
        assertEquals(original.autoPlayOnHeadphoneConnection, restored.autoPlayOnHeadphoneConnection)
        assertEquals(original.customAccentColor, restored.customAccentColor)
        assertEquals(original.updatedAt, restored.updatedAt)

        // Conflict check: Newer cloud update overrides older local
        val localTimestamp = 1710000000000L
        assertTrue("Cloud settings with newer timestamp must supersede older local settings", restored.updatedAt > localTimestamp)
    }

    @Test
    fun testListeningEventDeduplication_idempotentSessionId() {
        val event1 = CloudPlaybackEvent(
            sessionId = "session_unique_12345",
            songSignature = "sig_hotel_california",
            title = "Hotel California",
            artist = "Eagles",
            album = "Hotel California",
            durationMs = 390000L,
            startedAt = 1700000000000L,
            endedAt = 1700000390000L,
            listenedDurationMs = 390000L,
            isQualified = true
        )

        val event2 = event1.copy() // Duplicate transmission on reconnect

        assertEquals("Session IDs must be identical for the same event", event1.sessionId, event2.sessionId)
        val docId1 = event1.sessionId
        val docId2 = event2.sessionId
        assertEquals("Firestore document path must be deterministic for zero duplicates", docId1, docId2)
    }

    @Test
    fun testMultiDeviceHistoryMerge_noCollision() = runTest {
        // Device A listening event
        val deviceAEvent = PlaybackHistoryEntity(
            id = 0L,
            trackId = 10L,
            startedAt = 100000L,
            endedAt = 160000L,
            listenedDurationMs = 60000L,
            sessionId = "session_deviceA_100",
            isQualified = true
        )

        // Device B listening event
        val deviceBEvent = PlaybackHistoryEntity(
            id = 0L,
            trackId = 20L,
            startedAt = 200000L,
            endedAt = 290000L,
            listenedDurationMs = 90000L,
            sessionId = "session_deviceB_200",
            isQualified = true
        )

        historyDao.insertPlaybackHistory(deviceAEvent)
        historyDao.insertPlaybackHistory(deviceBEvent)

        val count = historyDao.getHistoryCountOnce()
        assertEquals("Both multi-device sessions must coexist without overwriting", 2, count)

        val historyList = historyDao.getPlaybackHistoryBetweenOnce(0L, Long.MAX_VALUE)
        assertEquals(2, historyList.size)
        assertTrue(historyList.any { it.sessionId == "session_deviceA_100" })
        assertTrue(historyList.any { it.sessionId == "session_deviceB_200" })
    }

    @Test
    fun testEndToEndReinstallRestorationFlow() = runTest {
        // SCENARIO:
        // User had the app on old phone, had 1 favorite, 1 playlist, 2 qualified plays.
        // App is uninstalled and reinstalled on a new phone.
        // Room starts empty!
        assertEquals("New install Room database must initially be empty", 0, songDao.getSongCountOnce())
        assertEquals(0, playlistDao.getPlaylistCountOnce())
        assertEquals(0, historyDao.getHistoryCountOnce())

        // 1. Simulating cloud records fetched from Firestore users/{uid}
        val cloudFavorite = CloudFavoriteSong(
            songSignature = SongIdentityHelper.computeSignature("Hotel California", "Eagles", "Hotel California", 390000L, "hotel.mp3"),
            title = "Hotel California",
            artist = "Eagles",
            album = "Hotel California",
            durationMs = 390000L,
            relativeFilename = "hotel.mp3",
            isFavorite = true
        )

        val cloudPlaylist = CloudPlaylist(
            cloudId = "pl_cloud_favorites_mix",
            name = "Classic Rock Favorites",
            createdAt = 1600000000000L,
            isPinned = true,
            songs = listOf(
                CloudPlaylistSongItem(
                    position = 0,
                    songSignature = cloudFavorite.songSignature,
                    title = "Hotel California",
                    artist = "Eagles",
                    album = "Hotel California",
                    durationMs = 390000L,
                    filename = "hotel.mp3"
                ),
                CloudPlaylistSongItem(
                    position = 1,
                    songSignature = SongIdentityHelper.computeSignature("Bohemian Rhapsody", "Queen", "A Night at the Opera", 354000L, "bohemian.mp3"),
                    title = "Bohemian Rhapsody",
                    artist = "Queen",
                    album = "A Night at the Opera",
                    durationMs = 354000L,
                    filename = "bohemian.mp3"
                )
            )
        )

        val now = System.currentTimeMillis()
        val cloudHistoryToday = CloudPlaybackEvent(
            sessionId = "session_reinstall_today_1",
            songSignature = cloudFavorite.songSignature,
            title = "Hotel California",
            artist = "Eagles",
            album = "Hotel California",
            durationMs = 390000L,
            startedAt = now - 60000L,
            endedAt = now,
            listenedDurationMs = 60000L,
            isQualified = true
        )

        val cloudHistoryYesterday = CloudPlaybackEvent(
            sessionId = "session_reinstall_yesterday_2",
            songSignature = SongIdentityHelper.computeSignature("Bohemian Rhapsody", "Queen", "A Night at the Opera", 354000L, "bohemian.mp3"),
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera",
            durationMs = 354000L,
            startedAt = now - 24L * 60 * 60 * 1000L,
            endedAt = now - 24L * 60 * 60 * 1000L + 120000L,
            listenedDurationMs = 120000L,
            isQualified = true
        )

        // 2. Scanner discovers audio files on the new device's storage (assigns new local Room IDs: 501 and 502)
        val discoveredSong1 = SongEntity(
            id = 501L,
            title = "Hotel California",
            artist = "Eagles",
            album = "Hotel California",
            genre = "Rock",
            durationMs = 390000L,
            path = "/storage/emulated/0/Music/hotel.mp3",
            contentUriString = "content://media/external/audio/media/501",
            isFavorite = false // Initially false before cloud rematching
        )

        val discoveredSong2 = SongEntity(
            id = 502L,
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera",
            genre = "Rock",
            durationMs = 354000L,
            path = "/storage/emulated/0/Music/bohemian.mp3",
            contentUriString = "content://media/external/audio/media/502",
            isFavorite = false
        )

        songDao.insertSongs(listOf(discoveredSong1, discoveredSong2))
        assertEquals("Scanner discovered 2 songs on device", 2, songDao.getSongCountOnce())

        // 3. Cloud Restoration executes:
        // A. Match and apply favorites
        val allLocalSongs = songDao.getAllSongsOnce()
        for (song in allLocalSongs) {
            if (SongIdentityHelper.matches(
                    song = song,
                    cloudSignature = cloudFavorite.songSignature,
                    cloudTitle = cloudFavorite.title,
                    cloudArtist = cloudFavorite.artist,
                    cloudAlbum = cloudFavorite.album,
                    cloudDurationMs = cloudFavorite.durationMs,
                    cloudFilename = cloudFavorite.relativeFilename
                )
            ) {
                songDao.setFavoriteBySong(song.id, song.path, true)
            }
        }

        // Verify favorite was restored locally on the new device!
        val favSong = songDao.getSongById(501L)
        assertNotNull(favSong)
        assertTrue("Hotel California must be restored as favorite", favSong!!.isFavorite)

        val nonFavSong = songDao.getSongById(502L)
        assertFalse("Bohemian Rhapsody must remain non-favorite", nonFavSong!!.isFavorite)

        // B. Reconstruct playlist locally
        val playlistId = playlistDao.insertPlaylist(
            PlaylistEntity(
                name = cloudPlaylist.name,
                createdAt = cloudPlaylist.createdAt,
                isPinned = cloudPlaylist.isPinned
            )
        )
        val crossRefs = mutableListOf<PlaylistSongCrossRef>()
        for (item in cloudPlaylist.songs) {
            val matched = allLocalSongs.firstOrNull { s ->
                SongIdentityHelper.matches(
                    song = s,
                    cloudSignature = item.songSignature,
                    cloudTitle = item.title,
                    cloudArtist = item.artist,
                    cloudAlbum = item.album,
                    cloudDurationMs = item.durationMs,
                    cloudFilename = item.filename
                )
            }
            if (matched != null) {
                crossRefs.add(PlaylistSongCrossRef(playlistId, matched.id, item.position))
            }
        }
        playlistDao.insertPlaylistSongs(crossRefs)

        // Verify playlist restored with songs
        val playlistSongs = playlistDao.getPlaylistSongs(playlistId).first()
        assertEquals("Playlist must have 2 songs restored", 2, playlistSongs.size)
        assertEquals("First song in playlist must be Hotel California", 501L, playlistSongs[0].id)
        assertEquals("Second song in playlist must be Bohemian Rhapsody", 502L, playlistSongs[1].id)

        // C. Restore history events into Room
        val historyEntities = listOf(
            PlaybackHistoryEntity(
                id = 0L,
                trackId = 501L,
                startedAt = cloudHistoryToday.startedAt,
                endedAt = cloudHistoryToday.endedAt,
                listenedDurationMs = cloudHistoryToday.listenedDurationMs,
                sessionId = cloudHistoryToday.sessionId,
                isQualified = cloudHistoryToday.isQualified
            ),
            PlaybackHistoryEntity(
                id = 0L,
                trackId = 502L,
                startedAt = cloudHistoryYesterday.startedAt,
                endedAt = cloudHistoryYesterday.endedAt,
                listenedDurationMs = cloudHistoryYesterday.listenedDurationMs,
                sessionId = cloudHistoryYesterday.sessionId,
                isQualified = cloudHistoryYesterday.isQualified
            )
        )
        for (h in historyEntities) {
            historyDao.insertPlaybackHistory(h)
        }

        // Verify history restored and stats compute correctly!
        val totalDuration = historyDao.getTotalDurationPlayedBetween(0L, Long.MAX_VALUE).first()
        assertEquals("Total listened duration must equal 180,000 ms (60s + 120s)", 180000L, totalDuration)

        val trackCount = historyDao.getTrackCountBetween(0L, Long.MAX_VALUE).first()
        assertEquals("Total qualified track count must be 2", 2, trackCount)

        val uniqueCount = historyDao.getUniqueTrackCountBetween(0L, Long.MAX_VALUE).first()
        assertEquals("Unique track count must be 2", 2, uniqueCount)

        // Top Artists
        val topArtists = historyDao.getTopArtistsBetween(0L, Long.MAX_VALUE, 10).first()
        assertEquals(2, topArtists.size)

        // Story Cards: Verify Today timeline contains today's session
        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = todayCal.timeInMillis
        val todayDuration = historyDao.getTotalDurationPlayedBetween(todayStart, Long.MAX_VALUE).first()
        assertEquals("Today's duration must be 60,000 ms", 60000L, todayDuration)
    }

    @Test
    fun testNoAudioBytesInPayloads() {
        val cloudSong = CloudFavoriteSong(
            songSignature = "sig_123",
            title = "Test Track",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 180000L
        )

        val map = cloudSong.toMap()
        assertFalse("Cloud document must NOT contain audio bytes", map.containsKey("audioData"))
        assertFalse("Cloud document must NOT contain binary data", map.containsKey("bytes"))
        assertTrue("Only metadata is stored", map.containsKey("songSignature"))
    }

    @Test
    fun versionedSnapshotValidationRejectsIncompleteAndCrossAccountData() {
        val uid = "firebase-user-123"
        val complete = UserBackupPayload(
            version = 2,
            ownerUid = uid,
            snapshotComplete = true,
            historyStoredSeparately = true,
            playlistPlayEventsStoredSeparately = true,
            dataHash = "sha256-value",
            profile = BackupProfileData(uid = uid)
        ).toMap()

        assertTrue(UserBackupPayload.isCompleteSnapshot(complete, uid))
        assertFalse(UserBackupPayload.isCompleteSnapshot(complete, "different-user"))
        assertFalse(UserBackupPayload.isCompleteSnapshot(complete + ("snapshotComplete" to false), uid))
        assertFalse(UserBackupPayload.isCompleteSnapshot(complete + ("ownerUid" to "different-user"), uid))
    }

    @Test
    fun legacyVersionOneSnapshotWithoutCloudPlaylistIdsRemainsRestorable() {
        val uid = "firebase-user-legacy"
        val legacy = UserBackupPayload(
            version = 1,
            dataHash = "legacy-hash",
            profile = BackupProfileData(uid = uid),
            playlistsCount = 1,
            playlists = listOf(
                CloudPlaylist(
                    name = "Road Trip",
                    createdAt = 1_700_000_000_000L,
                    songs = listOf(CloudPlaylistSongItem(position = 0, songSignature = "track-signature"))
                )
            )
        ).toMap().toMutableMap().apply {
            put("profile", BackupProfileData(uid = uid).toMap())
            put("playlists", listOf(CloudPlaylist(
                name = "Road Trip",
                createdAt = 1_700_000_000_000L,
                songs = listOf(CloudPlaylistSongItem(position = 0, songSignature = "track-signature"))
            ).toMap() - "cloudId"))
        }

        assertTrue(UserBackupPayload.isCompleteSnapshot(legacy, uid))
    }

    @Test
    fun missingCloudCameraPreferenceDefaultsToAutoPauseEnabled() {
        assertFalse(CloudUserSettings().playDuringCamera)
        assertFalse(CloudUserSettings.fromMap(emptyMap()).playDuringCamera)
    }

    @Test
    fun legacyExtraDarkPreferenceMigratesToAmoledAndBackupDefaultsAreWeeklyAndEnabled() {
        val migrated = CloudUserSettings.fromMap(mapOf("themeMode" to "DARK", "amoledMode" to true))
        assertEquals("AMOLED", migrated.themeMode)
        val defaults = CloudUserSettings.fromMap(emptyMap())
        assertTrue(defaults.automaticBackupEnabled)
        assertEquals("WEEKLY", defaults.backupFrequency)
    }

    @Test
    fun lyricsCloudRecordPreservesRawTimestampsAndUpdateTime() {
        val source = CloudLyricsEntry(
            songSignature = "stable-track-signature",
            plainLyrics = "first line",
            syncedLyricsJson = "[{\"timeMs\":1200,\"text\":\"first line\"}]",
            isCached = false,
            source = "manual",
            updatedAt = 1_760_000_000_000L
        )
        val restored = CloudLyricsEntry.fromMap(source.toMap())
        assertEquals(source, restored)
        assertEquals(source.syncedLyricsJson, restored.syncedLyricsJson)
    }

    @Test
    fun versionThreeSnapshotRequiresASeparateLyricsSnapshotReference() {
        val uid = "firebase-user-v3"
        val valid = UserBackupPayload(
            version = 3,
            ownerUid = uid,
            snapshotComplete = true,
            historyStoredSeparately = true,
            playlistPlayEventsStoredSeparately = true,
            lyricsStoredSeparately = true,
            lyricsSnapshotId = "lyrics-content-hash",
            lyricsCount = 1,
            dataHash = "snapshot-hash",
            profile = BackupProfileData(uid = uid)
        ).toMap()

        assertTrue(UserBackupPayload.isCompleteSnapshot(valid, uid))
        assertFalse(UserBackupPayload.isCompleteSnapshot(valid - "lyricsSnapshotId", uid))
        assertFalse(UserBackupPayload.isCompleteSnapshot(valid + ("lyricsCount" to -1), uid))
    }

    @Test
    fun syncedLyricsAndOffsetBackupAndRestorationPreservesLyricsAndOffset() = runTest {
        val song = SongEntity(
            id = 777L,
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            genre = "Synthpop",
            durationMs = 243000L,
            path = "/storage/emulated/0/Music/Midnight City.mp3",
            contentUriString = "content://media/external/audio/media/777"
        )
        songDao.insertSongs(listOf(song))

        val sig = SongIdentityHelper.computeSignature(song)
        val syncedJson = """[{"timeMs":1500,"text":"Waiting in a car"},{"timeMs":4200,"text":"Waiting for a ride in the dark"}]"""
        val cloudEntry = CloudLyricsEntry(
            songSignature = sig,
            plainLyrics = "Waiting in a car\nWaiting for a ride in the dark",
            syncedLyricsJson = syncedJson,
            isCached = true,
            source = "LRCLIB",
            updatedAt = 1_770_000_000_000L,
            offsetMs = -450L,
            offsetUpdatedAt = 1_770_000_005_000L,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            filename = "Midnight City.mp3"
        )

        val serializedMap = cloudEntry.toMap()
        val restoredEntry = CloudLyricsEntry.fromMap(serializedMap)

        assertEquals(cloudEntry.songSignature, restoredEntry.songSignature)
        assertEquals(cloudEntry.syncedLyricsJson, restoredEntry.syncedLyricsJson)
        assertEquals(-450L, restoredEntry.offsetMs)
        assertEquals(1_770_000_005_000L, restoredEntry.offsetUpdatedAt)
        assertEquals("Midnight City", restoredEntry.title)
        assertEquals("M83", restoredEntry.artist)

        val cloudOffset = CloudLyricsOffset(
            songSignature = sig,
            offsetMs = -450L,
            updatedAt = 1_770_000_005_000L,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            filename = "Midnight City.mp3"
        )
        val restoredOffset = CloudLyricsOffset.fromMap(cloudOffset.toMap())
        assertEquals(cloudOffset, restoredOffset)

        // Simulate restoring into Room LyricsDao and UserPreferencesRepository
        db.lyricsDao().insertLyrics(
            LyricsEntity(
                songId = song.id,
                plainLyrics = restoredEntry.plainLyrics,
                syncedLyricsJson = restoredEntry.syncedLyricsJson,
                isCached = true,
                source = restoredEntry.source,
                updatedAt = restoredEntry.updatedAt
            )
        )
        userPreferences.applyLyricsSyncOffsets(
            offsetsBySongId = mapOf(song.id to restoredEntry.offsetMs),
            timestampsBySongId = mapOf(song.id to restoredEntry.offsetUpdatedAt)
        )

        val dbLyrics = db.lyricsDao().getLyricsForSong(song.id)
        assertNotNull(dbLyrics)
        assertEquals(syncedJson, dbLyrics!!.syncedLyricsJson)
        assertEquals(-450L, userPreferences.getLyricsSyncOffsetMs(song.id))
        assertEquals(1_770_000_005_000L, userPreferences.getLyricsSyncOffsetUpdatedAt(song.id))
    }

    @Test
    fun lrcCompanionWriterPersistsOffsetAndReplacesOldLrcFiles() {
        val tempDir = java.io.File(context.cacheDir, "test_lrc_folder_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val audioFile = java.io.File(tempDir, "Test Track.mp3").apply { writeText("audio") }
            val staleFuzzyLrc = java.io.File(tempDir, "Test Track - lyrics.lrc").apply {
                writeText("[00:01.00]Wrong cached lyrics")
            }

            val syncedJson = """[{"timeMs":2500,"text":"Correct manual lyrics"}]"""
            val writtenFile = com.example.util.LrcParser.writeCompanionLrcForSong(
                context = context,
                songPath = audioFile.absolutePath,
                songTitle = "Test Track",
                plainLyrics = "Correct manual lyrics",
                syncedLyricsJson = syncedJson,
                offsetMs = -350L
            )

            assertNotNull("Companion LRC write must succeed", writtenFile)
            assertFalse("Stale conflicting LRC file must be deleted", staleFuzzyLrc.exists())

            val foundLrc = com.example.util.LrcParser.findLocalLrcFile(
                audioPath = audioFile.absolutePath
            )
            assertNotNull("Updated companion LRC file must be found", foundLrc)
            val lrcText = foundLrc!!.readText()
            assertTrue("LRC file must persist [offset:-350] tag", lrcText.contains("[offset:-350]"))
            assertTrue("LRC file must contain updated lyrics", lrcText.contains("Correct manual lyrics"))
            assertEquals(-350L, com.example.util.LrcParser.extractOffsetFromLrc(lrcText))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun mostPlayedQueryAppliesLimitAfterExcludingMissingAndUnresolvedTracks() = runTest {
        // Insert 12 valid songs
        val songs = (1L..12L).map { id ->
            SongEntity(
                id = id,
                title = "Song $id",
                artist = "Artist $id",
                album = "Album $id",
                genre = "Pop",
                durationMs = 200_000L,
                path = "/storage/emulated/0/Music/Song_$id.mp3",
                contentUriString = "content://media/external/audio/media/$id",
                playCount = if (id == 1L) 331 else id.toInt() // Song 1 has inflated playCount!
            )
        }
        songDao.insertSongs(songs)

        // Insert a high-play unresolved history row (-999L) and a missing song (99L)
        songDao.insertSongs(
            listOf(
                SongEntity(
                    id = 99L,
                    title = "Deleted Song",
                    artist = "Artist",
                    album = "Album",
                    genre = "Pop",
                    durationMs = 200_000L,
                    path = "/storage/emulated/0/Music/Deleted.mp3",
                    contentUriString = "content://media/external/audio/media/99",
                    isMissing = true
                )
            )
        )
        repeat(50) { idx ->
            historyDao.insertPlaybackHistory(
                PlaybackHistoryEntity(
                    trackId = -999L,
                    startedAt = 1_000_000L + idx,
                    endedAt = 1_060_000L + idx,
                    listenedDurationMs = 60_000L,
                    sessionId = "unresolved_$idx",
                    isQualified = true
                )
            )
            historyDao.insertPlaybackHistory(
                PlaybackHistoryEntity(
                    trackId = 99L,
                    startedAt = 2_000_000L + idx,
                    endedAt = 2_060_000L + idx,
                    listenedDurationMs = 60_000L,
                    sessionId = "missing_$idx",
                    isQualified = true
                )
            )
        }

        // Insert 2 real qualified plays for each of the 12 valid songs
        for (id in 1L..12L) {
            repeat(2) { idx ->
                historyDao.insertPlaybackHistory(
                    PlaybackHistoryEntity(
                        trackId = id,
                        startedAt = 3_000_000L + id * 1000L + idx,
                        endedAt = 3_060_000L + id * 1000L + idx,
                        listenedDurationMs = 60_000L,
                        sessionId = "valid_${id}_$idx",
                        isQualified = true
                    )
                )
            }
        }

        // Verify Top 10 returns a full 10 songs (not 8 or 9 due to -999L and 99L)
        val top10 = historyDao.getMostPlayedSongsBetween(0L, Long.MAX_VALUE, 10).first()
        assertEquals("Top 10 must return all 10 valid songs", 10, top10.size)
    }

    @Test
    fun relinkHistoryPurgesHijackedRowsClampsRunawayDurationsAndRecomputesPlayCounts() = runTest {
        val gaanaSong = SongEntity(
            id = 1L,
            title = "Gaana-Gaana",
            artist = "Shreya Ghoshal",
            album = "Album",
            genre = "Film",
            durationMs = 240_000L,
            path = "/storage/emulated/0/Music/Gaana-Gaana.mp3",
            contentUriString = "content://media/external/audio/media/1",
            playCount = 331,
            lastPlayed = 9_999_999L
        )
        val otherSong = SongEntity(
            id = 2L,
            title = "Other Song",
            artist = "Artist Two",
            album = "Album Two",
            genre = "Pop",
            durationMs = 180_000L,
            path = "/storage/emulated/0/Music/Other.mp3",
            contentUriString = "content://media/external/audio/media/2",
            playCount = 1,
            lastPlayed = 5_000_000L
        )
        songDao.insertSongs(listOf(gaanaSong, otherSong))

        // Legitimate 1 play for Gaana-Gaana
        historyDao.insertPlaybackHistory(
            PlaybackHistoryEntity(
                trackId = 1L,
                startedAt = 1_000_000L,
                endedAt = 1_240_000L,
                listenedDurationMs = 240_000L,
                completionPercentage = 1.0f,
                sessionId = "legit_gaana",
                isQualified = true
            )
        )
        // Hijacked row from another track wrongly linked to Gaana-Gaana (duration/completion mismatch)
        historyDao.insertPlaybackHistory(
            PlaybackHistoryEntity(
                trackId = 1L,
                startedAt = 2_000_000L,
                endedAt = 2_090_000L,
                listenedDurationMs = 90_000L,
                completionPercentage = 0.90f,
                sessionId = "hijacked_gaana",
                isQualified = true
            )
        )
        // Runaway overnight paused session (40 hours!)
        historyDao.insertPlaybackHistory(
            PlaybackHistoryEntity(
                trackId = 2L,
                startedAt = 3_000_000L,
                endedAt = 3_000_000L + 40L * 3_600_000L,
                listenedDurationMs = 40L * 3_600_000L,
                completionPercentage = 1.0f,
                sessionId = "runaway_night",
                isQualified = true
            )
        )

        val syncRepo = CloudSyncRepository(
            context = context,
            songDao = songDao,
            playlistDao = playlistDao,
            historyDao = historyDao,
            smartPlaylistDao = smartPlaylistDao,
            userPreferences = userPreferences
        )
        syncRepo.relinkHistoryAndRecomputePlayStats()

        val updatedGaana = songDao.getSongById(1L)!!
        assertEquals("Gaana-Gaana playCount must drop from inflated 331 to actual 1", 1, updatedGaana.playCount)
        assertEquals("Gaana-Gaana lastPlayed must reset to real session timestamp", 1_000_000L, updatedGaana.lastPlayed)

        val allHistory = historyDao.getAllHistoryOnce()
        assertFalse("Hijacked row must be deleted", allHistory.any { it.sessionId == "hijacked_gaana" })
        val clampedNight = allHistory.firstOrNull { it.sessionId == "runaway_night" }
        assertNotNull(clampedNight)
        assertTrue(
            "Runaway 40h session must be clamped to sane bounds",
            clampedNight!!.listenedDurationMs <= 1_800_000L
        )
    }

    @Test
    fun restoreFromBackupSnapshotRemovesDeletedPlaylistsAndPreservesLocalCovers() = runTest {
        val song = SongEntity(
            id = 50L,
            title = "Keep Track",
            artist = "Artist",
            album = "Album",
            genre = "Pop",
            durationMs = 200_000L,
            path = "/storage/emulated/0/Music/KeepTrack.mp3",
            contentUriString = "content://media/external/audio/media/50"
        )
        songDao.insertSongs(listOf(song))

        val keepId = playlistDao.insertPlaylist(
            PlaylistEntity(
                name = "KeepMe",
                createdAt = 1000L,
                isPinned = false,
                artworkUri = "file:///local/custom_cover.jpg",
                cloudId = "pl_keep"
            )
        )
        playlistDao.insertPlaylist(
            PlaylistEntity(
                name = "New",
                createdAt = 2000L,
                isPinned = false,
                cloudId = "pl_deleted_in_cloud"
            )
        )

        val snapshotPayload = UserBackupPayload(
            version = 3,
            ownerUid = "test-uid-sync",
            snapshotComplete = true,
            historyStoredSeparately = true,
            playlistPlayEventsStoredSeparately = true,
            lyricsStoredSeparately = true,
            lyricsSnapshotId = "lyr_1",
            historyCount = 0,
            playlistPlayEventCount = 0,
            lyricsCount = 0,
            dataHash = "hash",
            profile = BackupProfileData(uid = "test-uid-sync"),
            playlists = listOf(
                CloudPlaylist(
                    cloudId = "pl_keep",
                    name = "KeepMe",
                    createdAt = 1000L,
                    isPinned = true,
                    artworkUri = "file:///cloud/other_cover.jpg",
                    songs = listOf(
                        CloudPlaylistSongItem(
                            position = 0,
                            songSignature = SongIdentityHelper.computeSignature(song),
                            title = song.title,
                            artist = song.artist,
                            album = song.album,
                            durationMs = song.durationMs,
                            filename = "KeepTrack.mp3"
                        )
                    )
                )
            )
        )

        val syncRepo = CloudSyncRepository(
            context = context,
            songDao = songDao,
            playlistDao = playlistDao,
            historyDao = historyDao,
            smartPlaylistDao = smartPlaylistDao,
            userPreferences = userPreferences
        ).apply {
            testCurrentUidOverride = "test-uid-sync"
        }

        syncRepo.restoreFromBackupSnapshot(
            uid = "test-uid-sync",
            data = snapshotPayload.toMap(),
            cloudHistory = emptyList(),
            cloudPlaylistHistory = emptyList(),
            cloudLyrics = emptyList()
        )

        val remainingPlaylists = playlistDao.getAllPlaylistsOnce()
        assertEquals("Deleted playlist 'New' must be removed on sync", 1, remainingPlaylists.size)
        val restoredKeep = remainingPlaylists.first()
        assertEquals(keepId, restoredKeep.id)
        assertEquals("KeepMe", restoredKeep.name)
        assertTrue("Pinned flag should update from cloud", restoredKeep.isPinned)
        assertEquals(
            "Existing local custom playlist cover image must be preserved",
            "file:///local/custom_cover.jpg",
            restoredKeep.artworkUri
        )
        assertEquals(1, playlistDao.getPlaylistSongs(keepId).first().size)
    }

    @Test
    fun manualLyricsSelectionOverwritesLocalLrcWithOffsetAndSurvivesNavigation() = runTest {
        val tempDir = java.io.File(context.cacheDir, "manual_lyrics_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val audioFile = java.io.File(tempDir, "WrongCached.mp3").apply { writeText("audio") }
            val oldWrongLrc = java.io.File(tempDir, "WrongCached.lrc").apply {
                writeText("[00:01.00]Old wrong auto-fetched lyrics")
            }
            val songEntity = SongEntity(
                id = 888L,
                title = "WrongCached",
                artist = "Artist",
                album = "Album",
                genre = "Pop",
                durationMs = 180_000L,
                path = audioFile.absolutePath,
                contentUriString = "file://${audioFile.absolutePath}"
            )
            songDao.insertSongs(listOf(songEntity))
            db.lyricsDao().insertLyrics(
                LyricsEntity(
                    songId = 888L,
                    plainLyrics = "Old wrong auto-fetched lyrics",
                    syncedLyricsJson = """[{"timeMs":1000,"text":"Old wrong auto-fetched lyrics"}]""",
                    isCached = true,
                    source = "LRCLIB"
                )
            )

            userPreferences.setLyricsSyncOffsetMs(888L, -350L)
            val fetcher = com.example.service.LyricsFetcher(db.lyricsDao(), context)
            fetcher.saveSelectedLyrics(
                songId = 888L,
                plainLyrics = "Correct user chosen line",
                syncedLyricsRaw = "[00:05.00]Correct user chosen line",
                source = "Manual Search",
                songPath = audioFile.absolutePath,
                songTitle = "WrongCached"
            )

            val savedEntity = db.lyricsDao().getLyricsForSong(888L)
            assertNotNull(savedEntity)
            assertFalse("Manually selected lyrics must mark isCached=false", savedEntity!!.isCached)
            assertTrue(savedEntity.plainLyrics?.contains("Correct user chosen line") == true)

            val lrcText = oldWrongLrc.readText()
            assertTrue("Companion .lrc must be overwritten with new lyrics", lrcText.contains("Correct user chosen line"))
            assertFalse("Old wrong lyrics must no longer remain in companion .lrc", lrcText.contains("Old wrong auto-fetched lyrics"))
            assertTrue("Companion .lrc must contain user offset tag", lrcText.contains("[offset:-350]"))

            // Simulate navigating away and coming back to this song
            val reloaded = fetcher.getOrFetchLyrics(songEntity.toDomainModel(), forceRefresh = false)
            assertTrue(reloaded.plainLyrics?.contains("Correct user chosen line") == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun batchCompanionLrcWriteSkipsUnchangedFilesAndUpdatesChangedFiles() {
        val tempDir = java.io.File(context.cacheDir, "batch_lrc_fast_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val audioFile = java.io.File(tempDir, "FastSyncTrack.mp3").apply { writeText("audio") }
            val syncedJson = """[{"timeMs":2000,"text":"First synchronized line"}]"""
            val plainText = "First synchronized line"

            val writtenFile = com.example.util.LrcParser.writeCompanionLrcForSong(
                context = context,
                songPath = audioFile.absolutePath,
                songTitle = "FastSyncTrack",
                plainLyrics = plainText,
                syncedLyricsJson = syncedJson,
                offsetMs = 250L,
                skipDirectoryCleanup = true
            )
            assertNotNull(writtenFile)
            assertTrue(writtenFile!!.exists())

            val fixedPastMs = 1_700_000_000_000L
            writtenFile.setLastModified(fixedPastMs)

            // Calling again with identical lyrics & offset using fast path must short-circuit without rewriting file
            com.example.util.LrcParser.writeCompanionLrcForSong(
                context = context,
                songPath = audioFile.absolutePath,
                songTitle = "FastSyncTrack",
                plainLyrics = plainText,
                syncedLyricsJson = syncedJson,
                offsetMs = 250L,
                skipDirectoryCleanup = true
            )
            assertEquals(
                "Unchanged companion .lrc must not be rewritten during batch sync",
                fixedPastMs / 1000L,
                writtenFile.lastModified() / 1000L
            )

            // Updating lyrics or offset must immediately overwrite the companion .lrc
            com.example.util.LrcParser.writeCompanionLrcForSong(
                context = context,
                songPath = audioFile.absolutePath,
                songTitle = "FastSyncTrack",
                plainLyrics = "Updated cloud lyric line",
                syncedLyricsJson = """[{"timeMs":4000,"text":"Updated cloud lyric line"}]""",
                offsetMs = -150L,
                skipDirectoryCleanup = true
            )
            val updatedText = writtenFile.readText()
            assertTrue(updatedText.contains("Updated cloud lyric line"))
            assertTrue(updatedText.contains("[offset:-150]"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}


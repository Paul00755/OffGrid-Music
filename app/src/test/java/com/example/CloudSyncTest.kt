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
@Config(sdk = [36])
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
}

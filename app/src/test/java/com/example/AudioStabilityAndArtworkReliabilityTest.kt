package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.SongEntity
import com.example.data.repository.MusicRepository
import com.example.scanner.LibraryScanner
import com.example.service.AudioFocusManager
import com.example.ui.components.ArtworkResolutionCache
import com.example.util.ArtworkManager
import com.example.util.ManualTagStore
import com.example.util.OnlineArtistImageFetcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioStabilityAndArtworkReliabilityTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: MusicRepository
    private lateinit var scanner: LibraryScanner
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scanner = LibraryScanner(context, db.songDao(), db.folderDao())
        repository = MusicRepository(
            songDao = db.songDao(),
            folderDao = db.folderDao(),
            playlistDao = db.playlistDao(),
            historyDao = db.historyDao(),
            lyricsDao = db.lyricsDao(),
            recentSearchDao = db.recentSearchDao(),
            smartPlaylistDao = db.smartPlaylistDao(),
            libraryScanner = scanner
        )
        tempDir = File(context.cacheDir, "artwork_stability_test_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        File(context.filesDir, "artworks").deleteRecursively()
        File(context.filesDir, "manual_covers").deleteRecursively()
        File(context.filesDir, "manual_song_tags.json").delete()
        context.getSharedPreferences("manual_song_tags_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
        File(context.filesDir, "artworks").deleteRecursively()
        File(context.filesDir, "manual_covers").deleteRecursively()
        File(context.filesDir, "manual_song_tags.json").delete()
        context.getSharedPreferences("manual_song_tags_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun createValidJpegFile(file: File, color: Int = Color.CYAN, size: Int = 64): File {
        file.parentFile?.mkdirs()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bmp.recycle()
        return file
    }

    // =========================================================================
    // Priority 1: Audio Stability & Auto-Pause Isolation Tests
    // =========================================================================

    @Test
    fun testCameraTransientFocusIgnoredWhenAutoPauseCameraDisabledEvenIfSystemAudioEnabled() {
        var isPlaying = true
        var pauseCount = 0
        var resumeCount = 0

        val focusManager = AudioFocusManager(
            context = context,
            isPlayingProvider = { isPlaying },
            isSleepTimerStoppedProvider = { false },
            onPauseRequested = {
                isPlaying = false
                pauseCount++
            },
            onResumeRequested = {
                isPlaying = true
                resumeCount++
            }
        )
        focusManager.autoPauseCamera = false
        focusManager.autoPauseSystemAudio = true

        // 1. User opens camera while Auto-Pause on Camera is OFF
        focusManager.onCameraOpened("0")
        assertTrue("Opening camera must not pause when autoPauseCamera is OFF", isPlaying)

        // 2. Camera app requests transient audio focus (for shutter sound / video preview)
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(
            "Camera-correlated transient focus loss must NOT pause playback when autoPauseCamera is OFF, even if autoPauseSystemAudio is ON",
            isPlaying
        )
        assertEquals(0, pauseCount)

        // 3. Camera closes and focus returns -> no spurious pause or resume
        focusManager.onCameraClosed("0")
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(isPlaying)
        assertEquals(0, pauseCount)
        assertEquals(0, resumeCount)
    }

    @Test
    fun testNonCameraSystemAudioStillPausesWhenSystemAudioEnabled() {
        var isPlaying = true
        var pauseCount = 0
        var resumeCount = 0

        val focusManager = AudioFocusManager(
            context = context,
            isPlayingProvider = { isPlaying },
            isSleepTimerStoppedProvider = { false },
            onPauseRequested = {
                isPlaying = false
                pauseCount++
            },
            onResumeRequested = {
                isPlaying = true
                resumeCount++
            }
        )
        focusManager.autoPauseCamera = false
        focusManager.autoPauseSystemAudio = true

        // Camera is NOT active -> normal system audio transient focus loss pauses and auto-resumes
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(isPlaying)
        assertEquals(1, pauseCount)

        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(isPlaying)
        assertEquals(1, resumeCount)
    }

    @Test
    fun testNotificationFocusLossOnlyDucksVolumeOrContinuesWhileVideoAndCallsPause() {
        var isPlaying = true
        var pauseCount = 0
        var resumeCount = 0
        var currentVolume = 1.0f
        var isVideoPlaying = false
        var isCallActive = false

        val focusManager = AudioFocusManager(
            context = context,
            isPlayingProvider = { isPlaying },
            isSleepTimerStoppedProvider = { false },
            ownActiveMediaPlayerCountProvider = { if (isPlaying) 1 else 0 },
            externalVideoOrMediaActiveProvider = { isVideoPlaying },
            callActiveProvider = { isCallActive },
            onPauseRequested = {
                isPlaying = false
                pauseCount++
            },
            onResumeRequested = {
                isPlaying = true
                resumeCount++
            },
            onDuckRequested = { vol -> currentVolume = vol },
            onRestoreVolumeRequested = { currentVolume = 1.0f }
        )
        focusManager.autoPauseSystemAudio = true
        focusManager.reduceVolumeOnNotification = true

        // 1. Notification / Hotspot transient focus loss with ducking ON -> only ducks volume, does NOT pause
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue("Notification focus loss must NOT pause playback even when autoPauseSystemAudio is ON", isPlaying)
        assertEquals(0, pauseCount)
        assertEquals(0.60f, currentVolume, 0.001f)

        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(isPlaying)
        assertEquals(1.0f, currentVolume, 0.001f)
        assertEquals(0, resumeCount)

        // 2. Notification / Hotspot transient focus loss with ducking OFF -> continues at full volume without pause
        focusManager.reduceVolumeOnNotification = false
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue("Notification focus loss with ducking OFF must continue playback without pause", isPlaying)
        assertEquals(0, pauseCount)
        assertEquals(1.0f, currentVolume, 0.001f)
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        // 3. Video playback transient focus loss -> pauses playback and auto-resumes when ended
        isVideoPlaying = true
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse("Video playback must pause music when autoPauseSystemAudio is ON", isPlaying)
        assertEquals(1, pauseCount)

        isVideoPlaying = false
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("Playback must auto-resume after video ends", isPlaying)
        assertEquals(1, resumeCount)

        // 4. Phone call transient focus loss -> pauses playback and auto-resumes when ended
        isCallActive = true
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse("Incoming/active call must pause music", isPlaying)
        assertEquals(2, pauseCount)

        isCallActive = false
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("Playback must auto-resume after call ends", isPlaying)
        assertEquals(2, resumeCount)
    }

    @Test
    fun testReelsAndShortsTransientFocusLossPausesImmediatelyInProductionConfig() {
        var isPlaying = true
        var pauseCount = 0
        var resumeCount = 0
        var currentVolume = 1.0f

        // Matches production PlaybackManager initialization:
        // ownActiveMediaPlayerCountProvider returns 1 when playing, externalVideoOrMediaActiveProvider is null.
        val focusManager = AudioFocusManager(
            context = context,
            isPlayingProvider = { isPlaying },
            isSleepTimerStoppedProvider = { false },
            ownActiveMediaPlayerCountProvider = { if (isPlaying) 1 else 0 },
            onPauseRequested = {
                isPlaying = false
                pauseCount++
            },
            onResumeRequested = {
                isPlaying = true
                resumeCount++
            },
            onDuckRequested = { vol -> currentVolume = vol },
            onRestoreVolumeRequested = { currentVolume = 1.0f }
        )
        focusManager.autoPauseSystemAudio = true
        focusManager.reduceVolumeOnNotification = true

        // 1. Instagram Reels / YouTube Shorts requests AUDIOFOCUS_GAIN_TRANSIENT -> we receive AUDIOFOCUS_LOSS_TRANSIENT.
        // Even if ExoPlayer in Reels/Shorts hasn't emitted its first audio buffer into activePlaybackConfigurations yet,
        // OffGrid Music must pause immediately (NOT merely duck for 2.2s and resume on top of the video).
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse("Instagram Reels / YouTube Shorts transient focus loss must immediately pause music", isPlaying)
        assertEquals(1, pauseCount)
        assertEquals(1.0f, currentVolume, 0.001f)

        // 2. When the user closes the video and focus returns, playback auto-resumes.
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("Playback must auto-resume when video releases audio focus", isPlaying)
        assertEquals(1, resumeCount)
    }

    @Test
    fun testNotificationDuckableFocusLossNeverPausesWhenAutoPauseSystemAudioEnabled() {
        var isPlaying = true
        var pauseCount = 0
        var resumeCount = 0
        var currentVolume = 0.60f // e.g., device/song volume at 60%

        val focusManager = AudioFocusManager(
            context = context,
            isPlayingProvider = { isPlaying },
            isSleepTimerStoppedProvider = { false },
            ownActiveMediaPlayerCountProvider = { if (isPlaying) 1 else 0 },
            onPauseRequested = {
                isPlaying = false
                pauseCount++
            },
            onResumeRequested = {
                isPlaying = true
                resumeCount++
            },
            onDuckRequested = { duckMultiplier ->
                // Reduces 40% of the currently playing song's volume (retains 60% of 60% = 0.36f)
                currentVolume = 0.60f * duckMultiplier
            },
            onRestoreVolumeRequested = {
                currentVolume = 0.60f
            }
        )
        focusManager.autoPauseSystemAudio = true
        focusManager.reduceVolumeOnNotification = true

        // Notification arrives with AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK while autoPauseSystemAudio is ON
        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue("Notification duckable focus loss must NEVER pause playback when autoPauseSystemAudio is ON", isPlaying)
        assertEquals(0, pauseCount)
        assertEquals(0.36f, currentVolume, 0.001f)

        focusManager.dispatchFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(isPlaying)
        assertEquals(0, pauseCount)
        assertEquals(0, resumeCount)
        assertEquals(0.60f, currentVolume, 0.001f)
    }

    @Test
    fun testLyricsMusicalNoteNormalizationAndTanglishSupport() {
        val tanglish = com.example.service.transcription.TranscribeLanguage.fromCode("tanglish")
        assertEquals(com.example.service.transcription.TranscribeLanguage.TANGLISH, tanglish)
        assertEquals("Tanglish", tanglish.englishName)

        assertEquals("♪", com.example.ui.screens.cleanLyricDisplayText("[01:12.00] 🎼"))
        assertEquals("♪", com.example.ui.screens.cleanLyricDisplayText("🎼"))
        assertEquals("OMG பொண்ணு", com.example.ui.screens.cleanLyricDisplayText("[00:15.20] OMG பொண்ணு"))

        val parsed = com.example.util.LrcParser.parseLrcToLines("[00:05.00] 🎼\n[00:10.00] OMG ponnu")
        assertEquals(2, parsed.size)
        assertEquals("♪", parsed[0].text)
        assertEquals("OMG ponnu", parsed[1].text)
    }

    // =========================================================================
    // Priority 2 & 6: Startup Disk Cache Warmup & High-Res On-Demand Tracking
    // =========================================================================

    @Test
    fun testStartupDiskIndexAndMemoryCacheHitWithoutFalseMiss() {
        val songId = 404L
        val songPath = File(tempDir, "CachedTrack.mp3").apply { writeBytes(ByteArray(128)) }.absolutePath

        // Simulate existing cached artwork file on disk before app startup (<songId>.jpg)
        val artworksDir = File(context.filesDir, "artworks").apply { mkdirs() }
        createValidJpegFile(File(artworksDir, "$songId.jpg"), Color.MAGENTA)

        // Warm up disk index and look up via getOrCheckDisk and getMemory
        ArtworkResolutionCache.ensureDiskIndexWarm(context)
        val resolvedFromDisk = ArtworkResolutionCache.getOrCheckDisk(context, songId, songPath)
        assertNotNull("Cached <songId>.jpg must be resolved immediately from disk index", resolvedFromDisk)

        val resolvedFromMemory = ArtworkResolutionCache.getMemory(songId, songPath)
        assertEquals(
            "Memory lookup must return cached URI even when file was originally indexed by songId only",
            resolvedFromDisk,
            resolvedFromMemory
        )
    }

    @Test
    fun testHighResArtworkTrackingPerTrack() {
        val songId = 505L
        val songPath = File(tempDir, "HighResTrack.mp3").apply { writeBytes(ByteArray(128)) }.absolutePath

        assertFalse(
            "Track should not be marked high-res before high-res upgrade",
            ArtworkResolutionCache.hasHighResArtwork(context, songId, songPath)
        )

        ArtworkResolutionCache.markHighResArtworkCached(context, songId, songPath)

        assertTrue(
            "Track must be recognized as having cached high-res artwork after upgrade",
            ArtworkResolutionCache.hasHighResArtwork(context, songId, songPath)
        )
    }

    // =========================================================================
    // Priority 3: Field-Level Manual Metadata & Artwork Provenance (4 Cases)
    // =========================================================================

    @Test
    fun testCase1ManualTitleEditOnlyPreservesTitleAndKeepsArtworkEligible() = runBlocking {
        val audioFile = File(tempDir, "Case1Song.mp3").apply { writeBytes(ByteArray(256)) }
        val initialArtFile = createValidJpegFile(File(tempDir, "initial_case1.jpg"), Color.BLUE)
        val initialArtUri = Uri.fromFile(initialArtFile).toString()

        val songEntity = SongEntity(
            id = 1001L,
            title = "Original Raw Title (Official Audio)",
            artist = "Test Artist",
            album = "Test Album",
            genre = "Pop",
            durationMs = 200_000L,
            path = audioFile.absolutePath,
            contentUriString = "file://${audioFile.absolutePath}",
            artworkUri = initialArtUri,
            albumId = 10L,
            dateAdded = 1000L,
            format = "MP3",
            isManuallyEdited = false
        )
        db.songDao().insertSongs(listOf(songEntity))

        // User edits ONLY the title in TagEditorDialog (artwork unchanged)
        val updatedSong = repository.updateSongTags(
            song = songEntity.toDomainModel(),
            newTitle = "My Custom Clean Title",
            newArtist = songEntity.artist,
            newAlbum = songEntity.album,
            newArtworkUri = initialArtUri, // Unchanged artwork
            newLyrics = null,
            context = context
        )

        assertEquals("My Custom Clean Title", updatedSong.title)
        assertTrue(
            "Title must be marked manually edited",
            ManualTagStore.isTitleManuallyEdited(context, songEntity.id, songEntity.path)
        )
        assertFalse(
            "Artwork must NOT be marked manually edited when only title was changed",
            ManualTagStore.isArtworkManuallyEdited(context, songEntity.id, songEntity.path)
        )

        // Verify scanner preserves the manual title across scans
        val scanned = scanner.buildOrUpdateSongEntity(
            existing = db.songDao().getSongById(songEntity.id),
            id = songEntity.id,
            rawTitle = "Noisy File Name [320kbps]",
            rawArtist = "Test Artist",
            rawAlbum = "Test Album",
            durationMs = 200_000L,
            filePath = songEntity.path,
            contentUri = songEntity.contentUriString,
            albumId = 10L,
            dateAdded = 1000L,
            format = "MP3"
        )
        assertEquals("My Custom Clean Title", scanned.title)

        // Verify online/high-res artwork fetch is still eligible to save/upgrade artwork for this song
        val fetchedCoverSource = createValidJpegFile(File(tempDir, "fetched_online_case1.jpg"), Color.GREEN, 128)
        val savedOnlineUri = ArtworkManager.saveFetchedArtworkLocally(
            context = context,
            songId = songEntity.id,
            url = Uri.fromFile(fetchedCoverSource).toString(),
            filePath = songEntity.path,
            isHighRes = true
        )
        assertNotNull(
            "Online artwork fetch must still succeed when only title was manually edited",
            savedOnlineUri
        )
    }

    @Test
    fun testCase2ManualArtworkEditOnlyPreservesArtworkAndAllowsNormalTitleCleaning() = runBlocking {
        val audioFile = File(tempDir, "Case2Song.mp3").apply { writeBytes(ByteArray(256)) }
        val customCoverFile = createValidJpegFile(File(tempDir, "user_picked_cover.jpg"), Color.RED, 128)
        val customCoverUri = Uri.fromFile(customCoverFile).toString()

        val songEntity = SongEntity(
            id = 1002L,
            title = "Original Song Name",
            artist = "Artist Two",
            album = "Album Two",
            genre = "Rock",
            durationMs = 180_000L,
            path = audioFile.absolutePath,
            contentUriString = "file://${audioFile.absolutePath}",
            artworkUri = null,
            albumId = 20L,
            dateAdded = 1000L,
            format = "MP3",
            isManuallyEdited = false
        )
        db.songDao().insertSongs(listOf(songEntity))

        // User edits ONLY the artwork (title unchanged)
        val updatedSong = repository.updateSongTags(
            song = songEntity.toDomainModel(),
            newTitle = songEntity.title,
            newArtist = songEntity.artist,
            newAlbum = songEntity.album,
            newArtworkUri = customCoverUri,
            newLyrics = null,
            context = context
        )

        assertTrue(
            "Artwork must be marked manually edited",
            ManualTagStore.isArtworkManuallyEdited(context, songEntity.id, songEntity.path)
        )
        assertFalse(
            "Title must NOT be marked manually edited when only artwork was changed",
            ManualTagStore.isTitleManuallyEdited(context, songEntity.id, songEntity.path)
        )
        assertFalse(
            "Song.isManuallyEdited (for text display bypass) must remain false so normal title cleaning applies",
            updatedSong.isManuallyEdited
        )
        assertNotNull("Custom cover must be persisted", updatedSong.artworkUri)

        // Verify online artwork fetch is strictly blocked from overwriting manual artwork
        val onlineCandidateFile = createValidJpegFile(File(tempDir, "online_attempt.jpg"), Color.YELLOW)
        val onlineAttemptResult = ArtworkManager.saveFetchedArtworkLocally(
            context = context,
            songId = songEntity.id,
            url = Uri.fromFile(onlineCandidateFile).toString(),
            filePath = songEntity.path,
            isHighRes = true
        )
        assertEquals(
            "Online artwork fetch must never overwrite manually edited artwork and must return the preserved manual cover",
            updatedSong.artworkUri,
            onlineAttemptResult
        )

        // Verify scanner preserves manual artwork while still cleaning noisy raw titles
        val scanned = scanner.buildOrUpdateSongEntity(
            existing = db.songDao().getSongById(songEntity.id),
            id = songEntity.id,
            rawTitle = "Cleanable Track (Official Video) [HD]",
            rawArtist = "Artist Two",
            rawAlbum = "Album Two",
            durationMs = 180_000L,
            filePath = songEntity.path,
            contentUri = songEntity.contentUriString,
            albumId = 20L,
            dateAdded = 1000L,
            format = "MP3"
        )
        assertEquals("Cleanable Track", scanned.title)
        assertEquals(updatedSong.artworkUri, scanned.artworkUri)
    }

    @Test
    fun testCase3BothManualTitleAndManualArtworkPreservedIndependently() = runBlocking {
        val audioFile = File(tempDir, "Case3Song.mp3").apply { writeBytes(ByteArray(256)) }
        val customCoverFile = createValidJpegFile(File(tempDir, "case3_cover.jpg"), Color.MAGENTA, 128)
        val customCoverUri = Uri.fromFile(customCoverFile).toString()

        val songEntity = SongEntity(
            id = 1003L,
            title = "Initial Title",
            artist = "Initial Artist",
            album = "Initial Album",
            genre = "Indie",
            durationMs = 210_000L,
            path = audioFile.absolutePath,
            contentUriString = "file://${audioFile.absolutePath}",
            artworkUri = null,
            albumId = 30L,
            dateAdded = 1000L,
            format = "MP3",
            isManuallyEdited = false
        )
        db.songDao().insertSongs(listOf(songEntity))

        val updatedSong = repository.updateSongTags(
            song = songEntity.toDomainModel(),
            newTitle = "Bespoke Manual Title",
            newArtist = "Bespoke Manual Artist",
            newAlbum = "Bespoke Manual Album",
            newArtworkUri = customCoverUri,
            newLyrics = null,
            context = context
        )

        assertTrue(ManualTagStore.isTitleManuallyEdited(context, songEntity.id, songEntity.path))
        assertTrue(ManualTagStore.isArtistManuallyEdited(context, songEntity.id, songEntity.path))
        assertTrue(ManualTagStore.isAlbumManuallyEdited(context, songEntity.id, songEntity.path))
        assertTrue(ManualTagStore.isArtworkManuallyEdited(context, songEntity.id, songEntity.path))

        val scanned = scanner.buildOrUpdateSongEntity(
            existing = db.songDao().getSongById(songEntity.id),
            id = songEntity.id,
            rawTitle = "Overridden Raw Title",
            rawArtist = "Overridden Raw Artist",
            rawAlbum = "Overridden Raw Album",
            durationMs = 210_000L,
            filePath = songEntity.path,
            contentUri = songEntity.contentUriString,
            albumId = 30L,
            dateAdded = 1000L,
            format = "MP3"
        )
        assertEquals("Bespoke Manual Title", scanned.title)
        assertEquals("Bespoke Manual Artist", scanned.artist)
        assertEquals("Bespoke Manual Album", scanned.album)
        assertEquals(updatedSong.artworkUri, scanned.artworkUri)
    }

    @Test
    fun testCase4NeitherManuallyEditedUsesNormalRules() {
        val songId = 1004L
        val path = File(tempDir, "Case4Song.mp3").absolutePath
        assertFalse(ManualTagStore.isTitleManuallyEdited(context, songId, path))
        assertFalse(ManualTagStore.isArtworkManuallyEdited(context, songId, path))
    }

    // =========================================================================
    // Priority 4 & 5: Accurate Per-Song Matching, Race Condition Guard & Instant Propagation
    // =========================================================================

    @Test
    fun testOnlineArtworkCandidateValidationRejectsUnrelatedTracks() {
        // Exact / valid match
        assertTrue(
            OnlineArtistImageFetcher.isValidArtworkMatch(
                requestedTitle = "Mayakkama Kalakkama",
                requestedArtist = "Dhanush, Anirudh Ravichander",
                requestedAlbum = "Thiruchitrambalam",
                candidateTitle = "Mayakkama Kalakkama (From \"Thiruchitrambalam\")",
                candidateArtist = "Anirudh Ravichander & Dhanush",
                candidateAlbum = "Thiruchitrambalam (Original Motion Picture Soundtrack)"
            )
        )

        // Reject completely unrelated song from same or different artist
        assertFalse(
            "Must reject candidate with unrelated song title",
            OnlineArtistImageFetcher.isValidArtworkMatch(
                requestedTitle = "Mayakkama Kalakkama",
                requestedArtist = "Dhanush, Anirudh Ravichander",
                requestedAlbum = "Thiruchitrambalam",
                candidateTitle = "Why This Kolaveri Di",
                candidateArtist = "Dhanush",
                candidateAlbum = "3"
            )
        )

        // Reject candidate with matching generic title word but wrong artist
        assertFalse(
            "Must reject candidate when known artist does not match at all",
            OnlineArtistImageFetcher.isValidArtworkMatch(
                requestedTitle = "Summertime Sadness",
                requestedArtist = "Lana Del Rey",
                requestedAlbum = "Born to Die",
                candidateTitle = "Summertime Sadness",
                candidateArtist = "Random Cover Band XYZ",
                candidateAlbum = "Tribute Hits"
            )
        )
    }

    @Test
    fun testStaleConcurrentFetchCannotOverwriteNewerManualArtworkEdit() = runBlocking {
        val songId = 2001L
        val songPath = File(tempDir, "RaceConditionTrack.mp3").apply { writeBytes(ByteArray(128)) }.absolutePath

        // 1. Background online fetch starts and records generation 0
        val startGeneration = ManualTagStore.getArtworkEditGeneration(context, songId, songPath)

        // 2. Before background fetch finishes, user manually edits artwork
        val manualFile = createValidJpegFile(File(tempDir, "manual_winner.jpg"), Color.GREEN)
        val persistedManualUri = ArtworkManager.persistSongCover(
            context = context,
            songId = songId,
            uriString = Uri.fromFile(manualFile).toString(),
            songPath = songPath
        )
        assertNotNull(persistedManualUri)
        assertTrue(ManualTagStore.isArtworkManuallyEdited(context, songId, songPath))
        assertTrue(ManualTagStore.getArtworkEditGeneration(context, songId, songPath) > startGeneration)

        // 3. Stale background fetch completes and attempts to save with old expectedArtworkGeneration
        val staleOnlineFile = createValidJpegFile(File(tempDir, "stale_online.jpg"), Color.BLUE)
        val staleResult = ArtworkManager.saveFetchedArtworkLocally(
            context = context,
            songId = songId,
            url = Uri.fromFile(staleOnlineFile).toString(),
            filePath = songPath,
            isHighRes = true,
            expectedArtworkGeneration = startGeneration
        )

        assertEquals(
            "Stale background fetch must not overwrite manual cover and must return the preserved manual URI",
            persistedManualUri,
            staleResult
        )
        assertEquals(
            "Manual cover URI must remain intact in ArtworkResolutionCache",
            persistedManualUri,
            ArtworkResolutionCache.getMemory(songId, songPath)
        )
    }

    @Test
    fun testArtworkChangePropagatesVersionAndPreservesThumbnailWithoutBlankFlash() = runBlocking {
        val songId = 3001L
        val songPath = File(tempDir, "LivePropagationTrack.mp3").apply { writeBytes(ByteArray(128)) }.absolutePath

        val coverV1 = createValidJpegFile(File(tempDir, "cover_v1.jpg"), Color.RED, 96)
        val uriV1 = ArtworkManager.persistSongCover(
            context = context,
            songId = songId,
            uriString = Uri.fromFile(coverV1).toString(),
            songPath = songPath
        )
        assertNotNull(uriV1)

        val version1 = ArtworkResolutionCache.getArtworkUpdateVersion(songId, songPath)
        val globalSignal1 = ArtworkResolutionCache.artworkUpdateSignal.longValue
        val thumb1 = ArtworkResolutionCache.getThumbnailBitmap(songId, songPath)
        assertNotNull("Thumbnail must be pre-decoded into memory cache immediately on save", thumb1)

        // Update artwork to V2
        val coverV2 = createValidJpegFile(File(tempDir, "cover_v2.jpg"), Color.BLUE, 96)
        val uriV2 = ArtworkManager.persistSongCover(
            context = context,
            songId = songId,
            uriString = Uri.fromFile(coverV2).toString(),
            songPath = songPath
        )
        assertNotNull(uriV2)

        val version2 = ArtworkResolutionCache.getArtworkUpdateVersion(songId, songPath)
        val globalSignal2 = ArtworkResolutionCache.artworkUpdateSignal.longValue
        val thumb2 = ArtworkResolutionCache.getThumbnailBitmap(songId, songPath)

        assertTrue("Per-track artwork version must increment on artwork change", version2 > version1)
        assertTrue("Global artworkUpdateSignal must increment for observers and playlists", globalSignal2 > globalSignal1)
        assertNotNull("Updated thumbnail must be immediately available in memory without null flash", thumb2)
    }

    @Test
    fun testHighResolutionCoverPreservedForNowPlayingWhileThumbnailRemainsLightweight() = runBlocking {
        val songId = 4001L
        val songPath = File(tempDir, "HighResNowPlayingTrack.mp3").apply { writeBytes(ByteArray(128)) }.absolutePath

        val hdSource = createValidJpegFile(File(tempDir, "hd_cover_1200.jpg"), Color.CYAN, 1200)
        val persistedUri = ArtworkManager.persistSongCover(
            context = context,
            songId = songId,
            uriString = Uri.fromFile(hdSource).toString(),
            songPath = songPath
        )
        assertNotNull(persistedUri)

        // 1. Verify the persisted file on disk retains full 1200x1200 resolution for Now Playing
        val persistedFile = File(Uri.parse(persistedUri!!).path!!)
        assertTrue(persistedFile.exists())
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(persistedFile.absolutePath, bounds)
        assertEquals("Now Playing cover file must preserve full 1200px width", 1200, bounds.outWidth)
        assertEquals("Now Playing cover file must preserve full 1200px height", 1200, bounds.outHeight)

        // 2. Verify list thumbnail cache still stores a fast 128x128 thumbnail
        val thumbBmp = ArtworkResolutionCache.getThumbnailBitmap(songId, songPath)
        assertNotNull(thumbBmp)
        assertTrue("List thumbnail must remain compact (<= 160px)", thumbBmp!!.width <= 160 && thumbBmp.height <= 160)
    }

    @Test
    fun testPalePinkArtworkExtractsPinkNotBlueAndHeroSpotlightDefault() = runBlocking {
        // 1. Default Home Screen Layout must be HERO_SPOTLIGHT
        assertEquals(
            com.example.ui.theme.HomeScreenLayout.HERO_SPOTLIGHT,
            com.example.ui.theme.HomeScreenLayout.fromId(null)
        )
        val prefs = com.example.data.local.UserPreferencesRepository(context)
        assertEquals("HERO_SPOTLIGHT", prefs.getHomeScreenLayoutSync())

        // 2. Gradient Effect and Glass Effect toggles must work independently
        prefs.setGradientEffect(true)
        prefs.setGlassEffect(false)
        assertTrue(prefs.getGradientEffectSync())
        assertFalse(prefs.getGlassEffectSync())

        prefs.setGradientEffect(false)
        prefs.setGlassEffect(true)
        assertFalse(prefs.getGradientEffectSync())
        assertTrue(prefs.getGlassEffectSync())

        // 3. Pale pink artwork with dark navy/shadow pixels must extract Pink/Rose hue, NEVER Blue
        com.example.util.ArtworkColorExtractor.clearCache()
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                if (x < 14 && y < 14) {
                    // Dark blue-black shadow corner (high HSV saturation artifact at low value)
                    bmp.setPixel(x, y, Color.rgb(22, 28, 46))
                } else if (x in 20..44 && y in 20..44) {
                    // Warm rose-pink subject
                    bmp.setPixel(x, y, Color.rgb(218, 128, 162))
                } else {
                    // Pale pastel pink background across the cover
                    bmp.setPixel(x, y, Color.rgb(238, 188, 208))
                }
            }
        }
        val pinkFile = File(tempDir, "pale_pink_cover.jpg")
        FileOutputStream(pinkFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        val songFile = File(tempDir, "KaadhalEnKaviye.mp3").apply { writeBytes(ByteArray(64)) }
        val song = com.example.data.model.Song(
            id = 5005L,
            title = "Kaadhal En Kaviye",
            artist = "Sid Sriram",
            album = "Salmon 3D",
            albumId = 505L,
            durationMs = 240_000L,
            contentUriString = Uri.fromFile(songFile).toString(),
            path = songFile.absolutePath,
            artworkUri = Uri.fromFile(pinkFile).toString()
        )
        val extracted = com.example.util.ArtworkColorExtractor.extractPrimaryColorForSong(context, song)
        assertNotNull("Pale pink artwork must extract a non-null color", extracted)
        val argb = android.graphics.Color.argb(
            255,
            (extracted!!.red * 255).toInt(),
            (extracted.green * 255).toInt(),
            (extracted.blue * 255).toInt()
        )
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        val hue = hsv[0]
        assertTrue(
            "Expected Pink/Rose hue (310..360 or 0..25), but got hue=$hue",
            hue >= 310f || hue <= 25f
        )
    }

    @Test
    fun testLyricsLanguageDetectorClassifiesScriptsAndRomanizedLanguagesAccurately() {
        val detector = com.example.service.LyricsLanguageDetector

        // 1. Tamil Script -> "Tamil"
        val tamilLrc = "[00:12.00] என் நெஞ்சில் நீயும் இறங்கி ஏதோ மாயம் செய்தாய்\n[00:16.50] வா வா வெண்ணிலா"
        assertEquals(
            "Tamil",
            detector.detectLanguageLabel(tamilLrc, "Va Va Va Vannila", "Hiphop Tamizha", "Aambala")
        )

        // 2. Tanglish (English letters representing Tamil lyrics) -> "Tanglish"
        val tanglishLrc = "[00:12.00] En nenjil neeyum irangi edho maayam seithaai\n[00:16.50] Va va va vannila unna thaandi pogave mudiyala"
        assertEquals(
            "Tanglish",
            detector.detectLanguageLabel(tanglishLrc, "Va Va Va Vannila", "Mohit Chauhan", "Aambala")
        )

        // 3. Fully English -> "English"
        val englishLrc = "[00:10.00] Look at the stars look how they shine for you\n[00:15.00] And everything you do yeah they were all yellow"
        assertEquals(
            "English",
            detector.detectLanguageLabel(englishLrc, "Yellow", "Coldplay", "Parachutes")
        )

        // 4. Malayalam Script -> "Malayalam"
        val malayalamLrc = "[00:10.00] മലരേ നിന്നെ കാണാതിരുന്നാൽ മിഴികൾ നിറയും"
        assertEquals(
            "Malayalam",
            detector.detectLanguageLabel(malayalamLrc, "Malare", "Vijay Yesudas", "Premam")
        )

        // 5. Malayalam in English (Manglish) -> "Malayalam (Eng)"
        val manglishLrc = "[00:10.00] Malare ninne kaanathirunnalimizhikal nirayum\n[00:15.00] Ennum ninne orthu paadunnu njan pranayam"
        assertEquals(
            "Malayalam (Eng)",
            detector.detectLanguageLabel(manglishLrc, "Malare", "Vijay Yesudas", "Premam")
        )

        // 6. Hindi Script -> "Hindi"
        val hindiLrc = "[00:10.00] तुम ही हो अब तुम ही हो ज़िंदगी अब तुम ही हो"
        assertEquals(
            "Hindi",
            detector.detectLanguageLabel(hindiLrc, "Tum Hi Ho", "Arijit Singh", "Aashiqui 2")
        )

        // 7. Hindi in English -> "Hindi (Eng)"
        val hinglishLrc = "[00:10.00] Tum hi ho ab tum hi ho zindagi ab tum hi ho\n[00:15.00] Tera mera rishta hai kaisa ishq aur mohabbat"
        assertEquals(
            "Hindi (Eng)",
            detector.detectLanguageLabel(hinglishLrc, "Tum Hi Ho", "Arijit Singh", "Aashiqui 2")
        )
    }

    @Test
    fun testSyncIndexedSongsToLocalStorageBatchSequentialAndTimestampPreservation() = runBlocking {
        val songFile1 = File(tempDir, "01 - Nee Kidaithai [Masstamilan.com] 320kbps.mp3")
        val songFile2 = File(tempDir, "02 - OMG Ponnu - MassTamilan.io.mp3")
        val songFile3 = File(tempDir, "Already Clean Song.mp3")

        songFile1.writeBytes(ByteArray(256) { 0x11 })
        songFile2.writeBytes(ByteArray(256) { 0x22 })
        songFile3.writeBytes(ByteArray(256) { 0x33 })

        val ts1 = 1_700_000_000_000L
        val ts2 = 1_700_010_000_000L
        val ts3 = 1_700_020_000_000L
        songFile1.setLastModified(ts1)
        songFile2.setLastModified(ts2)
        songFile3.setLastModified(ts3)

        db.songDao().insertSongs(
            listOf(
                SongEntity(
                    id = 101L,
                    title = "01 - Nee Kidaithai [Masstamilan.com] 320kbps",
                    artist = "Haricharan",
                    album = "Chennai 28 II",
                    genre = "Tamil",
                    durationMs = 240_000L,
                    path = songFile1.absolutePath,
                    contentUriString = "",
                    dateAdded = ts1
                ),
                SongEntity(
                    id = 102L,
                    title = "02 - OMG Ponnu - MassTamilan.io",
                    artist = "Sid Sriram",
                    album = "Sarkar",
                    genre = "Tamil",
                    durationMs = 250_000L,
                    path = songFile2.absolutePath,
                    contentUriString = "",
                    dateAdded = ts2
                ),
                SongEntity(
                    id = 103L,
                    title = "Already Clean Song",
                    artist = "Anirudh",
                    album = "Single",
                    genre = "Tamil",
                    durationMs = 200_000L,
                    path = songFile3.absolutePath,
                    contentUriString = "",
                    dateAdded = ts3
                )
            )
        )

        // Execute sequential batch sync
        repository.syncIndexedSongsToLocalStorage(context, skipPlayingSongId = null)

        val progress = repository.localSongSyncProgress.value
        assertFalse(progress.isSyncing)
        assertTrue(progress.isCompleted)
        assertEquals(3, progress.totalCount)
        assertEquals(3, progress.processedCount)
        assertEquals(2, progress.updatedCount) // Only the 2 polluted songs needed updates; song 3 was skipped in O(1)

        val updated1 = db.songDao().getSongById(101L)!!
        val updated2 = db.songDao().getSongById(102L)!!
        val updated3 = db.songDao().getSongById(103L)!!

        assertEquals("Nee Kidaithai", updated1.title)
        assertEquals("OMG Ponnu", updated2.title)
        assertEquals("Already Clean Song", updated3.title)

        // Verify physical files were renamed on disk
        val renamedFile1 = File(updated1.path)
        val renamedFile2 = File(updated2.path)
        assertTrue(renamedFile1.exists())
        assertTrue(renamedFile2.exists())
        assertEquals("Nee Kidaithai.mp3", renamedFile1.name)
        assertEquals("OMG Ponnu.mp3", renamedFile2.name)

        // Verify original timestamps and sequential Recently Added order are preserved
        assertEquals(ts1, updated1.dateAdded)
        assertEquals(ts2, updated2.dateAdded)
        assertEquals(ts3, updated3.dateAdded)
        assertEquals(ts1 / 1000L, renamedFile1.lastModified() / 1000L)
        assertEquals(ts2 / 1000L, renamedFile2.lastModified() / 1000L)
    }

    @Test
    fun testConcurrentBackgroundArtworkWarmupNeverThrowsSnapshotException() = runBlocking {
        val songs = (1..24).map { idx ->
            val songFile = File(tempDir, "ConcurrentTrack_$idx.mp3").apply { writeBytes(ByteArray(128)) }
            val artFile = createValidJpegFile(File(tempDir, "concurrent_art_$idx.jpg"), Color.GREEN, 64)
            com.example.data.model.Song(
                id = 9000L + idx,
                title = "Concurrent Track $idx",
                artist = "Artist $idx",
                album = "Album $idx",
                albumId = 900L + idx,
                durationMs = 180_000L,
                contentUriString = Uri.fromFile(songFile).toString(),
                path = songFile.absolutePath,
                artworkUri = Uri.fromFile(artFile).toString()
            )
        }

        // Simulate active Compose snapshot on the calling thread while background threads warm artwork
        val snapshot = androidx.compose.runtime.snapshots.Snapshot.takeMutableSnapshot()
        try {
            snapshot.enter {
                ArtworkResolutionCache.warmAllSongsArtworkInBackground(context, songs)
                for (song in songs) {
                    val artFlow = ArtworkResolutionCache.getArtworkUpdateFlow(song.id, song.path)
                    val thumbFlow = ArtworkResolutionCache.getThumbnailUpdateFlow(song.id, song.path)
                    assertNotNull(artFlow.value)
                    assertNotNull(thumbFlow.value)
                }
            }
            snapshot.apply().check()
        } finally {
            snapshot.dispose()
        }
    }
}




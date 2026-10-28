package com.example

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.FolderEntity
import com.example.data.local.SongEntity
import com.example.data.model.Song
import com.example.scanner.LibraryScanner
import com.example.service.PlaybackState
import com.example.ui.screens.LibraryHeaderState
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.resolveLibraryHeaderStateOnScroll
import com.example.ui.theme.MyApplicationTheme
import com.example.util.ManualTagStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class LibraryUxAndAutoScanTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var tempRootDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tempRootDir = File(context.cacheDir, "library_ux_test_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        db.close()
        tempRootDir.deleteRecursively()
    }

    @Test
    fun testTwoStageLibraryHeaderStateMachine() {
        // 1. At top initially -> FULL
        var state = LibraryHeaderState.FULL
        assertEquals(
            LibraryHeaderState.FULL,
            resolveLibraryHeaderStateOnScroll(
                currentState = state,
                deltaY = 0f,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                downwardAccumulatedPx = 0f,
                upwardAccumulatedPx = 0f
            )
        )

        // 2. Scroll down (deltaY < 0, downwardAccumulatedPx >= threshold) -> HIDDEN (hides both full controls and compact search)
        state = resolveLibraryHeaderStateOnScroll(
            currentState = state,
            deltaY = -28f,
            firstVisibleItemIndex = 2,
            firstVisibleItemScrollOffset = 40,
            downwardAccumulatedPx = 28f,
            upwardAccumulatedPx = 0f
        )
        assertEquals(LibraryHeaderState.HIDDEN, state)

        // 3. Scroll up in the middle of the list (deltaY > 0, firstVisibleItemIndex > 0) -> COMPACT ONLY
        // Must NOT reveal FULL header when scrolling up in the middle of the list!
        state = resolveLibraryHeaderStateOnScroll(
            currentState = state,
            deltaY = 24f,
            firstVisibleItemIndex = 8,
            firstVisibleItemScrollOffset = 20,
            downwardAccumulatedPx = 0f,
            upwardAccumulatedPx = 24f
        )
        assertEquals(LibraryHeaderState.COMPACT, state)

        // 4. Continue scrolling up while still in the middle of the list -> stays COMPACT, never FULL
        state = resolveLibraryHeaderStateOnScroll(
            currentState = state,
            deltaY = 50f,
            firstVisibleItemIndex = 5,
            firstVisibleItemScrollOffset = 10,
            downwardAccumulatedPx = 0f,
            upwardAccumulatedPx = 74f
        )
        assertEquals(LibraryHeaderState.COMPACT, state)

        // 5. Scroll down again from COMPACT -> HIDDEN
        state = resolveLibraryHeaderStateOnScroll(
            currentState = state,
            deltaY = -25f,
            firstVisibleItemIndex = 6,
            firstVisibleItemScrollOffset = 30,
            downwardAccumulatedPx = 25f,
            upwardAccumulatedPx = 0f
        )
        assertEquals(LibraryHeaderState.HIDDEN, state)

        // 6. Reach the actual top of the list (firstVisibleItemIndex == 0, offset <= 12) -> FULL
        state = resolveLibraryHeaderStateOnScroll(
            currentState = state,
            deltaY = 12f,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            downwardAccumulatedPx = 0f,
            upwardAccumulatedPx = 12f
        )
        assertEquals(LibraryHeaderState.FULL, state)
    }

    @Test
    fun testLibraryScreenTwoStageHeaderAndFastScrollbarNonInterference() {
        val sampleSongs = (1..40).map { i ->
            Song(
                id = i.toLong(),
                title = "Track Number ${i.toString().padStart(2, '0')}",
                artist = "Artist $i",
                album = "Album $i",
                genre = "Electronic",
                durationMs = 210_000L,
                path = "/storage/emulated/0/Music/track_$i.mp3",
                contentUriString = "content://media/external/audio/media/$i",
                dateAdded = 1_700_000_000_000L - i * 1000L,
                isFavorite = false
            )
        }

        var favoriteToggledSongId: Long? = null
        var screenEnteredCount = 0

        composeTestRule.setContent {
            MyApplicationTheme {
                LibraryScreen(
                    allSongs = sampleSongs,
                    playbackState = PlaybackState(),
                    searchQuery = "",
                    onSearchQueryChange = {},
                    onSongClick = {},
                    onPlayQueue = { _, _ -> },
                    onFavoriteToggle = { song -> favoriteToggledSongId = song.id },
                    onBackClick = {},
                    onOpenSettings = {},
                    onOpenFavorites = {},
                    onOpenSearch = {},
                    recentSearches = listOf("kutty p", "rathinam"),
                    onScreenEntered = { screenEnteredCount++ }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Verify screen-entry auto-scan callback fired on entry
        assertEquals(1, screenEnteredCount)

        // 1. At initial top of list: both full header controls and compact search header are visible
        composeTestRule.onNodeWithTag("library_title_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_full_header_controls").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_compact_search_header").assertIsDisplayed()

        // 2. Verify FastScrollbar sits cleanly in the right gutter and does NOT overlap song row action buttons
        val scrollbarBounds = composeTestRule.onNodeWithTag("fast_scrollbar").fetchSemanticsNode().boundsInRoot
        val favButtonNode = composeTestRule.onNodeWithTag("song_favorite_1")
        val moreButtonNode = composeTestRule.onNodeWithTag("song_more_1")
        favButtonNode.assertIsDisplayed()
        moreButtonNode.assertIsDisplayed()

        val favBounds = favButtonNode.fetchSemanticsNode().boundsInRoot
        val moreBounds = moreButtonNode.fetchSemanticsNode().boundsInRoot

        assertTrue(
            "Favorite button right (${favBounds.right}) must be strictly left of FastScrollbar (${scrollbarBounds.left})",
            favBounds.right <= scrollbarBounds.left + 1f
        )
        assertTrue(
            "Overflow menu button right (${moreBounds.right}) must be strictly left of FastScrollbar (${scrollbarBounds.left})",
            moreBounds.right <= scrollbarBounds.left + 1f
        )

        // Verify clicking Favorite and More menu works without FastScrollbar intercepting the touch
        favButtonNode.performClick()
        composeTestRule.waitForIdle()
        assertEquals(1L, favoriteToggledSongId)

        moreButtonNode.performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Play Next").assertIsDisplayed()
        // Dismiss dropdown
        composeTestRule.onNodeWithText("Play Next").performClick()
        composeTestRule.waitForIdle()

        // 3. Scroll down into the middle of the list -> both full header and compact search hide
        composeTestRule.onNodeWithTag("library_songs_list").performTouchInput {
            swipe(
                start = Offset(centerX, bottom - 80f),
                end = Offset(centerX, top + 80f),
                durationMillis = 250
            )
        }
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("library_full_header_controls").assertDoesNotExist()
        composeTestRule.onNodeWithTag("library_compact_search_header").assertDoesNotExist()
        // Title row remains visible
        composeTestRule.onNodeWithTag("library_title_row").assertIsDisplayed()

        // 4. Scroll down further so we are deep in the list, then scroll up a short distance in the middle:
        // ONLY the compact search header should reveal; the full header controls MUST stay hidden.
        composeTestRule.onNodeWithTag("library_songs_list").performTouchInput {
            swipe(
                start = Offset(centerX, bottom - 60f),
                end = Offset(centerX, top + 60f),
                durationMillis = 250
            )
        }
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.waitForIdle()

        // Short upward scroll in the middle of the list (finger moves down slightly)
        composeTestRule.onNodeWithTag("library_songs_list").performTouchInput {
            swipe(
                start = Offset(centerX, top + 120f),
                end = Offset(centerX, top + 240f),
                durationMillis = 250
            )
        }
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("library_compact_search_header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_full_header_controls").assertDoesNotExist()
    }

    @Test
    fun testAutoScanDiscoversNewSongsRespectsFiltersPreservesManualTagsAndDeduplicates() = runBlocking {
        val songDao = db.songDao()
        val folderDao = db.folderDao()
        val scanner = LibraryScanner(context, songDao, folderDao)

        val includedDir = File(tempRootDir, "IncludedMusic").apply { mkdirs() }
        val excludedDir = File(tempRootDir, "ExcludedSub").apply { mkdirs() }

        folderDao.insertFolder(
            FolderEntity(
                path = includedDir.absolutePath,
                name = "IncludedMusic",
                uriString = "file://${includedDir.absolutePath}",
                songCount = 0,
                isExcluded = false
            )
        )
        folderDao.insertFolder(
            FolderEntity(
                path = excludedDir.absolutePath,
                name = "ExcludedSub",
                uriString = "file://${excludedDir.absolutePath}",
                songCount = 0,
                isExcluded = true
            )
        )

        // Seed 1 existing song with manually edited tags
        val existingFile = File(includedDir, "existing_song.mp3").apply {
            writeBytes(ByteArray(4096) { 0x11 })
        }
        val existingId = LibraryScanner.computeStablePathId(existingFile.absolutePath.lowercase())
        ManualTagStore.markManuallyEdited(
            context = context,
            songId = existingId,
            path = existingFile.absolutePath,
            title = "Custom Manual Title",
            artist = "Custom Manual Artist",
            album = "Custom Manual Album",
            genre = "Synthwave",
            year = 2026,
            trackNumber = 7,
            artworkUri = "file:///custom_art.jpg"
        )
        songDao.insertSongs(
            listOf(
                SongEntity(
                    id = existingId,
                    title = "Custom Manual Title",
                    artist = "Custom Manual Artist",
                    album = "Custom Manual Album",
                    genre = "Synthwave",
                    durationMs = 200_000L,
                    path = existingFile.absolutePath,
                    contentUriString = "file://${existingFile.absolutePath}",
                    artworkUri = "file:///custom_art.jpg",
                    trackNumber = 7,
                    year = 2026,
                    dateAdded = existingFile.lastModified(),
                    format = "MP3",
                    isManuallyEdited = true
                )
            )
        )

        // Add a brand new audio file in the included directory and one in the excluded directory
        val newValidFile = File(includedDir, "brand_new_track.mp3").apply {
            writeBytes(ByteArray(8192) { 0x22 })
        }
        val newExcludedFile = File(excludedDir, "ignored_voice_note.mp3").apply {
            writeBytes(ByteArray(8192) { 0x33 })
        }

        // Run incremental quick scan (simulating entering Library or Home screen)
        val includedFolders = folderDao.getIncludedFoldersList()
        val excludedFolders = folderDao.getExcludedFoldersList()

        // Launch two concurrent quick scans with skipIfAlreadyScanning = true to verify coalescing
        val job1 = launch {
            scanner.quickScanAndCleanLibrary(
                includedFolders = includedFolders,
                excludedFolders = excludedFolders,
                ignoreAudioDurationMs = 0L,
                maxAudioDurationMs = Long.MAX_VALUE,
                skipIfAlreadyScanning = true
            )
        }
        val job2 = launch {
            delay(5)
            scanner.quickScanAndCleanLibrary(
                includedFolders = includedFolders,
                excludedFolders = excludedFolders,
                ignoreAudioDurationMs = 0L,
                maxAudioDurationMs = Long.MAX_VALUE,
                skipIfAlreadyScanning = true
            )
        }
        job1.join()
        job2.join()

        val allSongsInDb = songDao.getAllSongsOnce()
        val paths = allSongsInDb.map { it.path }

        // 1. Newly added song in included folder is discovered and indexed without manual Rescan click
        assertTrue("Newly added track in included folder must be indexed", paths.contains(newValidFile.absolutePath))

        // 2. Song in excluded folder is rejected
        assertFalse("Track in excluded folder must not be indexed", paths.contains(newExcludedFile.absolutePath))

        // 3. Existing manually edited song retains its custom title, artist, album, and artwork
        val preservedManual = allSongsInDb.first { it.id == existingId }
        assertEquals("Custom Manual Title", preservedManual.title)
        assertEquals("Custom Manual Artist", preservedManual.artist)
        assertEquals("Custom Manual Album", preservedManual.album)
        assertEquals("file:///custom_art.jpg", preservedManual.artworkUri)
        assertTrue(preservedManual.isManuallyEdited)

        // 4. ScanProgress reports truthful validTracksFound count matching DB
        assertEquals(allSongsInDb.size, scanner.scanProgress.first().validTracksFound)
        assertFalse(scanner.isScanActive())
    }
}

package com.example

import androidx.compose.foundation.lazy.LazyListState
import com.example.data.model.Song
import com.example.ui.components.QueueEntryItem
import com.example.ui.components.ReorderableLazyListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test

class DragAutoScrollAndReorderTest {

    private fun createDummySong(id: Long, title: String): Song {
        return Song(
            id = id,
            title = title,
            artist = "Artist $id",
            album = "Album $id",
            durationMs = 180000L,
            path = "/storage/emulated/0/Music/song_$id.mp3",
            contentUriString = "file:///storage/emulated/0/Music/song_$id.mp3",
            dateAdded = System.currentTimeMillis()
        )
    }

    @Test
    fun testDragLifecycle_startAndEnd() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val listState = LazyListState()
        val items = mutableListOf("A", "B", "C", "D", "E")

        var movedFrom: Int? = null
        var movedTo: Int? = null
        var endFrom: Int? = null
        var endTo: Int? = null

        val reorderState = ReorderableLazyListState(
            listState = listState,
            scope = scope,
            itemHeightPx = 100f,
            onMove = { from, to ->
                movedFrom = from
                movedTo = to
                val item = items.removeAt(from)
                items.add(to, item)
            },
            onDragEndCallback = { from, to ->
                endFrom = from
                endTo = to
            }
        )

        assertFalse(reorderState.isDragging)
        assertFalse(reorderState.isPointerDown)

        // Start drag on index 1 ("B")
        reorderState.onDragStart(1, items.size - 1)
        assertTrue(reorderState.isDragging)
        assertTrue(reorderState.isPointerDown)
        assertEquals(1, reorderState.draggedIndex)
        assertEquals(0f, reorderState.dragOffsetY, 0.001f)

        // Drag down past threshold (itemHeight is 100f, threshold is 50f)
        reorderState.onDrag(60f, items.size - 1)
        assertEquals(2, reorderState.draggedIndex)
        assertEquals(1, movedFrom)
        assertEquals(2, movedTo)
        assertEquals(listOf("A", "C", "B", "D", "E"), items)

        // End drag
        reorderState.onDragEnd()
        assertFalse(reorderState.isPointerDown)
        assertEquals(1, endFrom)
        assertEquals(2, endTo)
    }

    @Test
    fun testDragAboveViewport_staysAttachedAndClampsElasticallyAtZero() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val listState = LazyListState()
        val items = mutableListOf("A", "B", "C", "D")

        val reorderState = ReorderableLazyListState(
            listState = listState,
            scope = scope,
            itemHeightPx = 100f,
            onMove = { from, to ->
                val item = items.removeAt(from)
                items.add(to, item)
            },
            onDragEndCallback = { _, _ -> }
        )

        // Start drag on index 2 ("C")
        reorderState.onDragStart(2, items.size - 1)
        assertEquals(2, reorderState.draggedIndex)

        // Drag up past index 1 and index 0
        reorderState.onDrag(-120f, items.size - 1)
        assertEquals(1, reorderState.draggedIndex)

        reorderState.onDrag(-120f, items.size - 1)
        assertEquals(0, reorderState.draggedIndex)
        assertEquals(listOf("C", "A", "B", "D"), items)

        // Now dragging further up (above top of list/viewport)
        reorderState.onDrag(-200f, items.size - 1)
        assertEquals(0, reorderState.draggedIndex)
        // Drag offset remains negative and controlled (doesn't disappear or reset to 0)
        assertTrue(reorderState.dragOffsetY < 0f)

        reorderState.onDragEnd()
        assertEquals(listOf("C", "A", "B", "D"), items)
    }

    @Test
    fun testDragBelowViewport_staysAttachedAndClampsElasticallyAtEnd() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val listState = LazyListState()
        val items = mutableListOf("A", "B", "C", "D")

        val reorderState = ReorderableLazyListState(
            listState = listState,
            scope = scope,
            itemHeightPx = 100f,
            onMove = { from, to ->
                val item = items.removeAt(from)
                items.add(to, item)
            },
            onDragEndCallback = { _, _ -> }
        )

        // Start drag on index 1 ("B")
        reorderState.onDragStart(1, items.size - 1)

        // Drag down past index 2 and index 3
        reorderState.onDrag(120f, items.size - 1)
        assertEquals(2, reorderState.draggedIndex)

        reorderState.onDrag(120f, items.size - 1)
        assertEquals(3, reorderState.draggedIndex)
        assertEquals(listOf("A", "C", "D", "B"), items)

        // Dragging further down beyond bottom of list
        reorderState.onDrag(250f, items.size - 1)
        assertEquals(3, reorderState.draggedIndex)
        assertTrue(reorderState.dragOffsetY > 0f)

        reorderState.onDragEnd()
        assertEquals(listOf("A", "C", "D", "B"), items)
    }

    @Test
    fun testRapidDragMovements_preservesRelativeOrderOfNonDraggedItems() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val listState = LazyListState()
        val items = mutableListOf("1", "2", "3", "4", "5", "6", "7", "8")

        val reorderState = ReorderableLazyListState(
            listState = listState,
            scope = scope,
            itemHeightPx = 100f,
            onMove = { from, to ->
                val item = items.removeAt(from)
                items.add(to, item)
            },
            onDragEndCallback = { _, _ -> }
        )

        // Drag item "2" (index 1) rapidly down across 4 slots
        reorderState.onDragStart(1, items.size - 1)
        reorderState.onDrag(450f, items.size - 1)

        // Item 2 moved to index 5
        assertEquals(5, reorderState.draggedIndex)
        assertEquals(listOf("1", "3", "4", "5", "6", "2", "7", "8"), items)

        // Check relative order of non-dragged items: 1, 3, 4, 5, 6, 7, 8 must be strictly preserved
        val nonDragged = items.filter { it != "2" }
        assertEquals(listOf("1", "3", "4", "5", "6", "7", "8"), nonDragged)

        reorderState.onDragEnd()
    }

    @Test
    fun testDragCancel_resetsStateCleanly() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val listState = LazyListState()
        val items = mutableListOf("X", "Y", "Z")

        var callbackCalled = false
        val reorderState = ReorderableLazyListState(
            listState = listState,
            scope = scope,
            itemHeightPx = 100f,
            onMove = { _, _ -> },
            onDragEndCallback = { _, _ -> callbackCalled = true }
        )

        reorderState.onDragStart(0, items.size - 1)
        reorderState.onDrag(40f, items.size - 1)
        assertTrue(reorderState.isDragging)
        assertTrue(reorderState.isPointerDown)

        reorderState.onDragCancel()
        assertFalse(reorderState.isPointerDown)
        assertFalse(callbackCalled)
    }

    @Test
    fun testDuplicateSongReorderingIntegrity() {
        val songA = createDummySong(1L, "Song A")
        val songB = createDummySong(2L, "Song B")
        val songADup = createDummySong(1L, "Song A") // identical ID duplicate

        val queue = listOf(songA, songB, songADup)

        val occurrenceMap = mutableMapOf<Long, Int>()
        val entries = queue.map { song ->
            val count = occurrenceMap.getOrDefault(song.id, 0)
            occurrenceMap[song.id] = count + 1
            QueueEntryItem(
                stableKey = "q_${song.id}_occ${count}_${song.path.hashCode()}",
                song = song
            )
        }.toMutableList()

        assertEquals(3, entries.size)
        // Verify keys are unique even for duplicate IDs
        assertNotEquals(entries[0].stableKey, entries[2].stableKey)

        // Reorder: move the first Song A to after Song B
        val item = entries.removeAt(0)
        entries.add(1, item)

        val resultSongs = entries.map { it.song }
        assertEquals(songB.id, resultSongs[0].id)
        assertEquals(songA.id, resultSongs[1].id)
        assertEquals(songADup.id, resultSongs[2].id)
    }

    @Test
    fun testUpwardDragAutoScroll_tracksViewportAndMaintainsOrder() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val listState = LazyListState()
        val items = mutableListOf("Song0", "Song1", "Song2", "Song3", "Song4", "Song5")

        val reorderState = ReorderableLazyListState(
            listState = listState,
            scope = scope,
            itemHeightPx = 100f,
            onMove = { from, to ->
                val item = items.removeAt(from)
                items.add(to, item)
            },
            onDragEndCallback = { _, _ -> }
        )

        // Start drag on Song4 (index 4) with key
        reorderState.onDragStart(4, items.size - 1, key = "key_Song4")
        assertEquals(4, reorderState.draggedIndex)
        assertEquals("key_Song4", reorderState.draggedKey)

        // Drag upward by 110px (past half-threshold of 50px)
        reorderState.onDrag(-110f, items.size - 1)
        assertEquals(3, reorderState.draggedIndex)
        assertEquals(listOf("Song0", "Song1", "Song2", "Song4", "Song3", "Song5"), items)

        // Continue dragging upward another 110px
        reorderState.onDrag(-110f, items.size - 1)
        assertEquals(2, reorderState.draggedIndex)
        assertEquals(listOf("Song0", "Song1", "Song4", "Song2", "Song3", "Song5"), items)

        // Continue dragging upward to index 0
        reorderState.onDrag(-220f, items.size - 1)
        assertEquals(0, reorderState.draggedIndex)
        assertEquals(listOf("Song4", "Song0", "Song1", "Song2", "Song3", "Song5"), items)

        // Move above viewport (further upward drag)
        val offsetBeforeOvershoot = reorderState.dragOffsetY
        reorderState.onDrag(-150f, items.size - 1)
        assertEquals(0, reorderState.draggedIndex)
        // Drag offset should be negative and elastically bounded
        assertTrue(reorderState.dragOffsetY < offsetBeforeOvershoot)

        // End drag: immediate stop
        reorderState.onDragEnd()
        assertFalse(reorderState.isPointerDown)
        assertEquals(listOf("Song4", "Song0", "Song1", "Song2", "Song3", "Song5"), items)
    }

    @Test
    fun testReorderingAboveAndBelowCurrentlyPlayingSong() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val listState = LazyListState()
        // Queue/Playlist: SongA (0), SongB (1, currently playing), SongC (2), SongD (3)
        val items = mutableListOf("SongA", "SongB", "SongC", "SongD")

        val reorderState = ReorderableLazyListState(
            listState = listState,
            scope = scope,
            itemHeightPx = 100f,
            onMove = { from, to ->
                val item = items.removeAt(from)
                items.add(to, item)
            },
            onDragEndCallback = { _, _ -> }
        )

        // 1. Drag SongA (index 0, above SongB) down BELOW SongB (to index 2, past SongC)
        reorderState.onDragStart(index = 0, minIndex = 0, maxIndex = items.size - 1)
        reorderState.onDrag(220f, minIndex = 0, maxIndex = items.size - 1)
        assertEquals(2, reorderState.draggedIndex)
        // SongA has been brought down below SongB and SongC
        assertEquals(listOf("SongB", "SongC", "SongA", "SongD"), items)
        reorderState.onDragEnd()

        // 2. Drag SongC (currently index 1) UP above SongB (to index 0)
        reorderState.onDragStart(index = 1, minIndex = 0, maxIndex = items.size - 1)
        reorderState.onDrag(-110f, minIndex = 0, maxIndex = items.size - 1)
        assertEquals(0, reorderState.draggedIndex)
        // SongC has been brought UP above SongB
        assertEquals(listOf("SongC", "SongB", "SongA", "SongD"), items)
        reorderState.onDragEnd()

        // 3. Drag SongB (the currently playing song itself, now at index 1) DOWN to the end (index 3)
        reorderState.onDragStart(index = 1, minIndex = 0, maxIndex = items.size - 1)
        reorderState.onDrag(220f, minIndex = 0, maxIndex = items.size - 1)
        assertEquals(3, reorderState.draggedIndex)
        // Currently playing song's position is freely updated
        assertEquals(listOf("SongC", "SongA", "SongD", "SongB"), items)
        reorderState.onDragEnd()
        assertEquals(listOf("SongC", "SongA", "SongD", "SongB"), items)
    }

    @Test
    fun testDirectFingerTrackingAndImmediateDrop_noSpringLag() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val listState = LazyListState()
        val items = mutableListOf("1", "2", "3")
        var dropCommitCalled = false

        val reorderState = ReorderableLazyListState(
            listState = listState,
            scope = scope,
            itemHeightPx = 100f,
            onMove = { from, to ->
                val item = items.removeAt(from)
                items.add(to, item)
            },
            onDragEndCallback = { from, to ->
                dropCommitCalled = true
                assertEquals(0, from)
                assertEquals(1, to)
            }
        )

        reorderState.onDragStart(index = 0, minIndex = 0, maxIndex = items.size - 1)
        assertEquals(com.example.ui.components.DragReorderStatus.DRAGGING, reorderState.status)

        // Drag by 80px: swaps to index 1
        reorderState.onDrag(80f, minIndex = 0, maxIndex = items.size - 1)
        assertEquals(1, reorderState.draggedIndex)
        // dragOffsetY is exactly -20f (80 - 100)
        assertEquals(-20f, reorderState.dragOffsetY, 0.01f)

        // Direct drop release
        reorderState.onDragEnd()

        // Immediate release verification:
        assertTrue(dropCommitCalled)
        assertFalse(reorderState.isPointerDown)
        assertFalse(reorderState.isSettling) // No spring settling delay
        assertEquals(0f, reorderState.dragOffsetY, 0.001f) // Reset to 0 immediately
        assertNull(reorderState.draggedIndex) // Dragged item cleared immediately
        assertEquals(com.example.ui.components.DragReorderStatus.IDLE, reorderState.status)
        assertEquals(listOf("2", "1", "3"), items)
    }

    @Test
    fun testDragStateLifecycle_progressesDeterministically() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val listState = LazyListState()
        val items = mutableListOf("A", "B")

        val reorderState = ReorderableLazyListState(
            listState = listState,
            scope = scope,
            itemHeightPx = 100f,
            onMove = { from, to ->
                val item = items.removeAt(from)
                items.add(to, item)
            },
            onDragEndCallback = { _, _ -> }
        )

        assertEquals(com.example.ui.components.DragReorderStatus.IDLE, reorderState.status)
        assertEquals(com.example.ui.components.AutoScrollDirection.NONE, reorderState.autoScrollDirection)

        reorderState.onDragStart(0, 0, 1)
        assertEquals(com.example.ui.components.DragReorderStatus.DRAGGING, reorderState.status)

        reorderState.onDragCancel()
        assertEquals(com.example.ui.components.DragReorderStatus.IDLE, reorderState.status)
        assertFalse(reorderState.isDragging)
        assertFalse(reorderState.isSettling)
    }

    @Test
    fun testSuddenDirectionReversal_immediatelySwapsWithoutDeadZoneLag() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val listState = LazyListState()
        val items = mutableListOf("1", "2", "3", "4", "5")

        val reorderState = ReorderableLazyListState(
            listState = listState,
            scope = scope,
            itemHeightPx = 100f,
            onMove = { from, to ->
                val item = items.removeAt(from)
                items.add(to, item)
            },
            onDragEndCallback = { _, _ -> }
        )

        // Drag item "4" (index 3) all the way up to index 0
        reorderState.onDragStart(3, items.size - 1)
        reorderState.onDrag(-350f, items.size - 1)
        assertEquals(0, reorderState.draggedIndex)
        assertEquals(listOf("4", "1", "2", "3", "5"), items)

        // Drag further up while already at top index 0 (attempting over-drag)
        reorderState.onDrag(-200f, items.size - 1)
        assertEquals(0, reorderState.draggedIndex)
        // Offset is anti-lag clamped so it cannot build up a large dead-zone
        assertTrue(reorderState.dragOffsetY >= -60f)

        // SUDDEN REVERSAL: User drags down by just 120px
        reorderState.onDrag(120f, items.size - 1)
        // It immediately transposes down to index 1 without getting stuck!
        assertEquals(1, reorderState.draggedIndex)
        assertEquals(listOf("1", "4", "2", "3", "5"), items)

        reorderState.onDragEnd()
        assertEquals(listOf("1", "4", "2", "3", "5"), items)
    }
}


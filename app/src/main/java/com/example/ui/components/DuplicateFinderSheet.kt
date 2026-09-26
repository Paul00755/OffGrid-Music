package com.example.ui.components

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Song
import com.example.ui.theme.*
import java.io.File

data class DuplicateGroup(
    val key: String,
    val displayTitle: String,
    val displayArtist: String,
    val songs: List<Song>
)

object DuplicateFinderUtil {

    /**
     * Groups library songs by normalized title + artist to find duplicate tracks.
     */
    fun findDuplicates(allSongs: List<Song>): List<DuplicateGroup> {
        val groups = allSongs.groupBy { song ->
            val normalizedTitle = song.displayTitle.lowercase().trim().replace(Regex("[^a-z0-9]"), "")
            val normalizedArtist = song.displayArtist.lowercase().trim().replace(Regex("[^a-z0-9]"), "")
            "$normalizedTitle|||$normalizedArtist"
        }

        return groups.filter { it.value.size > 1 }
            .map { (key, songs) ->
                DuplicateGroup(
                    key = key,
                    displayTitle = songs.first().displayTitle,
                    displayArtist = songs.first().displayArtist,
                    songs = songs.sortedWith(
                        compareByDescending<Song> { it.bitrate }
                            .thenByDescending { getSongFileSize(it) }
                            .thenByDescending { it.playCount }
                    )
                )
            }
            .sortedBy { it.displayTitle.lowercase() }
    }

    fun getSongFileSize(song: Song): Long {
        return try {
            val file = File(song.path)
            if (file.exists()) file.length() else 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        return "%d:%02d".format(minutes, seconds)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateFinderSheet(
    allSongs: List<Song>,
    onDismiss: () -> Unit,
    onDeleteSongs: (List<Song>) -> Unit,
    onPreviewSong: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val duplicateGroups = remember(allSongs) {
        DuplicateFinderUtil.findDuplicates(allSongs)
    }

    // Set of song IDs marked for deletion
    // By default, for each group, keep the first (highest quality) and mark the others for deletion
    val selectedForDeletion = remember(duplicateGroups) {
        mutableStateMapOf<Long, Boolean>().apply {
            duplicateGroups.forEach { group ->
                // Mark all except the first (best) copy
                group.songs.drop(1).forEach { song ->
                    put(song.id, true)
                }
            }
        }
    }

    var showConfirmDeleteDialog by remember { mutableStateOf(false) }

    val totalSelectedCount = selectedForDeletion.count { it.value }
    val totalReclaimableBytes = remember(selectedForDeletion, duplicateGroups) {
        var bytes = 0L
        duplicateGroups.forEach { group ->
            group.songs.forEach { song ->
                if (selectedForDeletion[song.id] == true) {
                    bytes += DuplicateFinderUtil.getSongFileSize(song)
                }
            }
        }
        bytes
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BentoBackground,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = BentoTextSecondary.copy(alpha = 0.5f)
            )
        }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Duplicate Songs Finder",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                    }
                    Text(
                        text = if (duplicateGroups.isEmpty()) "No duplicate tracks found in your library"
                        else "${duplicateGroups.size} duplicate groups • ${duplicateGroups.sumOf { it.songs.size }} files total",
                        fontSize = 13.sp,
                        color = BentoTextSecondary
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(BentoCardDarkPurple)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = BentoTextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (duplicateGroups.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = BentoPrimary.copy(alpha = 0.15f),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        Text(
                            text = "Library is Clean",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "No duplicate songs or identical tracks found on your device.",
                            fontSize = 14.sp,
                            color = BentoTextSecondary
                        )
                    }
                }
            } else {
                // Info & selection summary bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BentoCardDarkPurple)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = BentoPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "$totalSelectedCount selected (${Formatter.formatFileSize(context, totalReclaimableBytes)} to free)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Select All",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoPrimary,
                            modifier = Modifier
                                .clickable {
                                    duplicateGroups.forEach { g ->
                                        g.songs.drop(1).forEach { s ->
                                            selectedForDeletion[s.id] = true
                                        }
                                    }
                                }
                                .padding(4.dp)
                        )
                        Text(
                            text = "•",
                            fontSize = 12.sp,
                            color = BentoTextSecondary
                        )
                        Text(
                            text = "Clear",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSecondary,
                            modifier = Modifier
                                .clickable {
                                    selectedForDeletion.clear()
                                }
                                .padding(4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // List of duplicate groups
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(duplicateGroups, key = { it.key }) { group ->
                        DuplicateGroupCard(
                            group = group,
                            selectedForDeletion = selectedForDeletion,
                            onToggleSelection = { songId ->
                                val current = selectedForDeletion[songId] ?: false
                                selectedForDeletion[songId] = !current
                            },
                            onPreviewSong = onPreviewSong
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Delete Button
                Button(
                    onClick = {
                        if (totalSelectedCount > 0) {
                            showConfirmDeleteDialog = true
                        }
                    },
                    enabled = totalSelectedCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BentoAccentPinkText,
                        disabledContainerColor = BentoCardDarkPurple
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (totalSelectedCount > 0) "Delete $totalSelectedCount Selected Duplicate${if (totalSelectedCount > 1) "s" else ""} from Local Storage"
                        else "Select duplicates to delete",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (totalSelectedCount > 0) Color.White else BentoTextSecondary
                    )
                }
            }
        }
    }

    if (showConfirmDeleteDialog) {
        val songsToDelete = duplicateGroups.flatMap { it.songs }.filter { selectedForDeletion[it.id] == true }
        val freedSpaceFormatted = Formatter.formatFileSize(context, totalReclaimableBytes)

        AlertDialog(
            onDismissRequest = { showConfirmDeleteDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = BentoAccentPinkText,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Delete ${songsToDelete.size} Duplicate Files?",
                    color = BentoTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "This action will permanently delete ${songsToDelete.size} audio files from your device storage and free up approximately $freedSpaceFormatted.",
                        color = BentoTextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    Text(
                        text = "The kept copies of each track will remain safe in your library and on your device.",
                        color = BentoTextSecondary,
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDeleteDialog = false
                        onDeleteSongs(songsToDelete)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoAccentPinkText)
                ) {
                    Text("Delete Permanently", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDeleteDialog = false }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun DuplicateGroupCard(
    group: DuplicateGroup,
    selectedForDeletion: Map<Long, Boolean>,
    onToggleSelection: (Long) -> Unit,
    onPreviewSong: ((Song) -> Unit)? = null
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BentoCardSurface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Group Title & Artist
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.displayTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = group.displayArtist,
                        fontSize = 13.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = BentoCardDarkPurple
                ) {
                    Text(
                        text = "${group.songs.size} copies",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = BentoCardDarkPurple.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(10.dp))

            // Track options in group
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group.songs.forEachIndexed { index, song ->
                    val isSelectedToDelete = selectedForDeletion[song.id] == true
                    val fileSize = DuplicateFinderUtil.getSongFileSize(song)
                    val formattedSize = Formatter.formatFileSize(context, fileSize)
                    val durationStr = DuplicateFinderUtil.formatDuration(song.durationMs)
                    val isFirstBest = index == 0

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelectedToDelete) BentoAccentPinkBg else BentoCardDarkPurple.copy(alpha = 0.5f))
                            .border(
                                width = 1.dp,
                                color = if (isSelectedToDelete) BentoAccentPinkText.copy(alpha = 0.4f) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onToggleSelection(song.id) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Checkbox(
                            checked = isSelectedToDelete,
                            onCheckedChange = { onToggleSelection(song.id) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = BentoAccentPinkText,
                                uncheckedColor = BentoTextSecondary
                            ),
                            modifier = Modifier.size(24.dp)
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (isFirstBest) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = BentoPrimary.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = "KEEP",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoPrimary,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                } else if (isSelectedToDelete) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = BentoAccentPinkBg
                                    ) {
                                        Text(
                                            text = "DELETE",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoAccentPinkText,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = "$durationStr • $formattedSize • ${song.format}${if (song.bitrate > 0) " (${song.bitrate} kbps)" else ""}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isSelectedToDelete) BentoTextSecondary else BentoTextPrimary
                                )
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = song.path,
                                fontSize = 11.sp,
                                color = BentoTextSecondary.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (onPreviewSong != null) {
                            IconButton(
                                onClick = { onPreviewSong(song) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Preview song",
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

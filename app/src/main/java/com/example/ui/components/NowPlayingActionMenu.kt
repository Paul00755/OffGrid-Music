package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Song
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingActionMenu(
    expanded: Boolean,
    song: Song,
    onDismissRequest: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onOpenQueueSheet: () -> Unit,
    onChooseLrc: () -> Unit,
    onSearchOnlineLyrics: () -> Unit,
    onEditTags: (() -> Unit)? = null,
    onDeleteSong: () -> Unit
) {
    if (!expanded) return

    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = BentoCardSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header Row with Album Art, Title, Artist, Album, Close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtView(
                    song = song,
                    modifier = Modifier.size(56.dp),
                    cornerRadius = 12
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.displayTitle,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${song.displayArtist} • ${song.displayAlbum}",
                        fontSize = 13.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(BentoCardDarkPurple)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = BentoTextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(8.dp))

            // Action Items
            NowPlayingMenuOptionRow(
                icon = Icons.Default.Bedtime,
                title = "Sleep Timer",
                iconTint = BentoPrimary,
                onClick = {
                    onDismissRequest()
                    onOpenSleepTimer()
                }
            )

            NowPlayingMenuOptionRow(
                icon = Icons.Default.PlaylistAdd,
                title = "Add to Playlist",
                iconTint = BentoPrimary,
                onClick = {
                    onDismissRequest()
                    onAddToPlaylist()
                }
            )

            NowPlayingMenuOptionRow(
                icon = Icons.Default.RemoveCircleOutline,
                title = "Remove from Playlist",
                iconTint = BentoAccentPinkText,
                onClick = {
                    onDismissRequest()
                    onRemoveFromPlaylist()
                }
            )

            NowPlayingMenuOptionRow(
                icon = Icons.Default.FormatListNumberedRtl,
                title = "Play Next",
                iconTint = BentoPrimary,
                onClick = {
                    onDismissRequest()
                    onPlayNext()
                    Toast.makeText(context, "Playing next", Toast.LENGTH_SHORT).show()
                }
            )

            NowPlayingMenuOptionRow(
                icon = Icons.Default.QueueMusic,
                title = "Up Next Queue",
                iconTint = BentoPrimary,
                onClick = {
                    onDismissRequest()
                    onOpenQueueSheet()
                }
            )

            NowPlayingMenuOptionRow(
                icon = Icons.Default.InsertDriveFile,
                title = "Choose .LRC File",
                iconTint = BentoPrimary,
                onClick = {
                    onDismissRequest()
                    onChooseLrc()
                }
            )

            NowPlayingMenuOptionRow(
                icon = Icons.Default.ManageSearch,
                title = "Search Online Lyrics",
                iconTint = BentoPrimary,
                onClick = {
                    onDismissRequest()
                    onSearchOnlineLyrics()
                }
            )

            if (onEditTags != null) {
                NowPlayingMenuOptionRow(
                    icon = Icons.Default.EditNote,
                    title = "Edit Audio Tags & Lyrics",
                    iconTint = BentoPrimary,
                    onClick = {
                        onDismissRequest()
                        onEditTags()
                    }
                )
            }

            NowPlayingMenuOptionRow(
                icon = Icons.Default.Delete,
                title = "Delete Song",
                iconTint = BentoAccentPinkText,
                onClick = {
                    onDismissRequest()
                    onDeleteSong()
                }
            )
        }
    }
}

@Composable
private fun NowPlayingMenuOptionRow(
    icon: ImageVector,
    title: String,
    iconTint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = BentoTextPrimary
        )
    }
}

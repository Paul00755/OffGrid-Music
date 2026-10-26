package com.example.ui.screens.layouts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Playlist
import com.example.data.model.SmartPlaylist
import com.example.ui.components.PlaylistCover
import com.example.ui.theme.*
import com.example.util.HapticUtils

@Composable
fun PlaylistsScreenCompactList(
    playlists: List<Playlist>,
    smartPlaylists: List<SmartPlaylist>,
    onCreatePlaylist: () -> Unit,
    onCreateSmartPlaylist: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onSmartPlaylistClick: (SmartPlaylist) -> Unit,
    onPlayNow: (Playlist) -> Unit,
    onPlayNext: (Playlist) -> Unit,
    onAddToQueue: (Playlist) -> Unit,
    onRenamePlaylist: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onDeleteSmartPlaylist: (SmartPlaylist) -> Unit,
    onUpdatePlaylistArtwork: (Playlist) -> Unit,
    onImportM3u: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("COMPACT LIST", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = BentoPrimary, letterSpacing = 1.sp)
                    Text("Playlists (${playlists.size + smartPlaylists.size})", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onImportM3u != null) {
                        Surface(
                            onClick = onImportM3u,
                            shape = CircleShape,
                            color = BentoCardSurface,
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.FileUpload, contentDescription = "Import M3U Playlist", tint = BentoTextPrimary, modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                    Surface(
                        onClick = onCreateSmartPlaylist,
                        shape = CircleShape,
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.32f)),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Smart Playlist", tint = BentoPrimary, modifier = Modifier.size(17.dp))
                        }
                    }
                    Surface(
                        onClick = onCreatePlaylist,
                        shape = CircleShape,
                        color = BentoPrimary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.32f)),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = "New Playlist", tint = BentoPrimary, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }

        // Smart Playlists
        items(smartPlaylists) { smart ->
            var smartMenuExpanded by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BentoCardDarkPurple,
                border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSmartPlaylistClick(smart) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BentoPrimary.copy(alpha = 0.2f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(smart.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Smart Playlist • Auto Rules", fontSize = 11.5.sp, color = BentoTextSecondary)
                    }
                    Box {
                        IconButton(onClick = { smartMenuExpanded = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = BentoTextSecondary, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = smartMenuExpanded,
                            onDismissRequest = { smartMenuExpanded = false },
                            modifier = Modifier.background(BentoCardSurface)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete Smart Playlist", color = BentoError) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = BentoError) },
                                onClick = { smartMenuExpanded = false; onDeleteSmartPlaylist(smart) }
                            )
                        }
                    }
                }
            }
        }

        // Normal Playlists
        items(playlists, key = { it.id }) { playlist ->
            var menuExpanded by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BentoCardSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlaylistClick(playlist) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PlaylistCover(
                        playlist = playlist,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(playlist.name, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${playlist.songCount} songs", fontSize = 12.sp, color = BentoTextSecondary)
                    }
                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onPlayNow(playlist)
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(BentoPrimary)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = BentoOnPrimary, modifier = Modifier.size(18.dp))
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = BentoTextSecondary, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.background(BentoCardSurface)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Play Next", color = BentoTextPrimary) },
                                leadingIcon = { Icon(Icons.Default.SkipNext, null, tint = BentoPrimary) },
                                onClick = { menuExpanded = false; onPlayNext(playlist) }
                            )
                            DropdownMenuItem(
                                text = { Text("Add to Queue", color = BentoTextPrimary) },
                                leadingIcon = { Icon(Icons.Default.QueueMusic, null, tint = BentoPrimary) },
                                onClick = { menuExpanded = false; onAddToQueue(playlist) }
                            )
                            DropdownMenuItem(
                                text = { Text("Change Cover Art", color = BentoTextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Image, null, tint = BentoPrimary) },
                                onClick = { menuExpanded = false; onUpdatePlaylistArtwork(playlist) }
                            )
                            DropdownMenuItem(
                                text = { Text("Rename", color = BentoTextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Edit, null, tint = BentoPrimary) },
                                onClick = { menuExpanded = false; onRenamePlaylist(playlist) }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = BentoError) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = BentoError) },
                                onClick = { menuExpanded = false; onDeletePlaylist(playlist) }
                            )
                        }
                    }
                }
            }
        }
    }
}

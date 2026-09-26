package com.example.ui.screens.layouts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Playlist
import com.example.data.model.SmartPlaylist
import com.example.ui.components.BentoCard
import com.example.ui.components.PlaylistCover
import com.example.ui.theme.*
import com.example.util.HapticUtils

@Composable
fun PlaylistsScreenMagazineHero(
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
    val featuredPlaylist = playlists.firstOrNull()
    val otherPlaylists = if (playlists.size > 1) playlists.drop(1) else emptyList()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("MAGAZINE EDITORIAL", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = BentoPrimary, letterSpacing = 1.sp)
                    Text("Playlists Collection", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
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

        // Spotlight Hero Playlist
        if (featuredPlaylist != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = BentoCardSurface,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, BentoPrimary.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlaylistClick(featuredPlaylist) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        BentoPrimary.copy(alpha = 0.2f),
                                        BentoCardDarkPurple.copy(alpha = 0.95f),
                                        BentoCardSurface
                                    )
                                )
                            )
                            .padding(20.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = BentoPrimary.copy(alpha = 0.25f)
                                ) {
                                    Text(
                                        text = "SPOTLIGHT PLAYLIST",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        letterSpacing = 1.sp
                                    )
                                }

                                Text(
                                    text = "${featuredPlaylist.songCount} Songs",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoTextSecondary
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                PlaylistCover(
                                    playlist = featuredPlaylist,
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = featuredPlaylist.name,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Curated Mix • Tap to explore",
                                        fontSize = 12.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                            }

                            Surface(
                                shape = CircleShape,
                                color = BentoPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        HapticUtils.performClickHaptic(context)
                                        onPlayNow(featuredPlaylist)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BentoOnPrimary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Play Entire Playlist", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = BentoOnPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Smart Rules Playlists Section
        if (smartPlaylists.isNotEmpty()) {
            item {
                Text("Smart Playlists", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(smartPlaylists) { smart ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = BentoCardDarkPurple,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder),
                            modifier = Modifier
                                .width(200.dp)
                                .clickable { onSmartPlaylistClick(smart) }
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(18.dp))
                                    Text(smart.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("Dynamic auto-updating mix", fontSize = 11.sp, color = BentoTextSecondary)
                            }
                        }
                    }
                }
            }
        }

        // Other Playlists Magazine Stream
        if (otherPlaylists.isNotEmpty()) {
            item {
                Text("All Collections", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
            }
            items(otherPlaylists) { playlist ->
                var menuExpanded by remember { mutableStateOf(false) }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BentoCardSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlaylistClick(playlist) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        PlaylistCover(
                            playlist = playlist,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${playlist.songCount} tracks",
                                fontSize = 12.sp,
                                color = BentoTextSecondary
                            )
                        }
                        IconButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onPlayNow(playlist)
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(BentoPrimary)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = BentoOnPrimary, modifier = Modifier.size(20.dp))
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
}

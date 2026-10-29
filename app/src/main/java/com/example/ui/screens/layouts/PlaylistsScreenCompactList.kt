package com.example.ui.screens.layouts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Playlist
import com.example.data.model.SmartPlaylist
import com.example.ui.components.FastScrollbar
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
    onBackClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var filterQuery by remember { mutableStateOf("") }

    val filteredSmartPlaylists = remember(smartPlaylists, filterQuery) {
        if (filterQuery.isBlank()) smartPlaylists
        else smartPlaylists.filter { it.name.contains(filterQuery, ignoreCase = true) }
    }

    val filteredPlaylists = remember(playlists, filterQuery) {
        if (filterQuery.isBlank()) playlists
        else playlists.filter { it.name.contains(filterQuery, ignoreCase = true) }
    }

    fun formatDuration(ms: Long): String {
        val mins = (ms / (1000 * 60)).coerceAtLeast(0L)
        return if (mins >= 60) "${String.format("%.1f", mins / 60.0)}h" else "${mins}m"
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. High-Density Compact Header + Inline Filter Bar
            item(key = "compact_list_header", contentType = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (onBackClick != null) {
                                Surface(
                                    onClick = onBackClick,
                                    shape = CircleShape,
                                    color = BentoCardSurface,
                                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back",
                                            tint = BentoTextPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            Column {
                                Text(
                                    text = "COMPACT INDEX",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = BentoPrimary,
                                    letterSpacing = 1.1.sp
                                )
                                Text(
                                    text = "Playlists (${playlists.size + smartPlaylists.size})",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            if (onImportM3u != null) {
                                Surface(
                                    onClick = onImportM3u,
                                    shape = CircleShape,
                                    color = BentoCardSurface,
                                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
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
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Smart Playlist", tint = BentoPrimary, modifier = Modifier.size(17.dp))
                                }
                            }
                            Surface(
                                onClick = onCreatePlaylist,
                                shape = CircleShape,
                                color = BentoPrimary,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Add, contentDescription = "New Playlist", tint = BentoOnPrimary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    // Compact Instant Filter Bar
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Filter Playlists",
                                tint = BentoPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                if (filterQuery.isEmpty()) {
                                    Text(
                                        text = "Filter ${playlists.size + smartPlaylists.size} collections...",
                                        fontSize = 12.5.sp,
                                        color = BentoTextSecondary
                                    )
                                }
                                BasicTextField(
                                    value = filterQuery,
                                    onValueChange = { filterQuery = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = BentoTextPrimary,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    cursorBrush = SolidColor(BentoPrimary),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (filterQuery.isNotEmpty()) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear Filter",
                                    tint = BentoTextSecondary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { filterQuery = "" }
                                )
                            }
                        }
                    }
                }
            }

            // 2. Smart Playlists Compact Section
            if (filteredSmartPlaylists.isNotEmpty()) {
                item(key = "compact_smart_section_label") {
                    Text(
                        text = "SMART RULES (${filteredSmartPlaylists.size})",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = BentoPrimary,
                        letterSpacing = 0.9.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                itemsIndexed(filteredSmartPlaylists, key = { idx, sm -> "smart_${sm.id}_$idx" }, contentType = { _, _ -> "smart_compact_row" }) { _, smart ->
                    var smartMenuExpanded by remember { mutableStateOf(false) }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BentoCardDarkPurple,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSmartPlaylistClick(smart) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BentoPrimary.copy(alpha = 0.18f),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(18.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = smart.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = smart.getCriteriaBadges().take(3).joinToString(" • ").ifEmpty { "Smart Playlist • Auto Rules" },
                                    fontSize = 11.sp,
                                    color = BentoTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box {
                                IconButton(onClick = { smartMenuExpanded = true }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = BentoTextSecondary, modifier = Modifier.size(17.dp))
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
            }

            // 3. User Playlists High-Density Rows
            if (filteredPlaylists.isNotEmpty()) {
                item(key = "compact_user_section_label") {
                    Text(
                        text = "COLLECTIONS (${filteredPlaylists.size})",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = BentoTextSecondary,
                        letterSpacing = 0.9.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
            }

            itemsIndexed(filteredPlaylists, key = { _, it -> it.id }, contentType = { _, _ -> "playlist_compact_row" }) { index, playlist ->
                var menuExpanded by remember { mutableStateOf(false) }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BentoCardSurface,
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlaylistClick(playlist) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "%02d".format(index + 1),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSecondary,
                            modifier = Modifier.width(20.dp)
                        )
                        PlaylistCover(
                            playlist = playlist,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${playlist.songCount} songs • ${formatDuration(playlist.totalDurationMs)}",
                                fontSize = 11.5.sp,
                                color = BentoTextSecondary,
                                maxLines = 1
                            )
                        }
                        IconButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onPlayNow(playlist)
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(BentoPrimary)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = BentoOnPrimary, modifier = Modifier.size(17.dp))
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = BentoTextSecondary, modifier = Modifier.size(17.dp))
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

        FastScrollbar(
            listState = listState,
            totalItems = filteredPlaylists.size + filteredSmartPlaylists.size + 3,
            itemIndexToLabel = { idx ->
                val plIdx = (idx - filteredSmartPlaylists.size - 2).coerceIn(0, (filteredPlaylists.size - 1).coerceAtLeast(0))
                filteredPlaylists.getOrNull(plIdx)?.name?.firstOrNull()?.uppercaseChar()?.toString()
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(bottom = 100.dp, end = 2.dp)
        )
    }
}

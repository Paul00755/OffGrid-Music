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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 1. Frameless Audiophile Index Header + Floating Capsule Filter Bar
            item(key = "compact_list_header", contentType = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                    color = BentoCardSurface.copy(alpha = 0.65f),
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
                                    text = "AUDIOPHILE DIRECTORY // COMPACT STREAM",
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoPrimary,
                                    letterSpacing = 1.2.sp
                                )
                                Text(
                                    text = "Index (${playlists.size + smartPlaylists.size})",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = BentoTextPrimary
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            if (onImportM3u != null) {
                                Surface(
                                    onClick = onImportM3u,
                                    shape = CircleShape,
                                    color = BentoCardSurface.copy(alpha = 0.65f),
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
                                color = BentoCardSurface.copy(alpha = 0.65f),
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

                    // 50%-Rounded Floating Capsule Filter Bar
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = BentoCardSurface.copy(alpha = 0.72f),
                        border = BorderStroke(
                            1.dp,
                            if (filterQuery.isNotEmpty()) BentoPrimary.copy(alpha = 0.55f) else BentoBorder.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                                        text = "Quick filter ${playlists.size + smartPlaylists.size} collections...",
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

                    Spacer(modifier = Modifier.height(2.dp))
                }
            }

            // 2. Smart Playlists Frameless Capsule Strip
            if (filteredSmartPlaylists.isNotEmpty()) {
                item(key = "compact_smart_section_label") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DYNAMIC RULES // ${filteredSmartPlaylists.size}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = BentoPrimary,
                            letterSpacing = 1.1.sp
                        )
                    }
                }
                itemsIndexed(filteredSmartPlaylists, key = { idx, sm -> "smart_${sm.id}_$idx" }, contentType = { _, _ -> "smart_compact_row" }) { _, smart ->
                    var smartMenuExpanded by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSmartPlaylistClick(smart) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = BentoPrimary.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(16.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = smart.name,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
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
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = BentoPrimary.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = "AUTO",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = BentoPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = BentoBorder.copy(alpha = 0.25f),
                            modifier = Modifier.padding(start = 50.dp)
                        )
                    }
                }
            }

            // 3. User Playlists Frameless High-Density Index Rows (Unboxed with Hairline Dividers)
            if (filteredPlaylists.isNotEmpty()) {
                item(key = "compact_user_section_label") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MASTER ARCHIVE // ${filteredPlaylists.size}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSecondary,
                            letterSpacing = 1.1.sp
                        )
                        Text(
                            text = "TRK / DUR",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = BentoTextSecondary.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            itemsIndexed(filteredPlaylists, key = { _, it -> it.id }, contentType = { _, _ -> "playlist_compact_row" }) { index, playlist ->
                var menuExpanded by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlaylistClick(playlist) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "%02d".format(index + 1),
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = BentoPrimary.copy(alpha = 0.85f),
                            modifier = Modifier.width(22.dp)
                        )
                        PlaylistCover(
                            playlist = playlist,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape),
                            cornerRadius = 50
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (playlist.isPinned) "Pinned Collection" else "Custom Mix",
                                fontSize = 11.sp,
                                color = BentoTextSecondary,
                                maxLines = 1
                            )
                        }

                        // Compact Telemetry Pill (Track count + Duration)
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = BentoCardSurface.copy(alpha = 0.65f),
                            border = BorderStroke(0.5.dp, BentoBorder.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "${playlist.songCount} • ${formatDuration(playlist.totalDurationMs)}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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
                                .background(BentoPrimary.copy(alpha = 0.16f))
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = BentoPrimary, modifier = Modifier.size(17.dp))
                        }

                        Box {
                            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = BentoTextSecondary, modifier = Modifier.size(16.dp))
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

                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = BentoBorder.copy(alpha = 0.25f),
                        modifier = Modifier.padding(start = 78.dp)
                    )
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

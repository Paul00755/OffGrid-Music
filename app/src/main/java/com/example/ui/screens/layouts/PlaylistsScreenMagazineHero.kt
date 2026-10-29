package com.example.ui.screens.layouts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.ui.components.FastScrollbar
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
    onBackClick: (() -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val featuredPlaylist = playlists.firstOrNull()
    val otherPlaylists = if (playlists.size > 1) playlists.drop(1) else emptyList()

    fun formatDuration(ms: Long): String {
        val mins = (ms / (1000 * 60)).coerceAtLeast(0L)
        return if (mins >= 60) "${String.format("%.1f", mins / 60.0)} hrs" else "$mins mins"
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 1. Editorial Masthead Header
            item(key = "magazine_hero_header", contentType = "header") {
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
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = BentoTextPrimary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }

                        Column {
                            Text(
                                text = "MAGAZINE HERO • ISSUE 01",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoPrimary,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "Curated Collections",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onOpenSearch != null) {
                            Surface(
                                onClick = onOpenSearch,
                                shape = CircleShape,
                                color = BentoCardSurface,
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = BentoTextPrimary, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
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
            }

            // 2. Spotlight Magazine Cover Hero Card
            if (featuredPlaylist != null) {
                item(key = "magazine_hero_featured_${featuredPlaylist.id}", contentType = "hero") {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.5.dp, BentoPrimary.copy(alpha = 0.45f)),
                        shadowElevation = 10.dp,
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
                                            BentoPrimary.copy(alpha = 0.24f),
                                            BentoCardDarkPurple.copy(alpha = 0.96f),
                                            BentoCardSurface
                                        )
                                    )
                                )
                                .padding(20.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = BentoPrimary.copy(alpha = 0.22f),
                                        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f))
                                    ) {
                                        Text(
                                            text = "COVER STORY • SPOTLIGHT",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = BentoPrimary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            letterSpacing = 1.sp
                                        )
                                    }

                                    Text(
                                        text = "${featuredPlaylist.songCount} Tracks • ${formatDuration(featuredPlaylist.totalDurationMs)}",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BentoTextSecondary
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
                                        shadowElevation = 6.dp,
                                        modifier = Modifier.size(110.dp)
                                    ) {
                                        PlaylistCover(
                                            playlist = featuredPlaylist,
                                            modifier = Modifier.fillMaxSize(),
                                            cornerRadius = 20
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = featuredPlaylist.name,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = BentoTextPrimary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Featured Editorial Mix • Tap to open tracklist",
                                            fontSize = 12.sp,
                                            color = BentoTextSecondary,
                                            maxLines = 2
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = BentoPrimary,
                                        modifier = Modifier
                                            .weight(1.4f)
                                            .height(46.dp)
                                            .clickable {
                                                HapticUtils.performClickHaptic(context)
                                                onPlayNow(featuredPlaylist)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BentoOnPrimary, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Play Feature", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = BentoOnPrimary)
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = BentoCardDarkPurple,
                                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.6f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                            .clickable {
                                                HapticUtils.performClickHaptic(context)
                                                onAddToQueue(featuredPlaylist)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.QueueMusic, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(17.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Queue All", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = BentoTextPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Empty state when no playlists exist yet
                item(key = "magazine_empty_state") {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCreatePlaylist() }
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.QueueMusic, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(42.dp))
                            Text("Create Your First Editorial Mix", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = BentoTextPrimary)
                            Text("Tap + to build a custom playlist or import an .M3U collection.", fontSize = 12.5.sp, color = BentoTextSecondary)
                        }
                    }
                }
            }

            // 3. Editorial Smart Mixes Carousel
            if (smartPlaylists.isNotEmpty()) {
                item(key = "magazine_smart_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(18.dp))
                            Text("DYNAMIC SMART EDITIONS", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = BentoTextPrimary, letterSpacing = 0.8.sp)
                        }
                        Text(
                            text = "+ New Rule",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoPrimary,
                            modifier = Modifier.clickable { onCreateSmartPlaylist() }
                        )
                    }
                }
                item(key = "magazine_smart_row") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(smartPlaylists, key = { idx, sm -> "${sm.id}_$idx" }) { idx, smart ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = BentoCardDarkPurple,
                                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
                                modifier = Modifier
                                    .width(215.dp)
                                    .clickable { onSmartPlaylistClick(smart) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "EDITION 0${idx + 1}",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = BentoPrimary,
                                            letterSpacing = 1.sp
                                        )
                                        IconButton(
                                            onClick = { onDeleteSmartPlaylist(smart) },
                                            modifier = Modifier.size(22.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Delete", tint = BentoTextSecondary, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                    Text(
                                        text = smart.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = smart.getCriteriaBadges().take(2).joinToString(" • ").ifEmpty { "Auto-updating mix" },
                                        fontSize = 11.sp,
                                        color = BentoTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. All Collections Editorial Feature Stream
            if (otherPlaylists.isNotEmpty()) {
                item(key = "magazine_all_collections_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ARCHIVE • ALL COLLECTIONS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = BentoTextPrimary,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "${otherPlaylists.size} Volumes",
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary
                        )
                    }
                }
                itemsIndexed(otherPlaylists, key = { _, it -> it.id }, contentType = { _, _ -> "magazine_playlist_card" }) { index, playlist ->
                    var menuExpanded by remember { mutableStateOf(false) }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlaylistClick(playlist) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            PlaylistCover(
                                playlist = playlist,
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "VOL. %02d".format(index + 2),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = BentoPrimary,
                                    letterSpacing = 0.9.sp
                                )
                                Text(
                                    text = playlist.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${playlist.songCount} tracks • ${formatDuration(playlist.totalDurationMs)}",
                                    fontSize = 12.sp,
                                    color = BentoTextSecondary
                                )
                            }
                            Surface(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onPlayNow(playlist)
                                },
                                shape = CircleShape,
                                color = BentoPrimary,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = BentoOnPrimary, modifier = Modifier.size(20.dp))
                                }
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

        FastScrollbar(
            listState = listState,
            totalItems = otherPlaylists.size + smartPlaylists.size + 4,
            itemIndexToLabel = { idx ->
                val plIndex = (idx - 3).coerceIn(0, (otherPlaylists.size - 1).coerceAtLeast(0))
                otherPlaylists.getOrNull(plIndex)?.name?.firstOrNull()?.uppercaseChar()?.toString()
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(bottom = 100.dp, end = 2.dp)
        )
    }
}

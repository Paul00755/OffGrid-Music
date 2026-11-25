package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Song
import com.example.ui.theme.*
import com.example.util.HapticUtils

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
    onOpenSonicStudio: (() -> Unit)? = null,
    activeRepeatCount: Int = 0,
    onRepeatSong: (() -> Unit)? = null,
    onDeleteSong: () -> Unit,
    isSleepTimerActive: Boolean = false,
    onFavoriteToggle: (() -> Unit)? = null
) {
    if (!expanded) return

    val context = LocalContext.current
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetBg = if (isDarkTheme) BentoCardSurface else Color(0xFFFDFDFF)
    val cardBg = if (isDarkTheme) BentoCardDarkPurple.copy(alpha = 0.62f) else Color(0xFFF3F4F8)
    val cardBorder = if (isDarkTheme) BentoBorder.copy(alpha = 0.35f) else Color(0xFFE2E6F0)
    val dangerAccent = Color(0xFFFF4D6D)

    val formattedDuration = remember(song.durationMs) {
        val totalSec = (song.durationMs / 1000L).coerceAtLeast(0L)
        "%d:%02d".format(totalSec / 60L, totalSec % 60L)
    }
    val fileExt = remember(song.path) {
        song.path.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..5 }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = sheetBg,
        scrimColor = Color.Black.copy(alpha = 0.62f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(BentoTextSecondary.copy(alpha = 0.35f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(top = 4.dp, bottom = 24.dp)
                .testTag("now_playing_action_menu_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Rich Track Hero Card
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = if (isDarkTheme) 0.28f else 0.18f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    BentoPrimary.copy(alpha = if (isDarkTheme) 0.14f else 0.08f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                        shadowElevation = 4.dp,
                        modifier = Modifier.size(60.dp)
                    ) {
                        AlbumArtView(
                            song = song,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 14
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = song.displayTitle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${song.displayArtist} • ${song.displayAlbum}",
                            fontSize = 12.5.sp,
                            color = BentoTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            MetadataPill(text = formattedDuration, accent = BentoPrimary)
                            if (fileExt != null) {
                                MetadataPill(text = fileExt, accent = BentoTextSecondary)
                            }
                            if (activeRepeatCount > 0) {
                                MetadataPill(text = "Repeat ${activeRepeatCount}×", accent = BentoPrimary)
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (onFavoriteToggle != null) {
                            IconButton(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    onFavoriteToggle()
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (song.isFavorite) BentoAccentPinkText.copy(alpha = 0.16f)
                                        else BentoTextSecondary.copy(alpha = 0.10f)
                                    )
                                    .testTag("now_playing_menu_favorite_button")
                            ) {
                                AnimatedFavoriteIcon(
                                    isFavorite = song.isFavorite,
                                    activeTint = BentoAccentPinkText,
                                    inactiveTint = BentoTextSecondary,
                                    size = 20.dp,
                                    contentDescription = if (song.isFavorite) "Remove from favorites" else "Add to favorites",
                                    animationKey = song.id
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onDismissRequest()
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(BentoTextSecondary.copy(alpha = 0.12f))
                                .testTag("now_playing_menu_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close song actions menu",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // 2. Quick Action Grid (4 high-frequency actions)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickActionTile(
                    icon = Icons.Default.PlaylistAdd,
                    label = "Add to\nPlaylist",
                    accentColor = BentoPrimary,
                    containerColor = cardBg,
                    borderColor = cardBorder,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("now_playing_menu_add_playlist"),
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismissRequest()
                        onAddToPlaylist()
                    }
                )
                QuickActionTile(
                    icon = Icons.Default.FormatListNumberedRtl,
                    label = "Play\nNext",
                    accentColor = BentoPrimary,
                    containerColor = cardBg,
                    borderColor = cardBorder,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("now_playing_menu_play_next"),
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismissRequest()
                        onPlayNext()
                        Toast.makeText(context, "Playing next", Toast.LENGTH_SHORT).show()
                    }
                )
                QuickActionTile(
                    icon = Icons.Default.Bedtime,
                    label = if (isSleepTimerActive) "Timer\nActive" else "Sleep\nTimer",
                    accentColor = if (isSleepTimerActive) Color(0xFF00E5FF) else BentoPrimary,
                    containerColor = if (isSleepTimerActive) BentoPrimary.copy(alpha = 0.16f) else cardBg,
                    borderColor = if (isSleepTimerActive) BentoPrimary.copy(alpha = 0.45f) else cardBorder,
                    isHighlighted = isSleepTimerActive,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("now_playing_menu_sleep_timer"),
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismissRequest()
                        onOpenSleepTimer()
                    }
                )
                QuickActionTile(
                    icon = Icons.Default.QueueMusic,
                    label = "Up Next\nQueue",
                    accentColor = BentoPrimary,
                    containerColor = cardBg,
                    borderColor = cardBorder,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("now_playing_menu_queue"),
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismissRequest()
                        onOpenQueueSheet()
                    }
                )
            }

            // 3. Sonic Studio Spotlight Card
            if (onOpenSonicStudio != null) {
                val sonicCyan = Color(0xFF00D4FF)
                Surface(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismissRequest()
                        onOpenSonicStudio()
                    },
                    shape = RoundedCornerShape(18.dp),
                    color = cardBg,
                    border = BorderStroke(1.dp, sonicCyan.copy(alpha = if (isDarkTheme) 0.42f else 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("now_playing_menu_sonic_studio")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        sonicCyan.copy(alpha = if (isDarkTheme) 0.18f else 0.12f),
                                        BentoPrimary.copy(alpha = if (isDarkTheme) 0.10f else 0.06f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(sonicCyan.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = sonicCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sonic Studio",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = "A–B Loop, Audio Pins & DNA Radio",
                                fontSize = 12.sp,
                                color = BentoTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = sonicCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 4. Playback Controls Group
            if (onRepeatSong != null) {
                MenuSectionGroup(
                    label = "PLAYBACK",
                    containerColor = cardBg,
                    borderColor = cardBorder
                ) {
                    NowPlayingMenuOptionRow(
                        icon = Icons.Default.RepeatOne,
                        title = if (activeRepeatCount > 0) {
                            "Repeat this song ($activeRepeatCount× left)"
                        } else {
                            "Repeat this song"
                        },
                        subtitle = if (activeRepeatCount > 0) "Tap to adjust or cancel track repeat count" else "Loop this track a specific number of times",
                        iconTint = BentoPrimary,
                        trailingBadge = if (activeRepeatCount > 0) "${activeRepeatCount}×" else null,
                        modifier = Modifier.testTag("now_playing_repeat_song_action"),
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onDismissRequest()
                            onRepeatSong()
                        }
                    )
                }
            }

            // 5. Lyrics & Metadata Group
            MenuSectionGroup(
                label = "LYRICS & METADATA",
                containerColor = cardBg,
                borderColor = cardBorder
            ) {
                NowPlayingMenuOptionRow(
                    icon = Icons.Default.ManageSearch,
                    title = "Search Online Lyrics",
                    subtitle = "Browse synced and plain lyric matches",
                    iconTint = BentoPrimary,
                    modifier = Modifier.testTag("now_playing_menu_search_lyrics"),
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismissRequest()
                        onSearchOnlineLyrics()
                    }
                )
                HorizontalDivider(
                    color = BentoTextSecondary.copy(alpha = 0.12f),
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                NowPlayingMenuOptionRow(
                    icon = Icons.Default.InsertDriveFile,
                    title = "Choose .LRC File",
                    subtitle = "Attach a local synced lyrics file",
                    iconTint = BentoPrimary,
                    modifier = Modifier.testTag("now_playing_menu_choose_lrc"),
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismissRequest()
                        onChooseLrc()
                    }
                )
                if (onEditTags != null) {
                    HorizontalDivider(
                        color = BentoTextSecondary.copy(alpha = 0.12f),
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                    NowPlayingMenuOptionRow(
                        icon = Icons.Default.EditNote,
                        title = "Edit Audio Tags & Lyrics",
                        subtitle = "Update title, artist, album, cover, and lyrics",
                        iconTint = BentoPrimary,
                        modifier = Modifier.testTag("now_playing_menu_edit_tags"),
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onDismissRequest()
                            onEditTags()
                        }
                    )
                }
            }

            // 6. Library & File Management Group
            MenuSectionGroup(
                label = "LIBRARY & FILE",
                containerColor = cardBg,
                borderColor = cardBorder
            ) {
                NowPlayingMenuOptionRow(
                    icon = Icons.Default.RemoveCircleOutline,
                    title = "Remove from Playlist",
                    subtitle = "Take this track out of a saved playlist",
                    iconTint = BentoAccentPinkText,
                    modifier = Modifier.testTag("now_playing_menu_remove_playlist"),
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onDismissRequest()
                        onRemoveFromPlaylist()
                    }
                )
            }

            // 7. Distinct Danger Action Surface
            Surface(
                onClick = {
                    HapticUtils.performClickHaptic(context)
                    onDismissRequest()
                    onDeleteSong()
                },
                shape = RoundedCornerShape(16.dp),
                color = dangerAccent.copy(alpha = if (isDarkTheme) 0.12f else 0.08f),
                border = BorderStroke(1.dp, dangerAccent.copy(alpha = 0.34f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("now_playing_menu_delete_song")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(dangerAccent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = dangerAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Delete Song",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = dangerAccent
                        )
                        Text(
                            text = "Permanently remove audio file from device",
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataPill(
    text: String,
    accent: Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.14f)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    accentColor: Color,
    containerColor: Color,
    borderColor: Color,
    isHighlighted: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.heightIn(min = 82.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = if (isHighlighted) 0.24f else 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label.replace("\n", " "),
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = label,
                fontSize = 11.sp,
                lineHeight = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = BentoTextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MenuSectionGroup(
    label: String,
    containerColor: Color,
    borderColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
            color = BentoTextSecondary,
            modifier = Modifier.padding(start = 6.dp)
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = containerColor,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
    }
}

@Composable
private fun NowPlayingMenuOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color,
    trailingBadge: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 54.dp)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(19.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = BentoTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = BentoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (trailingBadge != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = iconTint.copy(alpha = 0.18f)
            ) {
                Text(
                    text = trailingBadge,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = iconTint,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = BentoTextSecondary.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

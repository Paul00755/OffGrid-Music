package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Song
import com.example.service.PlaybackState
import com.example.ui.theme.*
import com.example.util.HapticUtils

@Composable
fun HomeScreenSpeedDial(
    speedDialSongs: List<Song>,
    playbackState: PlaybackState,
    onSongClick: (Song, List<Song>) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (speedDialSongs.isEmpty()) return

    val context = LocalContext.current
    val firstEight = speedDialSongs.take(8)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BentoCardSurface)
            .border(1.dp, BentoBorder.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BentoPrimary.copy(alpha = 0.10f))
                        .border(1.dp, BentoPrimary.copy(alpha = 0.20f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FlashOn,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column {
                    Text(
                        text = "Speed Dial",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "${speedDialSongs.size} Instant Picks • Quick Access",
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Shuffle / Refresh Button
                Surface(
                    shape = CircleShape,
                    color = BentoCardSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BentoBorder.copy(alpha = 0.30f)),
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        onRefresh()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Shuffle Speed Dial",
                            tint = BentoTextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                // Play All Button
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BentoPrimary.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.30f)),
                    modifier = Modifier.clickable {
                        HapticUtils.performClickHaptic(context)
                        onPlayAll(speedDialSongs)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play All",
                            tint = BentoPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Play All",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoPrimary
                        )
                    }
                }
            }
        }

        // 3x3 Grid: 8 Song Cards + 9th Dotted Play Mix Card
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (row in 0 until 3) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (col in 0 until 3) {
                        val index = row * 3 + col
                        Box(
                            modifier = Modifier.weight(1f)
                        ) {
                            if (index < 8 && index < firstEight.size) {
                                val song = firstEight[index]
                                val isPlayingThis = playbackState.currentSong?.id == song.id && playbackState.isPlaying
                                SpeedDialSongCard(
                                    song = song,
                                    isPlaying = isPlayingThis,
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onSongClick(song, speedDialSongs)
                                    }
                                )
                            } else if (index == 8) {
                                // 9th Element (Row 3, Col 3): Action Card for All Mix
                                SpeedDialMixCard(
                                    totalCount = speedDialSongs.size,
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        onPlayAll(speedDialSongs)
                                    }
                                )
                            } else {
                                Spacer(modifier = Modifier.fillMaxWidth().height(74.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedDialSongCard(
    song: Song,
    isPlaying: Boolean,
    onClick: UnitFunction
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isPlaying) BentoPrimary.copy(alpha = 0.08f) else BentoCardSurface.copy(alpha = 0.60f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isPlaying) BentoPrimary.copy(alpha = 0.5f) else BentoBorder.copy(alpha = 0.25f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                AlbumArtView(
                    song = song,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    cornerRadius = 8
                )
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Playing",
                            tint = BentoPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = song.displayTitle,
                    fontSize = 11.sp,
                    fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isPlaying) BentoPrimary else BentoTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = song.displayArtist,
                    fontSize = 9.5.sp,
                    color = BentoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private typealias UnitFunction = () -> Unit

@Composable
private fun SpeedDialMixCard(
    totalCount: Int,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = BentoCardSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.30f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            BentoPrimary.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(BentoPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = "Speed Dial Mix",
                        tint = BentoPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Play Mix ($totalCount)",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoPrimary,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

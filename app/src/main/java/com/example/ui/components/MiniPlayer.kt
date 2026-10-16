package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import com.example.data.model.Song
import com.example.ui.theme.*

@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    progressMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onClick: () -> Unit,
    showPrevious: Boolean = true,
    showNext: Boolean = true,
    showFavorite: Boolean = true,
    onSkipPrevious: (() -> Unit)? = null,
    onFavoriteToggle: (() -> Unit)? = null,
    style: String = "FLOATING",
    progressFractionProvider: (() -> Float)? = null,
    playbackPositionFlow: StateFlow<Long>? = null,
    modifier: Modifier = Modifier
) {
    if (song == null) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val livePositionState = playbackPositionFlow?.collectAsStateWithLifecycle()
    val progressFraction = if (durationMs > 0) (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val isBottomBar = style.equals("BOTTOM_BAR", ignoreCase = true)
    val cardShape = if (isBottomBar) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
    } else {
        RoundedCornerShape(24.dp)
    }
    val cardPadding = if (isBottomBar) {
        Modifier.padding(horizontal = 0.dp, vertical = 0.dp)
    } else {
        Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(cardPadding)
            .clip(cardShape)
            .background(BentoCardSurface.copy(alpha = 0.98f))
            .border(
                border = BorderStroke(1.dp, BentoBorder),
                shape = cardShape
            )
            .clickable { onClick() }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Album Art Thumbnail
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BentoCardPurple),
                    contentAlignment = Alignment.Center
                ) {
                    AlbumArtView(
                        song = song,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 10
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Song Info
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = song.displayTitle,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.displayArtist,
                        fontSize = 11.5.sp,
                        color = BentoTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                if (showFavorite && onFavoriteToggle != null) {
                    IconButton(
                        onClick = {
                            com.example.util.HapticUtils.performClickHaptic(context)
                            onFavoriteToggle()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (song.isFavorite) BentoAccentPinkText else BentoTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                if (showPrevious && onSkipPrevious != null) {
                    IconButton(
                        onClick = {
                            com.example.util.HapticUtils.performClickHaptic(context)
                            onSkipPrevious()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Play/Pause Button
                IconButton(
                    onClick = {
                        com.example.util.HapticUtils.performClickHaptic(context)
                        onTogglePlayPause()
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(BentoPrimary)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = BentoOnPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                if (showNext) {
                    Spacer(modifier = Modifier.width(4.dp))

                    // Next Button
                    IconButton(
                        onClick = {
                            com.example.util.HapticUtils.performClickHaptic(context)
                            onSkipNext()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Progress Bar Line at bottom of mini player
            LinearProgressIndicator(
                progress = {
                    val effectiveDuration = if (durationMs > 0L) durationMs else song.durationMs
                    if (livePositionState != null) {
                        val curPos = livePositionState.value
                        if (effectiveDuration > 0L) (curPos.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f) else 0f
                    } else if (progressFractionProvider != null) {
                        progressFractionProvider()
                    } else {
                        if (effectiveDuration > 0L) (progressMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f) else 0f
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = BentoPrimary,
                trackColor = BentoCardBorder
            )
        }
    }
}

package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.model.Song
import com.example.jam.JamAccessMode
import com.example.jam.JamDownloadState
import com.example.jam.JamFeature
import com.example.jam.JamFeatureRequest
import com.example.jam.JamParticipant
import com.example.jam.JamPermissions
import com.example.jam.JamQueueItem
import com.example.jam.JamRecommendation
import com.example.jam.JamRecommendationType
import com.example.jam.JamSharedLibraryMatch
import com.example.jam.JamVote
import com.example.ui.theme.BentoCardBorder
import com.example.ui.theme.BentoCardPurple
import com.example.ui.theme.BentoCardSurface
import com.example.ui.theme.BentoOnPrimary
import com.example.ui.theme.BentoPrimary
import com.example.ui.theme.BentoScreenBackground
import com.example.ui.theme.BentoTextPrimary
import com.example.ui.theme.BentoTextSecondary
import com.example.ui.theme.LocalGlassThemeConfig
import kotlinx.coroutines.delay

private val VoteAcceptGreen = Color(0xFF1DB954)

/**
 * 2-column Bento summary grid displaying the Host's independent permission & feature settings.
 * Adapts to the user's selected theme tokens (BentoPrimary, BentoCardSurface, BentoCardPurple, etc.).
 */
@Composable
internal fun JamPermissionsBentoGrid(
    permissions: JamPermissions,
    isHost: Boolean,
    onToggleRecommendations: ((Boolean) -> Unit)? = null,
    onToggleLocalSharing: ((Boolean) -> Unit)? = null,
    onOpenPermissions: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ROOM FEATURES & ACCESS",
                style = MaterialTheme.typography.labelMedium,
                color = BentoTextSecondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.weight(1f)
            )
            if (isHost && onOpenPermissions != null) {
                TextButton(
                    onClick = onOpenPermissions,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("All Permissions", color = BentoPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BentoFeatureTile(
                title = "Song Recommendations",
                subtitle = if (permissions.allowSharedLibraryRecommendations) "Members can suggest & vote" else "Disabled by host",
                enabled = permissions.allowSharedLibraryRecommendations,
                icon = Icons.Default.HowToVote,
                isInteractive = isHost && onToggleRecommendations != null,
                onToggle = { onToggleRecommendations?.invoke(!permissions.allowSharedLibraryRecommendations) },
                modifier = Modifier.weight(1f).testTag("bento_tile_recommendations")
            )
            BentoFeatureTile(
                title = "Local Song Sharing",
                subtitle = if (permissions.allowLocalSongSharing) "Direct MP3/audio transfer" else "Disabled by host",
                enabled = permissions.allowLocalSongSharing,
                icon = Icons.Default.UploadFile,
                isInteractive = isHost && onToggleLocalSharing != null,
                onToggle = { onToggleLocalSharing?.invoke(!permissions.allowLocalSongSharing) },
                modifier = Modifier.weight(1f).testTag("bento_tile_local_sharing")
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val addModeLabel = when (permissions.addToQueue.mode) {
                JamAccessMode.HOST_ONLY -> "Host Only"
                JamAccessMode.EVERYONE -> "Everyone"
                JamAccessMode.SELECTED -> "Selected (${permissions.addToQueue.selectedUids.size})"
            }
            val manageModeLabel = when (permissions.manageQueue.mode) {
                JamAccessMode.HOST_ONLY -> "Host Only"
                JamAccessMode.EVERYONE -> "Everyone"
                JamAccessMode.SELECTED -> "Selected (${permissions.manageQueue.selectedUids.size})"
            }
            BentoAccessTile(
                title = "Add Tracks",
                modeBadge = addModeLabel,
                subtitle = if (permissions.addToQueue.mode == JamAccessMode.EVERYONE) "Anyone can queue songs" else "Direct queue access",
                onClick = if (isHost) onOpenPermissions else null,
                modifier = Modifier.weight(1f)
            )
            BentoAccessTile(
                title = "Queue Control",
                modeBadge = manageModeLabel,
                subtitle = "Reorder & remove tracks",
                onClick = if (isHost) onOpenPermissions else null,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BentoFeatureTile(
    title: String,
    subtitle: String,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isInteractive: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalGlassThemeConfig.current
    Card(
        modifier = modifier.then(
            if (isInteractive) Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onToggle) else Modifier
        ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) BentoCardPurple.copy(alpha = 0.65f) else BentoCardSurface
        ),
        border = BorderStroke(1.dp, if (enabled) BentoPrimary.copy(alpha = 0.55f) else BentoCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = if (glass.glassEnabled) 0.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (enabled) BentoPrimary.copy(alpha = 0.2f) else BentoCardBorder.copy(alpha = 0.35f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (enabled) BentoPrimary else BentoTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (enabled) VoteAcceptGreen.copy(alpha = 0.2f) else BentoCardBorder.copy(alpha = 0.4f)
                ) {
                    Text(
                        text = if (enabled) "ON" else "OFF",
                        color = if (enabled) VoteAcceptGreen else BentoTextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                text = title,
                color = BentoTextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = BentoTextSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BentoAccessTile(
    title: String,
    modeBadge: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val glass = LocalGlassThemeConfig.current
    Card(
        modifier = modifier.then(
            if (onClick != null) Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick) else Modifier
        ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = BentoCardSurface),
        border = BorderStroke(1.dp, BentoCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = if (glass.glassEnabled) 0.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    color = BentoTextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = BentoPrimary.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = modeBadge,
                        color = BentoPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                text = subtitle,
                color = BentoTextSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Active Feature Request banner shown to Host and Participants when someone requests
 * enabling Song Recommendations or Local Song Sharing.
 */
@Composable
internal fun JamFeatureRequestBanner(
    request: JamFeatureRequest,
    remainingMs: Long,
    isHost: Boolean,
    onRespond: () -> Unit,
    onIgnore: () -> Unit
) {
    val glass = LocalGlassThemeConfig.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BentoCardPurple.copy(alpha = 0.75f)),
        border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (glass.glassEnabled) 0.dp else 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BentoPrimary.copy(alpha = 0.2f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Campaign, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(22.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${request.requesterName} requested to enable ${request.feature.title}",
                        color = BentoTextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(14.dp))
                        Text(
                            text = "Expires in ${formatCountdownMmSs(remainingMs)}",
                            color = BentoPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (isHost) {
                    OutlinedButton(
                        onClick = onRespond,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BentoPrimary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Respond", color = BentoPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (isHost) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onIgnore) {
                        Text("Ignore", color = BentoTextSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * Participant card shown when Song Recommendations or Local Song Sharing is OFF.
 * Matches the exact copy required by the specification and Reference Image 4 Panels 2, 3, 4.
 */
@Composable
internal fun JamFeatureDisabledPromptCard(
    feature: JamFeature,
    request: JamFeatureRequest?,
    requestBlocked: Boolean,
    onRequest: () -> Unit
) {
    val glass = LocalGlassThemeConfig.current
    val promptText = when (feature) {
        JamFeature.SHARED_RECOMMENDATIONS ->
            "Want to recommend a song? Ask the host to enable song recommendations for this Jam."
        JamFeature.LOCAL_SONG_SHARING ->
            "Want to share a local song? Ask the host to enable local song sharing for this Jam."
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BentoCardSurface),
        border = BorderStroke(1.dp, BentoCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = if (glass.glassEnabled) 0.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BentoCardPurple,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (feature == JamFeature.SHARED_RECOMMENDATIONS) Icons.Default.MusicNote else Icons.Default.UploadFile,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (feature == JamFeature.SHARED_RECOMMENDATIONS) "Song Recommendations" else "Local Song Sharing",
                    color = BentoTextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = promptText,
                    color = BentoTextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                if (requestBlocked) {
                    Text(
                        text = "The host blocked requests for this feature in this Jam.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                } else if (request != null) {
                    Text(
                        text = "Requested by ${request.requesterName} · ${formatCountdownMmSs(request.remainingMs())} left",
                        color = BentoPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (!requestBlocked && request == null) {
                OutlinedButton(
                    onClick = onRequest,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BentoPrimary),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("request_feature_${feature.documentId}")
                ) {
                    Text("Request", color = BentoPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * Shared Queue preview strip shown on Recommendations and Local Sharing tabs (Reference Image 4 Panels 2, 3, 4, 5)
 * so songs already in the shared queue remain visible and accessible regardless of which setting is off.
 */
@Composable
internal fun SharedQueuePreviewStrip(
    queue: List<JamQueueItem>,
    currentQueueItemId: String?,
    songs: List<Song>,
    sharedLibraryMatches: List<JamSharedLibraryMatch>,
    onViewAllQueue: () -> Unit,
    onPlayQueueItem: (String) -> Unit
) {
    val glass = LocalGlassThemeConfig.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BentoCardSurface),
        border = BorderStroke(1.dp, BentoCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = if (glass.glassEnabled) 0.dp else 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.QueueMusic, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Shared Queue (${queue.size})",
                    color = BentoTextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onViewAllQueue,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("View All", color = BentoPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(14.dp))
                }
            }
            if (queue.isEmpty()) {
                Text(
                    text = "No tracks in the shared queue yet.",
                    color = BentoTextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    queue.take(10).forEach { item ->
                        val isCurrent = item.id == currentQueueItemId
                        val artworkUri = resolveTrackArtwork(item.track.sharedTrackId, item.track.title, item.track.artist, sharedLibraryMatches, songs)
                        Column(
                            modifier = Modifier
                                .width(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPlayQueueItem(item.id) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BentoCardPurple)
                                    .then(
                                        if (isCurrent) Modifier.border(2.dp, BentoPrimary, RoundedCornerShape(12.dp)) else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!artworkUri.isNullOrBlank()) {
                                    AsyncImage(
                                        model = artworkUri,
                                        contentDescription = item.track.title,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                                    )
                                } else {
                                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(24.dp))
                                }
                            }
                            Text(
                                text = item.track.title,
                                color = if (isCurrent) BentoPrimary else BentoTextPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Rich Recommendation & Local Song Share Voting Panel (Reference Images 4, 5, 6).
 * Placed ABOVE the matching songs library so active votes are immediately visible without scrolling.
 */
@Composable
internal fun RichRecommendationPanel(
    title: String,
    recommendations: List<JamRecommendation>,
    emptyMessage: String,
    participants: List<JamParticipant>,
    currentUid: String,
    hostUid: String,
    selectedRecommendationId: String?,
    songs: List<Song>,
    sharedLibraryMatches: List<JamSharedLibraryMatch>,
    onVote: (String, JamVote) -> Unit,
    onRetryUpload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onViewQueue: (() -> Unit)? = null
) {
    val glass = LocalGlassThemeConfig.current
    val activeVotesCount = recommendations.count { it.isVoting }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = BentoCardSurface),
            border = BorderStroke(1.dp, BentoCardBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = if (glass.glassEnabled) 0.dp else 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.HowToVote,
                        contentDescription = null,
                        tint = BentoPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = title,
                        color = BentoTextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (activeVotesCount > 0) BentoPrimary.copy(alpha = 0.2f) else BentoCardPurple
                    ) {
                        Text(
                            text = if (activeVotesCount > 0) "$activeVotesCount active vote${if (activeVotesCount == 1) "" else "s"}" else "${recommendations.size} total",
                            color = if (activeVotesCount > 0) BentoPrimary else BentoTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                if (recommendations.isEmpty()) {
                    Text(
                        text = emptyMessage,
                        color = BentoTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        recommendations.take(12).forEach { recommendation ->
            RichRecommendationCard(
                recommendation = recommendation,
                participants = participants,
                currentUid = currentUid,
                hostUid = hostUid,
                selected = selectedRecommendationId == recommendation.id,
                artworkUri = resolveTrackArtwork(
                    recommendation.track.sharedTrackId,
                    recommendation.track.title,
                    recommendation.track.artist,
                    sharedLibraryMatches,
                    songs
                ),
                onVote = { onVote(recommendation.id, it) },
                onRetryUpload = { onRetryUpload(recommendation.id) },
                onRetryDownload = { onRetryDownload(recommendation.id) },
                onViewQueue = onViewQueue
            )
        }
    }
}

@Composable
internal fun RichRecommendationCard(
    recommendation: JamRecommendation,
    participants: List<JamParticipant>,
    currentUid: String,
    hostUid: String,
    selected: Boolean,
    artworkUri: String?,
    onVote: (JamVote) -> Unit,
    onRetryUpload: () -> Unit,
    onRetryDownload: () -> Unit,
    onViewQueue: (() -> Unit)? = null
) {
    val glass = LocalGlassThemeConfig.current
    var nowMs by remember(recommendation.id, recommendation.status) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recommendation.id, recommendation.status) {
        while (recommendation.status == "VOTING") {
            nowMs = System.currentTimeMillis()
            delay(500L)
        }
    }

    val isLocalShare = recommendation.type == JamRecommendationType.LOCAL_SONG
    val proposerName = participants.firstOrNull { it.uid == recommendation.proposerUid }?.displayName
        ?.takeIf(String::isNotBlank) ?: recommendation.proposerName
    val isProposer = currentUid == recommendation.proposerUid
    val remainingSec = (((recommendation.deadlineAtMs - nowMs).coerceAtLeast(0L) + 999L) / 1000L).toInt()
    val canVote = recommendation.isVoting &&
        !isProposer &&
        recommendation.votes[currentUid] == JamVote.PENDING &&
        nowMs < recommendation.deadlineAtMs

    val statusBadgeText = when (recommendation.status) {
        "VOTING" -> "%02d:%02d left".format(remainingSec / 60, remainingSec % 60)
        "APPROVED_PENDING_UPLOAD" -> "Approved · Preparing"
        "UPLOADING" -> "Sending ${recommendation.uploadProgress}%"
        "DOWNLOADING" -> "Receiving Audio"
        "READY" -> "Ready to Add · Queued"
        "REJECTED" -> "Rejected"
        "EXPIRED" -> "Expired"
        "CANCELLED_PERMISSION_DISABLED" -> "Canceled"
        "FAILED" -> "Transfer Failed"
        else -> recommendation.status
    }

    val statusBadgeColor = when (recommendation.status) {
        "READY", "APPROVED_PENDING_UPLOAD" -> VoteAcceptGreen
        "REJECTED", "EXPIRED", "FAILED", "CANCELLED_PERMISSION_DISABLED" -> MaterialTheme.colorScheme.error
        else -> BentoPrimary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(2.dp, BentoPrimary, RoundedCornerShape(22.dp)) else Modifier),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = BentoCardSurface),
        border = BorderStroke(1.dp, if (recommendation.isVoting) BentoPrimary.copy(alpha = 0.5f) else BentoCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = if (glass.glassEnabled) 0.dp else 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header row: Category icon + "Recommended by / Shared by" + Status/Countdown pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isLocalShare) VoteAcceptGreen.copy(alpha = 0.18f) else BentoPrimary.copy(alpha = 0.18f),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isLocalShare) Icons.Default.UploadFile else Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = if (isLocalShare) VoteAcceptGreen else BentoPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isLocalShare) "Local Song Share" else "Song Recommendation",
                        color = BentoTextPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isLocalShare) {
                            "Shared by ${if (isProposer) "$proposerName (You)" else proposerName}"
                        } else {
                            "Recommended by ${if (isProposer) "$proposerName (You)" else proposerName}"
                        },
                        color = BentoTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = statusBadgeColor.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, statusBadgeColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = statusBadgeText,
                        color = statusBadgeColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            // Song info card with artwork, title, artist, duration
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BentoCardPurple.copy(alpha = 0.55f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!artworkUri.isNullOrBlank()) {
                    AsyncImage(
                        model = artworkUri,
                        contentDescription = null,
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(BentoCardSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(26.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recommendation.track.title,
                        color = BentoTextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val durationFormatted = formatDurationMmSs(recommendation.track.durationMs)
                    val sizePart = if (isLocalShare && recommendation.fileSize > 0L) {
                        " · %.1f MB".format(recommendation.fileSize / (1024.0 * 1024.0))
                    } else ""
                    Text(
                        text = "${recommendation.track.artist} · $durationFormatted$sizePart",
                        color = BentoTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Vote tally bar: "1 accepted · 0 rejected · 1 pending · Need N+"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${recommendation.approvalCount} accepted · ${recommendation.rejectionCount} rejected · ${recommendation.pendingCount} pending",
                    color = BentoTextPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isLocalShare) "Unanimous (${recommendation.requiredApprovalCount}/${recommendation.eligibleUids.size})"
                    else "Need ${recommendation.requiredApprovalCount}+",
                    color = BentoPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            // Prominent Accept / Reject buttons for eligible non-proposer members
            if (canVote) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onVote(JamVote.ACCEPT) },
                        modifier = Modifier.weight(1f).height(46.dp).testTag("vote_accept_${recommendation.id}"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VoteAcceptGreen,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Accept", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { onVote(JamVote.REJECT) },
                        modifier = Modifier.weight(1f).height(46.dp).testTag("vote_reject_${recommendation.id}"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reject", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Proposer auto-approval info banner (never show Accept/Reject to proposer for their own submission)
            if (isProposer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BentoPrimary.copy(alpha = 0.14f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(18.dp))
                    Text(
                        text = if (isLocalShare) {
                            "You are counted as approving this shared song."
                        } else {
                            "You are counted as approving this recommendation."
                        },
                        color = BentoTextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Per-participant vote status list
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                recommendation.eligibleUids.forEach { uid ->
                    val participant = participants.firstOrNull { it.uid == uid }
                    val name = participant?.displayName ?: if (uid == currentUid) "You" else "Listener"
                    val isMemberHost = uid == hostUid || participant?.isHost == true
                    val isMemberProposer = uid == recommendation.proposerUid
                    val vote = if (isMemberProposer) JamVote.ACCEPT else recommendation.votes[uid] ?: JamVote.PENDING
                    val badgeLabel = when {
                        isMemberProposer -> "Auto-✓"
                        vote == JamVote.ACCEPT -> "✓ Accepted"
                        vote == JamVote.REJECT -> "✕ Rejected"
                        else -> "Pending"
                    }
                    val badgeColor = when (vote) {
                        JamVote.ACCEPT -> VoteAcceptGreen
                        JamVote.REJECT -> MaterialTheme.colorScheme.error
                        JamVote.PENDING -> BentoTextSecondary
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!participant?.photoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = participant?.photoUrl,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp).clip(CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(26.dp).clip(CircleShape).background(BentoCardPurple),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = name.take(1).uppercase(),
                                    color = BentoPrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            text = buildString {
                                append(name)
                                if (uid == currentUid) append(" (You)")
                                if (isMemberHost) append(" · Host")
                            },
                            color = BentoTextPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = badgeColor.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = badgeLabel,
                                color = badgeColor,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            // Added to Queue banner when status == READY
            if (recommendation.status == "READY") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(VoteAcceptGreen.copy(alpha = 0.16f))
                        .border(1.dp, VoteAcceptGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = VoteAcceptGreen, modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Added to shared queue",
                            color = BentoTextPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Ready for synchronized playback across all devices.",
                            color = BentoTextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (onViewQueue != null) {
                        TextButton(onClick = onViewQueue) {
                            Text("View Queue", color = BentoPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Peer transfer progress for LOCAL_SONG
            if (isLocalShare && recommendation.status in setOf("APPROVED_PENDING_UPLOAD", "UPLOADING", "DOWNLOADING", "READY", "FAILED")) {
                HorizontalDivider(color = BentoCardBorder)
                Text(
                    text = "Device-to-Device Transfer Status",
                    color = BentoTextPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                if (recommendation.uploadStatus == "UPLOADING") {
                    Text(
                        text = "Sending audio · ${recommendation.uploadProgress}%",
                        color = BentoTextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    LinearProgressIndicator(
                        progress = { (recommendation.uploadProgress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = BentoPrimary
                    )
                }
                recommendation.eligibleUids.forEach { uid ->
                    val name = participants.firstOrNull { it.uid == uid }?.displayName ?: if (uid == currentUid) "You" else "Listener"
                    val status = recommendation.downloads[uid] ?: JamDownloadState()
                    val statusLabel = when {
                        recommendation.status == "UPLOADING" && uid == recommendation.proposerUid -> "Sending · ${recommendation.uploadProgress}%"
                        recommendation.status in setOf("APPROVED_PENDING_UPLOAD", "UPLOADING") -> "Waiting for sender"
                        else -> when (status.status) {
                            "WAITING_FOR_UPLOAD" -> "Waiting for sender"
                            "UPLOADING" -> "Sending directly"
                            "WAITING_TO_DOWNLOAD" -> "Waiting to receive"
                            "DOWNLOADING" -> "Receiving · ${status.progress}%"
                            "VERIFYING_FILE" -> "Verifying audio"
                            "READY_FOR_PLAYBACK" -> "✓ Ready"
                            "DOWNLOAD_FAILED" -> "Transfer failed · Retry required"
                            else -> status.status.lowercase().replace('_', ' ')
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (uid == currentUid) "$name (You)" else name,
                            color = BentoTextPrimary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = statusLabel,
                            color = if (status.status.endsWith("FAILED")) MaterialTheme.colorScheme.error
                            else if (status.status == "READY_FOR_PLAYBACK") VoteAcceptGreen
                            else BentoTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (status.status == "DOWNLOADING") {
                        LinearProgressIndicator(
                            progress = { (status.progress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = BentoPrimary
                        )
                    }
                    if (uid == currentUid && status.status == "DOWNLOAD_FAILED") {
                        TextButton(onClick = onRetryDownload) {
                            Text("Retry download", color = BentoPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (currentUid == recommendation.proposerUid && recommendation.uploadStatus == "UPLOAD_FAILED") {
                    TextButton(onClick = onRetryUpload) {
                        Text("Retry upload", color = BentoPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Matched Song Row with permission-aware primary button ("Add" when user can add tracks directly,
 * or "Recommend" when recommendations are on) plus a per-song Action Menu ("⋮") with
 * Play Now, Play Next, Add to Shared Queue, and Recommend for Vote.
 */
@Composable
internal fun EnhancedSharedLibraryMatchRow(
    match: JamSharedLibraryMatch,
    canAddTracks: Boolean,
    canPlayPause: Boolean,
    canRecommend: Boolean,
    isHost: Boolean,
    onPlayNow: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onRecommend: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BentoCardPurple.copy(alpha = 0.45f))
            .clickable {
                when {
                    canAddTracks -> onAddToQueue()
                    canRecommend -> onRecommend()
                    isHost -> onOpenPermissions()
                }
            }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!match.song.artworkUri.isNullOrBlank()) {
            AsyncImage(
                model = match.song.artworkUri,
                contentDescription = null,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(BentoCardSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = BentoPrimary)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = match.song.displayTitle,
                color = BentoTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${match.song.displayArtist} · ${formatDurationMmSs(match.song.durationMs)}",
                color = BentoTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Primary quick action button matching user's access
        when {
            canAddTracks -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = BentoPrimary.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onAddToQueue)
                        .testTag("match_add_${match.matchKey.take(12)}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(15.dp))
                        Text("Add", color = BentoPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            canRecommend -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = BentoPrimary.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onRecommend)
                        .testTag("match_recommend_${match.matchKey.take(12)}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.HowToVote, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(15.dp))
                        Text("Recommend", color = BentoPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            isHost -> {
                TextButton(onClick = onOpenPermissions) {
                    Text("Enable", color = BentoPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Per-song Action Menu ("⋮") with options matching the user's selected access
        if (canAddTracks || canRecommend) {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(34.dp).testTag("match_menu_${match.matchKey.take(12)}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Song options for ${match.song.displayTitle}",
                        tint = BentoTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (canAddTracks && canPlayPause) {
                        DropdownMenuItem(
                            text = { Text("Play Now") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BentoPrimary) },
                            onClick = {
                                menuExpanded = false
                                onPlayNow()
                            }
                        )
                    }
                    if (canAddTracks) {
                        DropdownMenuItem(
                            text = { Text("Play Next") },
                            leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = BentoPrimary) },
                            onClick = {
                                menuExpanded = false
                                onPlayNext()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Shared Queue") },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = BentoPrimary) },
                            onClick = {
                                menuExpanded = false
                                onAddToQueue()
                            }
                        )
                    }
                    if (canRecommend) {
                        DropdownMenuItem(
                            text = { Text("Recommend for Vote (30s)") },
                            leadingIcon = { Icon(Icons.Default.HowToVote, contentDescription = null, tint = BentoPrimary) },
                            onClick = {
                                menuExpanded = false
                                onRecommend()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-screen dialog for "View All Matching Songs" with search, bulk Include All / Shuffle & Include All,
 * and per-song action menus.
 */
@Composable
internal fun AllMatchingSongsDialog(
    matches: List<JamSharedLibraryMatch>,
    canAddTracks: Boolean,
    canPlayPause: Boolean,
    canRecommend: Boolean,
    isHost: Boolean,
    onAddAll: (Boolean) -> Unit,
    onPlayNow: (JamSharedLibraryMatch) -> Unit,
    onPlayNext: (JamSharedLibraryMatch) -> Unit,
    onAddToQueue: (JamSharedLibraryMatch) -> Unit,
    onRecommend: (JamSharedLibraryMatch) -> Unit,
    onOpenPermissions: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filtered = remember(matches, searchQuery) {
        val q = searchQuery.trim()
        if (q.isBlank()) matches
        else matches.filter {
            it.song.displayTitle.contains(q, ignoreCase = true) ||
                it.song.displayArtist.contains(q, ignoreCase = true) ||
                it.song.displayAlbum.contains(q, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(24.dp),
            color = BentoScreenBackground,
            border = BorderStroke(1.dp, BentoCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "All Matching Songs (${matches.size})",
                            color = BentoTextPrimary,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Available on every connected device in this Jam",
                            color = BentoTextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = BentoTextPrimary)
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search ${matches.size} matching songs") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = BentoPrimary) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", tint = BentoTextSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (canAddTracks && matches.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onAddAll(false)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BentoPrimary, contentColor = BentoOnPrimary)
                        ) {
                            Icon(Icons.Default.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Include All (${matches.size})", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = {
                                onAddAll(true)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, BentoPrimary)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null, tint = BentoPrimary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Shuffle All", color = BentoPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(filtered, key = { _, m -> m.matchKey }) { _, match ->
                        EnhancedSharedLibraryMatchRow(
                            match = match,
                            canAddTracks = canAddTracks,
                            canPlayPause = canPlayPause,
                            canRecommend = canRecommend,
                            isHost = isHost,
                            onPlayNow = { onPlayNow(match) },
                            onPlayNext = { onPlayNext(match) },
                            onAddToQueue = { onAddToQueue(match) },
                            onRecommend = { onRecommend(match) },
                            onOpenPermissions = onOpenPermissions
                        )
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = "No matching songs fit \"$searchQuery\".",
                                color = BentoTextSecondary,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Redesigned Queue Row (Reference Image 5 Panel 6: Shared Queue & Playback) that displays
 * track index / live playing indicator, album art thumbnail, full readable title & artist,
 * duration, play-on-tap, and a compact "⋮" menu so titles are never crushed.
 */
@Composable
internal fun RedesignedQueueRow(
    index: Int,
    item: JamQueueItem,
    artworkUri: String?,
    isCurrent: Boolean,
    canPlay: Boolean,
    canManage: Boolean,
    canRecommend: Boolean,
    onPlayNow: () -> Unit,
    onRecommend: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    isFirst: Boolean,
    isLast: Boolean
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isCurrent) BentoPrimary.copy(alpha = 0.16f) else BentoCardPurple.copy(alpha = 0.4f))
            .then(
                if (isCurrent) Modifier.border(1.dp, BentoPrimary.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                else Modifier
            )
            .clickable(enabled = canPlay) { onPlayNow() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Index or Now Playing equalizer icon
        Box(
            modifier = Modifier.width(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = "Now playing",
                    tint = BentoPrimary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = "${index + 1}",
                    color = BentoTextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Artwork thumbnail
        if (!artworkUri.isNullOrBlank()) {
            AsyncImage(
                model = artworkUri,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(BentoCardSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = if (isCurrent) BentoPrimary else BentoTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Full readable Song Title & Artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.track.title,
                color = if (isCurrent) BentoPrimary else BentoTextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${item.track.artist} · ${formatDurationMmSs(item.track.durationMs)}",
                color = BentoTextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Compact More Options menu ("⋮") so title/artist always have generous horizontal room
        if (canPlay || canManage || canRecommend) {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(34.dp).testTag("queue_item_menu_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Queue options for ${item.track.title}",
                        tint = BentoTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (canPlay) {
                        DropdownMenuItem(
                            text = { Text("Play Now") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BentoPrimary) },
                            onClick = {
                                menuExpanded = false
                                onPlayNow()
                            }
                        )
                    }
                    if (canManage && !isFirst) {
                        DropdownMenuItem(
                            text = { Text("Move Up") },
                            leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = BentoTextPrimary) },
                            onClick = {
                                menuExpanded = false
                                onMoveUp()
                            }
                        )
                    }
                    if (canManage && !isLast) {
                        DropdownMenuItem(
                            text = { Text("Move Down") },
                            leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = BentoTextPrimary) },
                            onClick = {
                                menuExpanded = false
                                onMoveDown()
                            }
                        )
                    }
                    if (canRecommend) {
                        DropdownMenuItem(
                            text = { Text("Recommend for Vote") },
                            leadingIcon = { Icon(Icons.Default.HowToVote, contentDescription = null, tint = BentoPrimary) },
                            onClick = {
                                menuExpanded = false
                                onRecommend()
                            }
                        )
                    }
                    if (canManage) {
                        DropdownMenuItem(
                            text = { Text("Remove from Queue", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onRemove()
                            }
                        )
                    }
                }
            }
        }
    }
}

internal fun resolveTrackArtwork(
    sharedTrackId: String,
    title: String,
    artist: String,
    sharedLibraryMatches: List<JamSharedLibraryMatch>,
    songs: List<Song>
): String? {
    return sharedLibraryMatches.firstOrNull { it.matchKey == sharedTrackId }?.song?.artworkUri
        ?: songs.firstOrNull {
            it.displayTitle.equals(title, ignoreCase = true) &&
                it.displayArtist.equals(artist, ignoreCase = true)
        }?.artworkUri
}

internal fun formatDurationMmSs(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

internal fun formatCountdownMmSs(remainingMs: Long): String {
    val totalSeconds = ((remainingMs.coerceAtLeast(0L) + 999L) / 1_000L).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

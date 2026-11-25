package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.DEFAULT_BOTTOM_NAV_TABS
import com.example.ui.components.NavTab
import com.example.ui.theme.*
import com.example.util.HapticUtils

@Composable
internal fun BottomNavOrderManagerCard(
    currentOrder: List<NavTab>,
    onUpdateOrder: (List<NavTab>) -> Unit,
    onResetOrder: () -> Unit,
    isSignedIn: Boolean = true,
    onOpenAuth: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showJamLockedDialog by remember { mutableStateOf(false) }

    val normalizedTabs = remember(currentOrder) {
        val valid = currentOrder.filter { it in DEFAULT_BOTTOM_NAV_TABS }.distinct().toMutableList()
        for (req in DEFAULT_BOTTOM_NAV_TABS) {
            if (req !in valid) valid.add(req)
        }
        valid
    }

    val reorderableTabs = remember(normalizedTabs, isSignedIn) {
        if (isSignedIn) normalizedTabs else normalizedTabs.filter { it != NavTab.JAM }
    }

    val expectedDefaultReorderable = remember(isSignedIn) {
        if (isSignedIn) DEFAULT_BOTTOM_NAV_TABS else DEFAULT_BOTTOM_NAV_TABS.filter { it != NavTab.JAM }
    }
    val isDefaultOrder = reorderableTabs == expectedDefaultReorderable

    fun persistReorderedTabs(updatedUnlocked: List<NavTab>) {
        if (isSignedIn) {
            onUpdateOrder(updatedUnlocked)
        } else {
            val savedJamIndex = normalizedTabs.indexOf(NavTab.JAM).takeIf { it >= 0 } ?: updatedUnlocked.size
            val merged = updatedUnlocked.toMutableList()
            merged.add(savedJamIndex.coerceIn(0, merged.size), NavTab.JAM)
            onUpdateOrder(merged)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("bottom_nav_order_manager_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Sub-group label matching SettingsScreen
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(BentoPrimary)
            )
            Text(
                text = "BOTTOM NAVIGATION BAR ORDER",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
                color = BentoTextSecondary
            )
        }

        Surface(
            shape = RoundedCornerShape(22.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                BentoPrimary.copy(alpha = 0.06f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header + Reset Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Reorder Bottom Bar",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = if (isSignedIn) {
                                "Arrange navigation icons in your preferred order. All 5 tabs stay pinned."
                            } else {
                                "Arrange navigation icons in your preferred order. Sign in to unlock Jam in the bottom bar."
                            },
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary,
                            lineHeight = 16.sp
                        )
                    }

                    if (!isDefaultOrder) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onResetOrder()
                            },
                            shape = CircleShape,
                            color = BentoPrimary.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 40.dp)
                                .testTag("reset_bottom_nav_order_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RestartAlt,
                                    contentDescription = "Reset Bottom Bar Order",
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Reset",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoPrimary
                                )
                            }
                        }
                    }
                }

                // Live Miniature Preview of the Floating Pill Bottom Bar (excludes locked Jam when signed out)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(CircleShape)
                        .background(BentoBackground.copy(alpha = 0.72f))
                        .border(BorderStroke(1.dp, BentoBorder.copy(alpha = 0.65f)), CircleShape)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        reorderableTabs.forEachIndexed { idx, tab ->
                            val highlightFirst = idx == 0
                            Box(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(CircleShape)
                                    .background(if (highlightFirst) BentoPrimary else Color.Transparent)
                                    .padding(horizontal = if (highlightFirst) 12.dp else 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.title,
                                        tint = if (highlightFirst) BentoOnPrimary else BentoTextSecondary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    if (highlightFirst) {
                                        Text(
                                            text = tab.title,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoOnPrimary,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Reorderable Tab Rows (no delete/hide button — tabs cannot be removed)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    reorderableTabs.forEachIndexed { index, tab ->
                        val description = when (tab) {
                            NavTab.HOME -> "Personalized home feed & recommendations"
                            NavTab.LIBRARY -> "Songs, albums, artists & folders"
                            NavTab.PLAYLISTS -> "Smart mixes & custom playlists"
                            NavTab.SEARCH -> "Instant search across your library"
                            NavTab.JAM -> "Off-Grid Jam room & group listening"
                            else -> tab.title
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = BentoCardPurple.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bottom_nav_order_row_${tab.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(BentoPrimary.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoPrimary
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(BentoCardSurface),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = null,
                                            tint = BentoPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = tab.title,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = description,
                                            fontSize = 11.sp,
                                            color = BentoTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                HapticUtils.performClickHaptic(context)
                                                val updated = reorderableTabs.toMutableList()
                                                val moved = updated.removeAt(index)
                                                updated.add(index - 1, moved)
                                                persistReorderedTabs(updated)
                                            }
                                        },
                                        enabled = index > 0,
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                            .testTag("bottom_nav_move_up_${tab.name.lowercase()}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowUp,
                                            contentDescription = "Move ${tab.title} left",
                                            tint = if (index > 0) BentoTextPrimary else BentoTextSubtle.copy(alpha = 0.35f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            if (index < reorderableTabs.lastIndex) {
                                                HapticUtils.performClickHaptic(context)
                                                val updated = reorderableTabs.toMutableList()
                                                val moved = updated.removeAt(index)
                                                updated.add(index + 1, moved)
                                                persistReorderedTabs(updated)
                                            }
                                        },
                                        enabled = index < reorderableTabs.lastIndex,
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                            .testTag("bottom_nav_move_down_${tab.name.lowercase()}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Move ${tab.title} right",
                                            tint = if (index < reorderableTabs.lastIndex) BentoTextPrimary else BentoTextSubtle.copy(alpha = 0.35f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Locked Jam row when user is not signed in
                    if (!isSignedIn) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = BentoBackground.copy(alpha = 0.62f),
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bottom_nav_jam_locked_card")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(BentoTextSecondary.copy(alpha = 0.16f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = "Jam tab locked",
                                                tint = BentoTextSecondary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(BentoCardSurface.copy(alpha = 0.65f))
                                                .alpha(0.6f),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = NavTab.JAM.icon,
                                                contentDescription = null,
                                                tint = BentoTextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = NavTab.JAM.title,
                                                    fontSize = 13.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = BentoTextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Surface(
                                                    shape = CircleShape,
                                                    color = BentoTextSecondary.copy(alpha = 0.14f),
                                                    border = BorderStroke(
                                                        1.dp,
                                                        BentoBorder.copy(alpha = 0.55f)
                                                    )
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(
                                                            horizontal = 7.dp,
                                                            vertical = 2.dp
                                                        ),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Lock,
                                                            contentDescription = null,
                                                            tint = BentoTextSecondary,
                                                            modifier = Modifier.size(10.dp)
                                                        )
                                                        Text(
                                                            text = "Locked",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = BentoTextSecondary
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = "Hidden from bottom bar until you sign in",
                                                fontSize = 11.sp,
                                                color = BentoTextSubtle,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    onClick = {
                                        HapticUtils.performClickHaptic(context)
                                        showJamLockedDialog = true
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = BentoPrimary.copy(alpha = 0.10f),
                                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.28f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("bottom_nav_jam_why_locked_button")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                            contentDescription = "Why is Jam locked",
                                            tint = BentoPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Why is it locked?",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BentoPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showJamLockedDialog) {
        AlertDialog(
            onDismissRequest = { showJamLockedDialog = false },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.testTag("jam_locked_reason_dialog"),
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(BentoPrimary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = "Off-Grid Jam",
                        tint = BentoPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Why is Jam locked?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Off-Grid Jam only works when you sign in via Google or Email.",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextPrimary,
                        lineHeight = 20.sp
                    )
                    Text(
                        text = "It lets you connect with your friends and family over the internet and listen to music together in real-time sync.",
                        fontSize = 13.5.sp,
                        color = BentoTextSecondary,
                        lineHeight = 20.sp
                    )
                    Text(
                        text = "Until you sign in, the Jam icon stays hidden from your bottom navigation bar. Sign in anytime to unlock and reorder it.",
                        fontSize = 12.5.sp,
                        color = BentoTextSubtle,
                        lineHeight = 18.sp
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showJamLockedDialog = false },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("jam_locked_dialog_dismiss_button")
                ) {
                    Text(
                        text = "Got it",
                        color = BentoTextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        HapticUtils.performClickHaptic(context)
                        showJamLockedDialog = false
                        onOpenAuth()
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BentoPrimary,
                        contentColor = BentoOnPrimary
                    ),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("jam_locked_dialog_signin_button")
                ) {
                    Text(
                        text = "Sign In",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }
}

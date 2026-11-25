package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import com.example.update.AppUpdateRepository
import com.example.update.ReleaseInfo

/**
 * Clean, focused in-app update dialog presenting only the version title and a structured,
 * human-readable Changelog (highlights, improvements, fixes) without GitHub tag/release clutter.
 */
@Composable
fun AppUpdateDialog(
    release: ReleaseInfo,
    onDismiss: () -> Unit,
    onUpdateNow: (String) -> Unit = {},
    onOpenRepoUrl: ((String) -> Unit)? = null
) {
    val changelog = remember(release.body) { release.getStructuredChangelog() }
    val cleanVersion = remember(release.tagName, release.version) {
        if (release.version.isValid) {
            "v${release.version.major}.${release.version.minor}.${release.version.patch}"
        } else {
            val cleaned = release.tagName.trim()
                .substringBefore('-')
                .removePrefix("v")
                .removePrefix("V")
            if (cleaned.isNotBlank()) "v$cleaned" else "latest"
        }
    }
    val isDarkSurface = BentoCardSurface.luminance() < 0.5f
    val headerCardColor = if (isDarkSurface) {
        BentoBackground.copy(alpha = 0.55f)
    } else {
        BentoCardPurple.copy(alpha = 0.45f)
    }
    val changelogCardColor = if (isDarkSurface) {
        BentoBackground.copy(alpha = 0.62f)
    } else {
        BentoCardPurple.copy(alpha = 0.35f)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 460.dp)
                .wrapContentHeight()
                .padding(vertical = 20.dp)
                .testTag("app_update_dialog"),
            shape = RoundedCornerShape(28.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Top Header Card: pill badge + "Version vX.Y.Z is available"
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = headerCardColor,
                    border = BorderStroke(0.75.dp, BentoBorder.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = BentoPrimary.copy(alpha = 0.18f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Update available",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoTextPrimary
                                )
                            }
                        }

                        Text(
                            text = "Version $cleanVersion is available",
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary,
                            letterSpacing = (-0.25).sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Scrollable Changelog Container
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = changelogCardColor,
                    border = BorderStroke(0.75.dp, BentoBorder.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 420.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Changelog",
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoPrimary
                        )

                        if (!changelog.intro.isNullOrBlank()) {
                            Text(
                                text = changelog.intro,
                                fontSize = 13.5.sp,
                                color = BentoTextSecondary,
                                lineHeight = 20.sp
                            )
                        }

                        changelog.sections.forEachIndexed { sectionIndex, section ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!section.heading.isNullOrBlank()) {
                                    if (sectionIndex > 0 || !changelog.intro.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                    }
                                    Text(
                                        text = section.heading,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoPrimary
                                    )
                                }

                                section.items.forEach { bullet ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 8.dp)
                                                .size(4.5.dp)
                                                .clip(CircleShape)
                                                .background(BentoTextPrimary.copy(alpha = 0.8f))
                                        )
                                        Text(
                                            text = bullet,
                                            fontSize = 13.5.sp,
                                            color = BentoTextPrimary.copy(alpha = 0.90f),
                                            lineHeight = 20.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Action Row: "Next time" + "Update"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = CircleShape,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("update_later_button")
                    ) {
                        Text(
                            text = "Next time",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }

                    Button(
                        onClick = {
                            onUpdateNow(AppUpdateRepository.LATEST_APK_DOWNLOAD_URL)
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoPrimary,
                            contentColor = BentoOnPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("update_now_button")
                    ) {
                        Text(
                            text = "Update",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

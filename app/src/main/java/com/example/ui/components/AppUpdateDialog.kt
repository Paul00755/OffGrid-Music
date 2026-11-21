package com.example.ui.components

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
 * Clean, minimal Material 3 update dialog announcing a verified stable release.
 * Avoids boxy nested containers, heavy badges, and emojis; presents concise
 * plain-language fixes and improvements with pill actions that adapt across screen sizes.
 */
@Composable
fun AppUpdateDialog(
    release: ReleaseInfo,
    onDismiss: () -> Unit,
    onUpdateNow: (String) -> Unit = {},
    onOpenRepoUrl: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val publishDate = remember(release.publishedAt) { release.getFormattedPublishDate() }
    val bulletNotes = remember(release.body) {
        release.getSanitizedNotes()
            .lines()
            .map { it.trim().removePrefix("•").trim() }
            .filter { it.isNotBlank() }
    }
    val versionLabel = remember(release.tagName, release.version) {
        if (release.version.isValid) {
            "Version ${release.version.major}.${release.version.minor}.${release.version.patch}"
        } else {
            release.tagName.removePrefix("v").let { "Version $it" }
        }
    }
    val metaLine = remember(versionLabel, publishDate, release.apkSize) {
        buildList {
            add(versionLabel)
            if (publishDate.isNotBlank()) add(publishDate)
            val sizeText = release.getFormattedApkSize()
            if (sizeText.isNotBlank()) add(sizeText)
        }.joinToString("  •  ")
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
                .widthIn(max = 440.dp)
                .wrapContentHeight()
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(28.dp),
            color = BentoCardSurface,
            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.55f)),
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
                // Calm editorial header without loud boxed icons or badges
                Text(
                    text = metaLine,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = BentoPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Update available",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary,
                    letterSpacing = (-0.2).sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "A newer version of OffGrid Music is ready. Your library, playlists, and settings stay intact.",
                    fontSize = 13.sp,
                    color = BentoTextSecondary,
                    lineHeight = 18.5.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider(
                    color = BentoBorder.copy(alpha = 0.35f),
                    thickness = 0.8.dp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Plain, scannable What's New list directly on the surface (no boxy inner card)
                Text(
                    text = "What's fixed and improved",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoTextPrimary
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 210.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    bulletNotes.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 7.dp)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(BentoPrimary)
                            )
                            Text(
                                text = item,
                                fontSize = 13.sp,
                                color = BentoTextSecondary,
                                lineHeight = 19.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Subtle inline link instead of a boxy rectangle card
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val repoUrl = AppUpdateRepository.GITHUB_REPO_URL
                            if (onOpenRepoUrl != null) {
                                onOpenRepoUrl(repoUrl)
                            } else {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl)))
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Could not open GitHub repository", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .defaultMinSize(minHeight = 40.dp)
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .testTag("update_github_repo_link"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "View release details on GitHub",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = BentoPrimary
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open GitHub repository",
                        tint = BentoPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Clean Material 3 pill actions (text dismiss + pill primary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
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
                            text = "Not now",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = BentoTextSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }

                    Button(
                        onClick = {
                            val targetUrl = release.htmlUrl.takeIf { it.isNotBlank() && it.startsWith("https://github.com/") }
                                ?: release.apkDownloadUrl?.takeIf { it.isNotBlank() && it.startsWith("https://github.com/") }
                                ?: "${AppUpdateRepository.GITHUB_REPO_URL}/releases/tag/${release.tagName}"
                            onUpdateNow(targetUrl)
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoPrimary,
                            contentColor = BentoOnPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("update_now_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Download update",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Update",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

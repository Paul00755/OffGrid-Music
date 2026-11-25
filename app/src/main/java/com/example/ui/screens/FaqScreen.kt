package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.util.HapticUtils

data class FaqEntry(
    val id: String,
    val category: String,
    val question: String,
    val answer: String
)

val OFFGRID_FAQ_ENTRIES: List<FaqEntry> = listOf(
    FaqEntry(
        id = "play_store_safety",
        category = "Trust & Safety",
        question = "The app is not published on the Google Play Store. Is it really safe to use?",
        answer = "Yes. OffGrid Music is built by an independent student developer and distributed directly through official GitHub Releases rather than the Play Store.\n\n" +
            "It contains no ads, no third-party ad trackers, and no hidden background data harvesting. Every release is signed consistently, requests only the permissions strictly needed to play and organize your audio files, and works fully offline without even needing an account."
    ),
    FaqEntry(
        id = "google_signin_privacy",
        category = "Account & Privacy",
        question = "If I use Google Sign-In, will my data get stolen?",
        answer = "No. Google Sign-In uses Android's official Credential Manager and Firebase Authentication.\n\n" +
            "OffGrid Music never sees or stores your Google password, cannot access your Gmail, Drive, or Contacts, and only receives your basic profile details (display name, email address, and profile picture) to identify your account. Your local music files are never uploaded anywhere."
    ),
    FaqEntry(
        id = "backup_storage_location",
        category = "Cloud & Backups",
        question = "Where is my backed-up data stored?",
        answer = "When you sign in and enable Cloud Sync / Backup, your backup is stored in your private per-user cloud space on Google Firebase (Cloud Firestore), protected by strict account-scoped security rules so only your signed-in account can read or write it.\n\n" +
            "Only lightweight metadata—such as your favorite song list, custom playlists, listening statistics, and app preferences—is backed up. Your actual audio files and Gemini API key are never uploaded."
    ),
    FaqEntry(
        id = "gemini_api_key_safety",
        category = "AI & API Key",
        question = "Is my Gemini API key safe?",
        answer = "Yes. Your Gemini API key is stored strictly locally on your device and is never backed up to the cloud, logged, or shared with anyone.\n\n" +
            "It is only sent directly to Google's Gemini API when you explicitly request AI lyrics transcription or translation. For your own security, never share your API key publicly or with other people."
    ),
    FaqEntry(
        id = "api_key_errors_troubleshooting",
        category = "AI & API Key",
        question = "I'm facing API key errors — what should I do?",
        answer = "Occasional Gemini API errors or temporary downtime can happen due to sudden traffic surges or free-tier rate limits on Google's servers. This is completely common and usually resolves on its own after waiting a little while.\n\n" +
            "If the error still does not go away after some time, we advise checking your API key in Google AI Studio (aistudio.google.com/api-keys) to make sure the key is still active, valid, not revoked, and pasted into the app without any extra spaces."
    ),
    FaqEntry(
        id = "offline_playback_support",
        category = "Playback & Library",
        question = "Does OffGrid Music work completely offline?",
        answer = "Yes! Local music playback, custom playlists, equalizer & audio DSP effects, home feed customization, and cached lyrics all work 100% offline. Signing in is completely optional and only required if you want cloud backups or Off-Grid Jam."
    ),
    FaqEntry(
        id = "offgrid_jam_signin",
        category = "Off-Grid Jam",
        question = "How does Off-Grid Jam work, and why do I need to sign in?",
        answer = "Off-Grid Jam lets you connect with friends and family over the internet to listen to music together in sync. Signing in with Google or Email is required so rooms can safely identify hosts and participants and keep shared playback sessions synchronized."
    ),
    FaqEntry(
        id = "missing_local_songs",
        category = "Playback & Library",
        question = "Why are some of my local audio files not appearing in the library?",
        answer = "Check two things under Settings → Library & Folders:\n\n" +
            "1. Short-audio duration filter: clips shorter than your configured minimum duration (for example ringtones or voice notes) are hidden automatically.\n" +
            "2. Folder filters / Quick Scan: verify the folder isn't excluded, then run 'Quick Scan & Clean' or 'Full-Device Scan' to index newly added songs."
    ),
    FaqEntry(
        id = "updates_preserve_data",
        category = "Updates & Support",
        question = "Will installing a new app update delete my playlists, favorites, or settings?",
        answer = "No. Installing an update over your existing OffGrid Music installation keeps all of your local playlists, favorites, listening stats, lyrics, API key, and app settings intact. You can also sign in and run a Cloud Backup anytime for extra peace of mind."
    )
)

@Composable
fun FaqScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var expandedIds by remember { mutableStateOf(setOf(OFFGRID_FAQ_ENTRIES.first().id)) }

    BackHandler(enabled = true) {
        onBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BentoBackground)
    ) {
        if (LocalGlassThemeConfig.current.gradientEnabled) {
            GlassGradientBackdrop()
        }

        LazyColumn(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "faq_top_bar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            onBack()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("faq_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Frequently Asked Questions",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Tap any question below to view or hide its answer",
                            fontSize = 12.sp,
                            color = BentoTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            item(key = "faq_intro_banner") {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = BentoPrimary.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(BentoPrimary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.HelpOutline,
                                contentDescription = null,
                                tint = BentoPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Honest answers about OffGrid Music",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "App safety, Google Sign-In privacy, cloud backups, Gemini API keys, and common tips.",
                                fontSize = 11.5.sp,
                                color = BentoTextSecondary,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            itemsIndexed(
                items = OFFGRID_FAQ_ENTRIES,
                key = { _, item -> item.id }
            ) { index, entry ->
                val isExpanded = entry.id in expandedIds
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = BentoCardSurface,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isExpanded) BentoPrimary.copy(alpha = 0.42f) else BentoBorder.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("faq_item_$index")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                HapticUtils.performClickHaptic(context)
                                expandedIds = if (isExpanded) {
                                    expandedIds - entry.id
                                } else {
                                    expandedIds + entry.id
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 36.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = entry.category.uppercase(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = BentoPrimary
                                )
                                Text(
                                    text = entry.question,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoTextPrimary,
                                    lineHeight = 20.sp
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isExpanded) BentoPrimary.copy(alpha = 0.16f)
                                        else BentoCardPurple.copy(alpha = 0.55f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isExpanded) "Collapse answer" else "Expand answer",
                                    tint = if (isExpanded) BentoPrimary else BentoTextSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                HorizontalDivider(
                                    thickness = 0.8.dp,
                                    color = BentoBorder.copy(alpha = 0.35f),
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                Text(
                                    text = entry.answer,
                                    fontSize = 13.sp,
                                    color = BentoTextSecondary,
                                    lineHeight = 19.5.sp
                                )
                            }
                        }
                    }
                }
            }

            item(key = "faq_contact_footer") {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = BentoCardSurface,
                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Still have a question or found a bug?",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Email us at $OFFGRID_SUPPORT_EMAIL with details (and attach a screenshot if you ran into an error) and we'll help as soon as possible.",
                            fontSize = 12.sp,
                            color = BentoTextSecondary,
                            lineHeight = 17.5.sp
                        )
                        OutlinedButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                openSupportEmailIntent(context)
                            },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.48f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BentoPrimary),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("faq_email_support_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MailOutline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Email $OFFGRID_SUPPORT_EMAIL",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

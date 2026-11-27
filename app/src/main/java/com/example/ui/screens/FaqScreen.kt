package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.*
import com.example.util.HapticUtils

data class FaqVisualImage(
    val drawableResId: Int,
    val caption: String,
    val contentDescription: String
)

data class FaqEntry(
    val id: String,
    val category: String,
    val question: String,
    val answer: String,
    val images: List<FaqVisualImage> = emptyList()
)

val OFFGRID_FAQ_ENTRIES: List<FaqEntry> = listOf(
    FaqEntry(
        id = "download_and_install_apk",
        category = "Download & Install",
        question = "How do I download and install the OffGrid Music APK?",
        answer = "Follow these step-by-step instructions to download and install OffGrid Music on Android 7.0+:\n\n" +
            "1. Download the APK: Tap Download Latest APK at https://offgrid-music-web.pages.dev/ or open official GitHub Releases (Releases → Latest → OffGrid-Music.apk).\n" +
            "2. Grant Browser Download Access (if prompted): Allow Storage / Downloads permission in your browser. If Android shows \"File might be harmful\", tap \"Download anyway\".\n" +
            "3. Allow \"Install unknown apps\" (one-time setup):\n" +
            "   • Tap Open on the completed download notification (or open your Files / Downloads app and tap OffGrid-Music.apk).\n" +
            "   • When prompted \"For your security, your phone currently isn't allowed to install unknown apps from this source\", tap Settings.\n" +
            "   • Or go to Android Settings → Apps → Special app access → Install unknown apps, pick your browser (Chrome, Brave, Samsung Internet, etc.) or Files, and switch \"Allow from this source\" ON.\n" +
            "4. Verify & Install: Press Back, let Google Play Protect scan the APK (\"This app looks safe\"), tap Install, and launch OffGrid Music.\n\n" +
            "Tip: To update later, install any newer OffGrid-Music.apk right over your current app (or tap Settings → Check for App Updates)—no need to uninstall and zero data is lost."
    ),
    FaqEntry(
        id = "browser_harmful_warning_safe",
        category = "Trust & Safety",
        question = "My browser shows \"File might be harmful\" when downloading OffGrid-Music.apk. Is it really safe?",
        answer = "Yes — it is 100% safe to download and install.\n\n" +
            "• Why this warning appears: Android browsers automatically show a generic \"File might be harmful\" prompt for EVERY .apk file downloaded outside Google Play. It is triggered only by the .apk file type, not by scanning OffGrid Music.\n" +
            "• Google Play Protect scan passes cleanly: Tap \"Download anyway\" (from release-assets.githubusercontent.com), open OffGrid-Music.apk, and Google Play Protect inspects the signed package directly on your device—confirming \"This app looks safe — You can continue to install it\".\n\n" +
            "Every release is 100% open source on GitHub, compiled via transparent CI builds, and contains zero ads or tracking SDKs.",
        images = listOf(
            FaqVisualImage(
                drawableResId = R.drawable.faq_browser_harmful_warning,
                caption = "1. Generic APK prompt — tap \"Download anyway\"",
                contentDescription = "Android browser prompt showing File might be harmful for OffGrid-Music.apk with Download anyway button"
            ),
            FaqVisualImage(
                drawableResId = R.drawable.faq_play_protect_safe,
                caption = "2. Play Protect passed: \"This app looks safe\"",
                contentDescription = "Google Play Protect scan dialog for OffGrid Music confirming This app looks safe and You can continue to install it"
            )
        )
    ),
    FaqEntry(
        id = "play_store_safety",
        category = "Trust & Safety",
        question = "The app is not published on the Google Play Store. Is it really safe to use?",
        answer = "Yes. OffGrid Music is built by an independent student developer and distributed directly through official GitHub Releases rather than the Play Store.\n\n" +
            "It contains no ads, no third-party ad trackers, and no hidden background data harvesting. Every release is signed consistently, requests only the permissions strictly needed to play and organize your audio files, and works fully offline without even needing an account."
    ),
    FaqEntry(
        id = "sign_in_methods",
        category = "Account & Backup",
        question = "In what ways can I sign in to OffGrid Music?",
        answer = "You can use OffGrid Music in three flexible ways:\n\n" +
            "1. Google Sign-In: One-tap sign-in using Android's official Credential Manager.\n" +
            "2. Email & Password: Create or sign in with your own email address and password.\n" +
            "3. Offline Guest Mode (No Sign-In): Skip login completely and use local playback, playlists, EQ, themes, and lyrics 100% offline. You can sign in anytime later from Settings → Account or Cloud & Sync."
    ),
    FaqEntry(
        id = "google_signin_privacy",
        category = "Trust & Safety",
        question = "If I use Google Sign-In, will my data get stolen?",
        answer = "No. Google Sign-In uses Android's official Credential Manager and Firebase Authentication.\n\n" +
            "OffGrid Music never sees or stores your Google password, cannot access your Gmail, Drive, or Contacts, and only receives your basic profile details (display name, email address, and profile picture) to identify your account. Your local music files are never uploaded anywhere."
    ),
    FaqEntry(
        id = "what_data_is_backed_up",
        category = "Account & Backup",
        question = "What data is backed up when I sign in?",
        answer = "When you sign in and run Cloud Backup / Sync, OffGrid Music safely backs up:\n\n" +
            "• Favorites (your hearted songs)\n" +
            "• Custom Playlists & Smart Playlists\n" +
            "• Listening Statistics & Play History\n" +
            "• Synced Lyrics & Per-Song Timing Offsets\n" +
            "• Folder Include / Exclude Rules\n" +
            "• Custom Home Feed Order, Bottom Bar Order & App Preferences (themes, EQ, playback settings)\n\n" +
            "What is NEVER uploaded: your actual local audio files (MP3/FLAC/etc.) and your Gemini API key stay strictly on your device."
    ),
    FaqEntry(
        id = "backup_storage_location",
        category = "Account & Backup",
        question = "Where is my backed-up data stored?",
        answer = "Your cloud backup is stored in your private per-user account space on Google Firebase (Cloud Firestore), protected by strict account-scoped security rules so only your signed-in account can read or write your data.\n\n" +
            "You can trigger Backup Now, Sync / Restore Now, or enable scheduled Automatic Backups (Daily, Weekly, or Monthly) anytime under Settings → Cloud & Sync."
    ),
    FaqEntry(
        id = "how_offgrid_jam_works",
        category = "Off-Grid Jam",
        question = "How does Off-Grid Jam work?",
        answer = "Off-Grid Jam lets 2 to 10 friends or family members listen to music together in real time over the internet.\n\n" +
            "1. Sign in with Google or Email to unlock Off-Grid Jam in the bottom navigation bar.\n" +
            "2. The host creates a Jam room and shares the Invite Code, Invite Link, or QR Code.\n" +
            "3. Friends join on the same latest version of OffGrid Music, and play/pause, seeks, and track switches stay synchronized automatically.\n" +
            "4. Participants can also vote on songs and share approved local tracks (up to 25 MB) directly device-to-device without uploading your library to cloud storage."
    ),
    FaqEntry(
        id = "gemini_api_key_safety",
        category = "AI & Lyrics",
        question = "Is my Gemini API key safe?",
        answer = "Yes. Your Gemini API key is stored strictly locally on your device and is never backed up to the cloud, logged, or shared with anyone.\n\n" +
            "It is only sent directly to Google's Gemini API when you explicitly request AI lyrics translation or audio transcription. For your own security, never share your API key with anyone."
    ),
    FaqEntry(
        id = "how_to_add_gemini_api_key",
        category = "AI & Lyrics",
        question = "How do I get and add a Gemini API key for lyrics translation or transcription?",
        answer = "1. Visit https://aistudio.google.com/docs/api-key\n" +
            "2. Tap 'Create or view a Gemini API key'.\n" +
            "3. On https://aistudio.google.com/api-keys, click 'Create API key' (or copy an existing key).\n" +
            "4. Open OffGrid Music → Settings → Audio / Lyrics AI Translation & Transcribe, paste your key, and save.\n\n" +
            "AI features are completely optional and turned OFF by default."
    ),
    FaqEntry(
        id = "api_key_errors_troubleshooting",
        category = "AI & Lyrics",
        question = "I'm facing API key errors — what should I do?",
        answer = "Occasional Gemini API errors or temporary downtime can happen due to sudden traffic surges or free-tier rate limits on Google's servers. This is completely common and usually becomes usable again after waiting a little while.\n\n" +
            "If the error still does not resolve after some time, we advise checking your API key in Google AI Studio (aistudio.google.com/api-keys) to ensure it is still valid, active, not revoked, and pasted without any extra spaces."
    ),
    FaqEntry(
        id = "smart_shuffle_explained",
        category = "Playback & Library",
        question = "What is Smart Shuffle (Smart Queue) and how does it work?",
        answer = "Smart Shuffle analyzes your playback history and genre preferences to dynamically reorder your shuffled queue for smoother, more engaging listening sessions instead of pure random jumps.\n\n" +
            "It is OFF by default and only activates when you turn on the Smart Queue toggle under Settings → Playback & Timers."
    ),
    FaqEntry(
        id = "offline_playback_support",
        category = "Playback & Library",
        question = "Does OffGrid Music work completely offline?",
        answer = "Yes! Local music playback, custom playlists, 5-band Equalizer & DSP effects, Now Playing themes, Home Feed customization, and cached `.lrc` lyrics all work 100% offline. Internet access is only used for optional Cloud Backup, Off-Grid Jam, online lyric lookup, AI lyrics, or checking for app updates."
    ),
    FaqEntry(
        id = "missing_local_songs",
        category = "Playback & Library",
        question = "Why are some of my local audio files not appearing in my library?",
        answer = "Check two settings under Settings → Library & Folders:\n\n" +
            "1. Short-audio duration filter: clips shorter than your configured threshold (such as ringtones or voice notes) are filtered out automatically.\n" +
            "2. Folder include/exclude filters & scan: make sure the song's folder is not excluded, then run 'Quick Scan & Clean' or 'Full-Device Scan' to index newly added files."
    ),
    FaqEntry(
        id = "customize_feed_and_bottom_bar",
        category = "Playback & Library",
        question = "How can I customize the Home Feed, Bottom Bar, or Now Playing screen?",
        answer = "• Home Feed & Bottom Bar: Go to Settings → Organize & Order Your Feed to show/hide or reorder Home sections (including New Music Mix Suggestions), save custom presets, and rearrange your bottom navigation bar icons.\n" +
            "• Now Playing & Themes: Go to Settings → UI & Theme → Screen Layout Studio to preview and switch between Classic, Vinyl Turntable, Minimal Glass, and Neon Studio layouts, waveform styles, seek interval buttons (5/10/15/30s), and dynamic artwork colors."
    ),
    FaqEntry(
        id = "updates_preserve_data",
        category = "Updates & Tips",
        question = "Will installing a new app update delete my playlists, favorites, or settings?",
        answer = "No. Installing the latest `OffGrid-Music.apk` directly over your existing installation preserves all of your local playlists, favorites, listening stats, synced lyrics, API key, and app settings. You can also run a Cloud Backup anytime for extra peace of mind."
    )
)

@Composable
fun FaqScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var expandedIds by remember { mutableStateOf(setOf(OFFGRID_FAQ_ENTRIES.first().id)) }

    val categories = remember {
        listOf("All") + OFFGRID_FAQ_ENTRIES.map { it.category }.distinct()
    }

    val filteredEntries by remember(searchQuery, selectedCategory) {
        derivedStateOf {
            val q = searchQuery.trim().lowercase()
            OFFGRID_FAQ_ENTRIES.filter { entry ->
                val matchesCat = selectedCategory == "All" || entry.category == selectedCategory
                val matchesQuery = q.isEmpty() ||
                    entry.question.lowercase().contains(q) ||
                    entry.answer.lowercase().contains(q) ||
                    entry.category.lowercase().contains(q)
                matchesCat && matchesQuery
            }
        }
    }

    val allFilteredExpanded = remember(filteredEntries, expandedIds) {
        filteredEntries.isNotEmpty() && filteredEntries.all { it.id in expandedIds }
    }

    BackHandler(enabled = true) {
        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
        } else {
            onBack()
        }
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
                .navigationBarsPadding()
                .testTag("faq_list"),
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
                            text = "${OFFGRID_FAQ_ENTRIES.size} helpful guides · tap any question to expand",
                            fontSize = 12.sp,
                            color = BentoTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Quick Search Input
            item(key = "faq_search_bar") {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = BentoCardSurface,
                    border = BorderStroke(
                        1.dp,
                        if (searchQuery.isNotBlank()) BentoPrimary.copy(alpha = 0.5f) else BentoBorder.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = if (searchQuery.isNotBlank()) BentoPrimary else BentoTextSubtle,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    text = "Search APK install, Play Protect, Jam, backup...",
                                    fontSize = 13.sp,
                                    color = BentoTextSubtle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = BentoTextPrimary,
                                unfocusedTextColor = BentoTextPrimary,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("faq_search_input")
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear search",
                                    tint = BentoTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Category Filter Chips + Expand/Collapse All Action
            item(key = "faq_category_chips") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(categories, key = { it }) { cat ->
                            val isSelected = selectedCategory == cat
                            Surface(
                                shape = CircleShape,
                                color = if (isSelected) BentoPrimary else BentoCardSurface,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) BentoPrimary else BentoBorder.copy(alpha = 0.45f)
                                ),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        HapticUtils.performClickHaptic(context)
                                        selectedCategory = cat
                                    }
                            ) {
                                Text(
                                    text = cat,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) BentoOnPrimary else BentoTextSecondary,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${filteredEntries.size} ${if (filteredEntries.size == 1) "question" else "questions"}",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = BentoTextSubtle
                        )
                        if (filteredEntries.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    HapticUtils.performClickHaptic(context)
                                    expandedIds = if (allFilteredExpanded) {
                                        expandedIds - filteredEntries.map { it.id }.toSet()
                                    } else {
                                        expandedIds + filteredEntries.map { it.id }.toSet()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("faq_toggle_all_button")
                            ) {
                                Text(
                                    text = if (allFilteredExpanded) "Collapse all" else "Expand all",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BentoPrimary
                                )
                            }
                        }
                    }
                }
            }

            if (filteredEntries.isEmpty()) {
                item(key = "faq_empty_search") {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = BentoCardSurface,
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.HelpOutline,
                                contentDescription = null,
                                tint = BentoTextSubtle,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "No FAQ matched \"$searchQuery\"",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = "Try searching for Jam, sign-in, backup, API key, or Play Store.",
                                fontSize = 12.sp,
                                color = BentoTextSecondary
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = filteredEntries,
                    key = { _, item -> item.id }
                ) { index, entry ->
                    val isExpanded = entry.id in expandedIds
                    Surface(
                        onClick = {
                            HapticUtils.performClickHaptic(context)
                            expandedIds = if (isExpanded) {
                                expandedIds - entry.id
                            } else {
                                expandedIds + entry.id
                            }
                        },
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
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 38.dp),
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
                                        .size(36.dp)
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
                                    if (entry.images.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(14.dp))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("faq_images_${entry.id}"),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            entry.images.forEachIndexed { imgIdx, visual ->
                                                Surface(
                                                    shape = RoundedCornerShape(14.dp),
                                                    color = BentoCardPurple.copy(alpha = 0.45f),
                                                    border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.45f)),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .testTag("faq_image_${entry.id}_$imgIdx")
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(8.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Image(
                                                            painter = painterResource(id = visual.drawableResId),
                                                            contentDescription = visual.contentDescription,
                                                            contentScale = ContentScale.Fit,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .heightIn(max = 220.dp)
                                                                .clip(RoundedCornerShape(10.dp))
                                                        )
                                                        Text(
                                                            text = visual.caption,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = BentoTextPrimary,
                                                            textAlign = TextAlign.Center,
                                                            lineHeight = 15.sp
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

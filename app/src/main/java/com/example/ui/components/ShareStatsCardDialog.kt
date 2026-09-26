package com.example.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.WrappedData
import com.example.ui.theme.*
import com.example.util.HapticUtils
import com.example.util.StatsCardImageGenerator
import kotlinx.coroutines.launch
import java.util.Calendar

enum class StoryPeriod(val label: String, val subtitle: String, val icon: ImageVector) {
    LAST_7_DAYS("Last 7 Days", "Past 7 days of listening activity", Icons.Default.DateRange),
    THIS_MONTH("This Month", "Current calendar month", Icons.Default.CalendarMonth),
    LAST_MONTH("Last Month", "Previous calendar month", Icons.Default.Event),
    ALL_TIME("All Time", "All-time listening journey", Icons.Default.AllInclusive);

    fun getTimeRange(nowMs: Long = System.currentTimeMillis()): Pair<Long, Long> {
        return when (this) {
            LAST_7_DAYS -> {
                val startCal = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.DAY_OF_YEAR, -6)
                }
                Pair(startCal.timeInMillis, Long.MAX_VALUE)
            }
            THIS_MONTH -> {
                val startCal = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endCal = (startCal.clone() as Calendar).apply {
                    add(Calendar.MONTH, 1)
                }
                Pair(startCal.timeInMillis, endCal.timeInMillis)
            }
            LAST_MONTH -> {
                val thisMonthStart = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val lastMonthStart = (thisMonthStart.clone() as Calendar).apply {
                    add(Calendar.MONTH, -1)
                }
                Pair(lastMonthStart.timeInMillis, thisMonthStart.timeInMillis)
            }
            ALL_TIME -> Pair(0L, Long.MAX_VALUE)
        }
    }
}

@Composable
fun SharePeriodSelectionDialog(
    onDismiss: () -> Unit,
    onPeriodSelected: (StoryPeriod) -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(26.dp))
                .border(1.dp, BentoBorder, RoundedCornerShape(26.dp)),
            color = BentoCardSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = BentoPrimary.copy(alpha = 0.15f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DateRange,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Select Time Period",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                            Text(
                                text = "Choose timeframe for your Story Card",
                                fontSize = 12.sp,
                                color = BentoTextSecondary
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(BentoCardPurple)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = BentoTextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Period Option Cards
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StoryPeriod.values().forEach { period ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    HapticUtils.performClickHaptic(context)
                                    onPeriodSelected(period)
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = BentoCardPurple,
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = BentoPrimary.copy(alpha = 0.12f),
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = period.icon,
                                                contentDescription = null,
                                                tint = BentoPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Column {
                                        Text(
                                            text = period.label,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BentoTextPrimary
                                        )
                                        Text(
                                            text = period.subtitle,
                                            fontSize = 12.sp,
                                            color = BentoTextSecondary
                                        )
                                    }
                                }

                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = BentoTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShareStatsCardDialog(
    wrappedData: WrappedData? = null,
    timePeriodTitle: String = "All Time",
    initialPeriod: StoryPeriod = StoryPeriod.ALL_TIME,
    loadWrappedData: (suspend (Long, Long) -> WrappedData)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentPeriod by remember { mutableStateOf(initialPeriod) }
    var effectiveData by remember { mutableStateOf(wrappedData) }
    var cardBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isGenerating by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var showPeriodSwitcherMenu by remember { mutableStateOf(false) }

    // Fetch data for selected period when loader is provided
    LaunchedEffect(currentPeriod) {
        isGenerating = true
        try {
            val data = if (loadWrappedData != null) {
                val (start, end) = currentPeriod.getTimeRange()
                loadWrappedData(start, end)
            } else {
                wrappedData ?: WrappedData()
            }
            effectiveData = data
            val bmp = StatsCardImageGenerator.generateStatsCardBitmap(
                context = context,
                wrappedData = data,
                timePeriodTitle = currentPeriod.label
            )
            cardBitmap = bmp
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Could not render stats card: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            isGenerating = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(28.dp))
                .border(1.dp, BentoBorder, RoundedCornerShape(28.dp)),
            color = BentoCardSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = BentoPrimary.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = BentoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Share My Stats Card",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                            // Period badge that is clickable to switch periods
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BentoPrimary.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { showPeriodSwitcherMenu = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = currentPeriod.label,
                                        color = BentoPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Switch Period",
                                        tint = BentoPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Preview Container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF090710))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isGenerating || cardBitmap == null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = BentoPrimary,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Generating ${currentPeriod.label} stats card...",
                                color = BentoTextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        cardBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Generated Stats Card Preview",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Save Image Button
                    OutlinedButton(
                        onClick = {
                            val bmp = cardBitmap ?: return@OutlinedButton
                            isSaving = true
                            coroutineScope.launch {
                                val saved = StatsCardImageGenerator.saveImageToGallery(context, bmp)
                                isSaving = false
                                if (saved) {
                                    Toast.makeText(context, "Saved to Pictures/OffGrid!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = cardBitmap != null && !isSaving,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Image", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }

                    // Share Image Button (Primary Action)
                    Button(
                        onClick = {
                            val bmp = cardBitmap ?: return@Button
                            val data = effectiveData ?: return@Button
                            coroutineScope.launch {
                                val uri = StatsCardImageGenerator.saveBitmapToCache(context, bmp)
                                if (uri != null) {
                                    val caption = buildString {
                                        appendLine("🔥 My OffGrid Music Stats (${currentPeriod.label}):")
                                        appendLine("⏱ Total Time: ${data.totalMinutes} mins")
                                        if (data.topSong != null) {
                                            appendLine("👑 #1 Track: ${data.topSong.displayTitle} by ${data.topSong.displayArtist}")
                                        }
                                        appendLine("🎧 Persona: ${data.listenerArchetype}")
                                    }
                                    StatsCardImageGenerator.shareImageCard(context, uri, caption)
                                } else {
                                    Toast.makeText(context, "Unable to prepare image for sharing", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = cardBitmap != null,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BentoPrimary,
                            contentColor = BentoOnPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = BentoOnPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share Image Card", fontWeight = FontWeight.Black, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (showPeriodSwitcherMenu) {
        SharePeriodSelectionDialog(
            onDismiss = { showPeriodSwitcherMenu = false },
            onPeriodSelected = { period ->
                currentPeriod = period
                showPeriodSwitcherMenu = false
            }
        )
    }
}

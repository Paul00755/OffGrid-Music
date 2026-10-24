package com.example.ui.components

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FolderInfo
import com.example.ui.theme.*
import java.io.File

private val SUPPORTED_AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "wav", "aac", "ogg", "opus", "wma")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HierarchicalFolderBrowserModal(
    folders: List<FolderInfo>,
    onAddFolder: (path: String, name: String, isExcluded: Boolean) -> Unit,
    onRemoveFolder: (path: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val defaultMusicDir = remember {
        val publicMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (publicMusic != null && publicMusic.exists()) {
            publicMusic
        } else {
            val root = Environment.getExternalStorageDirectory()
            val musicFolder = File(root, "Music")
            if (musicFolder.exists()) musicFolder else root
        }
    }

    var currentDirectory by remember { mutableStateOf(defaultMusicDir) }
    var showActiveSummary by remember { mutableStateOf(false) }

    val includedFolders = remember(folders) { folders.filter { !it.isExcluded } }
    val excludedFolders = remember(folders) { folders.filter { it.isExcluded } }

    val currentPath = currentDirectory.absolutePath
    val isCurrentDirectlyIncluded = remember(folders, currentPath) {
        folders.any { it.path == currentPath && !it.isExcluded }
    }
    val isCurrentDirectlyExcluded = remember(folders, currentPath) {
        folders.any { it.path == currentPath && it.isExcluded }
    }

    // Subdirectories list of current directory
    val subdirectories = remember(currentDirectory) {
        try {
            currentDirectory.listFiles { file ->
                file.isDirectory && !file.name.startsWith(".")
            }?.sortedBy { it.name.lowercase() }?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Audio files count in current directory
    val audioFilesCount = remember(currentDirectory) {
        try {
            currentDirectory.listFiles { file ->
                file.isFile && file.extension.lowercase() in SUPPORTED_AUDIO_EXTENSIONS
            }?.size ?: 0
        } catch (_: Exception) {
            0
        }
    }

    // Breadcrumb path components
    val pathSegments = remember(currentPath) {
        val rootPath = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        val displayPath = if (currentPath.startsWith(rootPath)) {
            "Storage" + currentPath.removePrefix(rootPath)
        } else {
            currentPath
        }
        val segments = displayPath.split("/").filter { it.isNotBlank() }
        var acc = if (currentPath.startsWith(rootPath)) rootPath else ""
        segments.mapIndexed { index, seg ->
            if (index == 0 && currentPath.startsWith(rootPath)) {
                seg to rootPath
            } else {
                acc = if (acc.endsWith("/")) "$acc$seg" else "$acc/$seg"
                seg to acc
            }
        }
    }

    val canGoUp = remember(currentDirectory) {
        val rootPath = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        currentDirectory.parentFile != null && currentDirectory.absolutePath != "/" && currentDirectory.absolutePath != rootPath && currentDirectory.absolutePath != "/storage"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BentoBackground,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = BentoTextSecondary.copy(alpha = 0.4f))
        },
        modifier = modifier.fillMaxHeight(0.92f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Folder Selection",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary
                    )
                    Text(
                        text = "Include music directories or exclude unwanted folders",
                        fontSize = 12.sp,
                        color = BentoTextSecondary
                    )
                }

                FilledTonalButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = BentoPrimaryContainer,
                        contentColor = BentoOnPrimaryContainer
                    )
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }

            // Quick Shortcut Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickFolderChip(
                    title = "Music",
                    icon = Icons.Default.LibraryMusic,
                    isSelected = currentPath.contains("/Music", ignoreCase = true),
                    onClick = {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                        if (dir != null && dir.exists()) currentDirectory = dir
                    }
                )
                QuickFolderChip(
                    title = "Downloads",
                    icon = Icons.Default.Download,
                    isSelected = currentPath.contains("/Download", ignoreCase = true),
                    onClick = {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (dir != null && dir.exists()) currentDirectory = dir
                    }
                )
                QuickFolderChip(
                    title = "Podcasts",
                    icon = Icons.Default.Podcasts,
                    isSelected = currentPath.contains("/Podcasts", ignoreCase = true),
                    onClick = {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS)
                        if (dir != null && dir.exists()) currentDirectory = dir
                    }
                )
                QuickFolderChip(
                    title = "Audiobooks",
                    icon = Icons.Default.MenuBook,
                    isSelected = currentPath.contains("/Audiobooks", ignoreCase = true),
                    onClick = {
                        val dir = File(Environment.getExternalStorageDirectory(), "Audiobooks")
                        if (dir.exists()) currentDirectory = dir
                    }
                )
                QuickFolderChip(
                    title = "Storage Root",
                    icon = Icons.Default.SdCard,
                    isSelected = currentPath == (Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"),
                    onClick = {
                        val dir = Environment.getExternalStorageDirectory()
                        if (dir != null && dir.exists()) currentDirectory = dir
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Breadcrumb Navigation Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = BentoCardPurple
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (canGoUp) {
                        IconButton(
                            onClick = {
                                currentDirectory.parentFile?.let { currentDirectory = it }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Go up a directory",
                                tint = BentoPrimary
                            )
                        }
                    } else {
                        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = BentoTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        pathSegments.forEachIndexed { idx, (name, targetPath) ->
                            Text(
                                text = name,
                                fontSize = 12.sp,
                                fontWeight = if (idx == pathSegments.lastIndex) FontWeight.Bold else FontWeight.Normal,
                                color = if (idx == pathSegments.lastIndex) BentoPrimary else BentoTextSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        val targetFile = File(targetPath)
                                        if (targetFile.exists() && targetFile.isDirectory) {
                                            currentDirectory = targetFile
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                            if (idx < pathSegments.lastIndex) {
                                Text(
                                    text = "/",
                                    fontSize = 12.sp,
                                    color = BentoTextSecondary.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Current Directory Status & Action Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = BentoCardDarkPurple,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when {
                        isCurrentDirectlyIncluded -> Color(0xFF4CAF50).copy(alpha = 0.5f)
                        isCurrentDirectlyExcluded -> Color(0xFFF44336).copy(alpha = 0.5f)
                        else -> BentoCardBorder.copy(alpha = 0.5f)
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentDirectory.name.ifBlank { "Root Storage" },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (audioFilesCount > 0) "$audioFilesCount audio files in this folder" else "No direct audio files",
                                fontSize = 11.sp,
                                color = BentoTextSecondary
                            )
                        }

                        // Current status badge
                        when {
                            isCurrentDirectlyIncluded -> {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF4CAF50).copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "✓ Included",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            isCurrentDirectlyExcluded -> {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFF44336).copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "✕ Excluded",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFF44336),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            else -> {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = BentoTextSecondary.copy(alpha = 0.1f)
                                ) {
                                    Text(
                                        text = "Neutral",
                                        fontSize = 11.sp,
                                        color = BentoTextSecondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Buttons for the current folder
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isCurrentDirectlyIncluded) {
                                    onRemoveFolder(currentPath)
                                } else {
                                    onAddFolder(currentPath, currentDirectory.name.ifBlank { "Folder" }, false)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCurrentDirectlyIncluded) Color(0xFF4CAF50) else Color(0xFF2E7D32)
                            )
                        ) {
                            Icon(
                                imageVector = if (isCurrentDirectlyIncluded) Icons.Default.Check else Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isCurrentDirectlyIncluded) "Included" else "Include This",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                if (isCurrentDirectlyExcluded) {
                                    onRemoveFolder(currentPath)
                                } else {
                                    onAddFolder(currentPath, currentDirectory.name.ifBlank { "Folder" }, true)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCurrentDirectlyExcluded) Color(0xFFF44336) else Color(0xFFC62828)
                            )
                        ) {
                            Icon(
                                imageVector = if (isCurrentDirectlyExcluded) Icons.Default.Block else Icons.Default.RemoveCircleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isCurrentDirectlyExcluded) "Excluded" else "Exclude This",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (isCurrentDirectlyIncluded || isCurrentDirectlyExcluded) {
                            OutlinedButton(
                                onClick = { onRemoveFolder(currentPath) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("Reset", fontSize = 12.sp, color = BentoTextSecondary)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Subdirectories List Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Subfolders (${subdirectories.size})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoTextPrimary
                )

                TextButton(
                    onClick = { showActiveSummary = !showActiveSummary }
                ) {
                    Text(
                        text = "${includedFolders.size} Incl. | ${excludedFolders.size} Excl.",
                        fontSize = 12.sp,
                        color = BentoPrimary
                    )
                }
            }

            // Summary of Configured Folders Dropdown if requested
            if (showActiveSummary && folders.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = BentoCardPurple
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Configured Folder Rules:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        folders.forEach { f ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (f.isExcluded) Color(0xFFF44336).copy(alpha = 0.2f) else Color(0xFF4CAF50).copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = if (f.isExcluded) "EXCLUDE" else "INCLUDE",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (f.isExcluded) Color(0xFFF44336) else Color(0xFF4CAF50),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = f.path.substringAfterLast("/").ifBlank { f.path },
                                        fontSize = 12.sp,
                                        color = BentoTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveFolder(f.path) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove rule",
                                        tint = BentoTextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Subdirectories List
            if (subdirectories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = BentoTextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "No subfolders inside this directory",
                            fontSize = 13.sp,
                            color = BentoTextSecondary
                        )
                        Text(
                            text = "You can include or exclude this folder using the buttons above",
                            fontSize = 11.sp,
                            color = BentoTextSecondary.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(subdirectories, key = { it.absolutePath }) { subfolder ->
                        val subPath = subfolder.absolutePath
                        val isSubDirectlyIncluded = folders.any { it.path == subPath && !it.isExcluded }
                        val isSubDirectlyExcluded = folders.any { it.path == subPath && it.isExcluded }

                        SubfolderItemRow(
                            folder = subfolder,
                            isIncluded = isSubDirectlyIncluded,
                            isExcluded = isSubDirectlyExcluded,
                            onNavigateIn = {
                                currentDirectory = subfolder
                            },
                            onToggleInclude = {
                                if (isSubDirectlyIncluded) {
                                    onRemoveFolder(subPath)
                                } else {
                                    onAddFolder(subPath, subfolder.name, false)
                                }
                            },
                            onToggleExclude = {
                                if (isSubDirectlyExcluded) {
                                    onRemoveFolder(subPath)
                                } else {
                                    onAddFolder(subPath, subfolder.name, true)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickFolderChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) BentoPrimaryContainer else BentoCardPurple,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) BentoPrimary else BentoCardBorder.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) BentoOnPrimaryContainer else BentoPrimary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) BentoOnPrimaryContainer else BentoTextPrimary
            )
        }
    }
}

@Composable
private fun SubfolderItemRow(
    folder: File,
    isIncluded: Boolean,
    isExcluded: Boolean,
    onNavigateIn: () -> Unit,
    onToggleInclude: () -> Unit,
    onToggleExclude: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onNavigateIn),
        shape = RoundedCornerShape(12.dp),
        color = BentoCardSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                isIncluded -> Color(0xFF4CAF50).copy(alpha = 0.4f)
                isExcluded -> Color(0xFFF44336).copy(alpha = 0.4f)
                else -> BentoCardBorder.copy(alpha = 0.3f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isIncluded -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                                isExcluded -> Color(0xFFF44336).copy(alpha = 0.15f)
                                else -> BentoPrimary.copy(alpha = 0.15f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isIncluded -> Icons.Default.Check
                            isExcluded -> Icons.Default.Block
                            else -> Icons.Default.Folder
                        },
                        contentDescription = null,
                        tint = when {
                            isIncluded -> Color(0xFF4CAF50)
                            isExcluded -> Color(0xFFF44336)
                            else -> BentoPrimary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BentoTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            isIncluded -> "Included in scan"
                            isExcluded -> "Excluded from scan"
                            else -> "Tap to open folder"
                        },
                        fontSize = 11.sp,
                        color = when {
                            isIncluded -> Color(0xFF4CAF50)
                            isExcluded -> Color(0xFFF44336)
                            else -> BentoTextSecondary
                        }
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Include Quick Toggle
                IconButton(
                    onClick = onToggleInclude,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isIncluded) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline,
                        contentDescription = "Include folder",
                        tint = if (isIncluded) Color(0xFF4CAF50) else BentoTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Exclude Quick Toggle
                IconButton(
                    onClick = onToggleExclude,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isExcluded) Icons.Default.Block else Icons.Default.RemoveCircleOutline,
                        contentDescription = "Exclude folder",
                        tint = if (isExcluded) Color(0xFFF44336) else BentoTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Navigate in arrow
                IconButton(
                    onClick = onNavigateIn,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = "Open folder",
                        tint = BentoTextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

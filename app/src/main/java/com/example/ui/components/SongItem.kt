package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Song
import com.example.ui.theme.*

private val SongItemRowShape = RoundedCornerShape(10.dp)
private val SongItemArtShape = RoundedCornerShape(9.dp)

@Composable
fun SongItem(
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onMoreClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(SongItemRowShape)
            .background(if (isPlaying) BentoCardDarkPurple else BentoCardSurface)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail Album Artwork
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(SongItemArtShape)
        ) {
            AlbumArtView(
                song = song,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 9
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Info
        val subtitleText = remember(song.displayArtist, song.displayAlbum) {
            "${song.displayArtist} • ${song.displayAlbum}"
        }
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = song.displayTitle,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isPlaying) BentoPrimary else BentoTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = subtitleText,
                fontSize = 11.5.sp,
                color = BentoTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Favorite Button
        IconButton(
            onClick = onFavoriteToggle,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (song.isFavorite) BentoAccentPinkText else BentoTextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        // More Menu
        IconButton(
            onClick = onMoreClick,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More",
                tint = BentoTextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

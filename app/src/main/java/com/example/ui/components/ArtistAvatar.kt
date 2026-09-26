package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ui.theme.BentoCardDarkPurple
import com.example.ui.theme.BentoPrimary
import com.example.util.OnlineArtistImageFetcher

@Composable
fun ArtistAvatar(
    artistName: String,
    modifier: Modifier = Modifier,
    fallbackArtworkUri: String? = null
) {
    val context = LocalContext.current
    var imageUrl by remember(artistName) { mutableStateOf<String?>(fallbackArtworkUri) }

    LaunchedEffect(artistName) {
        val fetched = OnlineArtistImageFetcher.getArtistImageUrl(context, artistName)
        if (!fetched.isNullOrBlank()) {
            imageUrl = fetched
        }
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(BentoCardDarkPurple),
        contentAlignment = Alignment.Center
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = artistName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val initial = artistName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
            if (initial.isNotBlank() && initial != "<" && initial != "?") {
                Text(
                    text = initial,
                    color = BentoPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = artistName,
                    tint = BentoPrimary,
                    modifier = Modifier.fillMaxSize(0.5f)
                )
            }
        }
    }
}

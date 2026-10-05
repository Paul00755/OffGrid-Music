package com.example.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FeaturedPlayList
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material.icons.filled.Waves
import androidx.compose.ui.graphics.vector.ImageVector

enum class HomeScreenLayout(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val icon: ImageVector
) {
    BENTO_DYNAMIC(
        id = "BENTO_DYNAMIC",
        displayName = "Bento Mosaic",
        subtitle = "Dynamic modular tiles, stats bento & quick shortcuts",
        icon = Icons.Default.Dashboard
    ),
    HERO_SPOTLIGHT(
        id = "HERO_SPOTLIGHT",
        displayName = "Hero Spotlight",
        subtitle = "Grand glowing album hero banner & magazine stream",
        icon = Icons.Default.Star
    ),
    MINIMAL_FEED(
        id = "MINIMAL_FEED",
        displayName = "Minimal Stream",
        subtitle = "Ultra-clean typography & high-density quick feed",
        icon = Icons.Default.ViewStream
    ),
    STUDIO_DASHBOARD(
        id = "STUDIO_DASHBOARD",
        displayName = "Studio Deck",
        subtitle = "Live waveform monitor, EQ dials & audio telemetry",
        icon = Icons.Default.Equalizer
    );

    companion object {
        fun fromId(id: String?): HomeScreenLayout {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BENTO_DYNAMIC
        }
    }
}

enum class NowPlayingLayout(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val icon: ImageVector
) {
    MODERN_BENTO(
        id = "MODERN_BENTO",
        displayName = "Modern Bento",
        subtitle = "Rich card hierarchy, waveform seeker & tactile pills",
        icon = Icons.Default.MusicNote
    ),
    VINYL_TURNTABLE(
        id = "VINYL_TURNTABLE",
        displayName = "Vinyl Turntable",
        subtitle = "Spinning vinyl record disk, tonearm & vintage glow",
        icon = Icons.Default.Radio
    ),
    MINIMAL_GLASS(
        id = "MINIMAL_GLASS",
        displayName = "Minimalist Glass",
        subtitle = "Edge-to-edge full artwork & frosted floating pill",
        icon = Icons.Default.ViewAgenda
    ),
    NEON_WAVEFORM(
        id = "NEON_WAVEFORM",
        displayName = "Neon Studio",
        subtitle = "Multi-band spectrum visualizer & cyber glowing deck",
        icon = Icons.Default.Waves
    );

    companion object {
        fun fromId(id: String?): NowPlayingLayout {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: MODERN_BENTO
        }
    }
}

enum class PlaylistsLayout(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val icon: ImageVector
) {
    BENTO_GRID(
        id = "BENTO_GRID",
        displayName = "Bento Grid",
        subtitle = "Modular 2-column cards with mosaic art & count badges",
        icon = Icons.Default.GridView
    ),
    MAGAZINE_HERO(
        id = "MAGAZINE_HERO",
        displayName = "Magazine Hero",
        subtitle = "Immersive top spotlight hero & grand editorial cards",
        icon = Icons.Default.FeaturedPlayList
    ),
    COMPACT_LIST(
        id = "COMPACT_LIST",
        displayName = "Compact List",
        subtitle = "High-density quick list with fast-scroll & instant play",
        icon = Icons.Default.ViewList
    ),
    GLOW_CARDS(
        id = "GLOW_CARDS",
        displayName = "Neon Deck",
        subtitle = "Frosted cards with glowing borders & vibrant tags",
        icon = Icons.Default.LibraryMusic
    );

    companion object {
        fun fromId(id: String?): PlaylistsLayout {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GLOW_CARDS
        }
    }
}

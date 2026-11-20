package com.example.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * Global configuration for the app-wide Gradient & Frosted Glass Effect.
 * Reflects the active song's artwork color or the selected UI theme palette,
 * while guaranteeing high text & artwork legibility in both dark and light modes.
 */
data class GlassThemeConfig(
    val enabled: Boolean = false,
    val gradientEnabled: Boolean = enabled,
    val glassEnabled: Boolean = enabled,
    val tintColor: Color = Color(0xFFF6A88E),
    val secondaryTintColor: Color = Color(0xFFC5A8FA),
    val isDark: Boolean = false,
    val solidBackground: Color = Color(0xFFFBF7F4),
    val solidSurface: Color = Color(0xFFFFFCFA),
    val solidSurfaceVariant: Color = Color(0xFFF4ECE6),
    val useDarkColors: Boolean = false,
    val isDynamicArtworkEnabled: Boolean = false,
    val isCustomDarkAccent: Boolean = false
)

val LocalGlassThemeConfig = staticCompositionLocalOf { GlassThemeConfig() }

/**
 * Shapes any color into a soft, airy pastel background tint (high brightness, subtle saturation),
 * gently shifting warm orange/brown hues toward peach-blush and yellow-olive hues toward fresh cream/sage
 * so dynamic artwork themes always look luminous, clean, and pastel rather than dark or muddy.
 */
fun Color.toPastelBackground(saturationTarget: Float = 0.16f, valueTarget: Float = 0.96f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    if (hsv[0] in 20f..48f) {
        hsv[0] = (hsv[0] - 6f).coerceAtLeast(12f)
    } else if (hsv[0] in 50f..86f) {
        hsv[1] = hsv[1] * 0.68f
    }
    hsv[1] = hsv[1].coerceIn(0.08f, saturationTarget)
    hsv[2] = valueTarget.coerceIn(0.86f, 0.99f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

fun Color.toPastelSurface(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    if (hsv[0] in 20f..48f) {
        hsv[0] = (hsv[0] - 6f).coerceAtLeast(12f)
    }
    hsv[1] = hsv[1].coerceIn(0.04f, 0.08f)
    hsv[2] = 0.99f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

fun Color.toPastelSurfaceVariant(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    if (hsv[0] in 20f..48f) {
        hsv[0] = (hsv[0] - 6f).coerceAtLeast(12f)
    }
    hsv[1] = hsv[1].coerceIn(0.11f, 0.19f)
    hsv[2] = 0.935f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

fun Color.toPastelAccent(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    if (hsv[0] in 20f..48f) {
        hsv[0] = (hsv[0] - 6f).coerceAtLeast(14f)
    }
    hsv[1] = hsv[1].coerceIn(0.34f, 0.48f)
    hsv[2] = hsv[2].coerceIn(0.84f, 0.92f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

fun Color.toPastelCompanionHue(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    val shift = if (hsv[0] in 15f..95f) -24f else 28f
    hsv[0] = (hsv[0] + shift + 360f) % 360f
    hsv[1] = hsv[1].coerceIn(0.22f, 0.38f)
    hsv[2] = hsv[2].coerceIn(0.88f, 0.95f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

fun Color.toReadableLightAccent(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    if (hsv[0] in 20f..48f) {
        hsv[0] = (hsv[0] - 6f).coerceAtLeast(14f)
    }
    hsv[1] = hsv[1].coerceIn(0.46f, 0.64f)
    hsv[2] = hsv[2].coerceIn(0.56f, 0.72f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Returns true when a user-configured RGB or preset accent is intentionally a dark color
 * (e.g., Dark Red, Dark Purple, Midnight Blue, Forest Green) so the theme engine preserves
 * its rich dark tone instead of washing it out into a pale pastel.
 */
fun Color.isExplicitDarkColor(): Boolean {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    return hsv[2] < 0.62f
}

/**
 * Shapes any color (including bright artwork or preset colors when "Use dark colors" is ON)
 * into a rich, saturated dark jewel accent. When [preserveExactIfAlreadyDark] is true and
 * the input is already an explicit dark user color, keeps the user's chosen RGB shade intact
 * with only a gentle visibility floor so buttons and icons stay clear on OLED black.
 */
fun Color.toRichDarkAccent(preserveExactIfAlreadyDark: Boolean = false): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    if (preserveExactIfAlreadyDark && hsv[2] < 0.62f) {
        hsv[2] = hsv[2].coerceAtLeast(0.24f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
    hsv[1] = hsv[1].coerceIn(0.58f, 0.90f)
    hsv[2] = hsv[2].coerceIn(0.36f, 0.54f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Companion hue for rich dark color themes & gradients.
 */
fun Color.toRichDarkCompanionHue(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    val shift = if (hsv[0] in 15f..95f) -26f else 32f
    hsv[0] = (hsv[0] + shift + 360f) % 360f
    hsv[1] = hsv[1].coerceIn(0.48f, 0.82f)
    hsv[2] = hsv[2].coerceIn(0.26f, 0.44f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

fun Color.toAdaptiveThemeAccent(
    useDarkColors: Boolean,
    preserveExactDark: Boolean = false
): Color {
    return if (useDarkColors || (preserveExactDark && isExplicitDarkColor())) {
        toRichDarkAccent(preserveExactIfAlreadyDark = preserveExactDark)
    } else {
        toPastelAccent()
    }
}

fun Color.toAdaptiveCompanionHue(
    useDarkColors: Boolean,
    preserveExactDark: Boolean = false
): Color {
    return if (useDarkColors || (preserveExactDark && isExplicitDarkColor())) {
        toRichDarkCompanionHue()
    } else {
        toPastelCompanionHue()
    }
}

/**
 * Full-screen production-grade ambient Gradient backdrop.
 * Honors "Use dark colors" (deep rich dark tones) vs Palette Lite (soft pastel tones),
 * while respecting Dark, AMOLED, and Light modes with zero overdraw when effects are off.
 */
@Composable
fun GlassGradientBackdrop(
    modifier: Modifier = Modifier,
    tintOverride: Color? = null,
    secondaryOverride: Color? = null
) {
    val glassConfig = LocalGlassThemeConfig.current
    val baseBg = glassConfig.solidBackground
    val primaryTint = tintOverride ?: glassConfig.tintColor
    val secondaryTint = secondaryOverride ?: glassConfig.secondaryTintColor
    val isDark = glassConfig.isDark
    val gradientActive = glassConfig.gradientEnabled
    val glassActive = glassConfig.glassEnabled
    val useRichDark = glassConfig.useDarkColors || glassConfig.isCustomDarkAccent
    val preserveExactDark = glassConfig.isCustomDarkAccent

    if (!gradientActive && !glassActive) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(baseBg)
        )
        return
    }

    val verticalWash = remember(baseBg, primaryTint, secondaryTint, isDark, gradientActive, useRichDark, preserveExactDark) {
        if (useRichDark) {
            val richTop = primaryTint.toRichDarkAccent(preserveExactIfAlreadyDark = preserveExactDark)
            val richMid = secondaryTint.toRichDarkCompanionHue()
            if (isDark) {
                val topAlpha = if (gradientActive) 0.40f else 0.16f
                val midAlpha = if (gradientActive) 0.22f else 0.08f
                val lowAlpha = if (gradientActive) 0.16f else 0.06f
                Brush.verticalGradient(
                    0.0f to richTop.copy(alpha = topAlpha),
                    0.40f to richMid.copy(alpha = midAlpha),
                    0.76f to Color.Transparent,
                    1.0f to richTop.copy(alpha = lowAlpha)
                )
            } else {
                val topAlpha = if (gradientActive) 0.24f else 0.12f
                val midAlpha = if (gradientActive) 0.13f else 0.06f
                val lowAlpha = if (gradientActive) 0.10f else 0.05f
                Brush.verticalGradient(
                    0.0f to lerp(baseBg, richTop, topAlpha),
                    0.44f to lerp(baseBg, richMid, midAlpha),
                    0.80f to baseBg,
                    1.0f to lerp(baseBg, richTop, lowAlpha)
                )
            }
        } else {
            val pastelTop = primaryTint.toPastelAccent()
            val pastelMid = secondaryTint.toPastelCompanionHue()
            if (isDark) {
                val topAlpha = if (gradientActive) 0.20f else 0.09f
                val midAlpha = if (gradientActive) 0.10f else 0.04f
                val lowAlpha = if (gradientActive) 0.08f else 0.03f
                Brush.verticalGradient(
                    0.0f to pastelTop.copy(alpha = topAlpha),
                    0.38f to pastelMid.copy(alpha = midAlpha),
                    0.72f to Color.Transparent,
                    1.0f to pastelTop.copy(alpha = lowAlpha)
                )
            } else {
                val topGlow = primaryTint.toPastelBackground(
                    saturationTarget = if (gradientActive) 0.24f else 0.18f,
                    valueTarget = 0.94f
                )
                val midGlow = secondaryTint.toPastelBackground(
                    saturationTarget = if (gradientActive) 0.18f else 0.14f,
                    valueTarget = 0.96f
                )
                val lowerGlow = primaryTint.toPastelBackground(
                    saturationTarget = if (gradientActive) 0.14f else 0.10f,
                    valueTarget = 0.975f
                )
                Brush.verticalGradient(
                    0.0f to topGlow,
                    0.44f to midGlow,
                    0.78f to baseBg,
                    1.0f to lowerGlow
                )
            }
        }
    }

    val diagonalAurora = remember(primaryTint, secondaryTint, isDark, gradientActive, useRichDark, preserveExactDark) {
        val alphaScale = if (gradientActive) 1.0f else 0.50f
        val topTone = primaryTint.toAdaptiveThemeAccent(useDarkColors = useRichDark, preserveExactDark = preserveExactDark)
        val secTone = secondaryTint.toAdaptiveCompanionHue(useDarkColors = useRichDark, preserveExactDark = preserveExactDark)
        val topMult = if (useRichDark) (if (isDark) 0.22f else 0.14f) else (if (isDark) 0.13f else 0.15f)
        val midMult = if (useRichDark) (if (isDark) 0.14f else 0.10f) else (if (isDark) 0.09f else 0.12f)
        val lowMult = if (useRichDark) (if (isDark) 0.11f else 0.08f) else (if (isDark) 0.07f else 0.09f)
        Brush.linearGradient(
            colors = listOf(
                topTone.copy(alpha = topMult * alphaScale),
                Color.Transparent,
                secTone.copy(alpha = midMult * alphaScale),
                topTone.copy(alpha = lowMult * alphaScale)
            ),
            start = Offset(0f, 0f),
            end = Offset(1400f, 2400f)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseBg)
            .background(verticalWash)
            .background(diagonalAurora)
    )
}

enum class AppUiTheme(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val isDark: Boolean,
    val supportedModes: List<String>,
    val defaultMode: String,
    val previewBg: Color,
    val previewSurface: Color,
    val previewPrimary: Color,
    val previewAccent: Color
) {
    MIDNIGHT_OBSIDIAN(
        id = "MIDNIGHT_OBSIDIAN",
        displayName = "Midnight Obsidian",
        subtitle = "Cosmic slate & electric cyan glow",
        isDark = true,
        supportedModes = listOf("DARK", "LIGHT", "AMOLED", "SYSTEM"),
        defaultMode = "DARK",
        previewBg = Color(0xFF0D0D15),
        previewSurface = Color(0xFF161622),
        previewPrimary = Color(0xFF80D8FF),
        previewAccent = Color(0xFF7C4DFF)
    ),
    PURE_AMOLED(
        id = "PURE_AMOLED",
        displayName = "Pure AMOLED",
        subtitle = "True pitch black & vibrant neon cyan",
        isDark = true,
        supportedModes = listOf("AMOLED", "DARK", "LIGHT", "SYSTEM"),
        defaultMode = "AMOLED",
        previewBg = Color(0xFF000000),
        previewSurface = Color(0xFF0C0C10),
        previewPrimary = Color(0xFF00F0FF),
        previewAccent = Color(0xFFFF4081)
    ),
    CYBERPUNK_NEON(
        id = "CYBERPUNK_NEON",
        displayName = "Cyberpunk Synth",
        subtitle = "Neon indigo & glowing hot pink",
        isDark = true,
        supportedModes = listOf("DARK", "LIGHT", "AMOLED", "SYSTEM"),
        defaultMode = "DARK",
        previewBg = Color(0xFF0B071A),
        previewSurface = Color(0xFF170E30),
        previewPrimary = Color(0xFFFF007F),
        previewAccent = Color(0xFF00F5D4)
    ),
    NORDIC_FROST(
        id = "NORDIC_FROST",
        displayName = "Nordic Frost",
        subtitle = "Arctic deep ocean & glacial teal",
        isDark = true,
        supportedModes = listOf("DARK", "LIGHT", "AMOLED", "SYSTEM"),
        defaultMode = "DARK",
        previewBg = Color(0xFF081420),
        previewSurface = Color(0xFF102334),
        previewPrimary = Color(0xFF00E5FF),
        previewAccent = Color(0xFF64B5F6)
    ),
    EMERALD_FOREST(
        id = "EMERALD_FOREST",
        displayName = "Emerald Forest",
        subtitle = "Deep moss & electric spring mint",
        isDark = true,
        supportedModes = listOf("DARK", "LIGHT", "AMOLED", "SYSTEM"),
        defaultMode = "DARK",
        previewBg = Color(0xFF07160D),
        previewSurface = Color(0xFF0F2618),
        previewPrimary = Color(0xFF00E676),
        previewAccent = Color(0xFF69F0AE)
    ),
    SUNSET_AMBER(
        id = "SUNSET_AMBER",
        displayName = "Sunset Amber",
        subtitle = "Espresso embers & fiery coral",
        isDark = true,
        supportedModes = listOf("DARK", "LIGHT", "AMOLED", "SYSTEM"),
        defaultMode = "DARK",
        previewBg = Color(0xFF160D09),
        previewSurface = Color(0xFF261611),
        previewPrimary = Color(0xFFFF9100),
        previewAccent = Color(0xFFFF5722)
    ),
    CRIMSON_VELVET(
        id = "CRIMSON_VELVET",
        displayName = "Crimson Velvet",
        subtitle = "Imperial wine & ruby red",
        isDark = true,
        supportedModes = listOf("DARK", "LIGHT", "AMOLED", "SYSTEM"),
        defaultMode = "DARK",
        previewBg = Color(0xFF16060B),
        previewSurface = Color(0xFF260C14),
        previewPrimary = Color(0xFFFF1744),
        previewAccent = Color(0xFFFF5252)
    ),
    ROYAL_AMETHYST(
        id = "ROYAL_AMETHYST",
        displayName = "Royal Amethyst",
        subtitle = "Midnight plum & lavender glow",
        isDark = true,
        supportedModes = listOf("DARK", "LIGHT", "AMOLED", "SYSTEM"),
        defaultMode = "DARK",
        previewBg = Color(0xFF0F0A1C),
        previewSurface = Color(0xFF1E1335),
        previewPrimary = Color(0xFFBB86FC),
        previewAccent = Color(0xFFE040FB)
    ),
    PORCELAIN_SLATE(
        id = "PORCELAIN_SLATE",
        displayName = "Porcelain Slate",
        subtitle = "Clean modern mist & vibrant indigo",
        isDark = false,
        supportedModes = listOf("LIGHT", "DARK", "AMOLED", "SYSTEM"),
        defaultMode = "LIGHT",
        previewBg = Color(0xFFF3F4F9),
        previewSurface = Color(0xFFFFFFFF),
        previewPrimary = Color(0xFF4F46E5),
        previewAccent = Color(0xFF06B6D4)
    ),
    WARM_PARCHMENT(
        id = "WARM_PARCHMENT",
        displayName = "Warm Parchment",
        subtitle = "Retro paper & caramel terracotta",
        isDark = false,
        supportedModes = listOf("LIGHT", "DARK", "AMOLED", "SYSTEM"),
        defaultMode = "LIGHT",
        previewBg = Color(0xFFFAF6EE),
        previewSurface = Color(0xFFFFFFFF),
        previewPrimary = Color(0xFFD97706),
        previewAccent = Color(0xFFEA580C)
    );

    companion object {
        fun fromId(id: String?): AppUiTheme {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: MIDNIGHT_OBSIDIAN
        }

        fun isThemeCompatibleWith(theme: AppUiTheme, mode: String, isSystemDark: Boolean = false): Boolean {
            val effDark = when (mode.uppercase()) {
                "DARK" -> true
                "AMOLED" -> true
                "LIGHT" -> false
                "SYSTEM" -> isSystemDark
                else -> true
            }
            return if (mode.uppercase() == "AMOLED") {
                theme.id == "PURE_AMOLED" || (theme.isDark && theme.supportedModes.contains("AMOLED"))
            } else if (effDark) {
                theme.isDark
            } else {
                !theme.isDark
            }
        }

        fun getCompatibleThemes(mode: String, isSystemDark: Boolean = false): List<AppUiTheme> {
            val effDark = when (mode.uppercase()) {
                "DARK" -> true
                "AMOLED" -> true
                "LIGHT" -> false
                "SYSTEM" -> isSystemDark
                else -> true
            }
            return if (mode.uppercase() == "AMOLED") {
                entries.filter { it.id == "PURE_AMOLED" || (it.isDark && it.supportedModes.contains("AMOLED")) }
            } else if (effDark) {
                entries.filter { it.isDark }
            } else {
                entries.filter { !it.isDark }
            }
        }

        fun getDefaultThemeForMode(mode: String, isSystemDark: Boolean = false): AppUiTheme {
            return when (mode.uppercase()) {
                "AMOLED" -> PURE_AMOLED
                "LIGHT" -> PORCELAIN_SLATE
                "DARK" -> MIDNIGHT_OBSIDIAN
                "SYSTEM" -> if (isSystemDark) MIDNIGHT_OBSIDIAN else PORCELAIN_SLATE
                else -> MIDNIGHT_OBSIDIAN
            }
        }
    }
}

@Composable
fun MyApplicationTheme(
    uiTheme: String = "MIDNIGHT_OBSIDIAN",
    themeMode: String = "DARK",
    accentColorPreset: String = "ORANGE",
    accentColor: Color? = null,
    isGradientGlassEffect: Boolean = false,
    isGradientEffect: Boolean = isGradientGlassEffect,
    isGlassEffect: Boolean = isGradientGlassEffect,
    dynamicArtworkColor: Color? = null,
    useDarkColors: Boolean = false,
    isDynamicArtworkEnabled: Boolean = (dynamicArtworkColor != null),
    content: @Composable () -> Unit
) {
    val isSystemInDark = isSystemInDarkTheme()
    val rawTheme = AppUiTheme.fromId(uiTheme)
    val activeTheme = if (AppUiTheme.isThemeCompatibleWith(rawTheme, themeMode, isSystemInDark)) {
        rawTheme
    } else {
        AppUiTheme.getDefaultThemeForMode(themeMode, isSystemInDark)
    }
    val effectiveMode = themeMode.uppercase()
    val isDark = when (effectiveMode) {
        "AMOLED", "DARK" -> true
        "LIGHT" -> false
        "SYSTEM" -> isSystemInDark
        else -> activeTheme.isDark
    }

    // AMOLED is the single mode that selects true-black surfaces.
    val isExtraDark = isDark && effectiveMode == "AMOLED"

    val presetUpper = accentColorPreset.uppercase()
    val primaryColor = accentColor ?: when (presetUpper) {
        "PINK" -> if (isDark) Color(0xFFFF4081) else Color(0xFFD81B60)
        "GREEN" -> if (isDark) Color(0xFF00E676) else Color(0xFF2E7D32)
        "ORANGE" -> if (isDark) Color(0xFFFF9100) else Color(0xFFE65100)
        "GOLD" -> Color(0xFFFFD700)
        "BLUE" -> Color(0xFF2979FF)
        "RED" -> Color(0xFFFF5252)
        "TEAL" -> Color(0xFF1DE9B6)
        "INDIGO" -> Color(0xFF7C4DFF)
        "PURPLE" -> if (isDark) Color(0xFFBB86FC) else Color(0xFF7B1FA2)
        "CYAN" -> if (isDark) Color(0xFF00E5FF) else Color(0xFF0097A7)
        "DARK_RED" -> Color(0xFF8B1A1A)
        "DARK_PURPLE" -> Color(0xFF4A148C)
        "DARK_BLUE" -> Color(0xFF1A3A7A)
        "DARK_GREEN" -> Color(0xFF1B5E20)
        "DARK_TEAL" -> Color(0xFF004D40)
        "DARK_AMBER" -> Color(0xFF7F3300)
        else -> if (isDark) Color(0xFFFF9100) else Color(0xFFE65100)
    }

    val highContrastOnPrimary = if (primaryColor.luminance() > 0.42f) Color(0xFF08080C) else Color.White

    val baseColorScheme = if (isDark) {
        when (activeTheme) {
            AppUiTheme.MIDNIGHT_OBSIDIAN -> darkColorScheme(
                primary = primaryColor,
                onPrimary = highContrastOnPrimary,
                primaryContainer = primaryColor.copy(alpha = 0.25f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFFB8C1FF),
                background = if (isExtraDark) Color(0xFF000000) else Color(0xFF020306),
                surface = if (isExtraDark) Color(0xFF050508) else Color(0xFF08090E),
                onBackground = Color.White,
                onSurface = Color.White,
                surfaceVariant = if (isExtraDark) Color(0xFF0C0C12) else Color(0xFF11121A),
                onSurfaceVariant = if (isExtraDark) Color(0xFFD2D6E6) else Color(0xFFB4B8CA),
                outline = if (isExtraDark) Color(0xFF20222E) else Color(0xFF232534)
            )
            AppUiTheme.PURE_AMOLED -> darkColorScheme(
                primary = primaryColor,
                onPrimary = highContrastOnPrimary,
                primaryContainer = primaryColor.copy(alpha = 0.24f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFFA6EAF2),
                background = Color(0xFF000000),
                surface = Color(0xFF040407),
                onBackground = Color.White,
                onSurface = Color.White,
                surfaceVariant = Color(0xFF0B0B10),
                onSurfaceVariant = Color(0xFFD2D6E6),
                outline = Color(0xFF20222E)
            )
            AppUiTheme.CYBERPUNK_NEON -> darkColorScheme(
                primary = primaryColor,
                onPrimary = highContrastOnPrimary,
                primaryContainer = primaryColor.copy(alpha = 0.25f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFF8FF7E8),
                background = if (isExtraDark) Color(0xFF000000) else Color(0xFF030208),
                surface = if (isExtraDark) Color(0xFF06040C) else Color(0xFF0A0614),
                onBackground = Color(0xFFFBFBFF),
                onSurface = Color(0xFFFBFBFF),
                surfaceVariant = if (isExtraDark) Color(0xFF0F0A1C) else Color(0xFF140D24),
                onSurfaceVariant = if (isExtraDark) Color(0xFFD7C8F5) else Color(0xFFC2AFE8),
                outline = if (isExtraDark) Color(0xFF281A45) else Color(0xFF2D1B4D)
            )
            AppUiTheme.NORDIC_FROST -> darkColorScheme(
                primary = primaryColor,
                onPrimary = highContrastOnPrimary,
                primaryContainer = primaryColor.copy(alpha = 0.22f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFFA2E6F2),
                background = if (isExtraDark) Color(0xFF000000) else Color(0xFF02060A),
                surface = if (isExtraDark) Color(0xFF04080D) else Color(0xFF070F17),
                onBackground = Color(0xFFF0F8FF),
                onSurface = Color(0xFFF0F8FF),
                surfaceVariant = if (isExtraDark) Color(0xFF0A121C) else Color(0xFF0E1B28),
                onSurfaceVariant = if (isExtraDark) Color(0xFFC5D8E6) else Color(0xFFA8C0D0),
                outline = if (isExtraDark) Color(0xFF1A2E40) else Color(0xFF1D3448)
            )
            AppUiTheme.EMERALD_FOREST -> darkColorScheme(
                primary = primaryColor,
                onPrimary = highContrastOnPrimary,
                primaryContainer = primaryColor.copy(alpha = 0.22f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFFA5E6B8),
                background = if (isExtraDark) Color(0xFF000000) else Color(0xFF020704),
                surface = if (isExtraDark) Color(0xFF040A06) else Color(0xFF07120B),
                onBackground = Color(0xFFF1FBF4),
                onSurface = Color(0xFFF1FBF4),
                surfaceVariant = if (isExtraDark) Color(0xFF09140E) else Color(0xFF0E1F15),
                onSurfaceVariant = if (isExtraDark) Color(0xFFC4DEC9) else Color(0xFFAED0B6),
                outline = if (isExtraDark) Color(0xFF183022) else Color(0xFF1A3826)
            )
            AppUiTheme.SUNSET_AMBER -> darkColorScheme(
                primary = primaryColor,
                onPrimary = highContrastOnPrimary,
                primaryContainer = primaryColor.copy(alpha = 0.24f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFFFFCF99),
                background = if (isExtraDark) Color(0xFF000000) else Color(0xFF070403),
                surface = if (isExtraDark) Color(0xFF0A0605) else Color(0xFF110A08),
                onBackground = Color(0xFFFFF7F2),
                onSurface = Color(0xFFFFF7F2),
                surfaceVariant = if (isExtraDark) Color(0xFF140D0A) else Color(0xFF1C120E),
                onSurfaceVariant = if (isExtraDark) Color(0xFFE0CFC8) else Color(0xFFD0BCB3),
                outline = if (isExtraDark) Color(0xFF2E1B15) else Color(0xFF362018)
            )
            AppUiTheme.CRIMSON_VELVET -> darkColorScheme(
                primary = primaryColor,
                onPrimary = highContrastOnPrimary,
                primaryContainer = primaryColor.copy(alpha = 0.25f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFFFFA8C5),
                background = if (isExtraDark) Color(0xFF000000) else Color(0xFF070204),
                surface = if (isExtraDark) Color(0xFF0A0406) else Color(0xFF12060A),
                onBackground = Color(0xFFFFF5F7),
                onSurface = Color(0xFFFFF5F7),
                surfaceVariant = if (isExtraDark) Color(0xFF14080C) else Color(0xFF1E0B12),
                onSurfaceVariant = if (isExtraDark) Color(0xFFE5C5CE) else Color(0xFFD4B0BA),
                outline = if (isExtraDark) Color(0xFF33141E) else Color(0xFF3B1722)
            )
            AppUiTheme.ROYAL_AMETHYST -> darkColorScheme(
                primary = primaryColor,
                onPrimary = highContrastOnPrimary,
                primaryContainer = primaryColor.copy(alpha = 0.25f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFFD9C7FA),
                background = if (isExtraDark) Color(0xFF000000) else Color(0xFF040208),
                surface = if (isExtraDark) Color(0xFF07050D) else Color(0xFF0C0816),
                onBackground = Color(0xFFFAF5FF),
                onSurface = Color(0xFFFAF5FF),
                surfaceVariant = if (isExtraDark) Color(0xFF110B1E) else Color(0xFF171029),
                onSurfaceVariant = if (isExtraDark) Color(0xFFD8CAF2) else Color(0xFFC5B4E8),
                outline = if (isExtraDark) Color(0xFF281B42) else Color(0xFF2D1E4A)
            )
            AppUiTheme.PORCELAIN_SLATE -> darkColorScheme(
                primary = primaryColor,
                onPrimary = highContrastOnPrimary,
                primaryContainer = primaryColor.copy(alpha = 0.22f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFFB4C2D8),
                background = if (isExtraDark) Color(0xFF000000) else Color(0xFF030509),
                surface = if (isExtraDark) Color(0xFF06080E) else Color(0xFF0A0E17),
                onBackground = Color(0xFFF8FAFC),
                onSurface = Color(0xFFF8FAFC),
                surfaceVariant = if (isExtraDark) Color(0xFF0E131E) else Color(0xFF131B2B),
                onSurfaceVariant = if (isExtraDark) Color(0xFFCBD5E1) else Color(0xFFB0BDD0),
                outline = if (isExtraDark) Color(0xFF202B3D) else Color(0xFF243247)
            )
            AppUiTheme.WARM_PARCHMENT -> darkColorScheme(
                primary = primaryColor,
                onPrimary = highContrastOnPrimary,
                primaryContainer = primaryColor.copy(alpha = 0.22f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFFE6CEAE),
                background = if (isExtraDark) Color(0xFF000000) else Color(0xFF060402),
                surface = if (isExtraDark) Color(0xFF0A0704) else Color(0xFF110C08),
                onBackground = Color(0xFFFFF8ED),
                onSurface = Color(0xFFFFF8ED),
                surfaceVariant = if (isExtraDark) Color(0xFF140F0A) else Color(0xFF1C150E),
                onSurfaceVariant = if (isExtraDark) Color(0xFFE5D2B8) else Color(0xFFD6C0A4),
                outline = if (isExtraDark) Color(0xFF2E2216) else Color(0xFF36281A)
            )
        }
    } else {
        when (activeTheme) {
            AppUiTheme.MIDNIGHT_OBSIDIAN -> lightColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.16f),
                onPrimaryContainer = Color(0xFF0F172A),
                secondary = Color(0xFF475559),
                background = Color(0xFFF1F5F9),
                surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF0F172A),
                onSurface = Color(0xFF0F172A),
                surfaceVariant = Color(0xFFE2E8F0),
                onSurfaceVariant = Color(0xFF334155),
                outline = Color(0xFFCBD5E1)
            )
            AppUiTheme.PURE_AMOLED -> lightColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.15f),
                onPrimaryContainer = Color(0xFF000000),
                secondary = Color(0xFF424242),
                background = Color(0xFFFFFFFF),
                surface = Color(0xFFF8F9FA),
                onBackground = Color(0xFF000000),
                onSurface = Color(0xFF000000),
                surfaceVariant = Color(0xFFEEEEEE),
                onSurfaceVariant = Color(0xFF212121),
                outline = Color(0xFFBDBDBD)
            )
            AppUiTheme.CYBERPUNK_NEON -> lightColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.18f),
                onPrimaryContainer = Color(0xFF581C87),
                secondary = Color(0xFF00B4D8),
                background = Color(0xFFFAF5FF),
                surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF1E1B4B),
                onSurface = Color(0xFF1E1B4B),
                surfaceVariant = Color(0xFFF3E8FF),
                onSurfaceVariant = Color(0xFF581C87),
                outline = Color(0xFFD8B4FE)
            )
            AppUiTheme.NORDIC_FROST -> lightColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.16f),
                onPrimaryContainer = Color(0xFF0C4A6E),
                secondary = Color(0xFF0284C7),
                background = Color(0xFFF0F9FF),
                surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF082F49),
                onSurface = Color(0xFF082F49),
                surfaceVariant = Color(0xFFE0F2FE),
                onSurfaceVariant = Color(0xFF0369A1),
                outline = Color(0xFFBAE6FD)
            )
            AppUiTheme.EMERALD_FOREST -> lightColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.18f),
                onPrimaryContainer = Color(0xFF14532D),
                secondary = Color(0xFF16A34A),
                background = Color(0xFFF0FDF4),
                surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF052E16),
                onSurface = Color(0xFF052E16),
                surfaceVariant = Color(0xFFDCFCE7),
                onSurfaceVariant = Color(0xFF15803D),
                outline = Color(0xFFBBF7D0)
            )
            AppUiTheme.SUNSET_AMBER -> lightColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.18f),
                onPrimaryContainer = Color(0xFF78350F),
                secondary = Color(0xFFD97706),
                background = Color(0xFFFFFBEB),
                surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF451A03),
                onSurface = Color(0xFF451A03),
                surfaceVariant = Color(0xFFFEF3C7),
                onSurfaceVariant = Color(0xFF92400E),
                outline = Color(0xFFFDE68A)
            )
            AppUiTheme.CRIMSON_VELVET -> lightColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.18f),
                onPrimaryContainer = Color(0xFF881337),
                secondary = Color(0xFFE11D48),
                background = Color(0xFFFFF1F2),
                surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF4C0519),
                onSurface = Color(0xFF4C0519),
                surfaceVariant = Color(0xFFFFE4E6),
                onSurfaceVariant = Color(0xFF9F1239),
                outline = Color(0xFFFECDD3)
            )
            AppUiTheme.ROYAL_AMETHYST -> lightColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.18f),
                onPrimaryContainer = Color(0xFF3B0764),
                secondary = Color(0xFF7E22CE),
                background = Color(0xFFFAF5FF),
                surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF2E1065),
                onSurface = Color(0xFF2E1065),
                surfaceVariant = Color(0xFFF3E8FF),
                onSurfaceVariant = Color(0xFF6B21A8),
                outline = Color(0xFFE9D5FF)
            )
            AppUiTheme.PORCELAIN_SLATE -> lightColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.15f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFF4B5563),
                background = Color(0xFFF3F4F9),
                surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF111827),
                onSurface = Color(0xFF111827),
                surfaceVariant = Color(0xFFE8EAF3),
                onSurfaceVariant = Color(0xFF4B5563),
                outline = Color(0xFFD1D5DB)
            )
            AppUiTheme.WARM_PARCHMENT -> lightColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.18f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFF78350F),
                background = Color(0xFFFAF6EE),
                surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF291B0F),
                onSurface = Color(0xFF291B0F),
                surfaceVariant = Color(0xFFF2EADC),
                onSurfaceVariant = Color(0xFF5F432A),
                outline = Color(0xFFE5D9C4)
            )
        }
    }

    // Determine whether the user explicitly picked a dark preset / dark RGB color, or enabled global "Use dark colors".
    val isCustomDarkAccent = !isDynamicArtworkEnabled && (
        presetUpper.startsWith("DARK_") || (accentColor != null && accentColor.isExplicitDarkColor())
    )
    val effectiveUseDarkPalette = useDarkColors || isCustomDarkAccent

    val rawTintSeed = if (isDynamicArtworkEnabled && dynamicArtworkColor != null) {
        dynamicArtworkColor
    } else {
        primaryColor
    }

    val targetGlassTintColor = remember(rawTintSeed, effectiveUseDarkPalette, isCustomDarkAccent) {
        rawTintSeed.toAdaptiveThemeAccent(
            useDarkColors = effectiveUseDarkPalette,
            preserveExactDark = isCustomDarkAccent
        )
    }
    val targetGlassSecondaryTint = remember(rawTintSeed, effectiveUseDarkPalette, isCustomDarkAccent) {
        rawTintSeed.toAdaptiveCompanionHue(
            useDarkColors = effectiveUseDarkPalette,
            preserveExactDark = isCustomDarkAccent
        )
    }

    val glassTintColor by animateColorAsState(
        targetValue = targetGlassTintColor,
        animationSpec = tween(durationMillis = 360),
        label = "theme_primary_tint"
    )
    val glassSecondaryTint by animateColorAsState(
        targetValue = targetGlassSecondaryTint,
        animationSpec = tween(durationMillis = 360),
        label = "theme_secondary_tint"
    )

    // Harmonize surfaces, variants, and outlines with either rich dark colors or palette lite pastel accents.
    val harmonizedColorScheme = remember(
        baseColorScheme,
        glassTintColor,
        glassSecondaryTint,
        isDark,
        isExtraDark,
        effectiveUseDarkPalette
    ) {
        if (isDark) {
            val surfaceBlend = if (isExtraDark) {
                if (effectiveUseDarkPalette) 0.04f else 0.015f
            } else {
                if (effectiveUseDarkPalette) 0.08f else 0.03f
            }
            val variantBlend = if (isExtraDark) {
                if (effectiveUseDarkPalette) 0.08f else 0.035f
            } else {
                if (effectiveUseDarkPalette) 0.14f else 0.06f
            }
            val outlineBlend = if (effectiveUseDarkPalette) 0.48f else 0.30f
            val onAccent = if (glassTintColor.luminance() > 0.40f) Color(0xFF08090D) else Color.White
            val onContainer = if (effectiveUseDarkPalette) Color(0xFFF8FAFC) else glassTintColor
            baseColorScheme.copy(
                primary = glassTintColor,
                onPrimary = onAccent,
                primaryContainer = glassTintColor.copy(alpha = if (effectiveUseDarkPalette) 0.36f else 0.22f),
                onPrimaryContainer = onContainer,
                secondary = glassSecondaryTint,
                surface = lerp(baseColorScheme.surface, glassTintColor, surfaceBlend),
                surfaceVariant = lerp(baseColorScheme.surfaceVariant, glassSecondaryTint, variantBlend),
                outline = lerp(baseColorScheme.outline, glassTintColor, outlineBlend),
                onBackground = Color(0xFFF8FAFC),
                onSurface = Color(0xFFF8FAFC),
                onSurfaceVariant = Color(0xFFD0D5E6)
            )
        } else if (effectiveUseDarkPalette) {
            val primaryInk = Color(0xFF141622)
            val secondaryInk = Color(0xFF42465A)
            val tintedBg = lerp(Color(0xFFF5F4F8), glassTintColor, 0.06f)
            val tintedSurface = lerp(Color(0xFFFFFFFF), glassTintColor, 0.03f)
            val tintedVariant = lerp(Color(0xFFECEAF2), glassSecondaryTint, 0.11f)
            val tintedOutline = lerp(Color(0xFFD5D2DF), glassTintColor, 0.26f)

            baseColorScheme.copy(
                primary = glassTintColor,
                onPrimary = Color.White,
                primaryContainer = glassTintColor.copy(alpha = 0.20f),
                onPrimaryContainer = primaryInk,
                secondary = glassSecondaryTint,
                onSecondary = Color.White,
                secondaryContainer = glassSecondaryTint.copy(alpha = 0.18f),
                onSecondaryContainer = primaryInk,
                background = tintedBg,
                onBackground = primaryInk,
                surface = tintedSurface,
                onSurface = primaryInk,
                surfaceVariant = tintedVariant,
                onSurfaceVariant = secondaryInk,
                outline = tintedOutline
            )
        } else {
            val pastelBg = glassTintColor.toPastelBackground(saturationTarget = 0.15f, valueTarget = 0.965f)
            val pastelSurface = glassTintColor.toPastelSurface()
            val pastelVariant = glassSecondaryTint.toPastelSurfaceVariant()
            val pastelOutline = glassTintColor.toPastelBackground(saturationTarget = 0.24f, valueTarget = 0.85f)
            val readableAccent = glassTintColor.toReadableLightAccent()
            val primaryInk = Color(0xFF141622)
            val secondaryInk = Color(0xFF42465A)

            baseColorScheme.copy(
                primary = readableAccent,
                onPrimary = Color.White,
                primaryContainer = glassTintColor.copy(alpha = 0.28f),
                onPrimaryContainer = primaryInk,
                secondary = glassSecondaryTint.toReadableLightAccent(),
                onSecondary = Color.White,
                secondaryContainer = glassSecondaryTint.copy(alpha = 0.25f),
                onSecondaryContainer = primaryInk,
                background = pastelBg,
                onBackground = primaryInk,
                surface = pastelSurface,
                onSurface = primaryInk,
                surfaceVariant = pastelVariant,
                onSurfaceVariant = secondaryInk,
                outline = pastelOutline
            )
        }
    }

    val anyEffectEnabled = isGradientEffect || isGlassEffect
    val glassConfig = remember(
        anyEffectEnabled,
        isGradientEffect,
        isGlassEffect,
        glassTintColor,
        glassSecondaryTint,
        isDark,
        harmonizedColorScheme.background,
        harmonizedColorScheme.surface,
        harmonizedColorScheme.surfaceVariant,
        useDarkColors,
        isDynamicArtworkEnabled,
        isCustomDarkAccent
    ) {
        GlassThemeConfig(
            enabled = anyEffectEnabled,
            gradientEnabled = isGradientEffect,
            glassEnabled = isGlassEffect,
            tintColor = glassTintColor,
            secondaryTintColor = glassSecondaryTint,
            isDark = isDark,
            solidBackground = harmonizedColorScheme.background,
            solidSurface = harmonizedColorScheme.surface,
            solidSurfaceVariant = harmonizedColorScheme.surfaceVariant,
            useDarkColors = useDarkColors,
            isDynamicArtworkEnabled = isDynamicArtworkEnabled,
            isCustomDarkAccent = isCustomDarkAccent
        )
    }

    CompositionLocalProvider(LocalGlassThemeConfig provides glassConfig) {
        MaterialTheme(
            colorScheme = harmonizedColorScheme,
            typography = Typography,
            content = content
        )
    }
}

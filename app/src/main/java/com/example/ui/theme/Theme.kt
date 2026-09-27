package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    val isAmoled = effectiveMode == "AMOLED"
    val isDark = when (effectiveMode) {
        "AMOLED", "DARK" -> true
        "LIGHT" -> false
        "SYSTEM" -> isSystemInDark
        else -> activeTheme.isDark
    }

    // Resolved primary accent
    val primaryColor = accentColor ?: when (accentColorPreset.uppercase()) {
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
        else -> if (isDark) Color(0xFFFF9100) else Color(0xFFE65100)
    }

    val colorScheme = if (isDark) {
        when (activeTheme) {
            AppUiTheme.MIDNIGHT_OBSIDIAN -> darkColorScheme(
                primary = primaryColor,
                onPrimary = Color(0xFF002733),
                primaryContainer = primaryColor.copy(alpha = 0.25f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFF9FA8DA),
                background = if (isAmoled) Color(0xFF000000) else Color(0xFF0D0D15),
                surface = if (isAmoled) Color(0xFF08080C) else Color(0xFF161622),
                onBackground = Color.White,
                onSurface = Color.White,
                surfaceVariant = if (isAmoled) Color(0xFF121218) else Color(0xFF1E1C2C),
                onSurfaceVariant = Color(0xFFA5A5B8),
                outline = if (isAmoled) Color(0xFF202028) else Color(0xFF2A283C)
            )
            AppUiTheme.PURE_AMOLED -> darkColorScheme(
                primary = primaryColor,
                onPrimary = Color.Black,
                primaryContainer = primaryColor.copy(alpha = 0.22f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFF80DEEA),
                background = Color(0xFF000000),
                surface = Color(0xFF0C0C10),
                onBackground = Color.White,
                onSurface = Color.White,
                surfaceVariant = Color(0xFF14141A),
                onSurfaceVariant = Color(0xFF9E9EA8),
                outline = Color(0xFF22222C)
            )
            AppUiTheme.CYBERPUNK_NEON -> darkColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.25f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFF00F5D4),
                background = if (isAmoled) Color(0xFF000000) else Color(0xFF0B071A),
                surface = if (isAmoled) Color(0xFF0A0614) else Color(0xFF170E30),
                onBackground = Color(0xFFFBFBFF),
                onSurface = Color(0xFFFBFBFF),
                surfaceVariant = if (isAmoled) Color(0xFF180E2E) else Color(0xFF231545),
                onSurfaceVariant = Color(0xFFB39DDB),
                outline = if (isAmoled) Color(0xFF2E1850) else Color(0xFF3C2066)
            )
            AppUiTheme.NORDIC_FROST -> darkColorScheme(
                primary = primaryColor,
                onPrimary = Color(0xFF002028),
                primaryContainer = primaryColor.copy(alpha = 0.22f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFF80DEEA),
                background = if (isAmoled) Color(0xFF000000) else Color(0xFF081420),
                surface = if (isAmoled) Color(0xFF060D14) else Color(0xFF102334),
                onBackground = Color(0xFFF0F8FF),
                onSurface = Color(0xFFF0F8FF),
                surfaceVariant = if (isAmoled) Color(0xFF102030) else Color(0xFF183248),
                onSurfaceVariant = Color(0xFF90A4AE),
                outline = if (isAmoled) Color(0xFF1A364E) else Color(0xFF22445E)
            )
            AppUiTheme.EMERALD_FOREST -> darkColorScheme(
                primary = primaryColor,
                onPrimary = Color(0xFF00240F),
                primaryContainer = primaryColor.copy(alpha = 0.22f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFF81C784),
                background = if (isAmoled) Color(0xFF000000) else Color(0xFF07160D),
                surface = if (isAmoled) Color(0xFF050F09) else Color(0xFF0F2618),
                onBackground = Color(0xFFF1FBF4),
                onSurface = Color(0xFFF1FBF4),
                surfaceVariant = if (isAmoled) Color(0xFF0F2418) else Color(0xFF173623),
                onSurfaceVariant = Color(0xFF9DB8A4),
                outline = if (isAmoled) Color(0xFF183824) else Color(0xFF1F482F)
            )
            AppUiTheme.SUNSET_AMBER -> darkColorScheme(
                primary = primaryColor,
                onPrimary = Color(0xFF331400),
                primaryContainer = primaryColor.copy(alpha = 0.24f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFFFFB74D),
                background = if (isAmoled) Color(0xFF000000) else Color(0xFF160D09),
                surface = if (isAmoled) Color(0xFF100906) else Color(0xFF261611),
                onBackground = Color(0xFFFFF7F2),
                onSurface = Color(0xFFFFF7F2),
                surfaceVariant = if (isAmoled) Color(0xFF22130E) else Color(0xFF382019),
                onSurfaceVariant = Color(0xFFBCAAA4),
                outline = if (isAmoled) Color(0xFF361E17) else Color(0xFF4D2B20)
            )
            AppUiTheme.CRIMSON_VELVET -> darkColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.25f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFFFF80AB),
                background = if (isAmoled) Color(0xFF000000) else Color(0xFF16060B),
                surface = if (isAmoled) Color(0xFF100408) else Color(0xFF260C14),
                onBackground = Color(0xFFFFF5F7),
                onSurface = Color(0xFFFFF5F7),
                surfaceVariant = if (isAmoled) Color(0xFF220812) else Color(0xFF38121E),
                onSurfaceVariant = Color(0xFFC29BA5),
                outline = if (isAmoled) Color(0xFF38141F) else Color(0xFF521C2A)
            )
            AppUiTheme.ROYAL_AMETHYST -> darkColorScheme(
                primary = primaryColor,
                onPrimary = Color(0xFF1E004A),
                primaryContainer = primaryColor.copy(alpha = 0.25f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFFD1C4E9),
                background = if (isAmoled) Color(0xFF000000) else Color(0xFF0F0A1C),
                surface = if (isAmoled) Color(0xFF0A0614) else Color(0xFF1E1335),
                onBackground = Color(0xFFFAF5FF),
                onSurface = Color(0xFFFAF5FF),
                surfaceVariant = if (isAmoled) Color(0xFF1A1030) else Color(0xFF2B1C4A),
                onSurfaceVariant = Color(0xFFB39DDB),
                outline = if (isAmoled) Color(0xFF2C1B46) else Color(0xFF3D2562)
            )
            AppUiTheme.PORCELAIN_SLATE -> darkColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.22f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFF94A3B8),
                background = if (isAmoled) Color(0xFF000000) else Color(0xFF0F172A),
                surface = if (isAmoled) Color(0xFF080D14) else Color(0xFF1E293B),
                onBackground = Color(0xFFF8FAFC),
                onSurface = Color(0xFFF8FAFC),
                surfaceVariant = if (isAmoled) Color(0xFF121B28) else Color(0xFF334155),
                onSurfaceVariant = Color(0xFF94A3B8),
                outline = if (isAmoled) Color(0xFF1E293B) else Color(0xFF475569)
            )
            AppUiTheme.WARM_PARCHMENT -> darkColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.22f),
                onPrimaryContainer = primaryColor,
                secondary = Color(0xFFD4B996),
                background = if (isAmoled) Color(0xFF000000) else Color(0xFF1A130C),
                surface = if (isAmoled) Color(0xFF100B06) else Color(0xFF291E13),
                onBackground = Color(0xFFFFF8ED),
                onSurface = Color(0xFFFFF8ED),
                surfaceVariant = if (isAmoled) Color(0xFF1C130B) else Color(0xFF3B2C1C),
                onSurfaceVariant = Color(0xFFD4B996),
                outline = if (isAmoled) Color(0xFF2E1E10) else Color(0xFF4D3822)
            )
        }
    } else {
        when (activeTheme) {
            AppUiTheme.MIDNIGHT_OBSIDIAN -> lightColorScheme(
                primary = primaryColor,
                onPrimary = Color.White,
                primaryContainer = primaryColor.copy(alpha = 0.16f),
                onPrimaryContainer = Color(0xFF0F172A),
                secondary = Color(0xFF475569),
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}


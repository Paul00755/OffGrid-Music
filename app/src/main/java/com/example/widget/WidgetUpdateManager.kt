package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.service.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.Locale

object WidgetUpdateManager {

    const val ACTION_WIDGET_PLAY_PAUSE = "com.example.widget.ACTION_PLAY_PAUSE"
    const val ACTION_WIDGET_NEXT = "com.example.widget.ACTION_NEXT"
    const val ACTION_WIDGET_PREV = "com.example.widget.ACTION_PREV"
    const val ACTION_WIDGET_FAVORITE = "com.example.widget.ACTION_FAVORITE"

    private val widgetScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var progressJob: Job? = null
    private var lastUpdatedSec = -1L
    @Volatile private var cachedHasAnyWidgets = true
    @Volatile private var lastWidgetIdsCheckTime = 0L

    fun notifyWidgetsChanged() {
        lastWidgetIdsCheckTime = 0L
        cachedHasAnyWidgets = true
    }

    fun updateAllWidgets(context: Context, state: PlaybackState) {
        lastWidgetIdsCheckTime = 0L
        widgetScope.launch {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context) ?: return@launch
                val cardComponent = ComponentName(context, CardMusicWidgetProvider::class.java)
                val wideComponent = ComponentName(context, WideMusicWidgetProvider::class.java)
                val pillComponent = ComponentName(context, PillMusicWidgetProvider::class.java)

                val cardIds = appWidgetManager.getAppWidgetIds(cardComponent) ?: IntArray(0)
                val wideIds = appWidgetManager.getAppWidgetIds(wideComponent) ?: IntArray(0)
                val pillIds = appWidgetManager.getAppWidgetIds(pillComponent) ?: IntArray(0)

                cachedHasAnyWidgets = cardIds.isNotEmpty() || wideIds.isNotEmpty() || pillIds.isNotEmpty()
                lastWidgetIdsCheckTime = System.currentTimeMillis()

                if (!cachedHasAnyWidgets) {
                    return@launch
                }

                val prefsRepo = com.example.data.local.UserPreferencesRepository(context)
                val isDynamic = try { prefsRepo.isDynamicArtworkTheme.first() } catch (_: Exception) { true }
                val customAccent = try { prefsRepo.customAccentColor.first() } catch (_: Exception) { 0 }
                val preset = try { prefsRepo.accentColorPreset.first() } catch (_: Exception) { "ORANGE" }

                val manualAccentColor = if (customAccent != 0) {
                    customAccent
                } else {
                    when (preset.uppercase()) {
                        "PINK" -> android.graphics.Color.parseColor("#FF4081")
                        "GREEN" -> android.graphics.Color.parseColor("#00E676")
                        "ORANGE" -> android.graphics.Color.parseColor("#FF9100")
                        "GOLD" -> android.graphics.Color.parseColor("#FFD700")
                        "BLUE" -> android.graphics.Color.parseColor("#2979FF")
                        "RED" -> android.graphics.Color.parseColor("#FF5252")
                        "TEAL" -> android.graphics.Color.parseColor("#1DE9B6")
                        "INDIGO" -> android.graphics.Color.parseColor("#7C4DFF")
                        "PURPLE" -> android.graphics.Color.parseColor("#BB86FC")
                        "CYAN" -> android.graphics.Color.parseColor("#00E5FF")
                        else -> android.graphics.Color.parseColor("#FF9100")
                    }
                }

                val currentSong = state.currentSong
                val artworkBitmap = WidgetBitmapRenderer.getOrLoadSongBitmap(context, currentSong)
                val colors = WidgetBitmapRenderer.getThemeColors(
                    songBitmap = artworkBitmap,
                    isDynamicTheme = isDynamic,
                    manualAccentColor = manualAccentColor
                )

                if (cardIds.isNotEmpty()) {
                    updateCardWidgets(context, appWidgetManager, cardIds, state, artworkBitmap, colors)
                }
                if (wideIds.isNotEmpty()) {
                    updateWideWidgets(context, appWidgetManager, wideIds, state, artworkBitmap, colors)
                }
                if (pillIds.isNotEmpty()) {
                    updatePillWidgets(context, appWidgetManager, pillIds, state, artworkBitmap, colors)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateWidgetsProgress(context: Context, positionMs: Long, durationMs: Long) {
        val currentSec = (positionMs / 1000).coerceAtLeast(0L)
        if (currentSec == lastUpdatedSec) return
        lastUpdatedSec = currentSec

        val now = System.currentTimeMillis()
        if (!cachedHasAnyWidgets && (now - lastWidgetIdsCheckTime < 15000L)) {
            return
        }

        if (progressJob?.isActive == true) return

        progressJob = widgetScope.launch {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context) ?: return@launch
                val cardComponent = ComponentName(context, CardMusicWidgetProvider::class.java)
                val wideComponent = ComponentName(context, WideMusicWidgetProvider::class.java)
                val pillComponent = ComponentName(context, PillMusicWidgetProvider::class.java)

                val cardIds = appWidgetManager.getAppWidgetIds(cardComponent) ?: IntArray(0)
                val wideIds = appWidgetManager.getAppWidgetIds(wideComponent) ?: IntArray(0)
                val pillIds = appWidgetManager.getAppWidgetIds(pillComponent) ?: IntArray(0)

                cachedHasAnyWidgets = cardIds.isNotEmpty() || wideIds.isNotEmpty() || pillIds.isNotEmpty()
                lastWidgetIdsCheckTime = System.currentTimeMillis()

                if (!cachedHasAnyWidgets) {
                    return@launch
                }

                val durationSec = (if (durationMs > 0L) durationMs else 0L) / 1000
                val durationStr = "${formatTime(currentSec)} / ${formatTime(durationSec)}"

                if (cardIds.isNotEmpty()) {
                    val views = RemoteViews(context.packageName, R.layout.widget_card)
                    views.setTextViewText(R.id.widget_card_duration, durationStr)
                    appWidgetManager.partiallyUpdateAppWidget(cardIds, views)
                }

                if (wideIds.isNotEmpty()) {
                    val views = RemoteViews(context.packageName, R.layout.widget_wide)
                    views.setTextViewText(R.id.widget_wide_duration, durationStr)
                    appWidgetManager.partiallyUpdateAppWidget(wideIds, views)
                }

                if (pillIds.isNotEmpty()) {
                    val views = RemoteViews(context.packageName, R.layout.widget_pill)
                    views.setTextViewText(R.id.widget_pill_duration, durationStr)
                    appWidgetManager.partiallyUpdateAppWidget(pillIds, views)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateCardWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetIds: IntArray,
        state: PlaybackState,
        artworkBitmap: Bitmap?,
        colors: WidgetBitmapRenderer.WidgetThemeColors
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_card)
        val song = state.currentSong
        val density = context.resources.displayMetrics.density

        // 1. Render card background with dynamic dark theme & glow border
        val bgBmp = WidgetBitmapRenderer.renderCardBackground(
            context = context,
            widthPx = (280 * density).toInt(),
            heightPx = (280 * density).toInt(),
            cornerRadiusDp = 28f,
            bgColor = colors.containerBgColor,
            borderColor = colors.containerBorderColor
        )
        views.setImageViewBitmap(R.id.widget_card_bg, bgBmp)

        // 2. Render top rounded artwork
        val artBmp = WidgetBitmapRenderer.renderRoundedArtwork(
            source = artworkBitmap,
            widthPx = (256 * density).toInt(),
            heightPx = (125 * density).toInt(),
            cornerRadiusDp = 18f,
            context = context
        )
        views.setImageViewBitmap(R.id.widget_card_artwork, artBmp)

        // 3. Texts
        val currentSec = state.positionMs / 1000
        val durationSec = (if (state.durationMs > 0) state.durationMs else 0L) / 1000
        val durationStr = "${formatTime(currentSec)} / ${formatTime(durationSec)}"

        views.setTextViewText(R.id.widget_card_duration, durationStr)
        views.setTextColor(R.id.widget_card_duration, colors.durationColor)

        views.setTextViewText(R.id.widget_card_title, song?.displayTitle ?: "OffGrid Music")
        views.setTextColor(R.id.widget_card_title, colors.titleColor)

        val subtitle = if (!song?.artist.isNullOrBlank() && !song?.album.isNullOrBlank()) {
            "${song?.artist} • ${song?.album}"
        } else if (!song?.artist.isNullOrBlank()) {
            song?.artist ?: ""
        } else {
            song?.album ?: "Offline Audio"
        }
        views.setTextViewText(R.id.widget_card_subtitle, subtitle)
        views.setTextColor(R.id.widget_card_subtitle, colors.subtitleColor)

        // 4. Render Circular Buttons in accent color with dark icons
        val prevBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (44 * density).toInt(),
            buttonColor = colors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PREV,
            iconColor = colors.iconColor
        )
        views.setImageViewBitmap(R.id.widget_card_btn_prev, prevBmp)

        val playPauseBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (48 * density).toInt(),
            buttonColor = colors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
            isPlaying = state.isPlaying,
            iconColor = colors.iconColor
        )
        views.setImageViewBitmap(R.id.widget_card_btn_play_pause, playPauseBmp)

        val nextBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (44 * density).toInt(),
            buttonColor = colors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.NEXT,
            iconColor = colors.iconColor
        )
        views.setImageViewBitmap(R.id.widget_card_btn_next, nextBmp)

        // 5. Pending Intents
        setBroadcast(context, views, CardMusicWidgetProvider::class.java, R.id.widget_card_btn_prev, ACTION_WIDGET_PREV, 101)
        setBroadcast(context, views, CardMusicWidgetProvider::class.java, R.id.widget_card_btn_play_pause, ACTION_WIDGET_PLAY_PAUSE, 102)
        setBroadcast(context, views, CardMusicWidgetProvider::class.java, R.id.widget_card_btn_next, ACTION_WIDGET_NEXT, 103)

        setOnClickOpenApp(context, views, R.id.widget_card_artwork)
        setOnClickOpenApp(context, views, R.id.widget_card_title)
        setOnClickOpenApp(context, views, R.id.widget_card_subtitle)

        appWidgetManager.updateAppWidget(widgetIds, views)
    }

    private fun updateWideWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetIds: IntArray,
        state: PlaybackState,
        artworkBitmap: Bitmap?,
        colors: WidgetBitmapRenderer.WidgetThemeColors
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_wide)
        val song = state.currentSong
        val density = context.resources.displayMetrics.density

        // 1. Render wide card background
        val bgBmp = WidgetBitmapRenderer.renderCardBackground(
            context = context,
            widthPx = (340 * density).toInt(),
            heightPx = (150 * density).toInt(),
            cornerRadiusDp = 28f,
            bgColor = colors.containerBgColor,
            borderColor = colors.containerBorderColor
        )
        views.setImageViewBitmap(R.id.widget_wide_bg, bgBmp)

        // 2. Top-left favorite / music button
        val isFav = song?.isFavorite == true
        val favBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (32 * density).toInt(),
            buttonColor = colors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.FAVORITE,
            isFavorite = isFav,
            iconColor = colors.iconColor
        )
        views.setImageViewBitmap(R.id.widget_wide_btn_favorite, favBmp)

        // 3. Texts
        val currentSec = state.positionMs / 1000
        val durationSec = (if (state.durationMs > 0) state.durationMs else 0L) / 1000
        val durationStr = "${formatTime(currentSec)} / ${formatTime(durationSec)}"

        views.setTextViewText(R.id.widget_wide_title, song?.displayTitle ?: "OffGrid Music")
        views.setTextColor(R.id.widget_wide_title, colors.titleColor)

        val subtitle = if (!song?.artist.isNullOrBlank() && !song?.album.isNullOrBlank()) {
            "${song?.artist} • ${song?.album}"
        } else if (!song?.artist.isNullOrBlank()) {
            song?.artist ?: ""
        } else {
            song?.album ?: "Offline Audio"
        }
        views.setTextViewText(R.id.widget_wide_subtitle, subtitle)
        views.setTextColor(R.id.widget_wide_subtitle, colors.subtitleColor)

        views.setTextViewText(R.id.widget_wide_duration, durationStr)
        views.setTextColor(R.id.widget_wide_duration, colors.durationColor)

        // 4. Circular Playback Controls Row
        val prevBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (38 * density).toInt(),
            buttonColor = colors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PREV,
            iconColor = colors.iconColor
        )
        views.setImageViewBitmap(R.id.widget_wide_btn_prev, prevBmp)

        val playPauseBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (44 * density).toInt(),
            buttonColor = colors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
            isPlaying = state.isPlaying,
            iconColor = colors.iconColor
        )
        views.setImageViewBitmap(R.id.widget_wide_btn_play_pause, playPauseBmp)

        val nextBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (38 * density).toInt(),
            buttonColor = colors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.NEXT,
            iconColor = colors.iconColor
        )
        views.setImageViewBitmap(R.id.widget_wide_btn_next, nextBmp)

        // 5. Right Artwork (rounded square ~20dp radius)
        val artBmp = WidgetBitmapRenderer.renderRoundedArtwork(
            source = artworkBitmap,
            widthPx = (125 * density).toInt(),
            heightPx = (125 * density).toInt(),
            cornerRadiusDp = 20f,
            context = context
        )
        views.setImageViewBitmap(R.id.widget_wide_artwork, artBmp)

        // 6. Pending Intents
        setBroadcast(context, views, WideMusicWidgetProvider::class.java, R.id.widget_wide_btn_favorite, ACTION_WIDGET_FAVORITE, 200)
        setBroadcast(context, views, WideMusicWidgetProvider::class.java, R.id.widget_wide_btn_prev, ACTION_WIDGET_PREV, 201)
        setBroadcast(context, views, WideMusicWidgetProvider::class.java, R.id.widget_wide_btn_play_pause, ACTION_WIDGET_PLAY_PAUSE, 202)
        setBroadcast(context, views, WideMusicWidgetProvider::class.java, R.id.widget_wide_btn_next, ACTION_WIDGET_NEXT, 203)

        setOnClickOpenApp(context, views, R.id.widget_wide_artwork)
        setOnClickOpenApp(context, views, R.id.widget_wide_title)
        setOnClickOpenApp(context, views, R.id.widget_wide_subtitle)

        appWidgetManager.updateAppWidget(widgetIds, views)
    }

    private fun updatePillWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetIds: IntArray,
        state: PlaybackState,
        artworkBitmap: Bitmap?,
        colors: WidgetBitmapRenderer.WidgetThemeColors
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_pill)
        val density = context.resources.displayMetrics.density

        // 1. Render stadium capsule background
        val bgBmp = WidgetBitmapRenderer.renderPillBackground(
            context = context,
            widthPx = (240 * density).toInt(),
            heightPx = (60 * density).toInt(),
            bgColor = colors.containerBgColor,
            borderColor = colors.containerBorderColor
        )
        views.setImageViewBitmap(R.id.widget_pill_bg, bgBmp)

        // 2. Prominent Left Circular Play/Pause button
        val playPauseBmp = WidgetBitmapRenderer.renderCircularButton(
            context = context,
            sizePx = (48 * density).toInt(),
            buttonColor = colors.accentColor,
            iconType = WidgetBitmapRenderer.ButtonIconType.PLAY_PAUSE,
            isPlaying = state.isPlaying,
            iconColor = colors.iconColor
        )
        views.setImageViewBitmap(R.id.widget_pill_btn_play_pause, playPauseBmp)

        // 3. Right Artwork with rounded right curve matching capsule
        val artBmp = WidgetBitmapRenderer.renderPillArtwork(
            source = artworkBitmap,
            widthPx = (175 * density).toInt(),
            heightPx = (50 * density).toInt(),
            rightRadiusDp = 25f,
            context = context
        )
        views.setImageViewBitmap(R.id.widget_pill_artwork, artBmp)

        // 4. Duration text at bottom
        val currentSec = state.positionMs / 1000
        val durationSec = (if (state.durationMs > 0) state.durationMs else 0L) / 1000
        val durationStr = "${formatTime(currentSec)} / ${formatTime(durationSec)}"
        views.setTextViewText(R.id.widget_pill_duration, durationStr)

        // 5. Pending Intents
        setBroadcast(context, views, PillMusicWidgetProvider::class.java, R.id.widget_pill_btn_play_pause, ACTION_WIDGET_PLAY_PAUSE, 301)
        setOnClickOpenApp(context, views, R.id.widget_pill_artwork)
        setOnClickOpenApp(context, views, R.id.widget_pill_duration)

        appWidgetManager.updateAppWidget(widgetIds, views)
    }

    private fun setOnClickOpenApp(context: Context, views: RemoteViews, viewId: Int) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(viewId, pendingIntent)
    }

    private fun setBroadcast(context: Context, views: RemoteViews, providerClass: Class<*>, viewId: Int, action: String, requestCode: Int) {
        val intent = Intent(context, providerClass).apply {
            this.action = action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(viewId, pendingIntent)
    }

    private fun formatTime(sec: Long): String {
        val mins = sec / 60
        val remainingSec = sec % 60
        return String.format(Locale.getDefault(), "%d:%02d", mins, remainingSec)
    }
}

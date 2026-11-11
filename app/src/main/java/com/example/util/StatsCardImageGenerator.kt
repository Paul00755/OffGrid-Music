package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.model.Song
import com.example.data.model.WrappedData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object StatsCardImageGenerator {

    private const val CARD_WIDTH = 1080
    private const val CARD_HEIGHT = 1600

    suspend fun generateStatsCardBitmap(
        context: Context,
        wrappedData: WrappedData,
        timePeriodTitle: String
    ): Bitmap = withContext(Dispatchers.IO) {
        val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val topSong = wrappedData.topSong
        val heroArtBitmap = if (topSong != null) loadAlbumArtBitmap(context, topSong, 240) else null
        val dominantArtColor = extractDominantVibrantColor(heroArtBitmap) ?: Color.parseColor("#D0BCFF")

        // 1. Deep Multi-Stop Studio Backdrop
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat(),
                intArrayOf(
                    Color.parseColor("#17102B"),
                    Color.parseColor("#0D091A"),
                    Color.parseColor("#06040C")
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat(), bgPaint)

        // 2. Ambient Radial Aura Orbs (blending dynamic album color + signature OffGrid neon)
        val orbPaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                180f, 200f, 480f,
                intArrayOf(Color.parseColor("#4AD0BCFF"), Color.parseColor("#00D0BCFF")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(180f, 200f, 480f, orbPaint1)

        val dynamicAlphaColor = Color.argb(
            68,
            Color.red(dominantArtColor),
            Color.green(dominantArtColor),
            Color.blue(dominantArtColor)
        )
        val dynamicZeroColor = Color.argb(
            0,
            Color.red(dominantArtColor),
            Color.green(dominantArtColor),
            Color.blue(dominantArtColor)
        )
        val orbPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                (CARD_WIDTH - 140).toFloat(), 620f, 520f,
                intArrayOf(dynamicAlphaColor, dynamicZeroColor),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle((CARD_WIDTH - 140).toFloat(), 620f, 520f, orbPaint2)

        val orbPaint3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                CARD_WIDTH * 0.5f, (CARD_HEIGHT - 180).toFloat(), 540f,
                intArrayOf(Color.parseColor("#2BFF80BF"), Color.parseColor("#00FF80BF")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(CARD_WIDTH * 0.5f, (CARD_HEIGHT - 180).toFloat(), 540f, orbPaint3)

        // 3. Subtle Architectural Studio Grid Lines in Upper Backdrop
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0AFFFFFF")
            strokeWidth = 1f
        }
        var gx = 80f
        while (gx < CARD_WIDTH) {
            canvas.drawLine(gx, 28f, gx, 520f, gridPaint)
            gx += 80f
        }
        var gy = 80f
        while (gy < 520f) {
            canvas.drawLine(28f, gy, CARD_WIDTH - 28f, gy, gridPaint)
            gy += 80f
        }

        // 4. Outer Glassmorphic Card Frame
        val outerBorderRect = RectF(24f, 24f, CARD_WIDTH - 24f, CARD_HEIGHT - 24f)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            shader = LinearGradient(
                0f, 0f, CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat(),
                intArrayOf(
                    Color.parseColor("#55D0BCFF"),
                    Color.parseColor("#22FFFFFF"),
                    Color.parseColor("#44FF80BF")
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(outerBorderRect, 36f, 36f, borderPaint)

        // --- TOP HEADER BAR ---
        var currentY = 82f

        // Left Brand Pill
        val brandPillRect = RectF(52f, currentY - 34f, 406f, currentY + 20f)
        val brandPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                brandPillRect.left, brandPillRect.top, brandPillRect.right, brandPillRect.bottom,
                intArrayOf(Color.parseColor("#322454"), Color.parseColor("#211838")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(brandPillRect, 27f, 27f, brandPillPaint)

        val brandPillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#77D0BCFF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(brandPillRect, 27f, 27f, brandPillStroke)

        val tagTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EADDFF")
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.09f
        }
        canvas.drawText("⚡ OFFGRID STUDIO RECAP", 74f, currentY + 1f, tagTextPaint)

        // Right Timeframe Badge Pill
        val periodText = timePeriodTitle.uppercase()
        val periodTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.05f
        }
        val maxPeriodWidth = (CARD_WIDTH - 52f) - 440f
        var currentTextSize = 22f
        while (periodTextPaint.measureText(periodText) > maxPeriodWidth - 44f && currentTextSize > 13f) {
            currentTextSize -= 1f
            periodTextPaint.textSize = currentTextSize
        }
        val measuredPeriodW = periodTextPaint.measureText(periodText)
        val periodPillRect = RectF(
            (CARD_WIDTH - 52f) - measuredPeriodW - 44f,
            currentY - 34f,
            CARD_WIDTH - 52f,
            currentY + 20f
        )
        val periodPillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1FFFFFFF")
        }
        val periodPillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#40FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawRoundRect(periodPillRect, 27f, 27f, periodPillBg)
        canvas.drawRoundRect(periodPillRect, 27f, 27f, periodPillStroke)
        canvas.drawText(
            periodText,
            periodPillRect.left + 22f,
            currentY + 1f,
            periodTextPaint
        )

        currentY += 56f

        // --- ASYMMETRIC BENTO ROW: TOTAL LISTENING TIME (LEFT) + STREAK SHOWCASE (RIGHT) ---
        val bentoRowHeight = 236f
        val leftBentoRect = RectF(52f, currentY, 650f, currentY + bentoRowHeight)
        val rightBentoRect = RectF(668f, currentY, CARD_WIDTH - 52f, currentY + bentoRowHeight)

        // Left Card: Total Listening Time
        val heroBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                leftBentoRect.left, leftBentoRect.top, leftBentoRect.right, leftBentoRect.bottom,
                intArrayOf(Color.parseColor("#2E1F4F"), Color.parseColor("#18122B")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(leftBentoRect, 28f, 28f, heroBgPaint)

        val heroBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#55D0BCFF")
        }
        canvas.drawRoundRect(leftBentoRect, 28f, 28f, heroBorderPaint)

        val subLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D0BCFF")
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        canvas.drawText("TOTAL LISTENING TIME", leftBentoRect.left + 30f, currentY + 46f, subLabelPaint)

        val durationFormatted = formatStatsCardDuration(wrappedData.totalMinutes)
        val bigValPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // Fit duration text cleanly inside left Bento card
        var durSize = 64f
        while (bigValPaint.measureText(durationFormatted) > leftBentoRect.width() - 60f && durSize > 38f) {
            durSize -= 2f
            bigValPaint.textSize = durSize
        }
        canvas.drawText(durationFormatted, leftBentoRect.left + 30f, currentY + 128f, bigValPaint)

        // Sub-metrics Pill inside Left Bento Card
        val subPillRect = RectF(
            leftBentoRect.left + 26f,
            currentY + 162f,
            leftBentoRect.right - 26f,
            currentY + 210f
        )
        val subPillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#14FFFFFF")
        }
        canvas.drawRoundRect(subPillRect, 16f, 16f, subPillBg)

        val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EAE6F5")
            textSize = 21f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val statsRowText = "🎵 ${wrappedData.totalTracks} Plays   •   💿 ${wrappedData.uniqueTracks} Unique   •   📅 ${wrappedData.activeDaysCount}d"
        val ellipsizedStatsRow = TextUtils.ellipsize(
            statsRowText,
            badgeTextPaint,
            subPillRect.width() - 28f,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(ellipsizedStatsRow, subPillRect.left + 16f, subPillRect.centerY() + 8f, badgeTextPaint)

        // Right Card: Streak Showcase (Current Streak & Best Streak)
        val streakBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rightBentoRect.left, rightBentoRect.top, rightBentoRect.right, rightBentoRect.bottom,
                intArrayOf(Color.parseColor("#331B26"), Color.parseColor("#1B1222")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rightBentoRect, 28f, 28f, streakBgPaint)

        val streakBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#66FF9E64")
        }
        canvas.drawRoundRect(rightBentoRect, 28f, 28f, streakBorderPaint)

        val streakHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFB86C")
            textSize = 19f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.1f
        }
        canvas.drawText("🔥 LISTENING STREAK", rightBentoRect.left + 24f, currentY + 44f, streakHeaderPaint)

        val streakValPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val streakSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C5B8D8")
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val currentStreakStr = "${wrappedData.currentStreakDays} ${if (wrappedData.currentStreakDays == 1) "Day" else "Days"}"
        canvas.drawText(currentStreakStr, rightBentoRect.left + 24f, currentY + 96f, streakValPaint)
        canvas.drawText("Current", rightBentoRect.right - 96f, currentY + 92f, streakSubPaint)

        // Divider inside Streak card
        val streakDivPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#24FFFFFF")
            strokeWidth = 1.5f
        }
        canvas.drawLine(
            rightBentoRect.left + 24f,
            currentY + 122f,
            rightBentoRect.right - 24f,
            currentY + 122f,
            streakDivPaint
        )

        val bestHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD54F")
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        canvas.drawText("🏆 HIGHEST STREAK", rightBentoRect.left + 24f, currentY + 156f, bestHeaderPaint)

        val bestStreakStr = "${wrappedData.longestStreakDays} ${if (wrappedData.longestStreakDays == 1) "Day" else "Days"}"
        canvas.drawText(bestStreakStr, rightBentoRect.left + 24f, currentY + 206f, streakValPaint)
        canvas.drawText("Record", rightBentoRect.right - 92f, currentY + 202f, streakSubPaint)

        currentY += bentoRowHeight + 22f

        // --- LISTENER ARCHETYPE & PEAK VIBE BANNER ---
        val personaCardRect = RectF(52f, currentY, CARD_WIDTH - 52f, currentY + 114f)
        val personaBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                personaCardRect.left, personaCardRect.top, personaCardRect.right, personaCardRect.bottom,
                intArrayOf(Color.parseColor("#231A38"), Color.parseColor("#161125")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(personaCardRect, 24f, 24f, personaBgPaint)

        val personaBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Color.parseColor("#38FFFFFF")
        }
        canvas.drawRoundRect(personaCardRect, 24f, 24f, personaBorderPaint)

        val personaTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF80BF")
            textSize = 19f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.1f
        }
        canvas.drawText("LISTENER ARCHETYPE & SONIC VIBE", personaCardRect.left + 28f, currentY + 38f, personaTitlePaint)

        val personaValPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val vibeBadgeText = wrappedData.timeOfDay.dominantVibe
        val personaMaxW = personaCardRect.width() - 320f
        val personaTitleStr = TextUtils.ellipsize(
            wrappedData.listenerArchetype,
            personaValPaint,
            personaMaxW,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(personaTitleStr, personaCardRect.left + 28f, currentY + 84f, personaValPaint)

        // Right-aligned Vibe Pill inside Persona Card
        val vibeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF80BF")
            textSize = 21f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val vibeTextW = vibeTextPaint.measureText(vibeBadgeText)
        val vibePillRect = RectF(
            personaCardRect.right - vibeTextW - 56f,
            currentY + 32f,
            personaCardRect.right - 24f,
            currentY + 84f
        )
        val vibePillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#29FF80BF")
        }
        canvas.drawRoundRect(vibePillRect, 26f, 26f, vibePillBg)
        canvas.drawText(vibeBadgeText, vibePillRect.left + 16f, vibePillRect.centerY() + 8f, vibeTextPaint)

        currentY += 136f

        // --- #1 TOP ANTHEM VINYL & SLEEVE SPOTLIGHT ---
        val anthemCardHeight = 204f
        val anthemCardRect = RectF(52f, currentY, CARD_WIDTH - 52f, currentY + anthemCardHeight)
        val anthemBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                anthemCardRect.left, anthemCardRect.top, anthemCardRect.right, anthemCardRect.bottom,
                intArrayOf(Color.parseColor("#321A40"), Color.parseColor("#191128")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(anthemCardRect, 28f, 28f, anthemBgPaint)

        val anthemStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#77FF80BF")
        }
        canvas.drawRoundRect(anthemCardRect, 28f, 28f, anthemStroke)

        val artSize = 156f
        val artRect = RectF(
            anthemCardRect.left + 24f,
            anthemCardRect.top + 24f,
            anthemCardRect.left + 24f + artSize,
            anthemCardRect.top + 24f + artSize
        )

        // Draw Vinyl Disc Peeking Out to the Right of the Album Sleeve
        val vinylCenterX = artRect.right - 14f
        val vinylCenterY = artRect.centerY()
        val vinylRadius = artSize * 0.47f
        val vinylDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#110E1A")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(vinylCenterX, vinylCenterY, vinylRadius, vinylDiscPaint)

        val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#26FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawCircle(vinylCenterX, vinylCenterY, vinylRadius * 0.82f, groovePaint)
        canvas.drawCircle(vinylCenterX, vinylCenterY, vinylRadius * 0.64f, groovePaint)
        canvas.drawCircle(vinylCenterX, vinylCenterY, vinylRadius * 0.46f, groovePaint)

        val vinylCenterLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dominantArtColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(vinylCenterX, vinylCenterY, vinylRadius * 0.26f, vinylCenterLabelPaint)
        canvas.drawCircle(vinylCenterX, vinylCenterY, vinylRadius * 0.08f, vinylDiscPaint)

        if (topSong != null) {
            if (heroArtBitmap != null) {
                val scaledHeroArt = Bitmap.createScaledBitmap(heroArtBitmap, artSize.toInt(), artSize.toInt(), true)
                drawRoundedBitmap(canvas, scaledHeroArt, artRect, 22f)
            } else {
                drawStylizedArtworkPlaceholder(canvas, artRect, 22f)
            }

            // Artwork Sleeve Border
            val sleeveBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.parseColor("#44FFFFFF")
            }
            canvas.drawRoundRect(artRect, 22f, 22f, sleeveBorderPaint)

            val textStartX = artRect.right + 78f
            val textMaxW = anthemCardRect.right - textStartX - 28f

            // Crown Badge Pill
            val crownPillRect = RectF(textStartX, anthemCardRect.top + 26f, textStartX + 245f, anthemCardRect.top + 64f)
            val crownPillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#33FFD54F")
            }
            canvas.drawRoundRect(crownPillRect, 19f, 19f, crownPillBg)

            val anthemTagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFD54F")
                textSize = 19f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.08f
            }
            canvas.drawText("👑 #1 TOP ANTHEM", crownPillRect.left + 14f, crownPillRect.centerY() + 7f, anthemTagPaint)

            // Play count pill on top-right of Anthem Card
            if (wrappedData.topSongPlayCount > 0) {
                val playsBadgeStr = "${wrappedData.topSongPlayCount} PLAYS"
                val playsBadgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#EADDFF")
                    textSize = 18f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val playsW = playsBadgePaint.measureText(playsBadgeStr)
                val playsRect = RectF(
                    anthemCardRect.right - playsW - 44f,
                    anthemCardRect.top + 26f,
                    anthemCardRect.right - 20f,
                    anthemCardRect.top + 64f
                )
                val playsBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#2BD0BCFF")
                }
                canvas.drawRoundRect(playsRect, 19f, 19f, playsBg)
                canvas.drawText(playsBadgeStr, playsRect.left + 12f, playsRect.centerY() + 6f, playsBadgePaint)
            }

            // Song Title
            val songTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 36f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val titleEllipsized = TextUtils.ellipsize(topSong.displayTitle, songTitlePaint, textMaxW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(titleEllipsized, textStartX, anthemCardRect.top + 114f, songTitlePaint)

            // Artist & Album
            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D0BCFF")
                textSize = 25f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val artistEllipsized = TextUtils.ellipsize(topSong.displayArtist, artistPaint, textMaxW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(artistEllipsized, textStartX, anthemCardRect.top + 154f, artistPaint)

            val albumSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#9E94B8")
                textSize = 20f
            }
            val albumStr = if (topSong.album.isNotBlank() && !topSong.album.equals("<unknown>", ignoreCase = true)) {
                "💿 ${topSong.album}"
            } else {
                "🎧 Heavy Rotation Crown"
            }
            val albumEllipsized = TextUtils.ellipsize(albumStr, albumSubPaint, textMaxW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(albumEllipsized, textStartX, anthemCardRect.top + 184f, albumSubPaint)
        } else {
            drawStylizedArtworkPlaceholder(canvas, artRect, 22f)
            val textStartX = artRect.right + 78f
            val anthemTagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFD54F")
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.08f
            }
            canvas.drawText("👑 #1 TOP ANTHEM", textStartX, anthemCardRect.top + 56f, anthemTagPaint)

            val songTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 32f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText("Start spinning your library", textStartX, anthemCardRect.top + 108f, songTitlePaint)

            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D0BCFF")
                textSize = 24f
            }
            canvas.drawText("Your #1 most played track appears here", textStartX, anthemCardRect.top + 148f, artistPaint)
        }

        currentY += anthemCardHeight + 28f

        // --- TOP 5 LEADERBOARD SECTION (WITH MINI ALBUM ARTWORK & PROPORTIONAL BARS) ---
        val leaderTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D0BCFF")
            textSize = 21f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.11f
        }
        canvas.drawText("TOP TRACKS ON HEAVY ROTATION", 56f, currentY + 20f, leaderTitlePaint)

        // Top Artist Mini Pill on the right of section header if available
        wrappedData.topArtists.firstOrNull()?.let { topArtist ->
            val topArtistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF80BF")
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            val artistBadge = TextUtils.ellipsize(
                "🎤 #1 Artist: ${topArtist.name}",
                topArtistPaint,
                420f,
                TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(artistBadge, CARD_WIDTH - 56f, currentY + 20f, topArtistPaint)
        }

        currentY += 38f

        val topSongsList = wrappedData.topSongs.take(5)
        val maxPlaysInList = (topSongsList.firstOrNull()?.playCount ?: 1).coerceAtLeast(1)
        val rowHeight = 86f

        topSongsList.forEachIndexed { index, song ->
            val rowRect = RectF(52f, currentY, CARD_WIDTH - 52f, currentY + rowHeight - 10f)
            val rowBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (index == 0) Color.parseColor("#281C40") else Color.parseColor("#171326")
            }
            canvas.drawRoundRect(rowRect, 18f, 18f, rowBgPaint)

            val rowBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
                color = if (index == 0) Color.parseColor("#44FFD54F") else Color.parseColor("#1AFFFFFF")
            }
            canvas.drawRoundRect(rowRect, 18f, 18f, rowBorderPaint)

            // Rank Number
            val rankPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when (index) {
                    0 -> Color.parseColor("#FFD54F")
                    1 -> Color.parseColor("#E0E0E0")
                    2 -> Color.parseColor("#FFAB91")
                    else -> Color.parseColor("#9E94B8")
                }
                textSize = 26f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("#${index + 1}", rowRect.left + 36f, rowRect.centerY() + 9f, rankPaint)

            // Mini Album Art Thumbnail (54x54)
            val thumbSize = 54f
            val thumbRect = RectF(
                rowRect.left + 68f,
                rowRect.centerY() - thumbSize / 2f,
                rowRect.left + 68f + thumbSize,
                rowRect.centerY() + thumbSize / 2f
            )
            val thumbBmp = loadAlbumArtBitmap(context, song, thumbSize.toInt())
            if (thumbBmp != null) {
                drawRoundedBitmap(canvas, thumbBmp, thumbRect, 12f)
            } else {
                drawStylizedArtworkPlaceholder(canvas, thumbRect, 12f)
            }

            // Title & Artist
            val rowTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 25f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val rowArtistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#A8A0C0")
                textSize = 20f
            }

            val textLeft = thumbRect.right + 18f
            val maxTextW = rowRect.width() - 320f
            val itemTitle = TextUtils.ellipsize(song.displayTitle, rowTitlePaint, maxTextW, TextUtils.TruncateAt.END).toString()
            val itemArtist = TextUtils.ellipsize(song.displayArtist, rowArtistPaint, maxTextW, TextUtils.TruncateAt.END).toString()

            canvas.drawText(itemTitle, textLeft, rowRect.top + 33f, rowTitlePaint)
            canvas.drawText(itemArtist, textLeft, rowRect.top + 60f, rowArtistPaint)

            // Play Count Pill + Proportional Bar on the Right
            if (song.playCount > 0) {
                val countText = "${song.playCount} plays"
                val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (index == 0) Color.parseColor("#FFD54F") else Color.parseColor("#D0BCFF")
                    textSize = 21f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.RIGHT
                }
                canvas.drawText(countText, rowRect.right - 22f, rowRect.top + 36f, countPaint)

                // Mini Bar
                val barWidth = 110f
                val barRight = rowRect.right - 22f
                val barLeft = barRight - barWidth
                val barTop = rowRect.top + 49f
                val barBottom = barTop + 8f
                val trackRect = RectF(barLeft, barTop, barRight, barBottom)
                val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#22FFFFFF")
                }
                canvas.drawRoundRect(trackRect, 4f, 4f, trackPaint)

                val fraction = (song.playCount.toFloat() / maxPlaysInList.toFloat()).coerceIn(0.15f, 1f)
                val fillRect = RectF(barRight - barWidth * fraction, barTop, barRight, barBottom)
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (index == 0) Color.parseColor("#FFD54F") else Color.parseColor("#D0BCFF")
                }
                canvas.drawRoundRect(fillRect, 4f, 4f, fillPaint)
            }

            currentY += rowHeight
        }

        // --- SIGNATURE STUDIO FOOTER ---
        val footerY = CARD_HEIGHT - 64f

        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#24FFFFFF")
            strokeWidth = 1.5f
        }
        canvas.drawLine(56f, footerY - 26f, (CARD_WIDTH - 56).toFloat(), footerY - 26f, dividerPaint)

        val footerLeftPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C8BFFF")
            textSize = 21f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("🎧 OffGrid Audiophile Studio", 56f, footerY + 10f, footerLeftPaint)

        val footerRightPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#88FFFFFF")
            textSize = 20f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("100% Local • Lossless Sound Identity", CARD_WIDTH - 56f, footerY + 10f, footerRightPaint)

        return@withContext bitmap
    }

    private fun drawStylizedArtworkPlaceholder(canvas: Canvas, rect: RectF, cornerRadius: Float) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(Color.parseColor("#2A1E44"), Color.parseColor("#140F24")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.parseColor("#FF80BF")
        }
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.28f, ringPaint)
    }

    private fun extractDominantVibrantColor(bitmap: Bitmap?): Int? {
        if (bitmap == null) return null
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
            var rSum = 0L
            var gSum = 0L
            var bSum = 0L
            var count = 0L
            for (x in 0 until scaled.width) {
                for (y in 0 until scaled.height) {
                    val pixel = scaled.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val brightness = (r + g + b) / 3
                    if (brightness in 35..235) {
                        rSum += r
                        gSum += g
                        bSum += b
                        count++
                    }
                }
            }
            if (count > 0) {
                Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun formatStatsCardDuration(totalMins: Long): String {
        val hours = totalMins / 60
        val mins = totalMins % 60
        return when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours} hours"
            else -> "${totalMins} mins"
        }
    }

    private fun loadAlbumArtBitmap(context: Context, song: Song, targetSize: Int): Bitmap? {
        try {
            // 1. Check if cached artwork file exists
            val cacheDir = File(context.cacheDir, "artworks")
            val cacheFile = File(cacheDir, "${song.id}.jpg")
            if (cacheFile.exists() && cacheFile.length() > 0) {
                val decoded = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (decoded != null) {
                    return Bitmap.createScaledBitmap(decoded, targetSize, targetSize, true)
                }
            }

            // 2. Try loading from artworkUri
            val uriToTry = song.artworkUri
            if (!uriToTry.isNullOrBlank()) {
                try {
                    val uri = Uri.parse(uriToTry)
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val decoded = BitmapFactory.decodeStream(stream)
                        if (decoded != null) {
                            return Bitmap.createScaledBitmap(decoded, targetSize, targetSize, true)
                        }
                    }
                } catch (_: Exception) {}
            }

            // 3. Try reading embedded artwork from song file path
            if (song.path.isNotBlank() && File(song.path).exists()) {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(song.path)
                    val artBytes = mmr.embeddedPicture
                    if (artBytes != null && artBytes.isNotEmpty()) {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, opts)

                        var sample = 1
                        while (opts.outWidth / (sample * 2) >= targetSize && opts.outHeight / (sample * 2) >= targetSize) {
                            sample *= 2
                        }

                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                        val decoded = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, decodeOpts)
                        if (decoded != null) {
                            return Bitmap.createScaledBitmap(decoded, targetSize, targetSize, true)
                        }
                    }
                } finally {
                    try { mmr.release() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun drawRoundedBitmap(canvas: Canvas, bitmap: Bitmap, rect: RectF, cornerRadius: Float) {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val clipCanvas = Canvas(output)
        val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val rRect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        clipCanvas.drawRoundRect(rRect, cornerRadius, cornerRadius, clipPaint)
        clipPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        clipCanvas.drawBitmap(bitmap, 0f, 0f, clipPaint)

        canvas.drawBitmap(output, null, rect, Paint(Paint.ANTI_ALIAS_FLAG))
    }

    suspend fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        try {
            val imagesFolder = File(context.cacheDir, "images").apply { mkdirs() }
            val file = File(imagesFolder, "offgrid_stats_${System.currentTimeMillis()}.png")
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.flush()
            stream.close()

            return@withContext FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    fun shareImageCard(context: Context, imageUri: Uri, captionText: String) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                putExtra(Intent.EXTRA_TEXT, captionText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share My Stats Card"))
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to launch share dialog: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun saveImageToGallery(context: Context, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        try {
            val filename = "OffGrid_Recap_${System.currentTimeMillis()}.png"
            var fos: OutputStream? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OffGrid")
                }
                val imageUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (imageUri != null) {
                    fos = context.contentResolver.openOutputStream(imageUri)
                }
            } else {
                val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString() + "/OffGrid"
                val file = File(imagesDir).apply { if (!exists()) mkdirs() }
                val image = File(file, filename)
                fos = FileOutputStream(image)
            }

            fos?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
}

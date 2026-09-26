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
    private const val CARD_HEIGHT = 1480

    suspend fun generateStatsCardBitmap(
        context: Context,
        wrappedData: WrappedData,
        timePeriodTitle: String
    ): Bitmap = withContext(Dispatchers.IO) {
        val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Draw Background Gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat(),
                intArrayOf(
                    Color.parseColor("#1B1233"),
                    Color.parseColor("#100C1F"),
                    Color.parseColor("#08060D")
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat(), bgPaint)

        // 2. Draw Decorative Glow Orbs
        val orbPaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                200f, 220f, 400f,
                intArrayOf(Color.parseColor("#44D0BCFF"), Color.parseColor("#00D0BCFF")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(200f, 220f, 400f, orbPaint1)

        val orbPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                (CARD_WIDTH - 150).toFloat(), 600f, 450f,
                intArrayOf(Color.parseColor("#38FF80BF"), Color.parseColor("#00FF80BF")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle((CARD_WIDTH - 150).toFloat(), 600f, 450f, orbPaint2)

        val orbPaint3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                CARD_WIDTH * 0.5f, (CARD_HEIGHT - 200).toFloat(), 500f,
                intArrayOf(Color.parseColor("#2580CBC4"), Color.parseColor("#0080CBC4")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(CARD_WIDTH * 0.5f, (CARD_HEIGHT - 200).toFloat(), 500f, orbPaint3)

        // Outer Card Border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.parseColor("#33FFFFFF")
        }
        val borderRect = RectF(24f, 24f, CARD_WIDTH - 24f, CARD_HEIGHT - 24f)
        canvas.drawRoundRect(borderRect, 32f, 32f, borderPaint)

        // --- TOP HEADER ---
        var currentY = 90f

        // App Tag Pill
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2B2244")
            style = Paint.Style.FILL
        }
        val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#66D0BCFF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val pillRect = RectF(60f, currentY - 32f, 340f, currentY + 18f)
        canvas.drawRoundRect(pillRect, 25f, 25f, pillPaint)
        canvas.drawRoundRect(pillRect, 25f, 25f, pillStroke)

        val tagTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D0BCFF")
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        canvas.drawText("⚡ OFFGRID WRAPPED", 80f, currentY - 2f, tagTextPaint)

        // Period Title on Top Right
        val periodTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B3FFFFFF")
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(timePeriodTitle.uppercase(), (CARD_WIDTH - 60).toFloat(), currentY - 2f, periodTextPaint)

        currentY += 80f

        // --- HERO LISTENING DURATION SECTION ---
        val heroCardRect = RectF(50f, currentY, (CARD_WIDTH - 50).toFloat(), currentY + 230f)
        val heroBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                heroCardRect.left, heroCardRect.top, heroCardRect.right, heroCardRect.bottom,
                intArrayOf(Color.parseColor("#2C1E4A"), Color.parseColor("#181429")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(heroCardRect, 28f, 28f, heroBgPaint)

        val heroBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#44D0BCFF")
        }
        canvas.drawRoundRect(heroCardRect, 28f, 28f, heroBorderPaint)

        // Hero Label
        val subLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D0BCFF")
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        canvas.drawText("TOTAL LISTENING TIME", heroCardRect.left + 36f, currentY + 50f, subLabelPaint)

        // Total Duration Big Value
        val durationFormatted = formatStatsCardDuration(wrappedData.totalMinutes)
        val bigValPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 72f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(durationFormatted, heroCardRect.left + 36f, currentY + 130f, bigValPaint)

        // Stat Badges row inside Hero Card
        val badgeY = currentY + 185f
        val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E0E0E0")
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val statsRowText = "🔥 ${wrappedData.totalTracks} Total Plays   •   🎵 ${wrappedData.uniqueTracks} Unique Tracks   •   📅 ${wrappedData.activeDaysCount} Active Days"
        canvas.drawText(statsRowText, heroCardRect.left + 36f, badgeY, badgeTextPaint)

        currentY += 255f

        // --- LISTENER PERSONA & VIBE BANNER ---
        val personaCardRect = RectF(50f, currentY, (CARD_WIDTH - 50).toFloat(), currentY + 110f)
        val personaBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1F1932")
        }
        canvas.drawRoundRect(personaCardRect, 20f, 20f, personaBgPaint)

        val personaBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Color.parseColor("#2EFFFFFF")
        }
        canvas.drawRoundRect(personaCardRect, 20f, 20f, personaBorderPaint)

        val personaTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF80BF")
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        canvas.drawText("LISTENER ARCHETYPE", personaCardRect.left + 28f, currentY + 40f, personaTitlePaint)

        val personaValPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val personaString = "${wrappedData.listenerArchetype} (${wrappedData.timeOfDay.dominantVibe})"
        canvas.drawText(
            TextUtils.ellipsize(personaString, personaValPaint, personaCardRect.width() - 56f, TextUtils.TruncateAt.END).toString(),
            personaCardRect.left + 28f,
            currentY + 84f,
            personaValPaint
        )

        currentY += 135f

        // --- #1 ANTHEM SPOTLIGHT SECTION ---
        val topSong = wrappedData.topSong
        val anthemCardRect = RectF(50f, currentY, (CARD_WIDTH - 50).toFloat(), currentY + 175f)
        val anthemBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                anthemCardRect.left, anthemCardRect.top, anthemCardRect.right, anthemCardRect.bottom,
                intArrayOf(Color.parseColor("#2E1838"), Color.parseColor("#1B1229")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(anthemCardRect, 24f, 24f, anthemBgPaint)
        val anthemStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#66FF80BF")
        }
        canvas.drawRoundRect(anthemCardRect, 24f, 24f, anthemStroke)

        // Album Art Thumbnail or Vinyl icon
        val artSize = 135f
        val artRect = RectF(
            anthemCardRect.left + 20f,
            anthemCardRect.top + 20f,
            anthemCardRect.left + 20f + artSize,
            anthemCardRect.top + 20f + artSize
        )

        if (topSong != null) {
            val artBitmap = loadAlbumArtBitmap(context, topSong, artSize.toInt())
            if (artBitmap != null) {
                drawRoundedBitmap(canvas, artBitmap, artRect, 18f)
            } else {
                // Vinyl Placeholder
                val vinylPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#151124")
                }
                canvas.drawRoundRect(artRect, 18f, 18f, vinylPaint)
                val vinylRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = Color.parseColor("#FF80BF")
                }
                canvas.drawCircle(artRect.centerX(), artRect.centerY(), artSize * 0.35f, vinylRing)
            }

            // Crown / #1 Badge
            val anthemTagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF80BF")
                textSize = 22f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.08f
            }
            canvas.drawText("👑 #1 TOP ANTHEM", artRect.right + 24f, anthemCardRect.top + 48f, anthemTagPaint)

            // Song Title
            val songTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 34f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val titleMaxW = anthemCardRect.width() - artSize - 70f
            val titleEllipsized = TextUtils.ellipsize(topSong.displayTitle, songTitlePaint, titleMaxW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(titleEllipsized, artRect.right + 24f, anthemCardRect.top + 92f, songTitlePaint)

            // Artist & Play Count
            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D0BCFF")
                textSize = 26f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            val artistStr = "${topSong.displayArtist}  •  ${wrappedData.topSongPlayCount} Plays"
            val artistEllipsized = TextUtils.ellipsize(artistStr, artistPaint, titleMaxW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(artistEllipsized, artRect.right + 24f, anthemCardRect.top + 134f, artistPaint)
        } else {
            val vinylPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#151124") }
            canvas.drawRoundRect(artRect, 18f, 18f, vinylPaint)
            val vinylRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = Color.parseColor("#FF80BF")
            }
            canvas.drawCircle(artRect.centerX(), artRect.centerY(), artSize * 0.35f, vinylRing)

            val anthemTagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF80BF")
                textSize = 22f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.08f
            }
            canvas.drawText("👑 #1 TOP ANTHEM", artRect.right + 24f, anthemCardRect.top + 48f, anthemTagPaint)

            val songTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 30f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText("Play your favorite tracks", artRect.right + 24f, anthemCardRect.top + 92f, songTitlePaint)

            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D0BCFF")
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            canvas.drawText("Your top anthem will be crowned here", artRect.right + 24f, anthemCardRect.top + 134f, artistPaint)
        }

        currentY += 200f

        // --- TOP 5 LEADERBOARD SECTION ---
        val leaderTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D0BCFF")
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.1f
        }
        canvas.drawText("TOP TRACKS ON HEAVY ROTATION", 54f, currentY + 28f, leaderTitlePaint)
        currentY += 46f

        val topSongsList = wrappedData.topSongs.take(5)
        val rowHeight = 76f

        topSongsList.forEachIndexed { index, song ->
            val rowRect = RectF(50f, currentY, (CARD_WIDTH - 50).toFloat(), currentY + rowHeight - 8f)
            val rowBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (index == 0) Color.parseColor("#261B3B") else Color.parseColor("#151222")
            }
            canvas.drawRoundRect(rowRect, 14f, 14f, rowBgPaint)

            // Rank Number
            val rankPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when (index) {
                    0 -> Color.parseColor("#FFD54F")
                    1 -> Color.parseColor("#D0BCFF")
                    2 -> Color.parseColor("#FF80BF")
                    else -> Color.parseColor("#A0A0B0")
                }
                textSize = 28f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("#${index + 1}", rowRect.left + 40f, currentY + 44f, rankPaint)

            // Title & Artist
            val rowTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 26f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val rowArtistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#A0A0B5")
                textSize = 22f
            }

            val maxTextW = rowRect.width() - 250f
            val itemTitle = TextUtils.ellipsize(song.displayTitle, rowTitlePaint, maxTextW, TextUtils.TruncateAt.END).toString()
            val itemArtist = TextUtils.ellipsize(song.displayArtist, rowArtistPaint, maxTextW, TextUtils.TruncateAt.END).toString()

            canvas.drawText(itemTitle, rowRect.left + 80f, currentY + 34f, rowTitlePaint)
            canvas.drawText(itemArtist, rowRect.left + 80f, currentY + 58f, rowArtistPaint)

            // Play Count Badge
            if (song.playCount > 0) {
                val countText = "${song.playCount} plays"
                val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#D0BCFF")
                    textSize = 22f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.RIGHT
                }
                canvas.drawText(countText, rowRect.right - 24f, currentY + 42f, countPaint)
            }

            currentY += rowHeight
        }

        // --- FOOTER BRANDING ---
        val footerY = CARD_HEIGHT - 65f

        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22FFFFFF")
            strokeWidth = 1.5f
        }
        canvas.drawLine(60f, footerY - 24f, (CARD_WIDTH - 60).toFloat(), footerY - 24f, dividerPaint)

        val footerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#99FFFFFF")
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("🎧 OffGrid Offline Music Player   •   Your Local Sound Identity", CARD_WIDTH * 0.5f, footerY + 12f, footerTextPaint)

        return@withContext bitmap
    }

    private fun formatStatsCardDuration(totalMins: Long): String {
        val hours = totalMins / 60
        val mins = totalMins % 60
        return when {
            hours > 0 && mins > 0 -> "${hours} hrs ${mins} mins"
            hours > 0 -> "${hours} hours"
            else -> "${totalMins} minutes"
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

package com.example.update

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * Encapsulates verified GitHub Release metadata for OffGrid Music.
 */
data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val htmlUrl: String,
    val apkDownloadUrl: String? = null,
    val apkSize: Long = 0L,
    val isDraft: Boolean = false,
    val isPrerelease: Boolean = false,
    val version: AppVersion = AppVersion.parse(tagName)
) {
    val tag: String get() = tagName
    val releaseUrl: String get() = htmlUrl
    val isApkAttached: Boolean get() = !apkDownloadUrl.isNullOrBlank()

    /**
     * Converts ISO-8601 published date (e.g. 2026-10-31T05:00:00Z) to readable format.
     */
    fun getFormattedPublishDate(): String {
        if (publishedAt.isBlank()) return ""
        return try {
            val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = isoParser.parse(publishedAt) ?: return publishedAt
            val displayFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            displayFormat.format(date)
        } catch (_: Exception) {
            try {
                // Try format with milliseconds
                val isoMillisParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = isoMillisParser.parse(publishedAt) ?: return publishedAt
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
            } catch (_: Exception) {
                publishedAt.take(10)
            }
        }
    }

    /**
     * Cleans up raw markdown notes so they render cleanly inside native Compose cards
     * without unwanted raw markdown syntax artifacts, while preserving readable bullet points.
     */
    fun getSanitizedNotes(): String {
        return sanitizeReleaseNotes(body)
    }

    fun getFormattedApkSize(): String {
        if (apkSize <= 0L) return ""
        val mb = apkSize / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    companion object {
        fun sanitizeReleaseNotes(notes: String): String {
            if (notes.isBlank()) {
                return "• Performance improvements and bug fixes for a smoother audio playback experience."
            }

            val lines = notes.lines()
            val filtered = lines.filterNot { line ->
                val trimmed = line.trim()
                // Filter out internal CI links and automated checksum lines that clutter user-facing dialogs
                trimmed.startsWith("SHA-256:", ignoreCase = true) ||
                trimmed.startsWith("| **SHA-256**", ignoreCase = true) ||
                trimmed.startsWith("### 📥 Direct APK Downloads", ignoreCase = true) ||
                trimmed.startsWith("- **[⬇️ Download", ignoreCase = true) ||
                trimmed.startsWith("| :---", ignoreCase = true) ||
                trimmed.startsWith("| Property |", ignoreCase = true) ||
                trimmed.startsWith("| **Branch**", ignoreCase = true) ||
                trimmed.startsWith("| **Commit**", ignoreCase = true)
            }.map { line ->
                line.replace(Regex("<[^>]*>"), "")
                    .replace(Regex("^#+\\s*"), "")
                    .replace("**", "")
                    .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
                    .trimEnd()
            }

            val result = filtered.joinToString("\n").trim()
            return if (result.isBlank()) {
                "• Performance improvements and stability enhancements."
            } else {
                result
            }
        }

        fun fromJson(json: JSONObject): ReleaseInfo? {
            return try {
                val tagName = json.optString("tag_name", "")
                if (tagName.isBlank()) return null
                val name = json.optString("name", tagName)
                val body = json.optString("body", "")
                val publishedAt = json.optString("published_at", "")
                val htmlUrl = json.optString("html_url", "https://github.com/Paul00755/OffGrid-Music/releases/tag/$tagName")
                val isDraft = json.optBoolean("draft", false)
                val isPrerelease = json.optBoolean("prerelease", false)

                var apkUrl: String? = null
                var apkSize: Long = 0L

                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val assetName = asset.optString("name", "")
                        val downloadUrl = asset.optString("browser_download_url", "")
                        if (downloadUrl.isNotBlank() && assetName.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = downloadUrl
                            apkSize = asset.optLong("size", 0L)
                            if (assetName.contains("OffGrid", ignoreCase = true)) {
                                break
                            }
                        }
                    }
                }

                ReleaseInfo(
                    tagName = tagName,
                    name = name,
                    body = body,
                    publishedAt = publishedAt,
                    htmlUrl = htmlUrl,
                    apkDownloadUrl = apkUrl,
                    apkSize = apkSize,
                    isDraft = isDraft,
                    isPrerelease = isPrerelease
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

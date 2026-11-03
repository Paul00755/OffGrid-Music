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
    val apkAssetName: String? = null,
    val apkSize: Long = 0L,
    val isDraft: Boolean = false,
    val isPrerelease: Boolean = false,
    val version: AppVersion = resolveVersion(tagName, name, apkAssetName, body)
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
        const val DEFAULT_RELEASE_URL = "https://github.com/Paul00755/OffGrid-Music/releases"

        fun resolveVersion(
            tagName: String,
            name: String = "",
            apkAssetName: String? = null,
            body: String = ""
        ): AppVersion {
            val fromTag = AppVersion.parse(tagName)
            if (fromTag.isValid) {
                if (fromTag.buildNumber == 0L) {
                    val fromAsset = apkAssetName?.let { AppVersion.parse(it) }
                    val fromName = AppVersion.parse(name)
                    val extraBuild = when {
                        fromAsset != null && fromAsset.major == fromTag.major && fromAsset.minor == fromTag.minor && fromAsset.patch == fromTag.patch && fromAsset.buildNumber > 0L -> fromAsset.buildNumber
                        fromName.major == fromTag.major && fromName.minor == fromTag.minor && fromName.patch == fromTag.patch && fromName.buildNumber > 0L -> fromName.buildNumber
                        fromName.buildNumber > 0L && !fromName.isValid -> fromName.buildNumber
                        else -> 0L
                    }
                    return if (extraBuild > 0L) fromTag.copy(buildNumber = extraBuild) else fromTag
                }
                return fromTag
            }

            val fromAsset = apkAssetName?.let { AppVersion.parse(it) }?.takeIf { it.isValid }
            val fromName = AppVersion.parse(name).takeIf { it.isValid }
            val fromBody = AppVersion.parse(body).takeIf { it.isValid }
            return fromAsset ?: fromName ?: fromBody ?: fromTag
        }

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
                trimmed.startsWith("📦 Direct APK Download", ignoreCase = true) ||
                trimmed.startsWith("- **[⬇️ Download", ignoreCase = true) ||
                trimmed.startsWith("| :---", ignoreCase = true) ||
                trimmed.startsWith("| Property |", ignoreCase = true) ||
                trimmed.startsWith("| **Branch**", ignoreCase = true) ||
                trimmed.startsWith("| **Commit**", ignoreCase = true)
            }.map { line ->
                line.replace(Regex("<[^>]*>"), "")
                    .replace(Regex("^#+\\s*"), "")
                    .replace("**", "")
                    .replace("__", "")
                    .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
                    .replace(Regex("^[-*]\\s+\\[[xX ]\\]\\s*"), "• ")
                    .replace(Regex("^[-*]\\s+"), "• ")
                    .trimEnd()
            }.filter { line ->
                val trimmed = line.trim()
                trimmed.isNotBlank() && !trimmed.startsWith("---")
            }

            val result = filtered.joinToString("\n").trim()
            return if (result.isBlank()) {
                "• Performance improvements and stability enhancements."
            } else {
                result
            }
        }

        fun fromJsonString(jsonString: String): ReleaseInfo? {
            if (jsonString.isBlank()) return null
            return try {
                fromJson(JSONObject(jsonString))
            } catch (_: Exception) {
                null
            }
        }

        fun fromJson(json: JSONObject): ReleaseInfo? {
            return try {
                val rawTagName = json.optString("tag_name", "").trim()
                if (rawTagName.isBlank()) return null
                val isDraft = json.optBoolean("draft", false)
                val isPrerelease = json.optBoolean("prerelease", false)

                // Ignore draft or prerelease builds for stable channel updates
                if (isDraft || isPrerelease) return null

                val rawName = json.optString("name", "").trim()
                val body = json.optString("body", "").trim()
                val publishedAt = json.optString("published_at", "").trim()
                val rawHtmlUrl = json.optString("html_url", "").trim()

                var apkUrl: String? = null
                var apkName: String? = null
                var apkSize: Long = 0L

                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val assetName = asset.optString("name", "")
                        val downloadUrl = asset.optString("browser_download_url", "")
                        val contentType = asset.optString("content_type", "")
                        if (downloadUrl.isNotBlank() &&
                            (assetName.endsWith(".apk", ignoreCase = true) ||
                                contentType.equals("application/vnd.android.package-archive", ignoreCase = true))
                        ) {
                            apkUrl = downloadUrl
                            apkName = assetName
                            apkSize = asset.optLong("size", 0L)
                            if (assetName.equals("OffGrid-Music.apk", ignoreCase = true) ||
                                assetName.contains("OffGrid", ignoreCase = true)
                            ) {
                                break
                            }
                        }
                    }
                }

                val resolvedVer = resolveVersion(rawTagName, rawName, apkName, body)
                if (!resolvedVer.isValid || resolvedVer.isPreRelease) {
                    return null
                }

                val effectiveTag = if (rawTagName.equals("latest", ignoreCase = true)) {
                    if (resolvedVer.buildNumber > 0L) {
                        "v${resolvedVer.major}.${resolvedVer.minor}.${resolvedVer.patch}-build.${resolvedVer.buildNumber}"
                    } else {
                        "v${resolvedVer.major}.${resolvedVer.minor}.${resolvedVer.patch}"
                    }
                } else {
                    rawTagName
                }

                val effectiveHtmlUrl = if (rawHtmlUrl.isNotBlank() && rawHtmlUrl.startsWith("https://github.com/")) {
                    rawHtmlUrl
                } else {
                    "https://github.com/Paul00755/OffGrid-Music/releases/tag/$effectiveTag"
                }

                ReleaseInfo(
                    tagName = effectiveTag,
                    name = rawName.ifBlank { "OffGrid Music $effectiveTag" },
                    body = body,
                    publishedAt = publishedAt,
                    htmlUrl = effectiveHtmlUrl,
                    apkDownloadUrl = apkUrl,
                    apkAssetName = apkName,
                    apkSize = apkSize,
                    isDraft = isDraft,
                    isPrerelease = isPrerelease,
                    version = resolvedVer
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}


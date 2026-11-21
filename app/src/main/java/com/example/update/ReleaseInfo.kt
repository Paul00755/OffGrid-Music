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

        private fun stripEmojis(input: String): String {
            val sb = StringBuilder(input.length)
            var i = 0
            while (i < input.length) {
                val cp = Character.codePointAt(input, i)
                val charCount = Character.charCount(cp)
                val type = Character.getType(cp)
                val isEmojiOrSymbol =
                    type == Character.OTHER_SYMBOL.toInt() ||
                        cp == 0xFE0E ||
                        cp == 0xFE0F ||
                        cp == 0x200D ||
                        cp in 0x1F000..0x1FAFF ||
                        cp in 0x2600..0x27BF
                if (!isEmojiOrSymbol) {
                    sb.appendCodePoint(cp)
                }
                i += charCount
            }
            return sb.toString()
        }

        private val CONVENTIONAL_COMMIT_REGEX = Regex(
            "^([a-zA-Z]+)(?:\\([^)]*\\))?!?\\s*:\\s*"
        )

        fun sanitizeReleaseNotes(notes: String): String {
            if (notes.isBlank()) {
                return "• Fixed stability issues and improved playback reliability."
            }

            val lines = notes.lines()
            val seen = LinkedHashSet<String>()
            val cleaned = mutableListOf<String>()

            for (rawLine in lines) {
                val trimmed = rawLine.trim()
                if (trimmed.isBlank()) continue
                val lower = trimmed.lowercase(Locale.US)

                // Drop GitHub release boilerplate, CI metadata tables, download links, and upgrade footnotes
                val isBoilerplate =
                    lower.startsWith("sha-256:") ||
                        lower.startsWith("|") ||
                        lower.startsWith(">") ||
                        lower.startsWith("---") ||
                        lower.startsWith("full changelog") ||
                        lower.contains("direct apk download") ||
                        lower.contains("one-click direct download") ||
                        lower.contains("direct download") ||
                        lower.contains("download offgrid-music") ||
                        lower.contains("download latest apk") ||
                        lower.contains("build & package details") ||
                        lower.contains("latest build summary") ||
                        lower.contains("build summary") ||
                        lower.contains("seamless upgrade") ||
                        lower.contains("upgrade & downgrade compatibility") ||
                        lower.contains("in-place updates") ||
                        lower.contains("switch versions anytime") ||
                        lower.contains("install older or newer") ||
                        lower.contains("looking for an older") ||
                        lower.contains("github.com/") ||
                        lower.startsWith("## offgrid music") ||
                        lower.startsWith("# offgrid music") ||
                        lower.matches(Regex("^#+\\s*(?:offgrid music|what'?s new|changes|fixes|improvements|changelog).*"))

                if (isBoilerplate) continue

                var text = stripEmojis(trimmed)
                    .replace(Regex("<[^>]*>"), "")
                    .replace(Regex("^#+\\s*"), "")
                    .replace("**", "")
                    .replace("__", "")
                    .replace("`", "")
                    .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
                    .replace(Regex("\\s*\\(#[0-9]+\\)\\s*$"), "")
                    .replace(Regex("\\s*\\([0-9a-f]{7,40}\\)\\s*$"), "")
                    .replace(Regex("^[-*•]\\s+\\[[xX ]\\]\\s*"), "")
                    .replace(Regex("^[-*•]\\s+"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()

                if (text.isBlank()) continue
                val textLower = text.lowercase(Locale.US)
                if (textLower == "what's new" ||
                    textLower == "whats new" ||
                    textLower.startsWith("offgrid music") ||
                    textLower.startsWith("merge pull request") ||
                    textLower.startsWith("merge branch") ||
                    textLower.startsWith("merge remote-tracking")
                ) {
                    continue
                }

                // Convert raw conventional-commit prefixes (fix:, feat:, perf:, refactor:) into natural language
                val prefixMatch = CONVENTIONAL_COMMIT_REGEX.find(text)
                if (prefixMatch != null) {
                    val kind = prefixMatch.groupValues[1].lowercase(Locale.US)
                    val rest = text.substring(prefixMatch.range.last + 1).trim()
                    if (rest.isNotBlank()) {
                        val verb = when (kind) {
                            "fix", "bugfix", "hotfix" -> "Fixed"
                            "feat", "feature", "add" -> "Added"
                            "perf", "opt", "optimize" -> "Improved"
                            "style", "ui", "ux" -> "Refined"
                            "refactor", "chore", "build", "ci", "docs" -> "Updated"
                            else -> ""
                        }
                        text = if (verb.isNotEmpty() && !rest.startsWith(verb, ignoreCase = true)) {
                            val loweredFirst = rest.replaceFirstChar { it.lowercase(Locale.US) }
                            "$verb $loweredFirst"
                        } else {
                            rest
                        }
                    }
                }

                // Sentence-case and ensure clean punctuation
                text = text.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
                }
                if (text.length < 4) continue

                val key = text.lowercase(Locale.US)
                if (seen.add(key)) {
                    cleaned.add("• $text")
                }
                if (cleaned.size >= 8) break
            }

            return if (cleaned.isEmpty()) {
                "• Fixed stability issues and improved playback reliability."
            } else {
                cleaned.joinToString("\n")
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


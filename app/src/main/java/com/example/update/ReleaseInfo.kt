package com.example.update

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
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

    data class ChangelogSection(
        val heading: String?,
        val items: List<String>
    )

    data class ChangelogDocument(
        val intro: String? = null,
        val sections: List<ChangelogSection> = emptyList()
    )

    /**
     * Cleans up raw markdown notes so they render cleanly inside native Compose cards
     * without unwanted raw markdown syntax artifacts, while preserving readable bullet points.
     */
    fun getSanitizedNotes(): String {
        return sanitizeReleaseNotes(body)
    }

    /**
     * Parses release notes into clean human-readable sections (intro + headings + bullets),
     * stripping GitHub tags, release headers, commit hashes, download links, and CI tables.
     */
    fun getStructuredChangelog(): ChangelogDocument {
        return parseStructuredChangelog(body)
    }

    fun getFormattedApkSize(): String {
        if (apkSize <= 0L) return ""
        val mb = apkSize / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    /**
     * Serializes this release (including any aggregated multi-release changelog body)
     * into the GitHub-compatible JSON format stored in local cache.
     */
    fun toCacheJsonString(): String {
        val root = JSONObject()
        root.put("tag_name", tagName)
        root.put("name", name)
        root.put("body", body)
        root.put("published_at", publishedAt)
        root.put("html_url", htmlUrl)
        root.put("draft", isDraft)
        root.put("prerelease", isPrerelease)
        if (!apkDownloadUrl.isNullOrBlank()) {
            val asset = JSONObject()
            asset.put("name", apkAssetName ?: "OffGrid-Music.apk")
            asset.put("browser_download_url", apkDownloadUrl)
            asset.put("size", apkSize)
            asset.put("content_type", "application/vnd.android.package-archive")
            val assets = JSONArray()
            assets.put(asset)
            root.put("assets", assets)
        }
        return root.toString()
    }

    companion object {
        const val DEFAULT_RELEASE_URL = "https://github.com/Paul00755/OffGrid-Music/releases"
        const val LATEST_APK_DOWNLOAD_URL = "https://github.com/Paul00755/OffGrid-Music/releases/latest/download/OffGrid-Music.apk"

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

        private val TAG_OR_VERSION_LINE_REGEX = Regex(
            "^(?:release\\s+|tag\\s*:?\\s*|version\\s+)?v?\\d+\\.\\d+(?:\\.\\d+)?(?:[-+][a-zA-Z0-9._-]+)?$",
            RegexOption.IGNORE_CASE
        )

        fun parseStructuredChangelog(notes: String): ChangelogDocument {
            if (notes.isBlank()) {
                return ChangelogDocument(
                    sections = listOf(
                        ChangelogSection(
                            heading = "Highlights",
                            items = listOf("Fixed stability issues and improved playback reliability.")
                        )
                    )
                )
            }

            val introParts = mutableListOf<String>()
            val sections = mutableListOf<Pair<String?, MutableList<String>>>()
            var currentSection: Pair<String?, MutableList<String>>? = null
            val seenBullets = LinkedHashSet<String>()

            fun getOrCreateSection(heading: String?): Pair<String?, MutableList<String>> {
                val existingCurrent = currentSection
                if (existingCurrent != null && existingCurrent.first.equals(heading, ignoreCase = true)) {
                    return existingCurrent
                }
                val matchingSection = sections.firstOrNull { it.first.equals(heading, ignoreCase = true) }
                if (matchingSection != null) {
                    currentSection = matchingSection
                    return matchingSection
                }
                val created = heading to mutableListOf<String>()
                sections.add(created)
                currentSection = created
                return created
            }

            for (rawLine in notes.lines()) {
                val trimmed = rawLine.trim()
                if (trimmed.isBlank()) continue
                val lower = trimmed.lowercase(Locale.US)

                // Strip unwanted GitHub/release/tag/CI/download boilerplate
                val isUnwantedMeta =
                    lower.startsWith("sha-256:") ||
                        lower.startsWith("sha256:") ||
                        lower.startsWith("|") ||
                        lower.startsWith(">") ||
                        lower.startsWith("---") ||
                        lower.startsWith("***") ||
                        lower.startsWith("full changelog") ||
                        lower.startsWith("tag:") ||
                        lower.startsWith("release tag") ||
                        lower.startsWith("releases:") ||
                        lower.startsWith("release:") ||
                        lower.startsWith("compare:") ||
                        lower.startsWith("build:") ||
                        lower.startsWith("commit:") ||
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
                        lower.contains("apk checksum") ||
                        lower.startsWith("merge pull request") ||
                        lower.startsWith("merge branch") ||
                        lower.startsWith("merge remote-tracking")

                if (isUnwantedMeta) continue

                val isMarkdownHeading = trimmed.startsWith("#")
                val isBulletLine = trimmed.startsWith("-") ||
                    trimmed.startsWith("*") ||
                    trimmed.startsWith("•") ||
                    Regex("^\\d+[.)]\\s+").containsMatchIn(trimmed)

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
                    .replace(Regex("^\\d+[.)]\\s+"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()

                if (text.isBlank() || text.length < 3) continue
                val textLower = text.lowercase(Locale.US)

                // Drop title/tag/release lines like "OffGrid Music v1.4.8", "v1.4.8-build.12", "Release v1.4.8", "Tag v1.4.8"
                if (TAG_OR_VERSION_LINE_REGEX.matches(text) ||
                    textLower.startsWith("offgrid music") ||
                    textLower == "changelog" ||
                    textLower == "release notes" ||
                    textLower == "releases" ||
                    textLower.startsWith("releases/") ||
                    textLower.startsWith("tags/")
                ) {
                    continue
                }

                // Detect clean section subheadings (e.g., "## Highlights", "### What's Fixed", "Playback", "Improvements:")
                val looksLikeSubheading = (isMarkdownHeading || (!isBulletLine && text.endsWith(":") && text.length <= 36) ||
                    (!isBulletLine && text.length <= 28 && !text.contains('.') &&
                        textLower in setOf(
                            "highlights", "what's new", "whats new", "new features", "features",
                            "improvements", "enhancements", "fixes", "bug fixes", "what's fixed",
                            "playback", "audio", "ui", "ui & theme", "library", "playlists",
                            "lyrics", "jam", "offgrid jam", "sync", "performance", "stability"
                        )))

                if (looksLikeSubheading) {
                    val headingClean = text.removeSuffix(":").trim()
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                    if (headingClean.isNotBlank() &&
                        !headingClean.equals("Changelog", ignoreCase = true) &&
                        !headingClean.equals("Release Notes", ignoreCase = true)
                    ) {
                        getOrCreateSection(headingClean)
                    }
                    continue
                }

                // Convert raw conventional-commit prefixes (fix:, feat:, perf:) into friendly prose
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
                            "$verb ${rest.replaceFirstChar { it.lowercase(Locale.US) }}"
                        } else {
                            rest
                        }
                    }
                }

                text = text.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
                }
                if (text.length < 4) continue

                if (!isBulletLine && sections.isEmpty() && introParts.size < 2 && text.length >= 24) {
                    introParts.add(text)
                } else {
                    val dedupeKey = text.lowercase(Locale.US)
                    if (seenBullets.add(dedupeKey)) {
                        val target = currentSection ?: getOrCreateSection("Highlights")
                        target.second.add(text)
                    }
                }
            }

            val nonEmptySections = sections
                .filter { it.second.isNotEmpty() }
                .map { ChangelogSection(heading = it.first, items = it.second.take(18)) }

            val introText = introParts.joinToString(" ").takeIf { it.isNotBlank() }

            if (nonEmptySections.isEmpty() && introText == null) {
                return ChangelogDocument(
                    sections = listOf(
                        ChangelogSection(
                            heading = "Highlights",
                            items = listOf("Fixed stability issues and improved playback reliability.")
                        )
                    )
                )
            }

            return ChangelogDocument(
                intro = introText,
                sections = nonEmptySections
            )
        }

        fun fromJsonString(jsonString: String): ReleaseInfo? {
            if (jsonString.isBlank()) return null
            return try {
                fromJson(JSONObject(jsonString))
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Parses a GitHub `/releases` JSON array, filtering out drafts, prereleases, and
         * duplicate rolling tags, and returns valid stable releases sorted newest-first.
         */
        fun parseReleaseListJson(jsonArrayString: String): List<ReleaseInfo> {
            if (jsonArrayString.isBlank()) return emptyList()
            return try {
                val array = JSONArray(jsonArrayString)
                val byVersion = LinkedHashMap<AppVersion, ReleaseInfo>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val rawTag = obj.optString("tag_name", "").trim()
                    val parsed = fromJson(obj) ?: continue
                    val existing = byVersion[parsed.version]
                    if (existing == null) {
                        byVersion[parsed.version] = parsed
                    } else {
                        // Prefer semantic tag over rolling "latest" tag, or richer release body/APK
                        val incomingIsRolling = rawTag.equals("latest", ignoreCase = true)
                        val preferIncoming = !incomingIsRolling && (
                            (parsed.isApkAttached && !existing.isApkAttached) ||
                                parsed.body.length > existing.body.length
                            )
                        if (preferIncoming) {
                            byVersion[parsed.version] = parsed
                        }
                    }
                }
                byVersion.values.sortedWith { a, b ->
                    when {
                        b.version.isNewerThan(a.version) -> 1
                        a.version.isNewerThan(b.version) -> -1
                        else -> 0
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        /**
         * Combines changelogs across all releases newer than the user's installed version
         * into one unified, deduplicated Markdown changelog without repeating tags or headings.
         */
        fun aggregateChangelogBodies(releasesNewestFirst: List<ReleaseInfo>): String {
            if (releasesNewestFirst.isEmpty()) return ""
            if (releasesNewestFirst.size == 1) return releasesNewestFirst.first().body

            val mergedSections = LinkedHashMap<String, MutableList<String>>()
            val seenBullets = LinkedHashSet<String>()
            var totalItems = 0
            val maxAggregatedItems = 20

            for (release in releasesNewestFirst) {
                val doc = parseStructuredChangelog(release.body)
                for (section in doc.sections) {
                    val heading = section.heading?.takeIf { it.isNotBlank() } ?: "Highlights"
                    val canonicalHeading = when (heading.lowercase(Locale.US)) {
                        "what's new", "whats new", "new features", "features", "highlights" -> "Highlights"
                        "improvements", "enhancements", "performance", "stability" -> "Improvements"
                        "fixes", "bug fixes", "what's fixed" -> "Fixes"
                        else -> heading
                    }
                    val targetList = mergedSections.getOrPut(canonicalHeading) { mutableListOf() }
                    for (item in section.items) {
                        if (totalItems >= maxAggregatedItems) break
                        val key = item.lowercase(Locale.US)
                        if (seenBullets.add(key)) {
                            targetList.add(item)
                            totalItems++
                        }
                    }
                    if (totalItems >= maxAggregatedItems) break
                }
                if (totalItems >= maxAggregatedItems) break
            }

            if (mergedSections.isEmpty()) {
                return releasesNewestFirst.first().body
            }

            val preferredOrder = listOf("Highlights", "Improvements", "Fixes")
            val orderedKeys = buildList {
                for (k in preferredOrder) {
                    if (mergedSections.containsKey(k)) add(k)
                }
                for (k in mergedSections.keys) {
                    if (k !in this) add(k)
                }
            }

            return orderedKeys.mapNotNull { heading ->
                val items = mergedSections[heading].orEmpty()
                if (items.isEmpty()) {
                    null
                } else {
                    buildString {
                        append("### ").append(heading).append('\n')
                        items.forEachIndexed { idx, bullet ->
                            append("- ").append(bullet)
                            if (idx < items.lastIndex) append('\n')
                        }
                    }
                }
            }.joinToString("\n\n")
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
                            // Never redirect users to version-numbered APK links; always point to the canonical latest OffGrid-Music.apk
                            apkUrl = LATEST_APK_DOWNLOAD_URL
                            if (apkName == null || assetName.equals("OffGrid-Music.apk", ignoreCase = true)) {
                                apkName = assetName
                                apkSize = asset.optLong("size", 0L)
                            }
                            if (assetName.equals("OffGrid-Music.apk", ignoreCase = true)) {
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


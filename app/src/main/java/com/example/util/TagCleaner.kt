package com.example.util

object TagCleaner {

    private val SOURCE_KEYWORDS = listOf(
        "mp3chetta", "mp3chetta.com", "mp3chetta_com", "pagalworld", "pagal-world", "pagalsong",
        "pagalworldcom", "pagalworld.com", "pagalfree", "pagalnew", "mp3pawan", "masstamilan",
        "masstamilan.so", "masstamilan.org", "masstamilan.dev", "masstamilan.in", "masstamilan.com",
        "isaimini", "starmusiq", "5starmusiq", "sensongsmp3", "sensongs", "kuttyweb", "tamildada",
        "songspk", "mr-jatt", "mrjatt", "pendujatt", "djpunjab", "djmaza", "wapking", "ytmp3",
        "tubidy", "yt1s", "y2mate", "320kbps", "128kbps", "64kbps", "vbr", "hq", "hd", "kbps",
        "tamiltunes", "saavn", "gaana", "wynk", "spotify", "naasongs", "mp3juices", "mp3quack",
        "mp3mad", "djsong", "ringtone", "audios", "soundtrack", "hungama", "jiosaavn", "mobcup",
        "zedge", "songslover", "freshmaza", "topic", "vevo", "records", "channel"
    )

    private val DOMAIN_EXTENSIONS = listOf(
        "so", "dev", "in", "io", "com", "net", "org", "co", "fm", "xyz", "info", "cc", "site",
        "download", "me", "biz", "tv", "app", "mobi", "tk", "co.in", "tech", "online",
        "store", "vip", "link", "top", "club", "is", "to", "ws"
    )

    // Precompiled regex patterns for high performance
    private val WWW_REGEX = Regex("(?i)\\bwww\\.[a-zA-Z0-9-]+\\.[a-zA-Z0-9.]+\\b")
    private val sourceKwPattern = SOURCE_KEYWORDS.joinToString("|") { Regex.escape(it) }
    private val domainExtPattern = DOMAIN_EXTENSIONS.joinToString("|") { Regex.escape(it) }

    private val BRACKETED_SOURCES_REGEX = Regex("(?i)[\\[\\(][^\\]\\)]*?(?:" + sourceKwPattern + "|\\.(?:" + domainExtPattern + "))[^\\]\\)]*?[\\]\\)]")
    private val BRACKETED_LABELS_REGEX = Regex(
        "(?i)[\\[\\(][^\\]\\)]*?(?:" +
        "320|128|256|64|kbps|mp3|download|video\\s*song|full\\s*video|official\\s*video|lyric\\s*video|audio\\s*song|" +
        "official\\s*audio|movie\\s*song|movie\\s*name|video|movie|soundtrack|singer|singers|artist|composed by|sung by|music by|" +
        "masstamilan|pagal\\s*world|pagalworld|pagalsong|tamil|malayalam|telugu|hindi|kannada|" +
        "remaster|remastered|deluxe|edition|bonus|extended|track|album|song" +
        ")[^\\]\\)]*?[\\]\\)]"
    )
    private val STANDALONE_VIDEO_AUDIO_REGEX = Regex("(?i)[\\s-_~|/\\\\]*(?:^|(?<=[^a-zA-Z0-9]))(?:video\\s*song|full\\s*video(?:\\s*song)?|official\\s*video(?:\\s*song)?|lyric(?:s)?\\s*video|audio\\s*song|full\\s*audio(?:\\s*song)?|official\\s*audio)(?:$|(?=[^a-zA-Z0-9]))[\\s-_~|/\\\\]*")
    private val WEBSITE_KEYWORDS_REGEX = Regex("(?i)[\\s-_~|/\\\\]*(?:^|(?<=[^a-zA-Z0-9]))(?:$sourceKwPattern)(?:\\.(?:$domainExtPattern))?(?:$|(?=[^a-zA-Z0-9]))[\\s-_~|/\\\\]*")
    private val STANDALONE_DOMAINS_REGEX = Regex("(?i)[\\s-_~|/\\\\]*(?:^|(?<=[^a-zA-Z0-9]))[a-zA-Z0-9-]+\\.(?:" + domainExtPattern + ")(?:$|(?=[^a-zA-Z0-9]))[\\s-_~|/\\\\]*")
    private val LEADING_TRACK_NUM_REGEX = Regex("^\\d{1,3}\\s*[-._]\\s*")
    private val TRAILING_DOMAIN_REGEX = Regex("(?i)\\.(?:" + domainExtPattern + ")(?:$|(?=[^a-zA-Z0-9]))")
    private val MASSTAMILAN_EXT_REGEX = Regex("(?i)\\.masstamilan[a-z0-9._-]*(?:$|(?=[^a-zA-Z0-9]))")
    private val KBPS_REGEX = Regex("(?i)(?:^|(?<=[^a-zA-Z0-9]))(?:320|128|256|64)\\s*kbps(?:$|(?=[^a-zA-Z0-9]))")
    private val EMPTY_BRACKETS_REGEX = Regex("[\\[\\(]\\s*[\\]\\)]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val EDGE_PUNCT_REGEX = Regex("^[\\s-_~|/\\\\]+|[\\s-_~|/\\\\]+$")
    private val FALLBACK_CLEAN_REGEX = Regex("(?i)[\\s-_~|]*(?:^|(?<=[^a-zA-Z0-9]))(?:$sourceKwPattern)(?:\\.(?:$domainExtPattern))?(?:$|(?=[^a-zA-Z0-9]))[\\s-_~|]*")

    // Meaningful hyphenated terms to preserve when normalizing separator hyphens
    private val PRESERVED_HYPHENATED_WORDS = setOf(
        "lo-fi", "hi-fi", "wi-fi", "sci-fi",
        "k-pop", "j-pop", "c-pop", "hip-hop", "chill-hop", "trip-hop",
        "synth-pop", "electro-pop", "indie-pop", "dream-pop", "dance-pop", "art-pop",
        "alt-rock", "post-rock", "pop-rock", "hard-rock", "punk-rock", "soft-rock", "prog-rock",
        "g-funk", "p-funk", "nu-metal", "post-punk", "synth-wave", "chill-out",
        "8-bit", "16-bit", "32-bit", "sub-bass", "multi-track",
        "a-ha", "jay-z", "ne-yo", "t-pain", "q-tip", "b-52", "blink-182", "sum-41", "alt-j", "wu-tang", "g-eazy",
        "x-ray", "spider-man", "ant-man", "pac-man", "he-man", "obi-wan",
        "co-op", "re-entry", "re-issue", "non-stop", "self-titled",
        "all-star", "all-time", "one-stop", "two-step", "half-time", "full-time"
    )
    private val HYPHENATED_TOKEN_REGEX = Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+")
    private val LOOSE_DASH_SEPARATOR_REGEX = Regex("\\s*[-–—]+\\s*")

    // Controlled release/source suffix normalization regex (preserves Part 1/2, Version 2, Remix, Live, Acoustic, Instrumental)
    private val SOURCE_WEBSITE_SUFFIX_REGEX = Regex(
        "(?i)(?:\\s*[-–—_~|/:.]+\\s*|\\s+)" +
        "(?:" +
            "masstamilan[a-z0-9._-]*|" +
            "pagal\\s*world[a-z0-9._-]*|pagalworld[a-z0-9._-]*|pagalsong[a-z0-9._-]*|pagalfree[a-z0-9._-]*|pagalnew[a-z0-9._-]*|" +
            "isaimini[a-z0-9._-]*|starmusiq[a-z0-9._-]*|5starmusiq[a-z0-9._-]*|" +
            "kuttyweb[a-z0-9._-]*|tamildada[a-z0-9._-]*|tamiltunes[a-z0-9._-]*|" +
            "songspk[a-z0-9._-]*|mr[- ]*jatt[a-z0-9._-]*|pendujatt[a-z0-9._-]*|" +
            "djpunjab[a-z0-9._-]*|djmaza[a-z0-9._-]*|wapking[a-z0-9._-]*|" +
            "naasongs[a-z0-9._-]*|sensongs(?:mp3)?[a-z0-9._-]*|mp3chetta(?:_com)?[a-z0-9._-]*|" +
            "mp3juices?[a-z0-9._-]*|mp3quack[a-z0-9._-]*|mp3mad[a-z0-9._-]*|mp3pawan[a-z0-9._-]*|" +
            "ytmp3[a-z0-9._-]*|tubidy[a-z0-9._-]*|yt1s[a-z0-9._-]*|y2mate[a-z0-9._-]*|" +
            "mobcup[a-z0-9._-]*|zedge[a-z0-9._-]*|songslover[a-z0-9._-]*|freshmaza[a-z0-9._-]*|" +
            "(?:320|128|256|64)\\s*kbps|kbps|hq|hd|cdrip|dvdrip|webrip" +
        ")\\s*$"
    )

    private val TRAILING_DOMAIN_SUFFIX_REGEX = Regex(
        "(?i)(?:\\s*[-–—_~|/:]?\\s*\\.(?:$domainExtPattern))\\s*$"
    )

    // Controlled generic noise suffix regex — strictly requires explicit separator preceding it
    // to avoid deleting words when they legitimately belong to the title
    private val GENERIC_NOISE_SUFFIX_REGEX = Regex(
        "(?i)(?:\\s*[-–—_~|/:]\\s*)" +
        "(?:" +
            "video\\s*song|full\\s*video(?:\\s*song)?|official\\s*video(?:\\s*song)?|lyric(?:s)?\\s*video|" +
            "audio\\s*song|full\\s*audio(?:\\s*song)?|official\\s*audio|" +
            "movie\\s*song|movie\\s*name|" +
            "tamil|malayalam|telugu|hindi|kannada|" +
            "video|movie|album|song" +
        ")\\s*$"
    )

    fun stripUnwantedReleaseSuffixes(title: String): String {
        var text = title.trim()
        var iterations = 0
        while (iterations < 8) {
            var next = text.replace(SOURCE_WEBSITE_SUFFIX_REGEX, "")
                .trim('-', '_', ' ', '|', '~', ':', '–', '—', '/', '\\', '.')
            if (next.isNotBlank() && next != text) {
                text = next
                iterations++
                continue
            }
            next = text.replace(TRAILING_DOMAIN_SUFFIX_REGEX, "")
                .trim('-', '_', ' ', '|', '~', ':', '–', '—', '/', '\\', '.')
            if (next.isNotBlank() && next != text) {
                text = next
                iterations++
                continue
            }
            next = text.replace(GENERIC_NOISE_SUFFIX_REGEX, "")
                .trim('-', '_', ' ', '|', '~', ':', '–', '—', '/', '\\', '.')
            if (next.isNotBlank() && next != text) {
                text = next
                iterations++
                continue
            }
            break
        }
        return text.ifBlank { title }
    }

    /**
     * Normalizes unwanted `_` and `-` filename/title separators into spaces while preserving
     * meaningful hyphenated compound words (e.g. `Lo-Fi`, `Hi-Fi`, `K-Pop`, `Hip-Hop`, `Spider-Man`).
     */
    private fun normalizeTitleSeparators(input: String): String {
        // 1. Replace all underscores with spaces
        var s = if (input.indexOf('_') >= 0) input.replace('_', ' ') else input

        // 2. Normalize hyphens/dashes into spaces except for preserved hyphenated words
        if (s.indexOf('-') >= 0 || s.indexOf('–') >= 0 || s.indexOf('—') >= 0) {
            // Protect preserved hyphenated tokens first using null-char sentinel
            val protectedTokens = mutableListOf<String>()
            s = HYPHENATED_TOKEN_REGEX.replace(s) { matchResult ->
                val token = matchResult.value
                if (token.lowercase() in PRESERVED_HYPHENATED_WORDS) {
                    val idx = protectedTokens.size
                    protectedTokens.add(token)
                    "\u0000$idx\u0000"
                } else {
                    token.replace('-', ' ')
                }
            }
            // Replace any remaining standalone or spaced dashes/hyphens with a single space
            s = s.replace(LOOSE_DASH_SEPARATOR_REGEX, " ")
            // Restore protected hyphenated compounds
            for (i in protectedTokens.indices) {
                s = s.replace("\u0000$i\u0000", protectedTokens[i])
            }
        }
        return s
    }

    // Ultra-fast LRU cache for already cleaned strings
    private class FastLruCache<K, V>(private val maxSize: Int) : java.util.LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    private val cleanTitleCache = FastLruCache<String, String>(2000)
    private val cleanArtistCache = FastLruCache<String, String>(1000)
    private val cleanAlbumCache = FastLruCache<String, String>(1000)

    fun cleanTitle(rawTitle: String?, rawFilename: String): String {
        val cacheKey = "${rawTitle ?: ""}|$rawFilename"
        synchronized(cleanTitleCache) {
            cleanTitleCache.get(cacheKey)?.let { return it }
        }

        var text = rawTitle?.takeIf { it.isNotBlank() } ?: rawFilename.substringBeforeLast('.')

        // Strip known file extensions if embedded in title or filename
        val lowerText = text.lowercase()
        if (lowerText.endsWith(".mp3") || lowerText.endsWith(".flac") || lowerText.endsWith(".m4a") ||
            lowerText.endsWith(".aac") || lowerText.endsWith(".ogg") || lowerText.endsWith(".wav") ||
            lowerText.endsWith(".opus") || lowerText.endsWith(".wma")
        ) {
            text = text.substringBeforeLast('.')
        }

        // 0. Remove www. prefix if present
        text = text.replace(WWW_REGEX, "")

        // 1. Remove bracketed / parenthesized tags containing website sources, domains, or release labels
        text = text.replace(BRACKETED_SOURCES_REGEX, "")
        text = text.replace(BRACKETED_LABELS_REGEX, "")
        text = text.replace(STANDALONE_VIDEO_AUDIO_REGEX, " ")

        // 2. Strip unwanted release/source suffixes (e.g., "- masstamilan", "_masstamilan", "- pagal world - video song", "- Tamil")
        text = stripUnwantedReleaseSuffixes(text)

        // 3. Remove standalone explicit website/source strings anywhere in the title
        text = text.replace(WEBSITE_KEYWORDS_REGEX, " ")

        // 4. Remove standalone domain tags
        text = text.replace(STANDALONE_DOMAINS_REGEX, " ")

        // 5. Remove leading track numbers or prefixes (e.g. "01 - " or "01. " or "01_")
        text = text.replace(LEADING_TRACK_NUM_REGEX, "")

        // 6. Remove leftover trailing domain extensions like ".so", ".com", or audio quality tags
        text = text.replace(TRAILING_DOMAIN_REGEX, "")
        text.let {
            text = it.replace(MASSTAMILAN_EXT_REGEX, "")
        }
        text = text.replace(KBPS_REGEX, "")

        // 7. Strip unwanted release/source suffixes again after removing in-between noise
        text = stripUnwantedReleaseSuffixes(text)

        // 8. Normalize unwanted `_` and `-` filename/title separators into clean spaces
        // while preserving meaningful hyphenated terms (e.g. Lo-Fi)
        text = normalizeTitleSeparators(text)

        // 9. Clean up duplicate spaces, empty brackets, leading/trailing hyphens/underscores/dots
        text = text.replace(EMPTY_BRACKETS_REGEX, "")
        text = text.replace(MULTI_SPACE_REGEX, " ")
        text = text.trim('-', '_', ' ', '|', '~', '.', ':', '–', '—', '/', '\\')
        if (text.contains('(') != text.contains(')')) {
            text = text.trim('(', ')')
        }
        if (text.contains('[') != text.contains(']')) {
            text = text.trim('[', ']')
        }

        // If title ends or starts with a trailing dash or bracketed tag
        text = text.replace(EDGE_PUNCT_REGEX, "")

        // Final check on release suffixes after edge punctuation cleanup
        text = stripUnwantedReleaseSuffixes(text)
        text = text.replace(MULTI_SPACE_REGEX, " ").trim()

        // Fallback if cleaning erased everything
        if (text.isBlank()) {
            val fallbackName = rawFilename.substringBeforeLast('.')
            text = normalizeTitleSeparators(
                fallbackName.replace(FALLBACK_CLEAN_REGEX, " ")
            ).replace(MULTI_SPACE_REGEX, " ")
                .trim('-', '_', ' ', '[', ']', '(', ')', '.')
            if (text.isBlank()) text = fallbackName
        }

        val result = text.ifBlank { rawFilename.substringBeforeLast('.') }
        synchronized(cleanTitleCache) {
            cleanTitleCache.put(cacheKey, result)
        }
        return result
    }

    fun cleanArtist(rawArtist: String?): String {
        if (rawArtist.isNullOrBlank()) return "Unknown Artist"
        val trimmed = rawArtist.trim()
        if (trimmed.equals("unknown", ignoreCase = true) || trimmed.equals("<unknown>", ignoreCase = true)) {
            return "Unknown Artist"
        }
        synchronized(cleanArtistCache) {
            cleanArtistCache.get(trimmed)?.let { return it }
        }
        // If plain string without brackets, domains, or channel tags, fast return
        if (!trimmed.contains('[') && !trimmed.contains('(') && !trimmed.contains('.') &&
            !trimmed.contains("www", ignoreCase = true) && !trimmed.contains("topic", ignoreCase = true) &&
            !trimmed.contains("vevo", ignoreCase = true) && !trimmed.contains("official", ignoreCase = true) &&
            !trimmed.contains("records", ignoreCase = true) && !trimmed.contains("channel", ignoreCase = true)
        ) {
            val clean = trimmed.trim('-', '_', ' ', '|', '~', ':', '–', '—', '/', '\\')
            val res = if (clean.isBlank()) "Unknown Artist" else clean
            synchronized(cleanArtistCache) { cleanArtistCache.put(trimmed, res) }
            return res
        }
        var text = cleanTitle(trimmed, "")
        text = text.replace(Regex("(?i)\\s*[-/|~]\\s*(?:Topic|Vevo|Records|Channel)\\b"), " ")
        text = text.replace(Regex("(?i)\\b(?:Topic|Vevo|Records|Channel)\\b"), " ")
        text = text.replace(WEBSITE_KEYWORDS_REGEX, " ")
        text = text.replace(EDGE_PUNCT_REGEX, "")
        text = text.trim('-', '_', ' ', '|', '~', ':', '–', '—', '/', '\\')
        val result = if (text.isBlank() || text.equals("unknown", ignoreCase = true)) "Unknown Artist" else text
        synchronized(cleanArtistCache) {
            cleanArtistCache.put(trimmed, result)
        }
        return result
    }

    fun cleanAlbum(rawAlbum: String?): String {
        if (rawAlbum.isNullOrBlank()) return "Unknown Album"
        val trimmed = rawAlbum.trim()
        if (trimmed.equals("unknown", ignoreCase = true) || trimmed.equals("<unknown>", ignoreCase = true)) {
            return "Unknown Album"
        }
        synchronized(cleanAlbumCache) {
            cleanAlbumCache.get(trimmed)?.let { return it }
        }
        if (!trimmed.contains('[') && !trimmed.contains('(') && !trimmed.contains('.') &&
            !trimmed.contains("www", ignoreCase = true) && !trimmed.contains("deluxe", ignoreCase = true) &&
            !trimmed.contains("edition", ignoreCase = true) && !trimmed.contains("remaster", ignoreCase = true)
        ) {
            val clean = trimmed.trim('-', '_', ' ', '|', '~', ':', '–', '—', '/', '\\')
            val res = if (clean.isBlank()) "Unknown Album" else clean
            synchronized(cleanAlbumCache) { cleanAlbumCache.put(trimmed, res) }
            return res
        }
        val text = cleanTitle(trimmed, "")
        val result = if (text.isBlank() || text.equals("unknown", ignoreCase = true)) "Unknown Album" else text
        synchronized(cleanAlbumCache) {
            cleanAlbumCache.put(trimmed, result)
        }
        return result
    }
}

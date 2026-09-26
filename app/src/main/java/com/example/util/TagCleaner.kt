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

    fun cleanTitle(rawTitle: String?, rawFilename: String): String {
        var text = rawTitle?.takeIf { it.isNotBlank() } ?: rawFilename.substringBeforeLast('.')

        // 0. Remove www. prefix if present
        text = text.replace(Regex("(?i)\\bwww\\.[a-zA-Z0-9-]+\\.[a-zA-Z0-9.]+\\b"), "")

        val sourceKwPattern = SOURCE_KEYWORDS.joinToString("|") { Regex.escape(it) }
        val domainExtPattern = DOMAIN_EXTENSIONS.joinToString("|") { Regex.escape(it) }

        // 1. Remove bracketed / parenthesized tags containing website sources, domains, or .so / .com / masstamilan / pagalworld
        text = text.replace(Regex("(?i)[\\[\\(][^\\]\\)]*?(?:" + sourceKwPattern + "|\\.(?:" + domainExtPattern + "))[^\\]\\)]*?[\\]\\)]"), "")
        
        // Remove bracketed / parenthesized tags containing audio/video/movie/artist labels e.g. [Video Song], [Official Video], [Movie Name], [Artist Name in song label]
        text = text.replace(Regex("(?i)[\\[\\(][^\\]\\)]*?(?:320|128|256|64|kbps|mp3|download|video song|full video|official video|lyric video|audio song|video|movie|soundtrack|singer|singers|artist|composed by|sung by|music by|masstamilan|pagalworld|remix|mix|edit|remaster|remastered|deluxe|edition|bonus|live|extended|version|track)[^\\]\\)]*?[\\]\\)]"), "")

        // 2. Remove standalone phrases like "video song", "official video", "audio song", "full song"
        text = text.replace(Regex("(?i)[\\s-_~|/\\\\]*\\b(?:video\\s*song|full\\s*video(?:\\s*song)?|official\\s*video(?:\\s*song)?|lyric(?:s)?\\s*video|audio\\s*song|full\\s*audio(?:\\s*song)?|official\\s*audio)\\b[\\s-_~|/\\\\]*"), " ")

        // 3. Remove explicit website/source strings anywhere in the title
        text = text.replace(Regex("(?i)[\\s-_~|/\\\\]*\\b(?:$sourceKwPattern)(?:\\.(?:$domainExtPattern))?\\b[\\s-_~|/\\\\]*"), " ")

        // 4. Remove standalone domain tags like "site.com", "music.in", "masstamilan.so", "something.so"
        text = text.replace(Regex("(?i)[\\s-_~|/\\\\]*\\b[a-zA-Z0-9-]+\\.(?:" + domainExtPattern + ")\\b[\\s-_~|/\\\\]*"), " ")

        // 5. Remove leading/trailing track numbers or prefixes e.g. "01 - ", "01. ", "01 "
        text = text.replace(Regex("^\\d{1,3}\\s*[-._]\\s*"), "")

        // 6. Remove leftover trailing domain extensions like ".so", ".com", ".masstamilan" or audio quality tags
        text = text.replace(Regex("(?i)\\.(?:" + domainExtPattern + ")\\b"), "")
        text = text.replace(Regex("(?i)\\.masstamilan\\b"), "")
        text = text.replace(Regex("(?i)\\b(?:320|128|256|64)\\s*kbps\\b"), "")

        // 7. Clean up duplicate spaces, empty brackets, leading/trailing hyphens/underscores/dots
        text = text.replace(Regex("[\\[\\(]\\s*[\\]\\)]"), "")
        text = text.replace(Regex("\\s+"), " ")
        text = text.trim('-', '_', ' ', '[', ']', '(', ')', '|', '~', '.', ':', '–', '—', '/', '\\')

        // If title ends or starts with a trailing dash or bracketed tag e.g. "Song Name - "
        text = text.replace(Regex("^[\\s-_~|/\\\\]+|[\\s-_~|/\\\\]+$"), "")

        // Fallback if cleaning erased everything
        if (text.isBlank()) {
            val fallbackName = rawFilename.substringBeforeLast('.')
            text = fallbackName
                .replace(Regex("(?i)[\\s-_~|]*\\b(?:$sourceKwPattern)(?:\\.(?:$domainExtPattern))?\\b[\\s-_~|]*"), " ")
                .trim('-', '_', ' ', '[', ']', '(', ')', '.')
            if (text.isBlank()) text = fallbackName
        }

        return text
    }

    fun cleanArtist(rawArtist: String?): String {
        if (rawArtist.isNullOrBlank()) return "Unknown Artist"
        val text = cleanTitle(rawArtist, "")
        return if (text.isBlank() || text.equals("unknown", ignoreCase = true)) "Unknown Artist" else text
    }

    fun cleanAlbum(rawAlbum: String?): String {
        if (rawAlbum.isNullOrBlank()) return "Unknown Album"
        val text = cleanTitle(rawAlbum, "")
        return if (text.isBlank() || text.equals("unknown", ignoreCase = true)) "Unknown Album" else text
    }
}


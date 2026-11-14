package com.example.service

import com.example.data.local.LyricsDao
import com.example.data.local.LyricsEntity
import com.example.data.model.Lyrics
import com.example.data.model.Song
import com.example.util.TagCleaner
import com.example.util.TransliterationUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class LyricsCandidate(
    val id: String,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Int,
    val plainLyrics: String?,
    val syncedLyricsRaw: String?,
    val source: String,
    val isSynced: Boolean = !syncedLyricsRaw.isNullOrBlank() && com.example.util.LrcParser.parseLrcToLines(syncedLyricsRaw).isNotEmpty(),
    val languageLabel: String = LyricsLanguageDetector.detectLanguageLabel(
        plainLyrics = plainLyrics,
        syncedLyricsRaw = syncedLyricsRaw,
        trackName = trackName,
        artistName = artistName,
        albumName = albumName
    ),
    val previewSnippet: String = LyricsLanguageDetector.extractPreviewSnippet(plainLyrics, syncedLyricsRaw)
)

object LyricsLanguageDetector {
    private val TIMESTAMP_REGEX = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")
    private val META_TAG_REGEX = Regex("""^\[(?:ti|ar|al|au|by|length|offset|re|ve):.*]$""", RegexOption.IGNORE_CASE)
    private val WORD_SPLIT_REGEX = Regex("""[^a-zA-Z']+""")

    private val ENGLISH_WORDS = setOf(
        "the", "and", "you", "that", "was", "for", "are", "with", "his", "they", "this", "have",
        "from", "one", "had", "but", "not", "what", "all", "were", "when", "your", "can", "there",
        "which", "she", "how", "their", "will", "other", "about", "out", "many", "then", "them",
        "these", "some", "her", "would", "make", "like", "him", "into", "time", "has", "look",
        "more", "could", "than", "first", "water", "been", "call", "who", "now", "find", "down",
        "day", "did", "get", "come", "made", "may", "love", "heart", "baby", "night", "know",
        "feel", "never", "want", "take", "life", "eyes", "away", "stay", "cause", "yeah", "girl",
        "world", "dream", "back", "through", "nothing", "everything", "always", "something",
        "without", "around", "tonight", "hold", "mind", "soul", "falling", "breaking", "walking",
        "running", "thinking", "waiting", "alone", "again", "before", "after", "every", "where",
        "while", "under", "over", "between", "because", "don't", "can't", "won't", "i'm", "you're",
        "we're", "it's", "that's", "there's", "let", "say", "tell", "give", "keep", "still"
    )

    private val TANGLISH_WORDS = setOf(
        "un", "en", "nee", "naan", "nan", "unai", "unnai", "enai", "ennai", "enthan", "endhan",
        "unthan", "undhan", "kadhal", "kaadhal", "kaathal", "kadhale", "kaadhale", "kanave", "kanavu",
        "kangal", "kankal", "vizhi", "vizhigal", "uyire", "uyirae", "uyir", "idhayam", "ithayam",
        "nenjam", "nenjil", "nenje", "manam", "manasu", "vaanam", "megam", "mazhai", "kaatru",
        "katru", "poove", "pookkal", "malar", "nilavu", "nila", "vannila", "vennila", "iravu",
        "pagal", "neram", "kaalam", "vazhkai", "vaazhkai", "ulagam", "ulagile", "azhage", "azhagi",
        "azhagu", "kanmani", "ponmani", "thangam", "chellam", "kannamma", "mutham", "sirippu",
        "pechu", "paarvai", "parvai", "thooram", "pakkam", "varumo", "varuma", "poga", "pogiren",
        "varugiren", "thaane", "thane", "thaana", "thana", "illai", "illa", "yeno", "yaeno",
        "etho", "yedho", "yetho", "indru", "endrum", "ini", "innum", "ingu", "angu", "engu",
        "eppo", "eppadi", "sollu", "solla", "ketkiren", "paadava", "vaa", "va", "podi", "vaadi",
        "penne", "kanna", "anbe", "anbu", "asai", "aasai", "urave", "thozha", "thozhi", "paravai",
        "kuyil", "thendral", "suvasam", "mouname", "mounam", "ragasiyam", "maayam", "mayam",
        "seithai", "seidhai", "konjam", "nerungi", "irangi", "maranthu", "marandhu", "pirinthu",
        "pirindhu", "valigal", "kanneer", "neeyum", "naanum", "unnodu", "ennodu", "unakkaga",
        "enakkaga", "paarthu", "parthu", "ninaithen", "pesum", "pesava", "vazhi", "vaazhvil"
    )

    private val MANGLISH_WORDS = setOf(
        "ente", "ninte", "njaan", "njan", "ninne", "enne", "ninakku", "enakku", "manassil",
        "hridayam", "pranayam", "sneham", "mizhi", "mizhikal", "ormakal", "orma", "koode",
        "innu", "ennomal", "kinaavu", "kinavu", "raavu", "pakal", "mazha", "kaattu", "poovu",
        "veyil", "aarum", "allathe", "enthe", "enthinu", "evide", "eppol", "angane", "ingane",
        "oru", "kure", "valare", "kandu", "vannu", "poyi", "paranju", "arinju", "marannu",
        "chirichu", "karanju", "paadi", "aaro", "ithu", "athu", "malare", "priye", "sakhi",
        "lokam", "swapnam", "moham", "sukham", "dukkham", "pokumo", "ariyathe", "kaanaathe",
        "kaanathe", "parayaathe", "manju", "konjum", "nenjile", "kiliye", "penne", "keralam",
        "thirike", "varumo", "iniyum", "ivide", "avide", "ozhuki", "unarum", "urakkam"
    )

    private val HINGLISH_WORDS = setOf(
        "mera", "meri", "mere", "tera", "teri", "tere", "tum", "tujhe", "tujhko", "mujhe",
        "mujhko", "hum", "hamein", "humein", "main", "mein", "hai", "hain", "tha", "thi",
        "hoon", "pyaar", "pyar", "ishq", "mohabbat", "dil", "jaan", "zindagi", "duniya",
        "raat", "din", "shaam", "subah", "aankhein", "aankhon", "sapna", "sapne", "khwab",
        "yaad", "yaadein", "saath", "paas", "door", "kabhi", "kahan", "kaise", "kyun", "kyon",
        "kya", "koi", "kuch", "sab", "abhi", "phir", "bhi", "nahi", "nahin", "naa", "mat",
        "aaja", "jaana", "deewana", "mahiya", "sanam", "rabba", "khuda", "maula", "bina",
        "sang", "baatein", "baat", "chahat", "dhadkan", "saansein", "hawa", "baarish", "aasmaan",
        "sitare", "chand", "chaand", "teri", "meri", "kahaani", "kahani", "lamha", "lamhe"
    )

    private val TELUGU_ROMAN_WORDS = setOf(
        "naa", "nenu", "nuvvu", "neetho", "naatho", "prema", "hrudayam", "kanulu", "kallu",
        "voohalu", "oohalu", "chirunavvu", "velugu", "vennela", "vaana", "gaali", "puvvu",
        "ningi", "nela", "kosam", "undhi", "undi", "ledhu", "ledu", "radhu", "vasthava",
        "velipoke", "cheliya", "andham", "amrutham", "pranama", "manasa", "entha", "ela",
        "ekkada", "enduku", "oka", "chinna", "pedda", "kotha", "paatha"
    )

    private val KANNADA_ROMAN_WORDS = setOf(
        "nanna", "ninna", "naanu", "neenu", "preethi", "hrudaya", "kannu", "kanasu", "belaku",
        "male", "hoovu", "bhoomi", "jotheyali", "jothe", "elli", "yake", "hege", "yaaru",
        "bandu", "hoda", "ninage", "nanage", "olave", "usire", "cheluve", "muddu", "gelathi",
        "gelaya", "baalina", "baduku", "sneha", "kuhu"
    )

    private val TAMIL_META_HINTS = listOf(
        "anirudh", "a.r. rahman", "ar rahman", "yuvan", "harris jayaraj", "santhosh narayanan",
        "ilaiyaraaja", "ilayaraja", "dhibu ninan", "gv prakash", "g.v. prakash", "d. imman",
        "imman", "hiphop tamizha", "hiphoptamizha", "dhanush", "s. p. balasubrahmanyam",
        "pradeep kumar", "sean roldan", "sam c.s.", "sam cs", "chinmayi", "karthik",
        "harish raghavendra", "naresh iyer", "vijay antony", "srikanth deva", "deva",
        "vidyasagar", "swetha mohan", "andrea jeremiah", "saindhavi", "vaikom vijayalakshmi",
        "tamil", "kollywood", "aambala", "masstamilan"
    )

    private val MALAYALAM_META_HINTS = listOf(
        "sushin shyam", "gopi sundar", "shaan rahman", "rex vijayan", "jakes bejoy",
        "hesham abdul wahab", "vineeth sreenivasan", "k.j. yesudas", "yesudas", "sithara",
        "govind vasantha", "job kurian", "sooraj santhosh", "sachin warrier", "shahabaz aman",
        "alphons joseph", "bijibal", "m. jayachandran", "oueppachan", "malayalam", "mollywood",
        "premam", "hridayam", "kumbalangi", "manjummel", "aavesham"
    )

    private val HINDI_META_HINTS = listOf(
        "arijit singh", "pritam", "vishal-shekhar", "vishal shekhar", "amit trivedi",
        "atif aslam", "jubin nautiyal", "badshah", "neha kakkar", "shankar-ehsaan-loy",
        "mithoon", "tanishk bagchi", "vishal mishra", "sachin-jigar", "kumar sanu",
        "udit narayan", "alka yagnik", "himesh reshammiya", "yo yo honey singh", "darshan raval",
        "b praak", "jaani", "irshad kamil", "amitabh bhattacharya", "gulzar", "hindi", "bollywood"
    )

    private val TELUGU_META_HINTS = listOf(
        "devi sri prasad", "thaman s", "s. thaman", "m.m. keeravani", "keeravani",
        "mickey j meyer", "anup rubens", "mani sharma", "ram miriyala", "sid sriram telugu",
        "anurag kulkarni", "geetha madhuri", "sunitha", "telugu", "tollywood"
    )

    private val KANNADA_META_HINTS = listOf(
        "arjun janya", "charan raj", "ajaneesh loknath", "mano murthy", "rajesh krishnan",
        "puneeth rajkumar", "sanjith hegde", "vijay prakash kannada", "hamsalekha",
        "gurukiran", "raghu dixit", "kannada", "sandalwood"
    )

    fun extractCleanLyricBody(plainLyrics: String?, syncedLyricsRaw: String?): String {
        val raw = when {
            !plainLyrics.isNullOrBlank() -> plainLyrics
            !syncedLyricsRaw.isNullOrBlank() -> syncedLyricsRaw
            else -> ""
        }
        if (raw.isBlank()) return ""
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !META_TAG_REGEX.matches(it) }
            .map { TIMESTAMP_REGEX.replace(it, "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun extractPreviewSnippet(plainLyrics: String?, syncedLyricsRaw: String?): String {
        val clean = extractCleanLyricBody(plainLyrics, syncedLyricsRaw)
        if (clean.isBlank()) return ""
        return clean.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.length >= 4 &&
                    !line.startsWith("lyrics", ignoreCase = true) &&
                    !line.startsWith("synced by", ignoreCase = true)
            }
            ?.take(72) ?: ""
    }

    fun detectLanguageLabel(
        plainLyrics: String?,
        syncedLyricsRaw: String?,
        trackName: String = "",
        artistName: String = "",
        albumName: String = ""
    ): String {
        val body = extractCleanLyricBody(plainLyrics, syncedLyricsRaw)
        if (body.isBlank()) return "Unknown"

        // 1. Check Native Non-Latin Scripts by Unicode block character counts
        var tamilChars = 0
        var malayalamChars = 0
        var devanagariChars = 0
        var teluguChars = 0
        var kannadaChars = 0
        var bengaliChars = 0
        var gurmukhiChars = 0
        var gujaratiChars = 0
        var sinhalaChars = 0
        var arabicChars = 0
        var hangulChars = 0
        var kanaChars = 0
        var cjkChars = 0
        var cyrillicChars = 0
        var latinChars = 0

        for (ch in body) {
            when (ch) {
                in '\u0B80'..'\u0BFF' -> tamilChars++
                in '\u0D00'..'\u0D7F' -> malayalamChars++
                in '\u0900'..'\u097F' -> devanagariChars++
                in '\u0C00'..'\u0C7F' -> teluguChars++
                in '\u0C80'..'\u0CFF' -> kannadaChars++
                in '\u0980'..'\u09FF' -> bengaliChars++
                in '\u0A00'..'\u0A7F' -> gurmukhiChars++
                in '\u0A80'..'\u0AFF' -> gujaratiChars++
                in '\u0D80'..'\u0DFF' -> sinhalaChars++
                in '\u0600'..'\u06FF' -> arabicChars++
                in '\uAC00'..'\uD7AF', in '\u1100'..'\u11FF' -> hangulChars++
                in '\u3040'..'\u30FF' -> kanaChars++
                in '\u4E00'..'\u9FFF' -> cjkChars++
                in '\u0400'..'\u04FF' -> cyrillicChars++
                in 'a'..'z', in 'A'..'Z' -> latinChars++
            }
        }

        val nativeCounts = listOf(
            "Tamil" to tamilChars,
            "Malayalam" to malayalamChars,
            "Hindi" to devanagariChars,
            "Telugu" to teluguChars,
            "Kannada" to kannadaChars,
            "Bengali" to bengaliChars,
            "Punjabi" to gurmukhiChars,
            "Gujarati" to gujaratiChars,
            "Sinhala" to sinhalaChars,
            "Arabic / Urdu" to arabicChars,
            "Korean" to hangulChars,
            "Japanese" to (kanaChars * 2 + if (kanaChars > 0) cjkChars else 0),
            "Chinese" to if (kanaChars == 0) cjkChars else 0,
            "Russian" to cyrillicChars
        )
        val topNative = nativeCounts.maxByOrNull { it.second }
        if (topNative != null && topNative.second >= 6) {
            return topNative.first
        }

        // 2. Latin-script lyrics: Disambiguate English vs Tanglish, Malayalam (Eng), Hindi (Eng), Telugu (Eng), Kannada (Eng)
        val words = body.lowercase()
            .split(WORD_SPLIT_REGEX)
            .filter { it.length >= 2 }

        if (words.isEmpty()) return "English"

        var englishHits = 0
        var tanglishHits = 0
        var manglishHits = 0
        var hinglishHits = 0
        var teluguRomanHits = 0
        var kannadaRomanHits = 0

        for (w in words) {
            if (w in ENGLISH_WORDS) englishHits += 2
            if (w in TANGLISH_WORDS) tanglishHits += 3
            if (w in MANGLISH_WORDS) manglishHits += 3
            if (w in HINGLISH_WORDS) hinglishHits += 3
            if (w in TELUGU_ROMAN_WORDS) teluguRomanHits += 3
            if (w in KANNADA_ROMAN_WORDS) kannadaRomanHits += 3

            // Phonetic / morphological cues for Indian Romanized scripts
            if (w.contains("zh")) {
                tanglishHits += 2
                manglishHits += 1
            }
            if (w.endsWith("kiren") || w.endsWith("giren") || w.endsWith("thaai") || w.endsWith("dhaai") || w.endsWith("aalum")) {
                tanglishHits += 2
            }
            if (w.contains("nj") || w.endsWith("athe") || w.endsWith("umbol") || w.endsWith("ukal") || w.endsWith("iloode")) {
                manglishHits += 2
            }
            if (w.endsWith("ein") || w.endsWith("oon") || w.startsWith("khw") || w.contains("aahe")) {
                hinglishHits += 1
            }
            if (w.endsWith("dhi") || w.endsWith("dhu") || w.endsWith("thunn") || w.endsWith("pothun")) {
                teluguRomanHits += 1
            }
            if (w.endsWith("yali") || w.endsWith("thide") || w.endsWith("vaagide")) {
                kannadaRomanHits += 1
            }
        }

        // Metadata hints from trackName / artistName / albumName
        val metaHaystack = "$trackName $artistName $albumName".lowercase()
        val hasTamilScriptInMeta = metaHaystack.any { it in '\u0B80'..'\u0BFF' }
        val hasMalayalamScriptInMeta = metaHaystack.any { it in '\u0D00'..'\u0D7F' }
        val hasHindiScriptInMeta = metaHaystack.any { it in '\u0900'..'\u097F' }
        val hasTeluguScriptInMeta = metaHaystack.any { it in '\u0C00'..'\u0C7F' }
        val hasKannadaScriptInMeta = metaHaystack.any { it in '\u0C80'..'\u0CFF' }

        if (hasTamilScriptInMeta || TAMIL_META_HINTS.any { metaHaystack.contains(it) }) {
            tanglishHits += 5
        }
        if (hasMalayalamScriptInMeta || MALAYALAM_META_HINTS.any { metaHaystack.contains(it) }) {
            manglishHits += 5
        }
        if (hasHindiScriptInMeta || HINDI_META_HINTS.any { metaHaystack.contains(it) }) {
            hinglishHits += 5
        }
        if (hasTeluguScriptInMeta || TELUGU_META_HINTS.any { metaHaystack.contains(it) }) {
            teluguRomanHits += 5
        }
        if (hasKannadaScriptInMeta || KANNADA_META_HINTS.any { metaHaystack.contains(it) }) {
            kannadaRomanHits += 5
        }

        // Also check if track title words themselves are in Tanglish/Manglish/Hinglish
        val titleWords = trackName.lowercase().split(WORD_SPLIT_REGEX).filter { it.length >= 2 }
        for (tw in titleWords) {
            if (tw in TANGLISH_WORDS || tw.contains("zh")) tanglishHits += 3
            if (tw in MANGLISH_WORDS) manglishHits += 3
            if (tw in HINGLISH_WORDS) hinglishHits += 3
            if (tw in TELUGU_ROMAN_WORDS) teluguRomanHits += 3
            if (tw in KANNADA_ROMAN_WORDS) kannadaRomanHits += 3
        }

        val romanIndianCandidates = listOf(
            "Tanglish" to tanglishHits,
            "Malayalam (Eng)" to manglishHits,
            "Hindi (Eng)" to hinglishHits,
            "Telugu (Eng)" to teluguRomanHits,
            "Kannada (Eng)" to kannadaRomanHits
        )
        val bestRomanIndian = romanIndianCandidates.maxByOrNull { it.second }

        // English ratio check: in genuine English lyrics, common English words make up a high fraction of tokens
        val englishWordRatio = englishHits.toFloat() / (words.size.coerceAtLeast(1) * 2).toFloat()

        if (bestRomanIndian != null && bestRomanIndian.second >= 4 && bestRomanIndian.second * 1.3f >= englishHits) {
            return bestRomanIndian.first
        }

        if (englishWordRatio >= 0.14f || englishHits >= 8) {
            return "English"
        }

        if (bestRomanIndian != null && bestRomanIndian.second >= 2) {
            return bestRomanIndian.first
        }

        // If almost no English stop words appear in a long Latin-script lyric (e.g. transliterated Tamil/regional song)
        if (words.size >= 12 && englishWordRatio < 0.06f) {
            val doubleVowelCount = words.count {
                it.contains("aa") || it.contains("ee") || it.contains("oo") ||
                    it.contains("th") || it.contains("dh") || it.contains("zh") || it.contains("nh")
            }
            if (doubleVowelCount >= 3) {
                return bestRomanIndian?.takeIf { it.second > 0 }?.first ?: "Tanglish"
            }
        }

        return "English"
    }
}

class LyricsFetcher(
    private val lyricsDao: LyricsDao,
    private val context: android.content.Context? = null
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private fun getOffsetForSong(songId: Long): Long {
        val ctx = context ?: return 0L
        return try {
            com.example.data.local.UserPreferencesRepository.getInstance(ctx).getLyricsSyncOffsetMs(songId)
        } catch (_: Exception) {
            0L
        }
    }

    private fun writeLyricsFileCache(
        songId: Long,
        plain: String?,
        syncedJson: String?,
        offsetMs: Long = getOffsetForSong(songId)
    ) {
        try {
            val ctx = context ?: return
            com.example.util.LrcParser.writeCachedLrcFile(
                context = ctx,
                songId = songId,
                plainLyrics = plain,
                syncedLyricsJson = syncedJson,
                offsetMs = offsetMs
            )
        } catch (_: Exception) {}
    }

    suspend fun saveSelectedLyrics(
        songId: Long,
        plainLyrics: String?,
        syncedLyricsRaw: String?,
        source: String,
        songPath: String? = null,
        songTitle: String? = null
    ): Lyrics = withContext(Dispatchers.IO) {
        val (parsedPlainFromLrc, parsedSynced) = if (!syncedLyricsRaw.isNullOrBlank()) {
            val (p, json) = com.example.util.LrcParser.parseLrcContent(syncedLyricsRaw)
            Pair(p, json ?: if (syncedLyricsRaw.trim().startsWith("[{")) syncedLyricsRaw else null)
        } else {
            Pair(null, null)
        }

        val effectivePlain = plainLyrics?.takeIf { it.isNotBlank() } ?: parsedPlainFromLrc
        val entity = LyricsEntity(
            songId = songId,
            plainLyrics = effectivePlain,
            syncedLyricsJson = parsedSynced,
            isCached = false,
            source = source,
            updatedAt = System.currentTimeMillis()
        )
        lyricsDao.insertLyrics(entity)
        val currentOffsetMs = getOffsetForSong(songId)
        writeLyricsFileCache(songId, entity.plainLyrics, entity.syncedLyricsJson, currentOffsetMs)
        if (!songPath.isNullOrBlank()) {
            com.example.util.LrcParser.writeCompanionLrcForSong(
                context = context,
                songPath = songPath,
                songTitle = songTitle,
                plainLyrics = entity.plainLyrics,
                syncedLyricsJson = entity.syncedLyricsJson,
                rawLrcContent = syncedLyricsRaw ?: effectivePlain,
                offsetMs = currentOffsetMs
            )
        }
        entity.toDomainModel()
    }

    /**
     * Search available lyrics across multiple providers concurrently:
     * - LRCLIB (international open database)
     * - JioSaavn (premier source for Tamil, South Asian, and new releases)
     * - NetEase Cloud Music (massive catalog of synced LRC lyrics)
     *
     * Issue #9 Enhancement: If the query includes an artist or movie/album and returns few/no results,
     * automatically runs a fallback pass using ONLY the song name and disambiguates multiple matches
     * using the artist/album/movie tokens.
     */
    suspend fun searchAvailableLyrics(query: String): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val cleanQ = cleanSearchQuery(query)
        if (cleanQ.isBlank()) return@withContext emptyList()

        val primaryResults = queryAllCandidateProviders(cleanQ)
        val titleOnlyQuery = extractSongTitleOnlyFromQuery(cleanQ)

        val fallbackResults = if (titleOnlyQuery.isNotBlank() &&
            !titleOnlyQuery.equals(cleanQ, ignoreCase = true) &&
            primaryResults.none { it.isSynced }
        ) {
            queryAllCandidateProviders(titleOnlyQuery)
        } else {
            emptyList()
        }

        val combined = mutableListOf<LyricsCandidate>()
        combined.addAll(primaryResults)
        combined.addAll(fallbackResults)

        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<LyricsCandidate>()
        for (c in combined) {
            val key = "${c.source}:${c.trackName.lowercase()}:${c.artistName.lowercase()}:${c.isSynced}:${c.languageLabel}"
            if (seen.add(key)) {
                deduped.add(c)
            }
        }

        val hintTokens = cleanQ.lowercase()
            .split(Regex("""[\s,\-&/()]+"""))
            .filter { it.length >= 3 && !titleOnlyQuery.lowercase().contains(it) }

        deduped.sortedWith(
            compareByDescending<LyricsCandidate> { it.isSynced }
                .thenByDescending { candidate ->
                    val hay = "${candidate.artistName} ${candidate.albumName}".lowercase()
                    hintTokens.count { hay.contains(it) }
                }
                .thenByDescending { isTamilText(it.plainLyrics ?: "") || isTamilText(it.trackName) }
                .thenBy { it.trackName.lowercase() }
        )
    }

    private suspend fun queryAllCandidateProviders(cleanQ: String): List<LyricsCandidate> = coroutineScope {
        val lrclibDef = async { searchLrclibCandidates(cleanQ) }
        val jioSaavnDef = async { searchJioSaavnCandidates(cleanQ) }
        val netEaseDef = async { searchNetEaseCandidates(cleanQ) }

        val lrclibResults = try { lrclibDef.await() } catch (_: Exception) { emptyList() }
        val jioSaavnResults = try { jioSaavnDef.await() } catch (_: Exception) { emptyList() }
        val netEaseResults = try { netEaseDef.await() } catch (_: Exception) { emptyList() }

        buildList {
            addAll(lrclibResults)
            addAll(jioSaavnResults)
            addAll(netEaseResults)
        }
    }

    private fun extractSongTitleOnlyFromQuery(cleanQ: String): String {
        val dashSplit = cleanQ.split(Regex("""\s+[-–—|]\s+""")).firstOrNull()?.trim() ?: cleanQ
        return TagCleaner.cleanTitle(dashSplit, dashSplit).trim()
    }

    private fun searchLrclibCandidates(cleanQ: String): List<LyricsCandidate> {
        val results = mutableListOf<LyricsCandidate>()
        try {
            val qEnc = URLEncoder.encode(cleanQ, "UTF-8")
            val url = "https://lrclib.net/api/search?q=$qEnc"
            val request = Request.Builder().url(url).header("User-Agent", "OffGridMusicPlayer/2.0").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val arr = JSONArray(bodyStr)
                    for (i in 0 until minOf(arr.length(), 10)) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("id", "lrc_$i")
                        val trackName = obj.optString("trackName", "Unknown Track")
                        val artistName = obj.optString("artistName", "Unknown Artist")
                        val albumName = obj.optString("albumName", "")
                        val duration = obj.optInt("duration", 0)
                        val plain = obj.optString("plainLyrics", null)
                        val synced = obj.optString("syncedLyrics", null)

                        if (!plain.isNullOrBlank() || !synced.isNullOrBlank()) {
                            val isSyncedLyrics = !synced.isNullOrBlank() && com.example.util.LrcParser.parseLrcToLines(synced).isNotEmpty()
                            results.add(
                                LyricsCandidate(
                                    id = id,
                                    trackName = trackName,
                                    artistName = artistName,
                                    albumName = albumName,
                                    durationSec = duration,
                                    plainLyrics = plain,
                                    syncedLyricsRaw = synced,
                                    source = if (isSyncedLyrics) "LRCLIB (Synced)" else "LRCLIB",
                                    isSynced = isSyncedLyrics
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private fun searchJioSaavnCandidates(cleanQ: String): List<LyricsCandidate> {
        val results = mutableListOf<LyricsCandidate>()
        try {
            val qEnc = URLEncoder.encode(cleanQ, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&n=6&p=1&_marker=0&ctx=android&q=$qEnc"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android; Mobile)").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val arr = json.optJSONArray("results")
                    if (arr != null) {
                        for (i in 0 until minOf(arr.length(), 6)) {
                            val obj = arr.getJSONObject(i)
                            val songId = obj.optString("id", "")
                            val trackName = unescapeHtml(obj.optString("title", "Unknown Track"))
                            val moreInfo = obj.optJSONObject("more_info")
                            val artistName = unescapeHtml(moreInfo?.optString("singers", obj.optString("primary_artists", "Unknown Artist")) ?: "Unknown Artist")
                            val albumName = unescapeHtml(obj.optString("album", ""))
                            val hasLyrics = obj.optBoolean("has_lyrics", false) ||
                                    obj.optString("has_lyrics") == "true" ||
                                    moreInfo?.optBoolean("has_lyrics", false) == true ||
                                    moreInfo?.optString("has_lyrics") == "true"

                            if (songId.isNotBlank() && hasLyrics) {
                                val fetched = fetchJioSaavnLyricsById(songId)
                                if (fetched != null) {
                                    val (plain, synced) = fetched
                                    val isTamil = isTamilText(plain ?: "") || isTamilText(trackName) || isTamilText(artistName)
                                    val isSyncedLyrics = !synced.isNullOrBlank()
                                    results.add(
                                        LyricsCandidate(
                                            id = "saavn_$songId",
                                            trackName = trackName,
                                            artistName = artistName,
                                            albumName = albumName,
                                            durationSec = obj.optInt("duration", 0),
                                            plainLyrics = plain,
                                            syncedLyricsRaw = synced,
                                            source = when {
                                                isSyncedLyrics && isTamil -> "JioSaavn (Tamil Synced)"
                                                isSyncedLyrics -> "JioSaavn (Synced)"
                                                isTamil -> "JioSaavn (Tamil)"
                                                else -> "JioSaavn"
                                            },
                                            isSynced = isSyncedLyrics
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private fun searchNetEaseCandidates(cleanQ: String): List<LyricsCandidate> {
        val results = mutableListOf<LyricsCandidate>()
        try {
            val qEnc = URLEncoder.encode(cleanQ, "UTF-8")
            val url = "https://music.163.com/api/search/get/web?s=$qEnc&type=1&offset=0&total=true&limit=6"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val resultObj = json.optJSONObject("result")
                    val songsArr = resultObj?.optJSONArray("songs")
                    if (songsArr != null) {
                        for (i in 0 until minOf(songsArr.length(), 6)) {
                            val songObj = songsArr.getJSONObject(i)
                            val songId = songObj.optLong("id", 0L)
                            if (songId <= 0L) continue
                            val trackName = songObj.optString("name", "Unknown Track")
                            val artistsArr = songObj.optJSONArray("artists")
                            val artistName = artistsArr?.optJSONObject(0)?.optString("name", "Unknown Artist") ?: "Unknown Artist"
                            val albumObj = songObj.optJSONObject("album")
                            val albumName = albumObj?.optString("name", "") ?: ""
                            val durationSec = songObj.optInt("duration", 0) / 1000

                            val lyricsPair = fetchNetEaseLyricsById(songId)
                            if (lyricsPair != null) {
                                val (plain, synced) = lyricsPair
                                val isSyncedLyrics = !synced.isNullOrBlank()
                                results.add(
                                    LyricsCandidate(
                                        id = "ne_$songId",
                                        trackName = trackName,
                                        artistName = artistName,
                                        albumName = albumName,
                                        durationSec = durationSec,
                                        plainLyrics = plain,
                                        syncedLyricsRaw = synced,
                                        source = if (isSyncedLyrics) "NetEase Cloud (Synced)" else "NetEase Cloud",
                                        isSynced = isSyncedLyrics
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private suspend fun persistFetchedLyricsIfNotOverridden(
        entity: LyricsEntity,
        forceRefresh: Boolean,
        songPath: String? = null,
        songTitle: String? = null
    ): Lyrics {
        val currentOffsetMs = getOffsetForSong(entity.songId)
        val latest = lyricsDao.getLyricsForSong(entity.songId)
        val latestHasContent = latest != null && (!latest.syncedLyricsJson.isNullOrBlank() || !latest.plainLyrics.isNullOrBlank())
        if (latestHasContent && latest != null) {
            val shouldKeepExisting = !forceRefresh ||
                (!latest.isCached && entity.isCached) ||
                latest.updatedAt > entity.updatedAt
            if (shouldKeepExisting) {
                writeLyricsFileCache(latest.songId, latest.plainLyrics, latest.syncedLyricsJson, currentOffsetMs)
                if (!songPath.isNullOrBlank()) {
                    com.example.util.LrcParser.writeCompanionLrcForSong(
                        context = context,
                        songPath = songPath,
                        songTitle = songTitle,
                        plainLyrics = latest.plainLyrics,
                        syncedLyricsJson = latest.syncedLyricsJson,
                        offsetMs = currentOffsetMs
                    )
                }
                return latest.toDomainModel()
            }
        }
        lyricsDao.insertLyrics(entity)
        writeLyricsFileCache(entity.songId, entity.plainLyrics, entity.syncedLyricsJson, currentOffsetMs)
        if (!songPath.isNullOrBlank()) {
            com.example.util.LrcParser.writeCompanionLrcForSong(
                context = context,
                songPath = songPath,
                songTitle = songTitle,
                plainLyrics = entity.plainLyrics,
                syncedLyricsJson = entity.syncedLyricsJson,
                offsetMs = currentOffsetMs
            )
        }
        return entity.toDomainModel()
    }

    suspend fun getOrFetchLyrics(song: Song, forceRefresh: Boolean = false): Lyrics = withContext(Dispatchers.IO) {
        // 1. Check database cache - if we already have configured or cached lyrics (synced or plain), return them immediately
        val cached = lyricsDao.getLyricsForSong(song.id)
        if (!forceRefresh && cached != null && (!cached.syncedLyricsJson.isNullOrBlank() || !cached.plainLyrics.isNullOrBlank())) {
            val currentOffsetMs = getOffsetForSong(song.id)
            writeLyricsFileCache(song.id, cached.plainLyrics, cached.syncedLyricsJson, currentOffsetMs)
            if (song.path.isNotBlank()) {
                com.example.util.LrcParser.writeCompanionLrcForSong(
                    context = context,
                    songPath = song.path,
                    songTitle = song.displayTitle,
                    plainLyrics = cached.plainLyrics,
                    syncedLyricsJson = cached.syncedLyricsJson,
                    offsetMs = currentOffsetMs
                )
            }
            return@withContext cached.toDomainModel()
        }

        var fallbackPlain: String? = if (!forceRefresh) cached?.plainLyrics else null
        var fallbackSource: String = if (!forceRefresh) (cached?.source ?: "none") else "none"

        // 2. Search for local .lrc file in the same folder as the audio file (only when not forcing an online refresh)
        if (!forceRefresh) {
            try {
                val localLrcFile = com.example.util.LrcParser.findLocalLrcFile(song.path)
                if (localLrcFile != null && localLrcFile.exists()) {
                    val rawBytes = localLrcFile.readBytes()
                    var rawContent = String(rawBytes, java.nio.charset.StandardCharsets.UTF_8)
                    if (rawContent.contains('\u0000')) {
                        rawContent = String(rawBytes, java.nio.charset.Charset.forName("GBK"))
                    }
                    val content = rawContent.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
                    val embeddedOffset = com.example.util.LrcParser.extractOffsetFromLrc(content)
                    if (embeddedOffset != null && context != null) {
                        val prefs = com.example.data.local.UserPreferencesRepository.getInstance(context)
                        if (prefs.getLyricsSyncOffsetUpdatedAt(song.id) == 0L) {
                            prefs.setLyricsSyncOffsetMs(song.id, embeddedOffset)
                        }
                    }
                    val (plain, synced) = com.example.util.LrcParser.parseLrcContent(content)
                    if (!synced.isNullOrBlank()) {
                        val entity = LyricsEntity(
                            songId = song.id,
                            plainLyrics = plain ?: fallbackPlain,
                            syncedLyricsJson = synced,
                            isCached = true,
                            source = "Local LRC (${localLrcFile.name})"
                        )
                        return@withContext persistFetchedLyricsIfNotOverridden(entity, forceRefresh, song.path, song.displayTitle)
                    } else if (!plain.isNullOrBlank()) {
                        fallbackPlain = plain
                        fallbackSource = "Local LRC (${localLrcFile.name})"
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Check for embedded lyrics in audio file tags
        val embedded = extractEmbeddedLyrics(song.path)
        if (embedded != null) {
            val (plain, synced) = com.example.util.LrcParser.parseLrcContent(embedded)
            if (!synced.isNullOrBlank()) {
                val entity = LyricsEntity(
                    songId = song.id,
                    plainLyrics = plain ?: fallbackPlain,
                    syncedLyricsJson = synced,
                    isCached = true,
                    source = "Embedded Tag"
                )
                return@withContext persistFetchedLyricsIfNotOverridden(entity, forceRefresh, song.path, song.displayTitle)
            } else if (!plain.isNullOrBlank()) {
                fallbackPlain = plain
                fallbackSource = "Embedded Tag"
            }
        }

        val rawTitle = song.title.ifBlank { song.path.substringAfterLast('/').substringBeforeLast('.') }
        val cleanTitle = TagCleaner.cleanTitle(rawTitle, song.path.substringAfterLast('/'))
        val cleanArtist = TagCleaner.cleanArtist(song.artist).let { if (it.equals("Unknown Artist", ignoreCase = true)) "" else it }
        val primaryArtist = extractPrimaryArtist(cleanArtist)
        val cleanAlbum = if (song.album.equals("Unknown Album", ignoreCase = true)) "" else song.album
        val durationSec = song.durationMs / 1000

        val isTamilSong = isTamilText(cleanTitle) || isTamilText(cleanArtist) || isTamilText(cleanAlbum) ||
                isTamilArtist(cleanArtist) || cleanTitle.contains("tamil", ignoreCase = true)

        // =========================================================================
        // PASS 1: EXISTING MULTI-PROVIDER FETCH ENGINE (Unchanged Primary Pipeline)
        // =========================================================================
        var lrclibResult: Pair<String?, String?>? = null

        // 4a. Direct LRCLIB get with exact tags + duration
        val directResult = fetchDirectLrclib(cleanTitle, primaryArtist.ifBlank { cleanArtist }, cleanAlbum, durationSec)
        if (directResult?.second?.isNotBlank() == true) {
            lrclibResult = directResult
        }

        // 4b. Direct LRCLIB get without album/duration restriction
        if (lrclibResult == null) {
            val directNoAlbum = fetchDirectLrclib(cleanTitle, primaryArtist.ifBlank { cleanArtist }, "", 0)
            if (directNoAlbum?.second?.isNotBlank() == true) {
                lrclibResult = directNoAlbum
            }
        }

        // 4c. Search LRCLIB by track + artist
        if (lrclibResult == null) {
            val searchResult = fetchSearchLrclib(cleanTitle, primaryArtist.ifBlank { cleanArtist }, durationSec)
            if (searchResult?.second?.isNotBlank() == true) {
                lrclibResult = searchResult
            } else if (lrclibResult == null) {
                lrclibResult = searchResult
            }
        }

        // 4d. Search LRCLIB by track name and full artist query
        if (lrclibResult?.second.isNullOrBlank() && cleanArtist.isNotBlank()) {
            val searchFull = fetchQueryLrclib("$cleanTitle $cleanArtist", durationSec)
            if (searchFull?.second?.isNotBlank() == true) {
                lrclibResult = searchFull
            } else if (lrclibResult == null) {
                lrclibResult = searchFull
            }
        }

        // 4e. Search LRCLIB by pure title query
        if (lrclibResult?.second.isNullOrBlank()) {
            val searchTitle = fetchQueryLrclib(cleanTitle, durationSec)
            if (searchTitle?.second?.isNotBlank() == true) {
                lrclibResult = searchTitle
            } else if (lrclibResult == null) {
                lrclibResult = searchTitle
            }
        }

        // If LRCLIB gave us verified synced lyrics, cache and return immediately!
        if (lrclibResult?.second?.isNotBlank() == true) {
            val (plain, rawSynced) = lrclibResult
            val parsedSynced = if (!rawSynced.isNullOrBlank()) {
                val (_, json) = com.example.util.LrcParser.parseLrcContent(rawSynced)
                json ?: rawSynced
            } else null

            val entity = LyricsEntity(
                songId = song.id,
                plainLyrics = plain ?: fallbackPlain,
                syncedLyricsJson = parsedSynced,
                isCached = true,
                source = "Online (LRCLIB - Synced)"
            )
            return@withContext persistFetchedLyricsIfNotOverridden(entity, forceRefresh, song.path, song.displayTitle)
        }

        // 5. Query NetEase Cloud Music (provides massive open library of Synced LRC lyrics)
        val netEaseResult = fetchNetEaseLyrics(cleanTitle, primaryArtist.ifBlank { cleanArtist }, durationSec)
        if (netEaseResult?.second?.isNotBlank() == true) {
            val (plain, rawSynced) = netEaseResult
            val (_, json) = com.example.util.LrcParser.parseLrcContent(rawSynced!!)
            val entity = LyricsEntity(
                songId = song.id,
                plainLyrics = plain ?: lrclibResult?.first ?: fallbackPlain,
                syncedLyricsJson = json ?: rawSynced,
                isCached = true,
                source = "Online (NetEase - Synced)"
            )
            return@withContext persistFetchedLyricsIfNotOverridden(entity, forceRefresh, song.path, song.displayTitle)
        }

        // 6. Query JioSaavn (Premier catalog for Tamil songs in Tamil script/synced & newly released tracks)
        val jioSaavnResult = fetchJioSaavnLyrics(cleanTitle, primaryArtist.ifBlank { cleanArtist }, cleanAlbum, isTamilSong)
        if (jioSaavnResult?.second?.isNotBlank() == true) {
            val (plain, rawSynced) = jioSaavnResult
            val (_, json) = com.example.util.LrcParser.parseLrcContent(rawSynced!!)
            val isTamil = isTamilText(plain ?: "") || isTamilSong
            val entity = LyricsEntity(
                songId = song.id,
                plainLyrics = plain ?: lrclibResult?.first ?: fallbackPlain,
                syncedLyricsJson = json ?: rawSynced,
                isCached = true,
                source = if (isTamil) "Online (JioSaavn - Tamil Synced)" else "Online (JioSaavn - Synced)"
            )
            return@withContext persistFetchedLyricsIfNotOverridden(entity, forceRefresh, song.path, song.displayTitle)
        }

        // =========================================================================
        // PASS 2 (ISSUE #9 FALLBACK): SONG-NAME-ONLY FETCH WITH ARTIST/ALBUM/MOVIE
        // DISAMBIGUATION WHEN MULTIPLE SONGS SHARE THE SAME NAME
        // =========================================================================
        // Sometimes lyrics sources have a different artist/singer/music-director string
        // than the local audio tag. When Pass 1 fails to find synced lyrics (or any lyrics),
        // we query using ONLY the song name (`cleanTitle`), and use `cleanArtist`,
        // `primaryArtist`, `cleanAlbum` (movie/album name), and `durationSec` ONLY to
        // disambiguate when multiple songs are returned with the same song name!
        val titleOnlyDisambiguated = fetchLyricsBySongNameOnlyWithDisambiguation(
            songTitle = cleanTitle,
            fullArtist = cleanArtist,
            primaryArtist = primaryArtist,
            albumOrMovie = cleanAlbum,
            durationSec = durationSec.toInt()
        )

        if (titleOnlyDisambiguated != null && !titleOnlyDisambiguated.syncedLyricsRaw.isNullOrBlank()) {
            val (_, json) = com.example.util.LrcParser.parseLrcContent(titleOnlyDisambiguated.syncedLyricsRaw)
            val entity = LyricsEntity(
                songId = song.id,
                plainLyrics = titleOnlyDisambiguated.plainLyrics ?: lrclibResult?.first ?: fallbackPlain,
                syncedLyricsJson = json ?: titleOnlyDisambiguated.syncedLyricsRaw,
                isCached = true,
                source = "${titleOnlyDisambiguated.source} (Title Match)"
            )
            return@withContext persistFetchedLyricsIfNotOverridden(entity, forceRefresh, song.path, song.displayTitle)
        }

        // 7. Query Lyrist API (indexes newly released songs, singles, and hot drops)
        var lyristResult: Pair<String?, String?>? = null
        if (lrclibResult?.first.isNullOrBlank() && jioSaavnResult?.first.isNullOrBlank() && titleOnlyDisambiguated?.plainLyrics.isNullOrBlank()) {
            lyristResult = fetchLyristLyrics(cleanTitle, primaryArtist.ifBlank { cleanArtist })
            if (lyristResult == null && cleanTitle.isNotBlank()) {
                // Song-name-only fallback on Lyrist
                lyristResult = fetchLyristLyrics(cleanTitle, "")
            }
        }

        // 8. Fallback to lyrics.ovh if still no lyrics found
        var ovhResult: Pair<String?, String?>? = null
        if (lrclibResult?.first.isNullOrBlank() && jioSaavnResult?.first.isNullOrBlank() && titleOnlyDisambiguated?.plainLyrics.isNullOrBlank() && lyristResult?.first.isNullOrBlank() && cleanArtist.isNotBlank()) {
            ovhResult = fetchOvhLyrics(cleanTitle, primaryArtist.ifBlank { cleanArtist })
        }

        val isJioSaavnTamil = jioSaavnResult?.first?.let { isTamilText(it) } == true
        val bestPlain = when {
            isTamilSong && isJioSaavnTamil -> jioSaavnResult?.first
            !lrclibResult?.first.isNullOrBlank() -> lrclibResult?.first
            !jioSaavnResult?.first.isNullOrBlank() -> jioSaavnResult?.first
            !netEaseResult?.first.isNullOrBlank() -> netEaseResult?.first
            !titleOnlyDisambiguated?.plainLyrics.isNullOrBlank() -> titleOnlyDisambiguated?.plainLyrics
            !lyristResult?.first.isNullOrBlank() -> lyristResult?.first
            !ovhResult?.first.isNullOrBlank() -> ovhResult?.first
            else -> fallbackPlain
        }

        val bestSource = when {
            isTamilSong && isJioSaavnTamil -> "Online (JioSaavn - Tamil)"
            !lrclibResult?.first.isNullOrBlank() -> "Online (LRCLIB)"
            !jioSaavnResult?.first.isNullOrBlank() -> "Online (JioSaavn)"
            !netEaseResult?.first.isNullOrBlank() -> "Online (NetEase)"
            !titleOnlyDisambiguated?.plainLyrics.isNullOrBlank() -> "${titleOnlyDisambiguated?.source ?: "Online"} (Title Match)"
            !lyristResult?.first.isNullOrBlank() -> "Online (Lyrist - Recent)"
            !ovhResult?.first.isNullOrBlank() -> "Online (Lyrics.ovh)"
            else -> fallbackSource
        }

        if (!bestPlain.isNullOrBlank()) {
            val entity = LyricsEntity(
                songId = song.id,
                plainLyrics = bestPlain,
                syncedLyricsJson = null,
                isCached = true,
                source = bestSource
            )
            return@withContext persistFetchedLyricsIfNotOverridden(entity, forceRefresh, song.path, song.displayTitle)
        }

        if (!forceRefresh) {
            val latest = lyricsDao.getLyricsForSong(song.id)
            if (latest != null) {
                return@withContext latest.toDomainModel()
            }
        }

        // Fallback: Return empty model
        val fallback = LyricsEntity(
            songId = song.id,
            plainLyrics = null,
            syncedLyricsJson = null,
            isCached = false,
            source = "none"
        )
        return@withContext fallback.toDomainModel()
    }

    /**
     * Issue #9 Fallback Method:
     * Searches LRCLIB, JioSaavn, and NetEase using ONLY the song title (`songTitle`),
     * including transliteration variants for regional/Tamil titles.
     * - If only 1 song match is found, returns it directly even if the artist name on the
     *   provider differs from the local file's artist tag.
     * - If multiple songs with the same name are found, uses `fullArtist`, `primaryArtist`,
     *   `albumOrMovie` (album/movie name), and `durationSec` to score and select the right song.
     */
    private suspend fun fetchLyricsBySongNameOnlyWithDisambiguation(
        songTitle: String,
        fullArtist: String,
        primaryArtist: String,
        albumOrMovie: String,
        durationSec: Int
    ): LyricsCandidate? = coroutineScope {
        val cleanSongTitle = cleanSearchQuery(songTitle)
        if (cleanSongTitle.length < 2) return@coroutineScope null

        val titleQueries = buildList {
            add(cleanSongTitle)
            val strippedParen = cleanSongTitle.replace(Regex("""\([^)]*\)|\[[^\]]*\]"""), "").trim()
            if (strippedParen.length >= 2 && !strippedParen.equals(cleanSongTitle, ignoreCase = true)) {
                add(strippedParen)
            }
            for (variant in TransliterationUtil.normalizeSearchQuery(strippedParen.ifBlank { cleanSongTitle })) {
                if (variant.length >= 2 && none { it.equals(variant, ignoreCase = true) }) {
                    add(variant)
                }
            }
        }.take(3)

        val allCandidates = mutableListOf<LyricsCandidate>()
        for (titleQ in titleQueries) {
            val lrcDef = async { searchLrclibByTrackNameOnly(titleQ) }
            val saavnDef = async { searchJioSaavnCandidates(titleQ) }
            val netEaseDef = async { searchNetEaseCandidates(titleQ) }

            allCandidates.addAll(try { lrcDef.await() } catch (_: Exception) { emptyList() })
            allCandidates.addAll(try { saavnDef.await() } catch (_: Exception) { emptyList() })
            allCandidates.addAll(try { netEaseDef.await() } catch (_: Exception) { emptyList() })

            if (allCandidates.any { it.isSynced }) break
        }

        if (allCandidates.isEmpty()) return@coroutineScope null

        val normalizedTargetTitle = normalizeComparisonToken(cleanSongTitle)
        // Filter to candidates whose track title reasonably matches our song name
        val titleMatchedCandidates = allCandidates.filter { candidate ->
            val candTitle = normalizeComparisonToken(candidate.trackName)
            candTitle == normalizedTargetTitle ||
                candTitle.contains(normalizedTargetTitle) ||
                normalizedTargetTitle.contains(candTitle)
        }.ifEmpty { allCandidates }

        // If only a single candidate exists, return it immediately without rejecting due to artist mismatch
        if (titleMatchedCandidates.size == 1) {
            return@coroutineScope titleMatchedCandidates.first()
        }

        // Multiple songs found with the same name: disambiguate using artist / album / movie name & duration
        val artistTokens = "$fullArtist $primaryArtist"
            .lowercase()
            .split(Regex("""[\s,\-&/().]+"""))
            .filter { it.length >= 3 && it != "unknown" && it != "artist" }
            .distinct()

        val albumMovieTokens = albumOrMovie
            .lowercase()
            .split(Regex("""[\s,\-&/().]+"""))
            .filter { it.length >= 3 && it != "unknown" && it != "album" && it != "movie" && it != "ost" }
            .distinct()

        titleMatchedCandidates.maxByOrNull { candidate ->
            var score = 0
            val candTitleNorm = normalizeComparisonToken(candidate.trackName)
            val candArtistLower = candidate.artistName.lowercase()
            val candAlbumLower = candidate.albumName.lowercase()

            // Exact title match bonus
            if (candTitleNorm == normalizedTargetTitle) {
                score += 300
            } else if (candTitleNorm.startsWith(normalizedTargetTitle) || normalizedTargetTitle.startsWith(candTitleNorm)) {
                score += 150
            }

            // Disambiguate among same-named songs using Album / Movie name first, then Artist name
            if (albumMovieTokens.isNotEmpty()) {
                val albumMatches = albumMovieTokens.count { token ->
                    candAlbumLower.contains(token) || candArtistLower.contains(token) || candidate.trackName.lowercase().contains(token)
                }
                score += albumMatches * 220
            }

            if (artistTokens.isNotEmpty()) {
                val artistMatches = artistTokens.count { token ->
                    candArtistLower.contains(token) || candAlbumLower.contains(token)
                }
                score += artistMatches * 180
            }

            // Duration proximity bonus
            if (durationSec > 0 && candidate.durationSec > 0) {
                val diff = abs(durationSec - candidate.durationSec)
                when {
                    diff <= 2 -> score += 200
                    diff <= 6 -> score += 120
                    diff <= 15 -> score += 50
                    diff > 45 -> score -= 100
                }
            }

            // Prefer synced lyrics when disambiguated scores are close
            if (candidate.isSynced) {
                score += 250
            }

            score
        }
    }

    private fun searchLrclibByTrackNameOnly(trackName: String): List<LyricsCandidate> {
        val results = mutableListOf<LyricsCandidate>()
        try {
            val titleEnc = URLEncoder.encode(trackName, "UTF-8")
            val url = "https://lrclib.net/api/search?track_name=$titleEnc"
            val request = Request.Builder().url(url).header("User-Agent", "OffGridMusicPlayer/2.0").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val arr = JSONArray(bodyStr)
                    for (i in 0 until minOf(arr.length(), 10)) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("id", "lrc_title_$i")
                        val tName = obj.optString("trackName", "Unknown Track")
                        val aName = obj.optString("artistName", "Unknown Artist")
                        val alName = obj.optString("albumName", "")
                        val duration = obj.optInt("duration", 0)
                        val plain = obj.optString("plainLyrics", null)
                        val synced = obj.optString("syncedLyrics", null)

                        if (!plain.isNullOrBlank() || !synced.isNullOrBlank()) {
                            val isSyncedLyrics = !synced.isNullOrBlank() && com.example.util.LrcParser.parseLrcToLines(synced).isNotEmpty()
                            results.add(
                                LyricsCandidate(
                                    id = id,
                                    trackName = tName,
                                    artistName = aName,
                                    albumName = alName,
                                    durationSec = duration,
                                    plainLyrics = plain,
                                    syncedLyricsRaw = synced,
                                    source = if (isSyncedLyrics) "LRCLIB (Synced)" else "LRCLIB",
                                    isSynced = isSyncedLyrics
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private fun normalizeComparisonToken(input: String): String {
        return input.lowercase()
            .replace(Regex("""\([^)]*\)|\[[^\]]*\]"""), " ")
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun extractEmbeddedLyrics(audioPath: String): String? {
        if (audioPath.isBlank()) return null
        return try {
            val file = java.io.File(audioPath)
            if (!file.exists() || !file.canRead()) return null
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanSearchQuery(q: String): String {
        return q.replace(Regex("""(?i)\(?(?:official|music|lyric|video|audio|remastered|remaster|hd|4k|hq|deluxe|explicit|clean|live|mono|stereo|from\s*['"][^'"]+['"])\)?"""), "")
            .replace(Regex("""(?i)\[(?:official|music|lyric|video|audio|remastered|remaster|hd|4k|hq|deluxe|explicit|clean|live|mono|stereo|tamil)\]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun isTamilText(text: String): Boolean {
        return text.any { it in '\u0B80'..'\u0BFF' }
    }

    private fun isTamilArtist(artist: String): Boolean {
        val lower = artist.lowercase()
        val tamilArtists = listOf(
            "anirudh", "a.r. rahman", "ar rahman", "yuvan", "shankar mahadevan",
            "sid sriram", "harris jayaraj", "santhosh narayanan", "ilaiyaraaja", "ilayaraja",
            "dhibu ninan", "gv prakash", "g.v. prakash", "d. imman", "imman", "hiphop tamizha",
            "dhanush", "vijay", "spb", "s. p. balasubrahmanyam", "s. janaki", "k. s. chithra", "chithra",
            "shreya ghoshal", "jonita gandhi", "pradeep kumar", "sean roldan", "sam c.s.", "sam cs"
        )
        return tamilArtists.any { lower.contains(it) }
    }

    private fun unescapeHtml(html: String): String {
        return html
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</p>"""), "\n\n")
            .replace(Regex("""(?i)<[^>]+>"""), "")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun extractPrimaryArtist(artist: String): String {
        if (artist.isBlank()) return ""
        val delimiters = listOf(" feat. ", " feat ", " ft. ", " ft ", " featuring ", " & ", " / ", " x ", " X ", " with ", " With ")
        var primary = artist
        for (d in delimiters) {
            if (primary.contains(d, ignoreCase = true)) {
                val idx = primary.indexOf(d, ignoreCase = true)
                if (idx > 0) {
                    primary = primary.substring(0, idx).trim()
                }
            }
        }
        return primary.trim()
    }

    // -------------------------------------------------------------
    // Provider 1: LRCLIB
    // -------------------------------------------------------------
    private fun fetchDirectLrclib(title: String, artist: String, album: String, durationSec: Long): Pair<String?, String?>? {
        return try {
            val titleEnc = URLEncoder.encode(title, "UTF-8")
            val artistEnc = URLEncoder.encode(artist, "UTF-8")
            val cleanAlbum = if (album.equals("Unknown Album", ignoreCase = true)) "" else album
            val albumEnc = URLEncoder.encode(cleanAlbum, "UTF-8")

            var url = "https://lrclib.net/api/get?track_name=$titleEnc"
            if (artistEnc.isNotBlank()) url += "&artist_name=$artistEnc"
            if (cleanAlbum.isNotBlank()) url += "&album_name=$albumEnc"
            if (durationSec > 0) url += "&duration=$durationSec"

            val request = Request.Builder().url(url).header("User-Agent", "OffGridMusicPlayer/2.0").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val plain = json.optString("plainLyrics", null)
                    val synced = json.optString("syncedLyrics", null)
                    if (!plain.isNullOrBlank() || !synced.isNullOrBlank()) {
                        return Pair(plain, synced)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchSearchLrclib(title: String, artist: String, durationSec: Long): Pair<String?, String?>? {
        return try {
            val titleEnc = URLEncoder.encode(title, "UTF-8")
            val artistEnc = URLEncoder.encode(artist, "UTF-8")
            var url = "https://lrclib.net/api/search?track_name=$titleEnc"
            if (artistEnc.isNotBlank()) url += "&artist_name=$artistEnc"

            val request = Request.Builder().url(url).header("User-Agent", "OffGridMusicPlayer/2.0").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val arr = JSONArray(bodyStr)
                    var bestMatch: Pair<String?, String?>? = null
                    var bestScore = -1

                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val plain = obj.optString("plainLyrics", null)
                        val synced = obj.optString("syncedLyrics", null)
                        val dur = obj.optInt("duration", 0)

                        if (!plain.isNullOrBlank() || !synced.isNullOrBlank()) {
                            var score = 0
                            if (!synced.isNullOrBlank()) score += 1000
                            if (durationSec > 0 && dur > 0) {
                                val diff = abs(durationSec - dur)
                                if (diff <= 3) score += 50
                                else if (diff <= 10) score += 20
                            }
                            if (score > bestScore) {
                                bestScore = score
                                bestMatch = Pair(plain, synced)
                            }
                        }
                    }
                    if (bestMatch != null) return bestMatch
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchQueryLrclib(query: String, durationSec: Long): Pair<String?, String?>? {
        return try {
            val cleanQ = cleanSearchQuery(query)
            val qEnc = URLEncoder.encode(cleanQ, "UTF-8")
            val url = "https://lrclib.net/api/search?q=$qEnc"

            val request = Request.Builder().url(url).header("User-Agent", "OffGridMusicPlayer/2.0").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val arr = JSONArray(bodyStr)
                    var bestMatch: Pair<String?, String?>? = null
                    var bestScore = -1

                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val plain = obj.optString("plainLyrics", null)
                        val synced = obj.optString("syncedLyrics", null)
                        val dur = obj.optInt("duration", 0)

                        if (!plain.isNullOrBlank() || !synced.isNullOrBlank()) {
                            var score = 0
                            if (!synced.isNullOrBlank()) score += 1000
                            if (durationSec > 0 && dur > 0) {
                                val diff = abs(durationSec - dur)
                                if (diff <= 3) score += 50
                                else if (diff <= 10) score += 20
                            }
                            if (score > bestScore) {
                                bestScore = score
                                bestMatch = Pair(plain, synced)
                            }
                        }
                    }
                    if (bestMatch != null) return bestMatch
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------
    // Provider 2: NetEase Cloud Music (Synced LRC)
    // -------------------------------------------------------------
    private fun fetchNetEaseLyrics(title: String, artist: String, durationSec: Long): Pair<String?, String?>? {
        return try {
            val query = if (artist.isNotBlank()) "$title $artist" else title
            val qEnc = URLEncoder.encode(query, "UTF-8")
            val url = "https://music.163.com/api/search/get/web?s=$qEnc&type=1&offset=0&total=true&limit=5"

            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val resultObj = json.optJSONObject("result")
                    val songsArr = resultObj?.optJSONArray("songs")
                    if (songsArr != null && songsArr.length() > 0) {
                        for (i in 0 until minOf(songsArr.length(), 4)) {
                            val songObj = songsArr.getJSONObject(i)
                            val songId = songObj.optLong("id", 0L)
                            if (songId > 0L) {
                                val pair = fetchNetEaseLyricsById(songId)
                                if (pair?.second?.isNotBlank() == true) {
                                    return pair
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchNetEaseLyricsById(songId: Long): Pair<String?, String?>? {
        return try {
            val url = "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val lrcObj = json.optJSONObject("lrc")
                    val lrcLyric = lrcObj?.optString("lyric", null)
                    if (!lrcLyric.isNullOrBlank()) {
                        val (plain, synced) = com.example.util.LrcParser.parseLrcContent(lrcLyric)
                        if (!synced.isNullOrBlank() || !plain.isNullOrBlank()) {
                            return Pair(plain, synced)
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------
    // Provider 3: JioSaavn (Premier Tamil & Recent Indian Releases)
    // -------------------------------------------------------------
    private fun fetchJioSaavnLyrics(title: String, artist: String, album: String, isTamilHint: Boolean): Pair<String?, String?>? {
        val searchQueries = mutableListOf<String>()
        if (isTamilHint || isTamilText(title) || isTamilText(artist)) {
            if (album.isNotBlank()) searchQueries.add("$title $album")
            if (artist.isNotBlank()) searchQueries.add("$title $artist")
            searchQueries.add(title)
            val variants = TransliterationUtil.normalizeSearchQuery(title)
            for (v in variants) {
                if (v != title.lowercase()) searchQueries.add(v)
            }
        } else {
            if (artist.isNotBlank()) searchQueries.add("$title $artist")
            if (album.isNotBlank()) searchQueries.add("$title $album")
            searchQueries.add(title)
        }

        for (q in searchQueries.distinct()) {
            val result = fetchJioSaavnByQuery(q)
            if (result != null) return result
        }
        return null
    }

    private fun fetchJioSaavnByQuery(query: String): Pair<String?, String?>? {
        return try {
            val qEnc = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&n=5&p=1&_marker=0&ctx=android&q=$qEnc"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android; Mobile)").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val arr = json.optJSONArray("results")
                    if (arr != null && arr.length() > 0) {
                        for (i in 0 until minOf(arr.length(), 4)) {
                            val obj = arr.getJSONObject(i)
                            val songId = obj.optString("id", "")
                            val moreInfo = obj.optJSONObject("more_info")
                            val hasLyrics = obj.optBoolean("has_lyrics", false) ||
                                    obj.optString("has_lyrics") == "true" ||
                                    moreInfo?.optBoolean("has_lyrics", false) == true ||
                                    moreInfo?.optString("has_lyrics") == "true"

                            if (songId.isNotBlank() && hasLyrics) {
                                val lyrics = fetchJioSaavnLyricsById(songId)
                                if (lyrics != null) return lyrics
                            }
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchJioSaavnLyricsById(songId: String): Pair<String?, String?>? {
        return try {
            val url = "https://www.jiosaavn.com/api.php?__call=lyrics.getLyrics&ctx=android&_format=json&_marker=0&lyrics_id=$songId"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android; Mobile)").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val rawLyrics = json.optString("lyrics", null)
                    if (!rawLyrics.isNullOrBlank()) {
                        val cleaned = unescapeHtml(rawLyrics)
                        if (cleaned.isNotBlank()) {
                            if (cleaned.contains(Regex("""\[\d{2}:\d{2}"""))) {
                                val (plain, synced) = com.example.util.LrcParser.parseLrcContent(cleaned)
                                return Pair(plain ?: cleaned, synced)
                            }
                            return Pair(cleaned, null)
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------
    // Provider 4: Lyrist (Newly released songs & singles)
    // -------------------------------------------------------------
    private fun fetchLyristLyrics(title: String, artist: String): Pair<String?, String?>? {
        return try {
            val titleEnc = URLEncoder.encode(title, "UTF-8")
            val artistEnc = URLEncoder.encode(artist, "UTF-8")
            val url = if (artistEnc.isNotBlank()) {
                "https://lyrist.vercel.app/api/$titleEnc/$artistEnc"
            } else {
                "https://lyrist.vercel.app/api/$titleEnc"
            }

            val request = Request.Builder().url(url).header("User-Agent", "OffGridMusicPlayer/2.0").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val lyrics = json.optString("lyrics", null)
                    if (!lyrics.isNullOrBlank()) {
                        val cleaned = unescapeHtml(lyrics)
                        if (cleaned.contains(Regex("""\[\d{2}:\d{2}"""))) {
                            val (plain, synced) = com.example.util.LrcParser.parseLrcContent(cleaned)
                            return Pair(plain ?: cleaned, synced)
                        }
                        return Pair(cleaned.trim(), null)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------
    // Provider 5: Lyrics.ovh (Plain Fallback)
    // -------------------------------------------------------------
    private fun fetchOvhLyrics(title: String, artist: String): Pair<String?, String?>? {
        return try {
            val artistEnc = URLEncoder.encode(artist, "UTF-8")
            val titleEnc = URLEncoder.encode(title, "UTF-8")
            val url = "https://api.lyrics.ovh/v1/$artistEnc/$titleEnc"

            val request = Request.Builder().url(url).header("User-Agent", "OffGridMusicPlayer/2.0").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val lyrics = json.optString("lyrics", null)
                    if (!lyrics.isNullOrBlank()) {
                        return Pair(lyrics.trim(), null)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
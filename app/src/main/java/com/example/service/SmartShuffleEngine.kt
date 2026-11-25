package com.example.service

import com.example.data.local.PlaybackHistoryEntity
import com.example.data.model.Song
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * Dynamically reorders a shuffled playback queue by analyzing the user's playback history,
 * genre preferences, artist affinity, track completion/skip behavior, and listening recency
 * while preventing same-artist/same-album clumping.
 *
 * Used ONLY when the "Smart Queue" setting toggle is enabled (OFF by default).
 */
object SmartShuffleEngine {

    data class ListeningProfile(
        val genreWeights: Map<String, Float> = emptyMap(),
        val artistWeights: Map<String, Float> = emptyMap(),
        val trackEngagement: Map<Long, Float> = emptyMap(),
        val trackSkipPenalty: Map<Long, Float> = emptyMap(),
        val lastPlayedMsByTrack: Map<Long, Long> = emptyMap(),
        val totalHistoryEvents: Int = 0
    ) {
        companion object {
            val EMPTY = ListeningProfile()
        }
    }

    private const val RECENT_COOLDOWN_MS = 90L * 60L * 1000L // 90 minutes
    private const val REDISCOVERY_MIN_MS = 12L * 60L * 60L * 1000L // 12 hours
    private const val REDISCOVERY_MAX_MS = 14L * 24L * 60L * 60L * 1000L // 14 days
    private const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L

    internal fun normalizeGenre(raw: String?): String? {
        val cleaned = raw?.trim()?.lowercase() ?: return null
        if (cleaned.isEmpty() ||
            cleaned == "unknown" ||
            cleaned == "unknown genre" ||
            cleaned == "<unknown>" ||
            cleaned == "other" ||
            cleaned == "none"
        ) {
            return null
        }
        return cleaned
    }

    internal fun normalizeArtist(raw: String?): String? {
        val cleaned = raw?.trim()?.lowercase() ?: return null
        if (cleaned.isEmpty() ||
            cleaned == "unknown" ||
            cleaned == "unknown artist" ||
            cleaned == "<unknown>"
        ) {
            return null
        }
        return cleaned
    }

    /**
     * Builds an in-memory [ListeningProfile] from library metadata and playback history.
     * Designed to run off the main thread on [kotlinx.coroutines.Dispatchers.IO].
     */
    fun buildListeningProfile(
        songs: List<Song>,
        history: List<PlaybackHistoryEntity>,
        nowMs: Long = System.currentTimeMillis()
    ): ListeningProfile {
        if (songs.isEmpty() && history.isEmpty()) return ListeningProfile.EMPTY

        val songById = HashMap<Long, Song>(songs.size)
        for (s in songs) {
            songById[s.id] = s
        }

        val genreScores = HashMap<String, Double>()
        val artistScores = HashMap<String, Double>()
        val trackWeightedSum = HashMap<Long, Double>()
        val trackEventCount = HashMap<Long, Int>()
        val trackSkipCount = HashMap<Long, Int>()
        val lastPlayedMap = HashMap<Long, Long>()

        // 1. Seed baseline preferences from song-level play counts, favorites, and lastPlayed
        for (song in songs) {
            if (song.lastPlayed > 0L) {
                val prev = lastPlayedMap[song.id] ?: 0L
                if (song.lastPlayed > prev) lastPlayedMap[song.id] = song.lastPlayed
            }
            val baseSignal = ln(1.0 + song.playCount.coerceAtLeast(0).toDouble()) +
                (if (song.isFavorite) 1.6 else 0.0)
            if (baseSignal > 0.0) {
                normalizeGenre(song.genre)?.let { g ->
                    genreScores[g] = (genreScores[g] ?: 0.0) + baseSignal
                }
                normalizeArtist(song.displayArtist)?.let { a ->
                    artistScores[a] = (artistScores[a] ?: 0.0) + baseSignal
                }
            }
        }

        // 2. Analyze granular playback history events (completion ratio, duration, recency, early skips)
        for (event in history) {
            val song = songById[event.trackId]
            val eventTime = maxOf(event.endedAt, event.startedAt)
            if (eventTime > 0L) {
                val prev = lastPlayedMap[event.trackId] ?: 0L
                if (eventTime > prev) lastPlayedMap[event.trackId] = eventTime
            }

            // Recent history carries slightly stronger preference weight than older history
            val ageMs = (nowMs - eventTime).coerceAtLeast(0L)
            val recencyDecay = 0.45 + 0.55 * exp(-ageMs.toDouble() / THIRTY_DAYS_MS.toDouble())

            val completion = event.completionPercentage.coerceIn(0f, 1f)
            val minutesListened = (event.listenedDurationMs.coerceAtLeast(0L) / 60_000.0).coerceAtMost(8.0)
            val isEarlySkip = !event.isQualified && completion in 0.001f..0.25f && event.listenedDurationMs < 25_000L

            trackEventCount[event.trackId] = (trackEventCount[event.trackId] ?: 0) + 1
            if (isEarlySkip) {
                trackSkipCount[event.trackId] = (trackSkipCount[event.trackId] ?: 0) + 1
            }

            val eventQuality = when {
                event.isQualified || completion >= 0.72f -> (1.15 + completion * 0.65 + minutesListened * 0.12) * recencyDecay
                isEarlySkip -> -0.55 * recencyDecay
                else -> (0.35 + completion * 0.50) * recencyDecay
            }

            trackWeightedSum[event.trackId] = (trackWeightedSum[event.trackId] ?: 0.0) + eventQuality

            if (song != null && eventQuality > 0.0) {
                normalizeGenre(song.genre)?.let { g ->
                    genreScores[g] = (genreScores[g] ?: 0.0) + eventQuality * 1.35
                }
                normalizeArtist(song.displayArtist)?.let { a ->
                    artistScores[a] = (artistScores[a] ?: 0.0) + eventQuality * 1.10
                }
            }
        }

        // Normalize maps to [0f..1f]
        val maxGenre = genreScores.values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val normalizedGenres = genreScores.mapValues { (_, v) -> (v / maxGenre).toFloat().coerceIn(0f, 1f) }

        val maxArtist = artistScores.values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val normalizedArtists = artistScores.mapValues { (_, v) -> (v / maxArtist).toFloat().coerceIn(0f, 1f) }

        val trackEngagement = HashMap<Long, Float>(trackWeightedSum.size)
        val trackSkipPenalty = HashMap<Long, Float>()
        for ((trackId, count) in trackEventCount) {
            val avgQuality = (trackWeightedSum[trackId] ?: 0.0) / count.coerceAtLeast(1)
            trackEngagement[trackId] = avgQuality.toFloat().coerceIn(-1f, 2f)
            val skips = trackSkipCount[trackId] ?: 0
            if (skips > 0) {
                val skipRatio = skips.toFloat() / count.toFloat()
                trackSkipPenalty[trackId] = (skipRatio * 1.4f).coerceIn(0f, 1.4f)
            }
        }

        return ListeningProfile(
            genreWeights = normalizedGenres,
            artistWeights = normalizedArtists,
            trackEngagement = trackEngagement,
            trackSkipPenalty = trackSkipPenalty,
            lastPlayedMsByTrack = lastPlayedMap,
            totalHistoryEvents = history.size
        )
    }

    /**
     * Dynamically reorders [songs] for a more engaging listening session.
     * Always returns an exact 1-to-1 permutation of [songs]. If [anchorSong] is present in [songs],
     * it stays pinned at index 0 so the currently playing track is never interrupted.
     */
    fun reorderQueue(
        songs: List<Song>,
        anchorSong: Song? = null,
        profile: ListeningProfile = ListeningProfile.EMPTY,
        nowMs: Long = System.currentTimeMillis(),
        random: Random = Random.Default
    ): List<Song> {
        if (songs.size <= 1) return songs

        val anchorIndex = if (anchorSong != null) songs.indexOfFirst { it.id == anchorSong.id } else -1
        val lockedAnchor = if (anchorIndex >= 0) songs[anchorIndex] else null
        val candidates = if (anchorIndex >= 0) {
            songs.filterIndexed { index, _ -> index != anchorIndex }
        } else {
            songs.toList()
        }

        if (candidates.size <= 1) {
            return if (lockedAnchor != null) listOf(lockedAnchor) + candidates else candidates
        }

        // If profile hasn't been populated from DB yet, derive an instant lightweight profile from the queue itself
        val effectiveProfile = if (profile.genreWeights.isEmpty() && profile.artistWeights.isEmpty() && profile.totalHistoryEvents == 0) {
            buildListeningProfile(songs, emptyList(), nowMs)
        } else {
            profile
        }

        val anchorGenre = normalizeGenre(lockedAnchor?.genre)
        val baseScoreByIndex = FloatArray(candidates.size)
        val genreByIndex = arrayOfNulls<String>(candidates.size)
        val artistByIndex = arrayOfNulls<String>(candidates.size)
        val albumByIndex = arrayOfNulls<String>(candidates.size)

        for (i in candidates.indices) {
            val song = candidates[i]
            val genre = normalizeGenre(song.genre)
            val artist = normalizeArtist(song.displayArtist)
            val album = song.displayAlbum.trim().lowercase().takeIf {
                it.isNotEmpty() && it != "unknown album" && it != "unknown"
            }
            genreByIndex[i] = genre
            artistByIndex[i] = artist
            albumByIndex[i] = album

            // 1. Genre preference affinity (strong driver of session taste)
            val genrePref = genre?.let { effectiveProfile.genreWeights[it] ?: 0f } ?: 0f
            val anchorGenreBonus = if (anchorGenre != null && genre == anchorGenre) 0.45f else 0f

            // 2. Artist preference affinity
            val artistPref = artist?.let { effectiveProfile.artistWeights[it] ?: 0f } ?: 0f

            // 3. Playback history engagement (completion, favorites, play count, skip penalty)
            val historyEngagement = effectiveProfile.trackEngagement[song.id] ?: 0f
            val skipPenalty = effectiveProfile.trackSkipPenalty[song.id] ?: 0f
            val playCountLift = (ln(1.0 + song.playCount.coerceAtLeast(0).toDouble()) * 0.32).toFloat().coerceAtMost(1.35f)
            val favoriteBonus = if (song.isFavorite) 0.95f else 0f

            // 4. Recency & rediscovery curve
            val lastPlayed = maxOf(song.lastPlayed, effectiveProfile.lastPlayedMsByTrack[song.id] ?: 0L)
            val recencyModifier = when {
                lastPlayed <= 0L -> {
                    // Unplayed discovery boost when the track matches a preferred genre or artist
                    if (genrePref >= 0.35f || artistPref >= 0.35f) 0.50f else 0.18f
                }
                else -> {
                    val elapsed = (nowMs - lastPlayed).coerceAtLeast(0L)
                    when {
                        elapsed < RECENT_COOLDOWN_MS -> {
                            // Strongly push back tracks played just moments ago
                            val cooldownFraction = 1f - (elapsed.toFloat() / RECENT_COOLDOWN_MS.toFloat())
                            -2.40f * cooldownFraction - 0.55f
                        }
                        elapsed in REDISCOVERY_MIN_MS..REDISCOVERY_MAX_MS && (song.playCount > 0 || song.isFavorite) -> {
                            // Rediscovery sweet spot: tracks you enjoy that you haven't heard recently
                            0.55f
                        }
                        else -> 0.12f
                    }
                }
            }

            // 5. Controlled stochastic jitter so every shuffle session feels fresh and organic
            val organicJitter = (random.nextFloat() - 0.5f) * 0.90f

            baseScoreByIndex[i] =
                (genrePref * 1.85f) +
                anchorGenreBonus +
                (artistPref * 0.95f) +
                (historyEngagement * 0.85f) +
                playCountLift +
                favoriteBonus -
                (skipPenalty * 1.65f) +
                recencyModifier +
                organicJitter
        }

        // Flow-aware greedy selection with artist/album anti-clumping and balanced genre pacing
        val remaining = BooleanArray(candidates.size) { true }
        val ordered = ArrayList<Song>(songs.size)
        if (lockedAnchor != null) {
            ordered.add(lockedAnchor)
        }

        val recentArtists = ArrayDeque<String>(4)
        var lastAlbum: String? = lockedAnchor?.displayAlbum?.trim()?.lowercase()
        var lastGenre: String? = anchorGenre
        var consecutiveSameGenre = if (anchorGenre != null) 1 else 0
        normalizeArtist(lockedAnchor?.displayArtist)?.let { recentArtists.addLast(it) }

        val topK = 5
        val topIndices = IntArray(topK)
        val topScores = FloatArray(topK)

        for (step in candidates.indices) {
            var foundCount = 0
            // Maintain top-K highest dynamically adjusted candidates for weighted stochastic pick
            for (i in candidates.indices) {
                if (!remaining[i]) continue
                var score = baseScoreByIndex[i]

                val artist = artistByIndex[i]
                if (artist != null) {
                    val lastPos = recentArtists.lastIndexOf(artist)
                    if (lastPos >= 0) {
                        val distance = recentArtists.size - lastPos
                        score -= when (distance) {
                            1 -> 3.40f // Strongly avoid back-to-back same artist
                            2 -> 1.85f
                            else -> 0.85f
                        }
                    }
                }

                val album = albumByIndex[i]
                if (album != null && album == lastAlbum) {
                    score -= 1.45f
                }

                val genre = genreByIndex[i]
                if (genre != null && lastGenre != null) {
                    if (genre == lastGenre) {
                        // Allow smooth 2-track genre flow, then encourage fresh variety on track 3+
                        score += if (consecutiveSameGenre < 2) 0.30f else -0.65f
                    } else if (consecutiveSameGenre >= 2) {
                        score += 0.25f
                    }
                }

                // Insert into sorted top-K buffer (descending)
                if (foundCount < topK || score > topScores[foundCount - 1]) {
                    var insertAt = if (foundCount < topK) foundCount else topK - 1
                    if (foundCount < topK) foundCount++
                    while (insertAt > 0 && score > topScores[insertAt - 1]) {
                        topScores[insertAt] = topScores[insertAt - 1]
                        topIndices[insertAt] = topIndices[insertAt - 1]
                        insertAt--
                    }
                    topScores[insertAt] = score
                    topIndices[insertAt] = i
                }
            }

            if (foundCount == 0) break

            // Weighted softmax choice among top-K so highest-scoring tracks strongly lead while remaining natural
            val pickedCandidateIdx = if (foundCount == 1) {
                topIndices[0]
            } else {
                val maxScore = topScores[0]
                val temperature = 0.65f
                var totalWeight = 0.0
                val weights = DoubleArray(foundCount)
                for (k in 0 until foundCount) {
                    val w = exp(((topScores[k] - maxScore) / temperature).toDouble().coerceIn(-12.0, 0.0))
                    weights[k] = w
                    totalWeight += w
                }
                var pick = random.nextDouble() * totalWeight
                var chosen = topIndices[0]
                for (k in 0 until foundCount) {
                    pick -= weights[k]
                    if (pick <= 0.0) {
                        chosen = topIndices[k]
                        break
                    }
                }
                chosen
            }

            remaining[pickedCandidateIdx] = false
            ordered.add(candidates[pickedCandidateIdx])

            val pickedArtist = artistByIndex[pickedCandidateIdx]
            if (pickedArtist != null) {
                if (recentArtists.size >= 3) recentArtists.removeFirst()
                recentArtists.addLast(pickedArtist)
            }
            lastAlbum = albumByIndex[pickedCandidateIdx]

            val pickedGenre = genreByIndex[pickedCandidateIdx]
            if (pickedGenre != null && pickedGenre == lastGenre) {
                consecutiveSameGenre++
            } else {
                lastGenre = pickedGenre
                consecutiveSameGenre = if (pickedGenre != null) 1 else 0
            }
        }

        return ordered
    }
}

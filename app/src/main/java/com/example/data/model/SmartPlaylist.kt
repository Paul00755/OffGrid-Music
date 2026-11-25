package com.example.data.model

import com.example.data.sync.SongIdentityHelper

data class SmartPlaylist(
    val id: Long = 0L,
    val name: String,
    val genreFilter: String = "ALL", // "ALL" or comma-separated genres
    val minPlayCount: Int = 0,
    val timeframeDays: Int = 0, // 0 = Anytime, 7, 30, 90
    val maxTrackLimit: Int = 0, // 0 = Unlimited, 10, 25, 50, 100
    val sortCriteria: String = "RECENTLY_ADDED", // RECENTLY_ADDED, MOST_PLAYED, RECENTLY_PLAYED, TITLE, ARTIST, DURATION_ASC, DURATION_DESC
    val createdAt: Long = System.currentTimeMillis(),
    val artistFilter: String = "ALL", // "ALL" or comma-separated artist names
    val favoritesOnly: Boolean = false,
    val durationFilter: String = "ANY", // "ANY", "SHORT" (<3m), "MEDIUM" (3-5m), "LONG" (>5m)
    val unplayedOnly: Boolean = false,
    val customTrackOrder: List<String> = emptyList(), // Persisted SongIdentityHelper signatures
    val displayOrder: Int = Int.MAX_VALUE
) {
    val hasCustomTrackOrder: Boolean
        get() = customTrackOrder.isNotEmpty()

    fun parsedGenres(): List<String> {
        if (genreFilter.isBlank() || genreFilter.equals("ALL", ignoreCase = true)) return emptyList()
        return genreFilter.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("ALL", ignoreCase = true) }
            .distinctBy { it.lowercase() }
    }

    fun parsedArtists(): List<String> {
        if (artistFilter.isBlank() || artistFilter.equals("ALL", ignoreCase = true)) return emptyList()
        return artistFilter.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("ALL", ignoreCase = true) }
            .distinctBy { it.lowercase() }
    }

    fun getCriteriaBadges(): List<String> {
        val badges = mutableListOf<String>()
        val genres = parsedGenres()
        if (genres.size == 1) {
            badges.add("Genre: ${genres.first()}")
        } else if (genres.size > 1) {
            badges.add("Genres: ${genres.take(2).joinToString(", ")}${if (genres.size > 2) " +${genres.size - 2}" else ""}")
        }
        val artists = parsedArtists()
        if (artists.size == 1) {
            badges.add("Artist: ${artists.first()}")
        } else if (artists.size > 1) {
            badges.add("Artists: ${artists.size}")
        }
        if (favoritesOnly) {
            badges.add("Favorites Only")
        }
        if (unplayedOnly) {
            badges.add("Unplayed")
        } else if (minPlayCount > 0) {
            badges.add("Plays ≥ $minPlayCount")
        }
        if (timeframeDays > 0) {
            badges.add("Last $timeframeDays Days")
        }
        when (durationFilter.uppercase()) {
            "SHORT" -> badges.add("< 3 min")
            "MEDIUM" -> badges.add("3–5 min")
            "LONG" -> badges.add("> 5 min")
        }
        if (maxTrackLimit > 0) {
            badges.add("Limit: $maxTrackLimit")
        }
        val sortLabel = when (sortCriteria) {
            "MOST_PLAYED" -> "Sort: Most Played"
            "RECENTLY_PLAYED" -> "Sort: Recently Played"
            "TITLE" -> "Sort: Title"
            "ARTIST" -> "Sort: Artist"
            "DURATION_ASC" -> "Sort: Shortest First"
            "DURATION_DESC" -> "Sort: Longest First"
            else -> "Sort: Recently Added"
        }
        badges.add(sortLabel)
        if (hasCustomTrackOrder) {
            badges.add("Custom Order")
        }
        return badges
    }

    fun filterSongs(allSongs: List<Song>): List<Song> {
        return try {
            val now = System.currentTimeMillis()
            var sequence = allSongs.asSequence()

            val selectedGenres = parsedGenres()
            if (selectedGenres.isNotEmpty()) {
                sequence = sequence.filter { song ->
                    val rawGenre = song.genre.trim()
                    if (rawGenre.isBlank() || rawGenre.equals("<unknown>", ignoreCase = true)) {
                        false
                    } else {
                        val songGenreTokens = rawGenre.split("/", ",", ";", "&")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                        selectedGenres.any { filter ->
                            rawGenre.equals(filter, ignoreCase = true) ||
                                songGenreTokens.any { it.equals(filter, ignoreCase = true) } ||
                                rawGenre.contains(filter, ignoreCase = true)
                        }
                    }
                }
            }

            val selectedArtists = parsedArtists()
            if (selectedArtists.isNotEmpty()) {
                sequence = sequence.filter { song ->
                    val artistText = song.displayArtist.trim()
                    selectedArtists.any { filter ->
                        artistText.equals(filter, ignoreCase = true) ||
                            artistText.contains(filter, ignoreCase = true)
                    }
                }
            }

            if (favoritesOnly) {
                sequence = sequence.filter { it.isFavorite }
            }

            if (unplayedOnly) {
                sequence = sequence.filter { it.playCount == 0 }
            } else if (minPlayCount > 0) {
                sequence = sequence.filter { song -> song.playCount >= minPlayCount }
            }

            if (timeframeDays > 0) {
                val cutoffMs = now - (timeframeDays.toLong() * 24L * 3600L * 1000L)
                sequence = sequence.filter { song ->
                    val normalizedAdded = normalizeEpochMillis(song.dateAdded)
                    val normalizedPlayed = normalizeEpochMillis(song.lastPlayed)
                    normalizedAdded >= cutoffMs || normalizedPlayed >= cutoffMs
                }
            }

            when (durationFilter.uppercase()) {
                "SHORT" -> sequence = sequence.filter { it.durationMs in 1L until 180_000L }
                "MEDIUM" -> sequence = sequence.filter { it.durationMs in 180_000L..300_000L }
                "LONG" -> sequence = sequence.filter { it.durationMs > 300_000L }
            }

            val sorted = when (sortCriteria) {
                "MOST_PLAYED" -> sequence.sortedWith(
                    compareByDescending<Song> { it.playCount }
                        .thenByDescending { normalizeEpochMillis(it.lastPlayed) }
                        .thenBy { it.displayTitle.lowercase() }
                ).toList()
                "RECENTLY_PLAYED" -> sequence.sortedWith(
                    compareByDescending<Song> { normalizeEpochMillis(it.lastPlayed) }
                        .thenByDescending { it.playCount }
                        .thenBy { it.displayTitle.lowercase() }
                ).toList()
                "TITLE" -> sequence.sortedWith(
                    compareBy<Song> { it.displayTitle.lowercase() }
                        .thenBy { it.displayArtist.lowercase() }
                ).toList()
                "ARTIST" -> sequence.sortedWith(
                    compareBy<Song> { it.displayArtist.lowercase() }
                        .thenBy { it.displayTitle.lowercase() }
                ).toList()
                "DURATION_ASC" -> sequence.sortedWith(
                    compareBy<Song> { it.durationMs }
                        .thenBy { it.displayTitle.lowercase() }
                ).toList()
                "DURATION_DESC" -> sequence.sortedWith(
                    compareByDescending<Song> { it.durationMs }
                        .thenBy { it.displayTitle.lowercase() }
                ).toList()
                else -> sequence.sortedWith(
                    compareByDescending<Song> { normalizeEpochMillis(it.dateAdded) }
                        .thenBy { it.displayTitle.lowercase() }
                ).toList()
            }

            val limited = if (maxTrackLimit > 0 && maxTrackLimit < sorted.size) {
                sorted.take(maxTrackLimit)
            } else {
                sorted
            }

            if (customTrackOrder.isEmpty() || limited.size <= 1) {
                limited
            } else {
                val orderRank = HashMap<String, Int>(customTrackOrder.size * 2)
                customTrackOrder.forEachIndexed { idx, sig ->
                    if (sig.isNotBlank() && !orderRank.containsKey(sig)) {
                        orderRank[sig] = idx
                    }
                }
                val indexed = limited.mapIndexed { originalIndex, song ->
                    val sig = SongIdentityHelper.computeSignature(song)
                    val rank = orderRank[sig] ?: (Int.MAX_VALUE - limited.size + originalIndex)
                    Triple(rank, originalIndex, song)
                }
                indexed.sortedWith(compareBy<Triple<Int, Int, Song>> { it.first }.thenBy { it.second })
                    .map { it.third }
            }
        } catch (_: Exception) {
            allSongs
        }
    }

    companion object {
        /**
         * MediaStore `DATE_ADDED` is often stored in epoch seconds (~1.7e9) while `lastPlayed` and
         * newer timestamps use epoch milliseconds (~1.7e12). Normalize seconds -> millis so timeframe
         * filtering and Recently Added sorting are always accurate.
         */
        internal fun normalizeEpochMillis(raw: Long): Long {
            if (raw <= 0L) return 0L
            return if (raw in 1L..99_999_999_999L) raw * 1000L else raw
        }
    }
}

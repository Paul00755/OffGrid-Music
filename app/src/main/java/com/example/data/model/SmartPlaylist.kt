package com.example.data.model

data class SmartPlaylist(
    val id: Long = 0L,
    val name: String,
    val genreFilter: String = "ALL", // "ALL" or specific genre
    val minPlayCount: Int = 0,
    val timeframeDays: Int = 0, // 0 = Anytime, 7, 30, 90
    val maxTrackLimit: Int = 0, // 0 = Unlimited, 10, 25, 50, 100
    val sortCriteria: String = "RECENTLY_ADDED", // RECENTLY_ADDED, MOST_PLAYED, TITLE, ARTIST
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getCriteriaBadges(): List<String> {
        val badges = mutableListOf<String>()
        if (genreFilter != "ALL" && genreFilter.isNotBlank()) {
            badges.add("Genre: $genreFilter")
        }
        if (minPlayCount > 0) {
            badges.add("Plays ≥ $minPlayCount")
        }
        if (timeframeDays > 0) {
            badges.add("Last $timeframeDays Days")
        }
        if (maxTrackLimit > 0) {
            badges.add("Limit: $maxTrackLimit")
        }
        val sortLabel = when (sortCriteria) {
            "MOST_PLAYED" -> "Sort: Most Played"
            "TITLE" -> "Sort: Title"
            "ARTIST" -> "Sort: Artist"
            else -> "Sort: Recently Added"
        }
        badges.add(sortLabel)
        return badges
    }

    fun filterSongs(allSongs: List<Song>): List<Song> {
        return try {
            val now = System.currentTimeMillis()
            var sequence = allSongs.asSequence()

            if (genreFilter != "ALL" && genreFilter.isNotBlank()) {
                val cleanFilter = genreFilter.trim()
                sequence = sequence.filter { song ->
                    song.genre.trim().equals(cleanFilter, ignoreCase = true) ||
                            song.genre.contains(cleanFilter, ignoreCase = true)
                }
            }

            if (minPlayCount > 0) {
                sequence = sequence.filter { song -> song.playCount >= minPlayCount }
            }

            if (timeframeDays > 0) {
                val cutoff = now - (timeframeDays.toLong() * 24 * 3600 * 1000L)
                sequence = sequence.filter { song -> song.dateAdded >= cutoff || song.lastPlayed >= cutoff }
            }

            val sorted = when (sortCriteria) {
                "MOST_PLAYED" -> sequence.sortedByDescending { it.playCount }.toList()
                "TITLE" -> sequence.sortedBy { it.displayTitle.lowercase() }.toList()
                "ARTIST" -> sequence.sortedBy { it.displayArtist.lowercase() }.toList()
                else -> sequence.sortedByDescending { it.dateAdded }.toList()
            }

            if (maxTrackLimit > 0 && maxTrackLimit < sorted.size) {
                sorted.take(maxTrackLimit)
            } else {
                sorted
            }
        } catch (e: Exception) {
            allSongs
        }
    }
}

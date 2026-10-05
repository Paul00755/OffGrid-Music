package com.example.service.transcription

enum class AudioTranscribeMode(val id: String, val displayName: String, val description: String) {
    OFF(
        id = "OFF",
        displayName = "Off",
        description = "Feature disabled"
    ),
    TRANSCRIBE(
        id = "TRANSCRIBE",
        displayName = "Transcribe Audio",
        description = "Recognize vocals directly from audio and generate synchronized lyrics"
    ),
    TRANSLATE(
        id = "TRANSLATE",
        displayName = "Translate Lyrics",
        description = "Translate existing lyrics while strictly preserving original timestamps"
    );

    companion object {
        fun fromId(id: String?): AudioTranscribeMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OFF
        }
    }
}

enum class TranscribeLanguage(val code: String, val englishName: String, val nativeName: String) {
    ENGLISH("en", "English", "English"),
    TAMIL("ta", "Tamil", "தமிழ்"),
    HINDI("hi", "Hindi", "हिन्दी"),
    MALAYALAM("ml", "Malayalam", "മലയാളം");

    val displayLabel: String get() = "$englishName ($nativeName)"

    companion object {
        fun fromCode(code: String?): TranscribeLanguage {
            if (code.isNullOrBlank()) return TAMIL
            return entries.firstOrNull {
                it.code.equals(code, ignoreCase = true) || it.englishName.equals(code, ignoreCase = true)
            } ?: TAMIL
        }
    }
}

sealed class TranscribeResult {
    data class Success(
        val plainLyrics: String,
        val syncedLyricsJson: String?,
        val isFromCache: Boolean,
        val language: TranscribeLanguage,
        val mode: AudioTranscribeMode
    ) : TranscribeResult()

    data class Error(val message: String, val canRetry: Boolean = true) : TranscribeResult()
}

data class TranscribeLyricsPreview(
    val song: com.example.data.model.Song,
    val mode: AudioTranscribeMode,
    val targetLanguage: TranscribeLanguage,
    val plainLyrics: String,
    val syncedLyricsJson: String?,
    val source: String
)

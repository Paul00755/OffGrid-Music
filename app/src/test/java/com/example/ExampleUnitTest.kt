package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testLyricsLanguageDetectorClassifiesScriptsAndRomanizedLanguagesAccurately() {
    val detector = com.example.service.LyricsLanguageDetector

    // 1. Tamil Script -> "Tamil"
    val tamilLrc = "[00:12.00] என் நெஞ்சில் நீயும் இறங்கி ஏதோ மாயம் செய்தாய்\n[00:16.50] வா வா வெண்ணிலா"
    assertEquals(
      "Tamil",
      detector.detectLanguageLabel(tamilLrc, "Va Va Va Vannila", "Hiphop Tamizha", "Aambala")
    )

    // 2. Tanglish (English letters representing Tamil lyrics) -> "Tanglish"
    val tanglishLrc = "[00:12.00] En nenjil neeyum irangi edho maayam seithaai\n[00:16.50] Va va va vannila unna thaandi pogave mudiyala"
    assertEquals(
      "Tanglish",
      detector.detectLanguageLabel(tanglishLrc, "Va Va Va Vannila", "Mohit Chauhan", "Aambala")
    )

    // 3. Fully English -> "English"
    val englishLrc = "[00:10.00] Look at the stars look how they shine for you\n[00:15.00] And everything you do yeah they were all yellow"
    assertEquals(
      "English",
      detector.detectLanguageLabel(englishLrc, "Yellow", "Coldplay", "Parachutes")
    )

    // 4. Malayalam Script -> "Malayalam"
    val malayalamLrc = "[00:10.00] മലരേ നിന്നെ കാണാതിരുന്നാൽ മിഴികൾ നിറയും"
    assertEquals(
      "Malayalam",
      detector.detectLanguageLabel(malayalamLrc, "Malare", "Vijay Yesudas", "Premam")
    )

    // 5. Malayalam in English (Manglish) -> "Malayalam (Eng)"
    val manglishLrc = "[00:10.00] Malare ninne kaanathirunnalimizhikal nirayum\n[00:15.00] Ennum ninne orthu paadunnu njan pranayam"
    assertEquals(
      "Malayalam (Eng)",
      detector.detectLanguageLabel(manglishLrc, "Malare", "Vijay Yesudas", "Premam")
    )

    // 6. Hindi Script -> "Hindi"
    val hindiLrc = "[00:10.00] तुम ही हो अब तुम ही हो ज़िंदगी अब तुम ही हो"
    assertEquals(
      "Hindi",
      detector.detectLanguageLabel(hindiLrc, "Tum Hi Ho", "Arijit Singh", "Aashiqui 2")
    )

    // 7. Hindi in English -> "Hindi (Eng)"
    val hinglishLrc = "[00:10.00] Tum hi ho ab tum hi ho zindagi ab tum hi ho\n[00:15.00] Tera mera rishta hai kaisa ishq aur mohabbat"
    assertEquals(
      "Hindi (Eng)",
      detector.detectLanguageLabel(hinglishLrc, "Tum Hi Ho", "Arijit Singh", "Aashiqui 2")
    )

    // 8. Duck volume multiplier reduces 40% of current total volume (1.0f - 0.40f = 0.60f)
    assertEquals(0.60f, com.example.service.AudioFocusManager.DEFAULT_NOTIFICATION_DUCK_VOLUME, 0.001f)
  }
}

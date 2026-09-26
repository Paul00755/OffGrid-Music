package com.example.util

object TransliterationUtil {

    private val TAMIL_MAP = mapOf(
        // Common Tamil to English approximations
        "க" to "ka", "கா" to "kaa", "கி" to "ki", "கீ" to "kee", "கு" to "ku", "கூ" to "koo", "கெ" to "ke", "கே" to "kae", "கை" to "kai", "கொ" to "ko", "கோ" to "koa",
        "ச" to "sa", "சா" to "saa", "சி" to "si", "சீ" to "see", "சு" to "su", "சூ" to "soo", "செ" to "se", "சே" to "sae", "சை" to "sai", "சொ" to "so", "சோ" to "soa",
        "த" to "tha", "தா" to "thaa", "தி" to "thi", "தீ" to "thee", "து" to "thu", "தூ" to "thoo", "தெ" to "the", "தே" to "thae", "தை" to "thai", "தொ" to "tho", "தோ" to "thoa",
        "ப" to "pa", "பா" to "paa", "பி" to "pi", "பீ" to "pee", "பு" to "pu", "பூ" to "poo", "பெ" to "pe", "பே" to "pae", "பை" to "pai", "பொ" to "po", "போ" to "poa",
        "ம" to "ma", "மா" to "maa", "மி" to "mi", "மீ" to "mee", "மு" to "mu", "மூ" to "moo", "மெ" to "me", "மே" to "mae", "மை" to "mai", "மொ" to "mo", "மோ" to "moa",
        "வ" to "va", "வா" to "vaa", "வி" to "vi", "வீ" to "vee", "வு" to "vu", "வூ" to "voo", "வெ" to "ve", "வே" to "vae", "வை" to "vai", "வொ" to "vo", "வோ" to "voa",
        "ர" to "ra", "ரா" to "raa", "ரி" to "ri", "ரீ" to "ree", "ரு" to "ru", "ரூ" to "roo", "ரெ" to "re", "ரே" to "rae", "ரை" to "rai", "ரொ" to "ro", "ரோ" to "roa",
        "ல" to "la", "லா" to "laa", "லி" to "li", "லீ" to "lee", "லு" to "lu", "லூ" to "loo", "லெ" to "le", "லே" to "lae", "லை" to "lai", "லொ" to "lo", "லோ" to "loa"
    )

    fun normalizeSearchQuery(query: String): List<String> {
        val lowercase = query.lowercase().trim()
        val variants = mutableListOf(lowercase)

        // Handle double vowel variants e.g. "kadhal" <-> "kaadhal"
        if (lowercase.contains("aa")) {
            variants.add(lowercase.replace("aa", "a"))
        } else if (lowercase.contains("a")) {
            variants.add(lowercase.replace("a", "aa"))
        }

        if (lowercase.contains("ee")) {
            variants.add(lowercase.replace("ee", "i"))
        } else if (lowercase.contains("i")) {
            variants.add(lowercase.replace("i", "ee"))
        }

        if (lowercase.contains("oo")) {
            variants.add(lowercase.replace("oo", "u"))
        } else if (lowercase.contains("u")) {
            variants.add(lowercase.replace("u", "oo"))
        }

        return variants.distinct()
    }
}

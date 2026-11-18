package com.kbhatiya999.jarvis.core

/**
 * A rough guess at what language a piece of transcribed speech is in, for the log only (to see when Gemini
 * transcribes someone's English as Chinese or Spanish). Non-Latin scripts are told apart by their letters; Latin
 * text is English or Spanish by common words, else "?".
 */
object Language {
    fun guess(text: String): String {
        var han = 0; var kana = 0; var hangul = 0; var devanagari = 0; var arabic = 0; var cyrillic = 0; var latin = 0
        for (c in text) when (c.code) {
            in 0x4E00..0x9FFF -> han++
            in 0x3040..0x30FF -> kana++
            in 0xAC00..0xD7AF -> hangul++
            in 0x0900..0x097F -> devanagari++
            in 0x0600..0x06FF -> arabic++
            in 0x0400..0x04FF -> cyrillic++
            in 'A'.code..'Z'.code, in 'a'.code..'z'.code, in 0xC0..0x17F -> latin++
        }
        val scripts = listOf(
            (if (kana > 0) "ja" else "zh") to (han + kana), "ko" to hangul, "hi" to devanagari, "ar" to arabic, "ru" to cyrillic,
        )
        val (code, count) = scripts.maxByOrNull { it.second }!!
        if (count > 0 && count >= latin) return code
        if (latin == 0) return "?"
        val words = text.lowercase().split(Regex("[^a-záéíóúñü]+")).filter { it.isNotEmpty() }
        val es = words.count { it in SPANISH }
        val en = words.count { it in ENGLISH }
        return when {
            es >= 2 && es > en -> "es"
            en >= 1 && en >= es -> "en"
            else -> "?"
        }
    }

    private val ENGLISH = setOf("the", "and", "you", "is", "are", "to", "of", "it", "in", "that", "what", "me", "my", "remind", "tell", "i", "for", "on", "with", "can", "please")
    private val SPANISH = setOf("el", "la", "los", "las", "de", "que", "y", "es", "en", "un", "una", "por", "con", "para", "me", "mi", "recuérdame", "dime", "qué", "cómo", "está", "hola", "gracias")
}

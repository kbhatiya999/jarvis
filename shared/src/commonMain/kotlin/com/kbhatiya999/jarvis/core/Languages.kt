package com.kbhatiya999.jarvis.core

/**
 * The languages the user speaks to Jarvis, in order: English (India) and Hindi unless they change it.
 * Gemini listens for all of them (so Hinglish works and English isn't written down as Chinese or Spanish), speaks
 * the first, and replies in whichever of them the user just spoke. Main thread only.
 */
class Languages internal constructor(private val store: KeyValueStore) {
    val codes = Watchable(load())

    /** The language Jarvis speaks by default: the first one. */
    val primary: String get() = codes.value.first()

    /** Adds [code] (e.g. "mr-IN") at the end. Returns an error message, or null. */
    fun add(code: String): String? {
        val c = normalize(code) ?: return "That isn't a language code. Use one like mr-IN or ta-IN."
        if (codes.value.any { it.equals(c, ignoreCase = true) }) return "${name(c)} is already in the list."
        if (codes.value.size >= MAX) return "At most $MAX languages; remove one first."
        set(codes.value + c)
        return null
    }

    /** Removes [code]; the last language can't be removed. */
    fun remove(code: String) {
        if (codes.value.size <= 1) return
        set(codes.value.filterNot { it == code })
    }

    /** Moves [code] one place up (to the front makes it the language Jarvis speaks). */
    fun moveUp(code: String) {
        val list = codes.value.toMutableList()
        val i = list.indexOf(code)
        if (i <= 0) return
        list.removeAt(i)
        list.add(i - 1, code)
        set(list)
    }

    fun resetToDefaults() = set(DEFAULT)

    /** True if a guess from [Language.guess] ("hi", "zh", "?"...) is one of the user's languages (or unknown). */
    fun includesGuess(guess: String): Boolean =
        guess == "?" || codes.value.any { base(it) == guess }

    private fun set(list: List<String>) {
        codes.value = list
        store.set(KEY, stringListJson(list))
        JarvisLog.i("Languages", "languages: ${list.joinToString(", ")}")
    }

    private fun load(): List<String> =
        stringList(store.get(KEY)).mapNotNull { normalize(it) }.distinct().take(MAX).ifEmpty { DEFAULT }

    companion object {
        private const val KEY = "codes"
        const val MAX = 4
        val DEFAULT = listOf("en-IN", "hi-IN")

        /** Languages Gemini Live speaks and transcribes well; offered under "Add language". Others can be typed. */
        val SUGGESTED = listOf(
            "en-IN", "hi-IN", "bn-IN", "gu-IN", "kn-IN", "ml-IN", "mr-IN", "ta-IN", "te-IN",
            "en-US", "en-GB", "en-AU", "es-ES", "es-US", "fr-FR", "fr-CA", "de-DE", "it-IT", "pt-BR", "nl-NL",
            "pl-PL", "ru-RU", "tr-TR", "ar-XA", "id-ID", "vi-VN", "th-TH", "ja-JP", "ko-KR", "cmn-CN",
        )

        private val CODE = Regex("^([a-zA-Z]{2,3})(-[a-zA-Z0-9]{2,8})*$")

        /** "mr-in" -> "mr-IN"; null if it doesn't look like a BCP-47 code. */
        fun normalize(code: String): String? {
            val c = code.trim().replace('_', '-')
            if (!CODE.matches(c)) return null
            val parts = c.split('-')
            return (listOf(parts[0].lowercase()) + parts.drop(1).map { if (it.length == 2) it.uppercase() else it }).joinToString("-")
        }

        /** "hi-IN" -> "hi"; Gemini's "cmn" (Mandarin) counts as "zh". */
        fun base(code: String): String = code.substringBefore('-').lowercase().let { if (it == "cmn") "zh" else it }

        /** "Hindi (hi-IN)"; just the code for languages without a name here. */
        fun label(code: String): String = NAMES[code] ?: NAMES[base(code)]?.let { "$it ($code)" } ?: code

        /** "Hindi"; the code if unknown. */
        fun name(code: String): String = NAMES[code]?.substringBefore(" (") ?: NAMES[base(code)] ?: code

        private val NAMES = mapOf(
            "en-IN" to "English (India)", "en-US" to "English (US)", "en-GB" to "English (UK)", "en-AU" to "English (Australia)",
            "es-US" to "Spanish (US)", "es-ES" to "Spanish (Spain)", "fr-CA" to "French (Canada)", "fr-FR" to "French (France)",
            "en" to "English", "hi" to "Hindi", "bn" to "Bengali", "gu" to "Gujarati", "kn" to "Kannada", "ml" to "Malayalam",
            "mr" to "Marathi", "ta" to "Tamil", "te" to "Telugu", "pa" to "Punjabi", "ur" to "Urdu", "or" to "Odia",
            "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch",
            "pl" to "Polish", "ru" to "Russian", "tr" to "Turkish", "ar" to "Arabic", "id" to "Indonesian", "vi" to "Vietnamese",
            "th" to "Thai", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "cmn" to "Mandarin Chinese",
        )
    }
}

package com.kbhatiya999.jarvis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageTest {
    @Test
    fun scriptsAreTold() {
        assertEquals("zh", Language.guess("提醒我喝水"))
        assertEquals("ja", Language.guess("水を飲むように教えて"))
        assertEquals("hi", Language.guess("मुझे याद दिलाओ"))
        assertEquals("ru", Language.guess("напомни мне"))
    }

    @Test
    fun latinIsEnglishOrSpanishByWords() {
        assertEquals("en", Language.guess("Remind me to drink water"))
        assertEquals("es", Language.guess("Recuérdame que beba agua por favor, gracias"))
        assertEquals("?", Language.guess("zzz"))
        assertEquals("?", Language.guess("123 !!"))
    }
}

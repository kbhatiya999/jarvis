package com.kbhatiya999.jarvis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguagesTest {
    private fun languages(store: MemStore = MemStore()) = Languages(store)

    @Test
    fun defaultsToEnglishIndiaThenHindi() {
        val l = languages()
        assertEquals(listOf("en-IN", "hi-IN"), l.codes.value)
        assertEquals("en-IN", l.primary)
    }

    @Test
    fun addNormalizesRejectsBadCodesAndDuplicates() {
        val l = languages()
        assertNull(l.add("mr_in"))
        assertEquals("mr-IN", l.codes.value.last())
        assertNotNull(l.add("Hindi"), "a name is not a code")
        assertNotNull(l.add("HI-in"), "already there, any case")
        assertNull(l.add("cmn-CN"))
        assertNotNull(l.add("ta-IN"), "at most ${Languages.MAX}")
    }

    @Test
    fun removeKeepsAtLeastOneAndMoveUpChangesTheSpokenLanguage() {
        val l = languages()
        l.moveUp("hi-IN")
        assertEquals("hi-IN", l.primary)
        l.remove("en-IN")
        l.remove("hi-IN")
        assertEquals(listOf("hi-IN"), l.codes.value)
    }

    @Test
    fun savedListSurvivesAndBadSavedDataFallsBackToDefaults() {
        val store = MemStore()
        languages(store).add("gu-IN")
        assertEquals(listOf("en-IN", "hi-IN", "gu-IN"), languages(store).codes.value)
        store.set("codes", "not json")
        assertEquals(Languages.DEFAULT, languages(store).codes.value)
    }

    @Test
    fun guessesOutsideTheListAreFlagged() {
        val l = languages()
        assertTrue(l.includesGuess("hi"))
        assertTrue(l.includesGuess("en"))
        assertTrue(l.includesGuess("?"))
        assertFalse(l.includesGuess("zh"))
        assertFalse(l.includesGuess("es"))
        l.add("cmn-CN")
        assertTrue(l.includesGuess("zh"), "Gemini's cmn is Chinese")
    }

    @Test
    fun promptNamesEveryLanguageAndForbidsOthers() {
        val rules = Prompts.languageRules(listOf("en-IN", "hi-IN"))
        assertTrue("English (en-IN)" in rules && "Hindi (hi-IN)" in rules, rules)
        assertTrue("mix" in rules)
        assertTrue("Never answer in any other language" in rules)
        assertTrue("never translate" in rules)
        val one = Prompts.languageRules(listOf("ta-IN"))
        assertTrue("The user speaks Tamil (ta-IN)." in one, one)
    }
}

package com.kbhatiya999.jarvis.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The model sometimes voices a stage direction instead of staying quiet; Jarvis must catch it. */
class SilenceTest {
    @Test
    fun placeholdersAreCaught() {
        listOf("<no speech>{pause}", "<no speech>", "{pause}", "[silence]", "(silence)", "(no response)", " <noise> ",
            "No speech.", "silence").forEach { assertTrue(LiveSession.isPlaceholder(it), it) }
    }

    @Test
    fun realRepliesAreNot() {
        listOf("Sir, it is time for you to stretch.", "Yes, Sir?", "Pause the music? Done.", "No problem, Sir.",
            "Silence is golden, Sir.").forEach { assertFalse(LiveSession.isPlaceholder(it), it) }
    }

    @Test
    fun thePromptTellsJarvisToUseTheToolNotWords() {
        assertTrue("call stay_silent" in Prompts.addressedRules)
        assertTrue("<no speech>" in Prompts.addressedRules)
    }
}

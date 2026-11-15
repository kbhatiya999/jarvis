package com.kbhatiya999.jarvis.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActiveModeTest {
    // Jarvis is one instance per process: use its platform's clock.
    private val jarvis = Jarvis.setUp(FakePlatform())
    private val platform = jarvis.platform as FakePlatform
    private val active = jarvis.active

    private fun reset() {
        jarvis.saveApiKey("test-key")
        active.cooldownSeconds = 600
        active.quietHours = true
        active.lastPromptAt = 0
    }

    @Test
    fun defaults() {
        // Same defaults as Android 0.6.0: off, 30 minutes, quiet hours on.
        val store = platform.store("jarvis_active") as MemStore
        val saved = store.map.toMap()
        store.map.clear()
        assertFalse(active.enabled)
        assertFalse(active.hasSettings)
        assertEquals(1800, active.cooldownSeconds)
        assertTrue(active.quietHours)
        // What the user picked as defaults: listen 5 s after Jarvis finishes, also on the lock screen, no interrupting.
        assertEquals(5, active.listenSeconds)
        assertTrue(active.speakWhenLocked)
        assertFalse(active.interruptible)
        assertEquals(ActiveMode.INTERRUPT_NAME, active.interruptMode)
        assertFalse(active.wakeEnabled)
        assertTrue(active.wakePauseAtNight)
        assertEquals(20, active.wakeMinBattery)
        assertEquals(ActiveMode.DEFAULT_WAKE_LINES, active.wakeLines)
        store.map.putAll(saved)
    }

    @Test
    fun cooldown() {
        reset()
        assertTrue(active.allowed(hour = 12))
        active.markSpoke()
        assertFalse(active.allowed(hour = 12))
        assertTrue(active.allowed(hour = 12, ignoreCooldown = true))
        platform.now += 599_000
        assertFalse(active.allowed(hour = 12))
        platform.now += 1_000
        assertTrue(active.allowed(hour = 12))
    }

    @Test
    fun quietHours() {
        reset()
        assertFalse(active.allowed(hour = 23))
        assertFalse(active.allowed(hour = 6))
        assertTrue(active.allowed(hour = 7))
        active.quietHours = false
        assertTrue(active.allowed(hour = 2))
    }

    @Test
    fun needsApiKey() {
        reset()
        jarvis.saveApiKey("")
        assertFalse(active.allowed(hour = 12))
    }

    @Test
    fun promptWithoutListening() {
        reset()
        assertContains(active.appOpenedPrompt("Instagram", "9:00 AM", canListen = false), "don't ask questions")
        assertContains(active.appOpenedPrompt("Instagram", "9:00 AM"), "converse naturally")
    }

    @Test
    fun voiceCommandsRestToSaveBattery() {
        active.wakePauseAtNight = true
        active.wakeMinBattery = 20
        assertEquals(null, active.wakePauseReason(hour = 12, batteryPercent = 80, charging = false, batterySaver = false))
        assertEquals("night (11 pm–7 am)", active.wakePauseReason(23, 80, charging = false, batterySaver = false))
        assertEquals("battery below 20%", active.wakePauseReason(12, 19, charging = false, batterySaver = false))
        assertEquals("Battery Saver is on", active.wakePauseReason(12, 80, charging = false, batterySaver = true))
        // Charging always listens.
        assertEquals(null, active.wakePauseReason(2, 5, charging = true, batterySaver = true))
        active.wakePauseAtNight = false
        active.wakeMinBattery = 0
        assertEquals(null, active.wakePauseReason(2, 5, charging = false, batterySaver = false))
    }
}

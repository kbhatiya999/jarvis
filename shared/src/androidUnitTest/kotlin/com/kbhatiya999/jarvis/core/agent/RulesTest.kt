package com.kbhatiya999.jarvis.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RulesTest {
    @Test
    fun thinkingIsLimitedPerDayAndResetsTheNextDay() {
        val phone = Phone()
        val agent = phone.agent()
        phone.now = at(agent.clock, "2026-11-18T09:00")
        repeat(Rules.MAX_THINKS_PER_DAY) { assertTrue(agent.rules.think().allowed) }
        val over = agent.rules.think()
        assertFalse(over.allowed)
        assertTrue("limit" in over.why)
        phone.now = at(agent.clock, "2026-11-19T09:00")
        assertTrue(agent.rules.think().allowed, "a new day")
    }

    @Test
    fun nudgesAreSpacedLimitedPerGoalAndQuietAtNight() {
        val phone = Phone()
        val agent = phone.agent()
        phone.now = at(agent.clock, "2026-11-18T08:00")
        assertTrue(agent.rules.nudge("g1").allowed)
        phone.now += 10 * 60_000
        assertFalse(agent.rules.nudge("g1").allowed, "too soon after the last one")
        assertTrue(agent.rules.nudge("g2").allowed, "another goal has its own count")
        repeat(3) { phone.now += Rules.NUDGE_SPACING_MS; assertTrue(agent.rules.nudge("g1").allowed) }
        phone.now += Rules.NUDGE_SPACING_MS
        assertFalse(agent.rules.nudge("g1").allowed, "4 a day per goal")

        agent.isQuietHour = { it >= 23 || it < 7 }
        phone.now = at(agent.clock, "2026-11-19T23:30")
        val quiet = agent.rules.nudge("g3")
        assertFalse(quiet.allowed)
        assertEquals("quiet hours", quiet.why)
    }

    @Test
    fun plansHaveAtMostThreeConditions() {
        val agent = Phone().agent()
        assertTrue(agent.rules.planSize(3).allowed)
        assertFalse(agent.rules.planSize(4).allowed)
    }
}

package com.kbhatiya999.jarvis.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue


class AgentTest {
    @Test
    fun aTimedReminderIsSaidWhenDue() {
        val phone = Phone()
        val agent = phone.agent()
        val r = agent.timedReminders.add("call Mom", "2026-11-18T18:00")
        assertIs<TimedReminders.Result.Ok>(r)
        assertEquals(1, phone.alarms.size)

        phone.advanceTo(agent, at(agent.clock, "2026-11-18T17:59"))
        assertTrue(phone.spoken.isEmpty(), "not before it's due")

        phone.advanceTo(agent, at(agent.clock, "2026-11-18T18:00"))
        assertEquals(1, phone.spoken.size)
        assertTrue("call Mom" in phone.spoken[0].prompt)
        assertEquals(TimedReminders.Status.SAID, agent.timedReminders.items.value.single().status)
        assertTrue(agent.pending().isEmpty())
    }

    @Test
    fun theSameWakeUpIsHandledOnlyOnce() {
        val phone = Phone()
        val agent = phone.agent()
        agent.timedReminders.add("stretch", "2026-11-18T09:00")
        val due = at(agent.clock, "2026-11-18T09:00")
        val trigger = Trigger.Timer("reminder:x@$due", agent.pending().single().id, "", due)
        phone.now = due
        agent.onAlarm(trigger.id)
        agent.fire(trigger.copy(key = "${trigger.id}@$due")) // a duplicate alarm after a restart
        assertEquals(1, phone.spoken.size)
    }

    @Test
    fun whenJarvisCantSpeakTheReminderBecomesANotification() {
        val phone = Phone().apply { canSpeak = false }
        val agent = phone.agent()
        agent.timedReminders.add("take the medicine", "2026-11-18T08:00")
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T08:00"))
        assertEquals(listOf("Reminder" to "take the medicine"), phone.notifications)
    }

    @Test
    fun cancelledAndMovedRemindersFireOnlyAtTheirNewTime() {
        val phone = Phone()
        val agent = phone.agent()
        agent.timedReminders.add("call Mom", "2026-11-18T18:00")
        agent.timedReminders.add("buy milk", "2026-11-18T12:00")
        assertIs<TimedReminders.Result.Ok>(agent.timedReminders.cancel("milk"))
        assertIs<TimedReminders.Result.Ok>(agent.timedReminders.move("mom", "2026-11-18T19:30"))
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T18:30"))
        assertTrue(phone.spoken.isEmpty())
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T19:30"))
        assertEquals(1, phone.spoken.size)
        assertTrue("call Mom" in phone.spoken[0].prompt)
    }

    @Test
    fun afterARebootFutureTimersAreSetAgainAndLateOnesFireOrAreMissed() {
        val phone = Phone()
        val first = phone.agent()
        first.timedReminders.add("soon after boot", "2026-11-18T10:00")
        first.timedReminders.add("long before boot", "2026-11-18T06:00")
        first.timedReminders.add("tomorrow", "2026-11-19T10:00")
        phone.alarms.clear() // the reboot wipes Android's alarms

        phone.now = at(first.clock, "2026-11-18T10:10") // phone comes back 10 min after the first was due
        val restarted = phone.agent()
        restarted.rearm()
        assertEquals(1, phone.spoken.size, "10 min late still fires")
        assertTrue("soon after boot" in phone.spoken[0].prompt)
        assertEquals(setOf("tomorrow"), restarted.timedReminders.pending().map { it.text }.toSet())
        assertEquals(1, phone.alarms.size, "tomorrow's alarm is set again")
        val missed = restarted.timedReminders.items.value.single { it.text == "long before boot" }
        assertEquals(TimedReminders.Status.MISSED, missed.status)
    }

    @Test
    fun badTimesAndPastTimesAreRefusedWithAReason() {
        val phone = Phone()
        val agent = phone.agent()
        val bad = agent.timedReminders.add("x", "six pm")
        assertIs<TimedReminders.Result.Error>(bad)
        val past = agent.timedReminders.add("x", "2026-11-17T18:00")
        assertTrue((past as TimedReminders.Result.Error).message.contains("passed"))
        assertIs<TimedReminders.Result.Error>(agent.timedReminders.add("  ", "2026-11-18T18:00"))
        assertTrue(phone.alarms.isEmpty())
    }

    @Test
    fun utcClockReadsAndWritesLocalTimes() {
        val clock = UtcClock { 0 }
        assertEquals(0L, clock.parseLocal("1970-01-01T00:00"))
        val t = clock.parseLocal("2026-11-18T18:05")!!
        assertEquals("2026-11-18 18:05 UTC", clock.describe(t))
        assertEquals(null, clock.parseLocal("2026-13-01T00:00"))
    }
}

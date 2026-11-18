package com.kbhatiya999.jarvis.core.agent

import com.kbhatiya999.jarvis.core.MemStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoalsTest {
    @Test
    fun aGoalIsSaidAtItsDeadlineAndThenWaitsForTheOutcome() {
        val phone = Phone()
        val agent = phone.agent()
        assertIs<Goals.Result.Ok>(agent.goals.add("go to the gym", "2026-11-18T18:00", Importance.IMPORTANT))
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T18:00"))
        assertEquals(1, phone.spoken.size)
        assertTrue("go to the gym" in phone.spoken[0].prompt && "important" in phone.spoken[0].prompt)
        assertEquals(GoalStatus.DUE, agent.goals.items.value.single().status)
        assertTrue("outcome unknown" in agent.goals.describeActive())
    }

    @Test
    fun doneAndSkippedAreLoggedAsAttemptsWithTheReason() {
        val phone = Phone()
        val agent = phone.agent()
        agent.goals.add("go to the gym", "2026-11-18T18:00", Importance.NICE)
        agent.goals.add("finish the report", "2026-11-18T20:00", Importance.IMPORTANT)
        assertIs<Goals.Result.Ok>(agent.goals.skip("gym", "forgot my gym clothes"))
        assertIs<Goals.Result.Ok>(agent.goals.done("report"))
        val (skip, done) = agent.goals.attempts.value
        assertEquals(Attempt.Outcome.SKIPPED, skip.outcome)
        assertEquals("forgot my gym clothes", skip.reason)
        assertEquals(Attempt.Outcome.DONE, done.outcome)
        assertTrue(agent.goals.active().isEmpty())
        assertTrue(phone.alarms.isEmpty(), "finished goals don't fire")
    }

    @Test
    fun goalsAndAttemptsSurviveARestart() {
        val phone = Phone()
        phone.agent().goals.apply {
            add("go to the gym", "2026-11-18T18:00", Importance.NICE)
            add("call the bank", "2026-11-18T11:00", Importance.IMPORTANT)
            skip("bank", "it was closed")
        }
        val again = phone.agent()
        assertEquals(listOf("go to the gym"), again.goals.active().map { it.what })
        assertEquals("it was closed", again.goals.attempts.value.single().reason)
    }

    @Test
    fun movingAndCancelling() {
        val phone = Phone()
        val agent = phone.agent()
        agent.goals.add("go to the gym", "2026-11-18T18:00", Importance.NICE)
        assertIs<Goals.Result.Ok>(agent.goals.move("gym", "2026-11-18T19:00"))
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T18:30"))
        assertTrue(phone.spoken.isEmpty())
        assertIs<Goals.Result.Ok>(agent.goals.cancel("gym"))
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T19:30"))
        assertTrue(phone.spoken.isEmpty())
        assertIs<Goals.Result.Error>(agent.goals.done("gym"), "a cancelled goal is no longer open")
    }

    @Test
    fun profileKeepsAtMostTwentyNotesAndNoDuplicates() {
        val phone = Phone()
        val p = phone.agent().profile
        assertTrue(p.add("tired after 5 pm", "gym goal, Nov 18"))
        assertFalse(p.add("Tired after 5 PM", "again"))
        repeat(25) { p.add("note $it", "test") }
        assertEquals(Profile.MAX, p.notes.value.size)
        assertEquals("note 24", p.notes.value.last().text)
        assertTrue(p.remove("note 24"))
    }

    @Test
    fun recordStoreUpgradesOldFormatsAndSurvivesGarbage() {
        val store = MemStore()
        // Version 1 called it "title"; version 2 calls it "what".
        store.set("k", """{"v":1,"items":[{"title":"tired after 5 pm","source":"s","at":1}]}""")
        val v2 = RecordStore(store, "k", Note.serializer(), version = 2, upgrades = mapOf(1 to { items ->
            kotlinx.serialization.json.JsonArray(items.map { e ->
                val o = e as kotlinx.serialization.json.JsonObject
                kotlinx.serialization.json.JsonObject(o - "title" + ("text" to o["title"]!!))
            })
        }))
        assertEquals("tired after 5 pm", v2.load().single().text)
        store.set("k", "not json")
        assertTrue(v2.load().isEmpty())
    }

    @Test
    fun withTwoGoalsOfTheSameNameTheOneJustDueIsTheOneAnswered() {
        val phone = Phone()
        val agent = phone.agent()
        phone.now = at(agent.clock, "2026-11-18T20:00")
        val old = (agent.goals.add("take a short walk", "2026-11-18T20:40", Importance.NICE) as Goals.Result.Ok).goal
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T20:40")) // due, never answered
        val new = (agent.goals.add("take a short walk", "2026-11-18T21:40", Importance.NICE) as Goals.Result.Ok).goal
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T21:40"))
        val skipped = agent.goals.skip("take a short walk", "it's raining") as Goals.Result.Ok
        assertEquals(new.id, skipped.goal.id, "the goal just announced, not the old one")
        assertEquals(listOf(old.id), agent.goals.active().map { it.id })
    }

    @Test
    fun cantIsNotAReasonSoJarvisIsToldToAskWhy() {
        val phone = Phone()
        val agent = phone.agent()
        agent.goals.add("take a short walk", "2026-11-18T18:00", Importance.NICE)
        for (notWhy in listOf("I cannot go out today.", "I can't", "no", "not today", "I won't go out today", "")) {
            val r = agent.goals.skip("walk", notWhy)
            assertTrue(r is Goals.Result.Error && "what got in the way" in r.message, "\"$notWhy\" isn't a reason")
        }
        assertTrue(agent.goals.attempts.value.isEmpty())
        assertIs<Goals.Result.Ok>(agent.goals.skip("walk", "my knee hurts"))
        assertEquals("my knee hurts", agent.goals.attempts.value.single().reason)
    }

    @Test
    fun theUserMayDeclineToSayWhy() {
        val phone = Phone()
        val agent = phone.agent()
        agent.goals.add("take a short walk", "2026-11-18T18:00", Importance.NICE)
        assertIs<Goals.Result.Ok>(agent.goals.skip("walk", "didn't say"))
        assertEquals(null, agent.goals.attempts.value.single().reason)
    }

    @Test
    fun aDueGoalNobodyAnsweredClosesAfterSixHours() {
        val phone = Phone()
        val agent = phone.agent()
        agent.goals.add("take a short walk", "2026-11-18T18:00", Importance.NICE)
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T18:00"))
        phone.now = at(agent.clock, "2026-11-18T23:00")
        assertEquals(1, agent.goals.active().size, "5 hours: still waiting")
        phone.now = at(agent.clock, "2026-11-19T00:30")
        assertTrue(agent.goals.active().isEmpty())
        assertEquals(Attempt.Outcome.NO_ANSWER, agent.goals.attempts.value.single().outcome)
    }
}

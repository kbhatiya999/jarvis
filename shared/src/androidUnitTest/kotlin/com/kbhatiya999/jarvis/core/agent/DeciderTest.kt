package com.kbhatiya999.jarvis.core.agent

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeciderTest {
    private fun goalAt(phone: Phone, agent: Agent, time: String = "2026-11-18T18:00"): Goal {
        phone.now = at(agent.clock, "2026-11-18T09:00")
        return (agent.goals.add("go to the gym", time, Importance.NICE) as Goals.Result.Ok).goal
    }

    @Test
    fun aFirstGoalGetsAtMostOneConditionAndBadTimesAreDropped() = runBlocking {
        val phone = Phone()
        val agent = phone.agent()
        val g = goalAt(phone, agent)
        phone.answer = { job, _ ->
            assertEquals(DecisionJob.PLAN, job)
            Decision(conditions = listOf(
                DraftCondition("bag packed", "12:00", "forgets clothes"),
                DraftCondition("ate something", "16:30"),
            ), question = "Anything that usually gets in the way?", why = "test")
        }
        val plan = assertNotNull(agent.goals.draftPlan(g.id))
        assertEquals(listOf("bag packed"), plan.goal.conditions.map { it.what }, "first time: one condition")
        assertEquals("Anything that usually gets in the way?", plan.question)
        assertTrue("first time" in phone.asked.single().second)
        assertEquals(2, phone.alarms.size, "deadline + one checkpoint")

        phone.answer = { _, _ -> Decision(conditions = listOf(DraftCondition("too late", "19:00"), DraftCondition("past", "08:00")), why = "t") }
        val g2 = (agent.goals.add("go for a run", "2026-11-18T18:00", Importance.NICE) as Goals.Result.Ok).goal
        assertTrue(agent.goals.draftPlan(g2.id)!!.goal.conditions.isEmpty(), "checks after the deadline or in the past are dropped")
    }

    @Test
    fun pastHurdlesAllowMoreConditionsAndReachTheModel() = runBlocking {
        val phone = Phone()
        val agent = phone.agent()
        phone.now = at(agent.clock, "2026-11-17T09:00")
        agent.goals.add("go to the gym", "2026-11-17T18:00", Importance.NICE)
        agent.goals.skip("gym", "forgot my gym clothes")
        agent.goals.add("gym session", "2026-11-17T19:00", Importance.NICE)
        agent.goals.skip("gym session", "too tired after work")
        val g = goalAt(phone, agent)
        phone.answer = { _, _ -> Decision(conditions = (1..5).map { DraftCondition("c$it", "1$it:00") }, why = "t") }
        val plan = agent.goals.draftPlan(g.id)!!
        assertEquals(3, plan.goal.conditions.size, "2 hurdles → up to 3")
        val context = phone.asked.last().second
        assertTrue("forgot my gym clothes" in context && "too tired after work" in context && "at most 3" in context)
    }

    @Test
    fun aCheckpointSpeaksOnlyWhenTheModelSaysSoAndWithinTheRules() = runBlocking {
        val phone = Phone()
        val agent = phone.agent()
        val g = goalAt(phone, agent)
        phone.answer = { _, _ -> Decision(conditions = listOf(DraftCondition("bag packed", "12:00")), why = "t") }
        agent.goals.draftPlan(g.id)

        phone.answer = { job, _ -> assertEquals(DecisionJob.CHECKPOINT, job); Decision(speak = true, text = "Sir, is your gym bag packed?", why = "unknown") }
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T12:00"))
        assertEquals(1, phone.spoken.size)
        assertTrue("Sir, is your gym bag packed?" in phone.spoken[0].prompt && "answer_condition" in phone.spoken[0].prompt)
    }

    @Test
    fun aConfirmedConditionOrAQuietModelOrAFailingModelMeansSilence() = runBlocking {
        val phone = Phone()
        val agent = phone.agent()
        val g = goalAt(phone, agent)
        phone.answer = { _, _ -> Decision(conditions = listOf(DraftCondition("bag packed", "12:00")), why = "t") }
        agent.goals.draftPlan(g.id)
        assertIs<Goals.Result.Ok>(agent.goals.answer("gym", "bag", ok = true))
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T12:00"))
        assertTrue(phone.spoken.isEmpty(), "already confirmed: no model call, no speech")
        assertEquals(1, phone.asked.size)

        val g2 = (agent.goals.add("call the bank", "2026-11-18T17:00", Importance.IMPORTANT) as Goals.Result.Ok).goal
        phone.answer = { _, _ -> Decision(conditions = listOf(DraftCondition("number ready", "13:00")), why = "t") }
        agent.goals.draftPlan(g2.id)
        phone.answer = { _, _ -> null } // the model fails
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T13:00"))
        assertTrue(phone.spoken.isEmpty() && phone.notifications.isEmpty(), "a failing model means silence, not a crash")
    }

    @Test
    fun theGuardKeepsTheBudgetAndPausesAfterRepeatedFailures() = runBlocking {
        val phone = Phone()
        val agent = phone.agent()
        phone.now = at(agent.clock, "2026-11-18T09:00")
        phone.answer = { _, _ -> null }
        repeat(GuardedDecider.TRIP_AFTER) { assertEquals("model unavailable", agent.decide(DecisionJob.CHECKPOINT, "x").why) }
        val paused = agent.decide(DecisionJob.CHECKPOINT, "x")
        assertTrue("paused" in paused.why)
        assertFalse(paused.speak)
        assertEquals(GuardedDecider.TRIP_AFTER, phone.asked.size, "no calls while paused")

        phone.now += GuardedDecider.COOL_OFF_MS + 1
        phone.answer = { _, _ -> Decision(speak = true, text = "hi", why = "ok") }
        assertTrue(agent.decide(DecisionJob.CHECKPOINT, "x").speak, "tries again after the pause")

        repeat(Rules.MAX_THINKS_PER_DAY) { agent.rules.think() }
        val over = agent.decide(DecisionJob.CHECKPOINT, "x")
        assertTrue("limit" in over.why)
        assertNull(over.text)
    }

    @Test
    fun decisionsAreShownToTheUserWithTheirReasons() = runBlocking {
        val phone = Phone()
        val agent = phone.agent()
        val shown = mutableListOf<String>()
        agent.onDecision = { shown += it }
        val g = goalAt(phone, agent)
        phone.answer = { _, _ -> Decision(conditions = listOf(DraftCondition("bag packed", "12:00")), why = "you forget it") }
        agent.goals.draftPlan(g.id)
        assertTrue(shown.last().startsWith("Plan for \"go to the gym\": I'll check \"bag packed\"") && "you forget it" in shown.last(), shown.last())

        phone.answer = { _, _ -> Decision(speak = false, why = "you said you packed it this morning") }
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T12:00"))
        assertTrue(shown.last().startsWith("Stayed quiet:") && "packed it this morning" in shown.last(), shown.last())

        phone.advanceTo(agent, at(agent.clock, "2026-11-18T18:00"))
        assertTrue(shown.last().startsWith("Spoke up (goal)"), shown.last())
    }
}

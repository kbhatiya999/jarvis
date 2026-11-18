package com.kbhatiya999.jarvis.core.agent

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HurdlesTest {
    private fun skippedGym(phone: Phone, agent: Agent): Goal {
        phone.now = at(agent.clock, "2026-11-17T09:00")
        val g = (agent.goals.add("go to the gym", "2026-11-17T18:00", Importance.NICE) as Goals.Result.Ok).goal
        return g
    }

    @Test
    fun aReasonLeadsToOneSuggestionAndMaybeANote() = runBlocking {
        val phone = Phone()
        val agent = phone.agent()
        val shown = mutableListOf<String>()
        agent.onDecision = { shown += it }
        val g = skippedGym(phone, agent)
        phone.answer = { job, context ->
            assertEquals(DecisionJob.REFLECT, job)
            assertTrue("forgot my gym clothes" in context)
            Decision(conditions = listOf(DraftCondition("gym bag packed and by the door", "360", "forgot clothes")),
                note = "Often forgets things when leaving in a rush", why = "packing ahead removes the hurdle")
        }
        val h = assertNotNull(agent.hurdles.reflect(g, "forgot my gym clothes"))
        assertEquals("gym bag packed and by the door", h.suggestion)
        assertEquals(360, h.minutesBefore)
        assertEquals(Hurdle.Status.PROPOSED, h.status)
        assertEquals("Often forgets things when leaving in a rush", agent.profile.notes.value.single().text)
        assertTrue(shown.any { it.startsWith("Suggested for next time") && "6 h before" in it })
        assertTrue(shown.any { it.startsWith("Noted about you") })
    }

    @Test
    fun oneOffReasonsOrBadAnswersGiveNoSuggestion() = runBlocking {
        val phone = Phone()
        val agent = phone.agent()
        val g = skippedGym(phone, agent)
        phone.answer = { _, _ -> Decision(why = "a one-off emergency") }
        assertNull(agent.hurdles.reflect(g, "my kid was ill"))
        phone.answer = { _, _ -> Decision(conditions = listOf(DraftCondition("x", "noon")), why = "t") }
        assertNull(agent.hurdles.reflect(g, "something"))
        assertTrue(agent.hurdles.items.value.isEmpty())
    }

    @Test
    fun aReplyOnTheNotificationLeadsToAYesNoSuggestionAndYesKeepsIt() {
        val phone = Phone()
        val agent = phone.agent()
        val g = skippedGym(phone, agent)
        phone.advanceTo(agent, at(agent.clock, "2026-11-17T18:00"))
        val outcome = agent.questions.open().single()
        agent.questions.answerFromNotification(outcome.id, Answer.NO)
        val reason = agent.questions.open().single()
        phone.answer = { _, _ -> Decision(conditions = listOf(DraftCondition("gym bag packed", "360")), why = "t") }
        agent.questions.answerFromNotification(reason.id, Answer.TEXT, "forgot my gym clothes")

        val suggestion = agent.questions.open().single()
        assertEquals(OpenQuestion.Kind.SUGGESTION, suggestion.kind)
        assertTrue("gym bag packed" in suggestion.text && "6 h before" in suggestion.text)
        assertEquals(Delivery.NOTIFIED, phone.asks.last().how)

        agent.questions.answerFromNotification(suggestion.id, Answer.YES)
        assertEquals(Hurdle.Status.ACCEPTED, agent.hurdles.items.value.single().status)
        assertTrue(agent.questions.open().isEmpty())
        assertEquals("forgot my gym clothes", agent.goals.attempts.value.single { it.goalId == g.id }.reason)
    }

    @Test
    fun agreedChecksGoIntoTheNextPlanForASimilarGoal() = runBlocking {
        val phone = Phone()
        val agent = phone.agent()
        val g = skippedGym(phone, agent)
        agent.goals.skip("gym", "forgot my gym clothes")
        phone.answer = { _, _ -> Decision(conditions = listOf(DraftCondition("gym bag packed", "360")), why = "t") }
        agent.hurdles.reflect(g, "forgot my gym clothes")
        agent.hurdles.accept()

        phone.now = at(agent.clock, "2026-11-18T09:00")
        val next = (agent.goals.add("gym session", "2026-11-18T18:00", Importance.NICE) as Goals.Result.Ok).goal
        phone.answer = { job, context ->
            assertEquals(DecisionJob.PLAN, job)
            assertTrue("Already planned" in context && "gym bag packed" in context, context)
            Decision(conditions = listOf(DraftCondition("leave work by 17:15", "17:00")), why = "t")
        }
        val plan = agent.goals.draftPlan(next.id)!!
        assertEquals(listOf("gym bag packed", "leave work by 17:15"), plan.goal.conditions.map { it.what })
        assertEquals(at(agent.clock, "2026-11-18T12:00"), plan.goal.conditions.first().checkAt, "6 h before 18:00")
    }

    @Test
    fun declinedSuggestionsAreNotUsedAndUnrelatedGoalsDontGetThem() = runBlocking {
        val phone = Phone()
        val agent = phone.agent()
        val g = skippedGym(phone, agent)
        phone.answer = { _, _ -> Decision(conditions = listOf(DraftCondition("gym bag packed", "360")), why = "t") }
        agent.hurdles.reflect(g, "forgot my gym clothes")
        agent.hurdles.decline()
        assertTrue(agent.hurdles.acceptedFor("go to the gym").isEmpty())

        agent.hurdles.reflect(g, "forgot my gym clothes")
        agent.hurdles.accept()
        assertTrue(agent.hurdles.acceptedFor("call the bank").isEmpty(), "only goals that share a word")
        assertEquals(1, agent.hurdles.acceptedFor("gym tomorrow").size)
    }
}

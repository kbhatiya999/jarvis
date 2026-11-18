package com.kbhatiya999.jarvis.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuestionsTest {
    /** A goal at 18:00 whose deadline has passed unanswered: "did you?" is now open. */
    private fun dueGoal(phone: Phone, importance: Importance = Importance.NICE): Pair<Agent, Goal> {
        val agent = phone.agent()
        phone.now = at(agent.clock, "2026-11-18T09:00")
        val g = (agent.goals.add("go to the gym", "2026-11-18T18:00", importance) as Goals.Result.Ok).goal
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T18:00"))
        return agent to g
    }

    @Test
    fun afterAnUnansweredDeadlineJarvisAsksDidYou30MinutesLaterThenRetries() {
        val phone = Phone()
        val (agent, _) = dueGoal(phone)
        val q = agent.questions.open().single()
        assertEquals(OpenQuestion.Kind.OUTCOME, q.kind)
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T18:29"))
        assertTrue(phone.asks.isEmpty())
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T18:30"))
        assertEquals(Delivery.NOTIFIED, phone.asks.single().how, "nice to have: a notification, not a voice")
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T19:00"))
        assertEquals(2, phone.asks.size, "second try 30 min later")
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T22:00"))
        assertEquals(2, phone.asks.size, "a nice-to-have goal gets 2 tries")
    }

    @Test
    fun anImportantGoalIsAskedOutLoudAndGetsThreeTries() {
        val phone = Phone()
        val (agent, _) = dueGoal(phone, Importance.IMPORTANT)
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T18:30"))
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T19:00"))
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T20:00"))
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T23:00"))
        assertEquals(listOf(Delivery.SPOKE, Delivery.SPOKE, Delivery.SPOKE), phone.asks.map { it.how })
    }

    @Test
    fun answeringByVoiceClosesTheQuestionAndItsNotification() {
        val phone = Phone()
        val (agent, _) = dueGoal(phone)
        val q = agent.questions.open().single()
        agent.goals.done("gym")
        assertTrue(agent.questions.open().isEmpty())
        assertTrue(q.id in phone.dismissed)
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T19:00"))
        assertTrue(phone.asks.isEmpty(), "nothing left to ask")
    }

    @Test
    fun noOnTheNotificationAsksWhyAndTheReplyIsRecorded() {
        val phone = Phone()
        val (agent, g) = dueGoal(phone)
        val outcome = agent.questions.open().single()
        agent.questions.answerFromNotification(outcome.id, Answer.NO)
        val reason = agent.questions.open().single()
        assertEquals(OpenQuestion.Kind.REASON, reason.kind)
        assertEquals(OpenQuestion.Kind.REASON, phone.asks.last().question.kind, "asked right away, with a reply box")
        agent.questions.answerFromNotification(reason.id, Answer.TEXT, "work ran late")
        assertEquals("work ran late", agent.goals.attempts.value.single { it.goalId == g.id }.reason)
        assertTrue(agent.questions.open().isEmpty())
    }

    @Test
    fun yesOnTheNotificationMarksItDoneAndLaterAsksAgainInAnHour() {
        val phone = Phone()
        val (agent, g) = dueGoal(phone)
        val q = agent.questions.open().single()
        agent.questions.answerFromNotification(q.id, Answer.LATER)
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T18:30"))
        assertTrue(phone.asks.isEmpty(), "later moved the try")
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T19:00"))
        assertEquals(1, phone.asks.size)
        agent.questions.answerFromNotification(q.id, Answer.YES)
        assertEquals(GoalStatus.DONE, agent.goals.items.value.single { it.id == g.id }.status)
    }

    @Test
    fun unlockIsAGoodMomentAndAfterLongIdleItsSpoken() {
        val quick = Phone()
        val (a1, _) = dueGoal(quick)
        quick.now = at(a1.clock, "2026-11-18T18:05")
        assertTrue(a1.moment(Moment.UNLOCK, idleMs = 10 * 60_000))
        assertEquals(Delivery.NOTIFIED, quick.asks.last().how, "a quick unlock: a notification")

        val rested = Phone()
        val (a2, _) = dueGoal(rested)
        rested.now = at(a2.clock, "2026-11-18T18:05")
        assertTrue(a2.moment(Moment.UNLOCK, idleMs = 3 * 60 * 60_000))
        assertEquals(Delivery.SPOKE, rested.asks.last().how, "unlocked after a long break: asked out loud")
    }

    @Test
    fun aConversationGoingIsUsedFirst() {
        val phone = Phone().apply { inConversation = true }
        val (agent, _) = dueGoal(phone)
        phone.now = at(agent.clock, "2026-11-18T18:05")
        assertTrue(agent.moment(Moment.APP_OPEN))
        assertEquals(Delivery.JOINED_CONVERSATION, phone.asks.last().how)
    }

    @Test
    fun theRulesSpacingAppliesToMomentsToo() {
        val phone = Phone()
        val (agent, _) = dueGoal(phone)
        phone.now = at(agent.clock, "2026-11-18T18:05")
        assertTrue(agent.moment(Moment.UNLOCK))
        phone.now += 10 * 60_000
        assertTrue(!agent.moment(Moment.APP_OPEN), "asked 10 min ago: wait")
    }

    @Test
    fun questionsExpireAndStopAskingStopsThem() {
        val phone = Phone()
        val (agent, _) = dueGoal(phone)
        phone.now = at(agent.clock, "2026-11-19T00:05") // 6 h after the deadline
        assertTrue(agent.questions.open().isEmpty(), "too late to help")

        val p2 = Phone()
        val (a2, _) = dueGoal(p2)
        a2.goals.stopAsking("gym")
        assertTrue(a2.questions.open().isEmpty())
        assertEquals(1, a2.goals.active().size, "the goal stays set")
    }
}

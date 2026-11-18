package com.kbhatiya999.jarvis.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesTest {
    @Test
    fun daysAndTheClock() {
        val clock = UtcClock { 0 }
        assertEquals(3, clock.dayOfWeek(clock.parseLocal("2026-11-18T12:00")!!), "18 Nov 2026 is a Wednesday")
        assertEquals(7, clock.dayOfWeek(clock.parseLocal("2026-11-22T23:59")!!))
        assertEquals(listOf(1, 3, 5), Goals.parseDays("mon, wed and fri"))
        assertEquals((1..5).toList(), Goals.parseDays("weekdays"))
        assertEquals((1..7).toList(), Goals.parseDays("every day"))
        assertEquals("on Mon, Wed, Fri", Goals.describeDays(listOf(5, 1, 3)))
        assertEquals("on weekends", Goals.describeDays(listOf(6, 7)))
    }

    @Test
    fun aRepeatingGoalGetsItsNextDayWhenOneFinishesWithAFreshPlan() {
        val phone = Phone()
        val agent = phone.agent()
        phone.now = at(agent.clock, "2026-11-18T09:00") // Wednesday
        agent.goals.add("go to the gym", "2026-11-18T18:00", Importance.NICE)
        assertIs<Goals.Result.Ok>(agent.goals.repeat("gym", listOf(1, 3, 5), "18:00"))
        assertEquals(1, agent.goals.active().size, "today's goal is the first one")

        phone.now = at(agent.clock, "2026-11-18T18:30")
        agent.goals.done("gym")
        val next = agent.goals.active().single()
        assertEquals(at(agent.clock, "2026-11-20T18:00"), next.deadline, "next: Friday 18:00")
        assertTrue(phone.asked.any { it.first == DecisionJob.PLAN }, "each occurrence gets its own plan")
    }

    @Test
    fun anUnansweredOccurrenceStillLeadsToTheNextOne() {
        val phone = Phone()
        val agent = phone.agent()
        phone.now = at(agent.clock, "2026-11-18T09:00")
        agent.goals.repeat("evening walk", (1..7).toList(), "20:00")
        phone.advanceTo(agent, at(agent.clock, "2026-11-18T20:00"))
        phone.now = at(agent.clock, "2026-11-19T03:00") // 7 h later: closed as no answer
        val next = agent.goals.active().single()
        assertEquals(at(agent.clock, "2026-11-19T20:00"), next.deadline)
    }

    @Test
    fun stoppingCancelsWhatsComing() {
        val phone = Phone()
        val agent = phone.agent()
        phone.now = at(agent.clock, "2026-11-18T09:00")
        agent.goals.repeat("evening walk", (1..7).toList(), "20:00")
        assertIs<Goals.Result.Ok>(agent.goals.stopRepeating("walk"))
        assertTrue(agent.goals.active().isEmpty())
        assertEquals("none", agent.goals.describeSeries())
    }

    @Test
    fun repeatIsOfferedOnlyAfterThreeSuccessesAndAtMostWeekly() {
        val phone = Phone()
        val agent = phone.agent()
        fun winOn(date: String): Goal {
            phone.now = at(agent.clock, "${date}T09:00")
            val g = (agent.goals.add("go to the gym", "${date}T18:00", Importance.NICE) as Goals.Result.Ok).goal
            phone.now = at(agent.clock, "${date}T18:30")
            agent.goals.done("gym")
            return g
        }
        assertNull(agent.goals.repeatOffer(winOn("2026-11-16"))) // Mon
        assertNull(agent.goals.repeatOffer(winOn("2026-11-18"))) // Wed
        val offer = assertNotNull(agent.goals.repeatOffer(winOn("2026-11-20"))) // Fri
        assertEquals(listOf(1, 3, 5), offer.days)
        assertEquals("18:00", offer.time)
        assertEquals(3, offer.successes)
        assertNull(agent.goals.repeatOffer(winOn("2026-11-22")), "one offer a week")
    }

    @Test
    fun yesOnTheRepeatNotificationSetsItUp() {
        val phone = Phone()
        val agent = phone.agent()
        listOf("2026-11-16", "2026-11-18").forEach { d ->
            phone.now = at(agent.clock, "${d}T09:00")
            agent.goals.add("go to the gym", "${d}T18:00", Importance.NICE)
            phone.now = at(agent.clock, "${d}T18:30")
            agent.goals.done("gym")
        }
        phone.now = at(agent.clock, "2026-11-20T09:00")
        agent.goals.add("go to the gym", "2026-11-20T18:00", Importance.NICE)
        phone.advanceTo(agent, at(agent.clock, "2026-11-20T18:00"))
        phone.advanceTo(agent, at(agent.clock, "2026-11-20T18:30")) // "did you?" notification
        val did = agent.questions.open().single { it.kind == OpenQuestion.Kind.OUTCOME }
        agent.questions.answerFromNotification(did.id, Answer.YES)
        val offer = agent.questions.open().single()
        assertEquals(OpenQuestion.Kind.REPEAT, offer.kind)
        agent.questions.answerFromNotification(offer.id, Answer.YES)
        assertTrue("on Mon, Wed, Fri at 18:00" in agent.goals.describeSeries(), agent.goals.describeSeries())
        assertEquals(at(agent.clock, "2026-11-23T18:00"), agent.goals.active().single().deadline, "next Monday")
    }
}

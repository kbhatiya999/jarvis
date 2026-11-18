package com.kbhatiya999.jarvis.core.agent

import com.kbhatiya999.jarvis.core.KeyValueStore
import kotlinx.serialization.Serializable

/**
 * What the agent may do, decided by plain code, never by the model: how often it may think (a model call), how
 * often it may speak up about one goal, quiet hours, spacing, and how big a plan may get. Each answer carries its
 * reason for the journal. Things the user asked for at a set time (a timed reminder, a goal's deadline) don't go
 * through these limits; Jarvis's own nudges and checks do.
 */
class Rules internal constructor(
    private val clockOf: () -> AgentClock,
    private val store: KeyValueStore,
    /** Active mode's quiet hours (23:00–07:00 when on). */
    private val isQuietHour: (hour: Int) -> Boolean,
) {
    data class Verdict(val allowed: Boolean, val why: String)

    private val clock get() = clockOf()

    /** May Jarvis make one more silent model call today? Counts it when allowed. */
    fun think(): Verdict {
        val d = today()
        if (d.thinks >= MAX_THINKS_PER_DAY) return Verdict(false, "daily limit of $MAX_THINKS_PER_DAY decisions reached")
        save(d.copy(thinks = d.thinks + 1))
        return Verdict(true, "decision ${d.thinks + 1} of $MAX_THINKS_PER_DAY today")
    }

    /** May Jarvis speak up about [goalId] now (a nudge or check it decided on)? Counts it when allowed, unless [count] is false. */
    fun nudge(goalId: String, count: Boolean = true): Verdict {
        val now = clock.now()
        if (isQuietHour(clock.hour(now))) return Verdict(false, "quiet hours")
        val d = today()
        val times = d.nudges[goalId].orEmpty()
        if (times.size >= MAX_NUDGES_PER_GOAL_PER_DAY) return Verdict(false, "already spoke up $MAX_NUDGES_PER_GOAL_PER_DAY times about it today")
        val last = times.maxOrNull()
        if (last != null && now - last < NUDGE_SPACING_MS) {
            return Verdict(false, "spoke up about it ${(now - last) / 60_000} min ago (needs ${NUDGE_SPACING_MS / 60_000})")
        }
        if (count) save(d.copy(nudges = d.nudges + (goalId to times + now)))
        return Verdict(true, "nudge ${times.size + 1} of $MAX_NUDGES_PER_GOAL_PER_DAY today for this goal")
    }

    /** May a plan grow to [count] conditions? */
    fun planSize(count: Int) =
        if (count <= MAX_CONDITIONS) Verdict(true, "$count of $MAX_CONDITIONS conditions")
        else Verdict(false, "a plan has at most $MAX_CONDITIONS conditions")

    // The counters, reset each local day.
    @Serializable
    private data class Day(val date: String, val thinks: Int = 0, val nudges: Map<String, List<Long>> = emptyMap())

    private fun today(): Day {
        val date = clock.localDate(clock.now())
        val saved = try {
            store.get(KEY)?.let { RecordStore.json.decodeFromString(Day.serializer(), it) }
        } catch (e: Exception) {
            null
        }
        return if (saved?.date == date) saved else Day(date)
    }

    private fun save(d: Day) = store.set(KEY, RecordStore.json.encodeToString(Day.serializer(), d))

    companion object {
        private const val KEY = "day"
        const val MAX_THINKS_PER_DAY = 30
        const val MAX_NUDGES_PER_GOAL_PER_DAY = 4
        const val NUDGE_SPACING_MS = 30 * 60_000L
        const val MAX_CONDITIONS = 3
        const val MAX_QUESTIONS_PER_CHECK = 1
    }
}

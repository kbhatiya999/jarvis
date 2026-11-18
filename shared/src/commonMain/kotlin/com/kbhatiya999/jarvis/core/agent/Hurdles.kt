package com.kbhatiya999.jarvis.core.agent

import com.kbhatiya999.jarvis.core.KeyValueStore
import com.kbhatiya999.jarvis.core.Watchable
import kotlinx.serialization.Serializable

/**
 * What got in the way of a goal, and what Jarvis will do about it next time. After a skip with a reason, Jarvis
 * reflects (one model call) and suggests one check for next time; only the user's yes keeps it. Accepted ones go into
 * every new plan for a similar goal, without depending on the model.
 */
@Serializable
data class Hurdle(
    val id: String,
    /** The goal it came from, and its meaningful words ("gym"), to find similar goals later. */
    val goalWhat: String,
    val goalWords: List<String>,
    /** What got in the way, in the user's words. */
    val reason: String,
    /** The check for next time ("gym bag packed"). */
    val suggestion: String,
    /** When to check it: this many minutes before the deadline. */
    val minutesBefore: Int,
    val status: Status = Status.PROPOSED,
    val createdAt: Long,
) {
    @Serializable
    enum class Status { PROPOSED, ACCEPTED, DECLINED }
}

class Hurdles internal constructor(private val agent: Agent, store: KeyValueStore) {
    private val records = RecordStore(store, "hurdles", Hurdle.serializer(), version = 1)
    val items = Watchable(records.load())

    /**
     * Thinks about why [goal] didn't happen ([reason]) and proposes one check for next time, plus maybe a general
     * note for the profile. Null when the model has nothing useful (or isn't available).
     */
    suspend fun reflect(goal: Goal, reason: String): Hurdle? {
        val d = agent.decide(DecisionJob.REFLECT, reflectContext(goal, reason))
        d.note?.trim()?.takeIf { it.isNotEmpty() }?.let { note ->
            if (agent.profile.add(note, "from \"${goal.what}\", ${agent.clock.describe(agent.now)}")) {
                agent.decision("Noted about you: \"$note\" (you can see or delete it under goals). Why: ${d.why}")
            }
        }
        val c = d.conditions.firstOrNull() ?: return null.also { agent.journal("reflect on ${goal.id}: no suggestion (${d.why})") }
        val minutes = c.checkAt.trim().removeSuffix("m").toIntOrNull()?.coerceIn(MIN_BEFORE, MAX_BEFORE)
            ?: return null.also { agent.journal("reflect on ${goal.id}: unreadable time \"${c.checkAt}\"") }
        if (c.what.isBlank()) return null
        val h = Hurdle(
            id = "h" + agent.now.toString(36) + (0..2).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString(""),
            goalWhat = goal.what, goalWords = Goals.significant(goal.what).toList(), reason = reason,
            suggestion = c.what.trim().take(200), minutesBefore = minutes, createdAt = agent.now,
        )
        // One open suggestion per goal kind: a newer one replaces an unanswered older one.
        save(items.value.filterNot { it.status == Hurdle.Status.PROPOSED && it.goalWords.any { w -> w in h.goalWords } } + h)
        agent.decision("Suggested for next time: check \"${h.suggestion}\" ${describeBefore(minutes)} (because: \"$reason\"). " +
            "I'll only do it if you say yes. Why: ${d.why}")
        return h
    }

    /** The newest suggestion still waiting for an answer. */
    fun latestProposed(): Hurdle? = items.value.filter { it.status == Hurdle.Status.PROPOSED }.maxByOrNull { it.createdAt }

    fun accept(id: String? = null): Hurdle? = answer(id, Hurdle.Status.ACCEPTED)

    fun decline(id: String? = null): Hurdle? = answer(id, Hurdle.Status.DECLINED)

    /** "Remove" on the goals screen: Jarvis stops using this check. */
    fun remove(id: String) {
        val h = items.value.firstOrNull { it.id == id } ?: return
        save(items.value.map { if (it.id == id) it.copy(status = Hurdle.Status.DECLINED) else it })
        agent.questions.answeredHurdle(h.id)
        agent.decision("Removed the check \"${h.suggestion}\"; I won't use it in plans any more.")
    }

    /** Accepted checks for goals like [what] (sharing a meaningful word), newest first. */
    fun acceptedFor(what: String): List<Hurdle> {
        val words = Goals.significant(what)
        return items.value.filter { it.status == Hurdle.Status.ACCEPTED && it.goalWords.any { w -> w in words } }.sortedByDescending { it.createdAt }
    }

    fun describeBefore(minutes: Int) = when {
        minutes % 60 == 0 -> "${minutes / 60} h before"
        minutes > 60 -> "${minutes / 60} h ${minutes % 60} min before"
        else -> "$minutes min before"
    }

    private fun answer(id: String?, status: Hurdle.Status): Hurdle? {
        val h = (if (id != null) items.value.firstOrNull { it.id == id } else latestProposed()) ?: return null
        if (h.status != Hurdle.Status.PROPOSED) return h
        val answered = h.copy(status = status)
        save(items.value.map { if (it.id == h.id) answered else it })
        agent.questions.answeredHurdle(h.id)
        agent.decision(
            if (status == Hurdle.Status.ACCEPTED) "You agreed: next time I'll check \"${h.suggestion}\" ${describeBefore(h.minutesBefore)} for goals like \"${h.goalWhat}\"."
            else "You said no to checking \"${h.suggestion}\"; I won't."
        )
        return answered
    }

    private fun reflectContext(g: Goal, reason: String) = buildString {
        appendLine("Goal: \"${g.what}\" (it was due ${agent.clock.describe(g.deadline)})")
        appendLine("What got in the way, in the user's words: \"$reason\"")
        appendLine("Earlier attempts at similar goals: " + agent.goals.attempts.value
            .filter { a -> agent.goals.items.value.firstOrNull { it.id == a.goalId }?.let { Goals.significant(it.what).any { w -> w in Goals.significant(g.what) } } == true }
            .takeLast(6).joinToString("; ") { "${it.outcome.name.lowercase()}${it.reason?.let { r -> " (\"$r\")" } ?: ""}" }.ifEmpty { "none" })
        appendLine("Checks the user already agreed to for goals like this: " + acceptedFor(g.what).joinToString("; ") { "\"${it.suggestion}\"" }.ifEmpty { "none" })
        appendLine("About the user: " + agent.profile.notes.value.joinToString("; ") { it.text }.ifEmpty { "nothing yet" })
    }

    private fun save(list: List<Hurdle>) {
        val kept = list.sortedBy { it.createdAt }.takeLast(MAX)
        items.value = kept
        records.save(kept)
    }

    companion object {
        private const val MAX = 100
        const val MIN_BEFORE = 10
        const val MAX_BEFORE = 24 * 60
    }
}

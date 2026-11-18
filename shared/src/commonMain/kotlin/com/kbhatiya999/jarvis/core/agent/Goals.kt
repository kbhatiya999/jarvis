package com.kbhatiya999.jarvis.core.agent

import com.kbhatiya999.jarvis.core.KeyValueStore
import com.kbhatiya999.jarvis.core.Prompts
import com.kbhatiya999.jarvis.core.Watchable

/**
 * Goals: "make sure I go to the gym at 6". Unlike a timed reminder, a goal is something to make happen: in the next
 * steps Jarvis plans conditions and checks on them before the deadline, and checks in after. This step: the goal
 * records, making / moving / cancelling / finishing them by voice, the attempts log, and saying it at the deadline.
 * Main thread only.
 */
class Goals internal constructor(private val agent: Agent, store: KeyValueStore) : Feature {
    private val goalStore = RecordStore(store, "goals", Goal.serializer(), version = 1)
    private val attemptStore = RecordStore(store, "attempts", Attempt.serializer(), version = 1)

    val items = Watchable(goalStore.load())
    val attempts = Watchable(attemptStore.load())

    override val name = "goals"

    sealed interface Result {
        data class Ok(val goal: Goal) : Result
        data class Error(val message: String) : Result
    }

    fun add(what: String, deadlineLocal: String, importance: Importance, success: String = ""): Result {
        val w = what.trim()
        if (w.isEmpty()) return Result.Error("What's the goal?")
        val at = agent.clock.parseLocal(deadlineLocal) ?: return Result.Error("I couldn't read the time \"$deadlineLocal\"; use 2026-11-18T18:00.")
        if (at <= agent.now) return Result.Error("${agent.clock.describe(at)} has already passed.")
        if (active().size >= MAX_ACTIVE) return Result.Error("You already have $MAX_ACTIVE goals going; finish or cancel one first.")
        val g = Goal(id = newId(), what = w.take(MAX_TEXT), deadline = at, importance = importance,
            success = success.trim().take(MAX_TEXT), createdAt = agent.now)
        save(items.value + g)
        agent.schedule(deadlineTimer(g.id), at)
        agent.journal("goal made: ${g.id} \"${g.what}\" by ${agent.clock.describe(at)} (${importance.name.lowercase()})")
        return Result.Ok(g)
    }

    fun move(what: String, deadlineLocal: String): Result {
        val g = find(what) ?: return notFound(what)
        val at = agent.clock.parseLocal(deadlineLocal) ?: return Result.Error("I couldn't read the time \"$deadlineLocal\".")
        if (at <= agent.now) return Result.Error("${agent.clock.describe(at)} has already passed.")
        val moved = g.copy(deadline = at, status = GoalStatus.ACTIVE)
        update(moved)
        agent.schedule(deadlineTimer(g.id), at)
        return Result.Ok(moved)
    }

    fun cancel(what: String): Result = finish(what, GoalStatus.CANCELLED, null, null)

    /** The user did it: logs a successful attempt. */
    fun done(what: String): Result = finish(what, GoalStatus.DONE, Attempt.Outcome.DONE, null)

    /** The user skipped it this time, ideally with why: logs the attempt (the reason feeds later plans). */
    fun skip(what: String, reason: String): Result = finish(what, GoalStatus.SKIPPED, Attempt.Outcome.SKIPPED, reason.trim().ifEmpty { null })

    /** Goals not finished yet (active or past the deadline with no answer), soonest first. */
    fun active(): List<Goal> = items.value.filter { it.status == GoalStatus.ACTIVE || it.status == GoalStatus.DUE }.sortedBy { it.deadline }

    fun describeActive(): String = active().joinToString("; ") {
        "\"${it.what}\" by ${agent.clock.describe(it.deadline)}" +
            (if (it.importance == Importance.IMPORTANT) " (important)" else "") +
            (if (it.status == GoalStatus.DUE) " (deadline passed, outcome unknown)" else "")
    }.ifEmpty { "none" }

    // ------------------------------------------------------------------ Feature

    override fun owns(id: String) = id.startsWith(PREFIX)

    override fun handles(trigger: Trigger) = trigger is Trigger.Timer && owns(trigger.id)

    override fun handle(trigger: Trigger): Outcome {
        val t = trigger as Trigger.Timer
        val id = t.id.removePrefix(PREFIX).substringBefore(':')
        val g = items.value.firstOrNull { it.id == id } ?: return Outcome.Silent("goal $id no longer exists")
        if (g.status != GoalStatus.ACTIVE) return Outcome.Silent("goal $id is ${g.status.name.lowercase()}")
        update(g.copy(status = GoalStatus.DUE))
        return Outcome.Speak(
            prompt = Prompts.goalDue(g.what, g.importance == Importance.IMPORTANT, agent.clock.nowForPrompt()),
            cue = "(The goal's time has come. Say it.)",
            fallbackTitle = "It's time",
            fallbackText = g.what,
            source = "goal",
            why = "deadline ${agent.clock.describe(g.deadline)}",
        )
    }

    override fun missed(id: String, at: Long) {
        val gid = id.removePrefix(PREFIX).substringBefore(':')
        items.value.firstOrNull { it.id == gid && it.status == GoalStatus.ACTIVE }?.let { update(it.copy(status = GoalStatus.DUE)) }
    }

    // ------------------------------------------------------------------ helpers

    private fun finish(what: String, status: GoalStatus, outcome: Attempt.Outcome?, reason: String?): Result {
        val g = find(what) ?: return notFound(what)
        val finished = g.copy(status = status)
        update(finished)
        agent.cancel(deadlineTimer(g.id))
        if (outcome != null) {
            val a = Attempt(goalId = g.id, at = agent.now, outcome = outcome, reason = reason)
            attempts.value = (attempts.value + a).takeLast(MAX_ATTEMPTS)
            attemptStore.save(attempts.value)
        }
        agent.journal("goal ${status.name.lowercase()}: ${g.id} \"${g.what}\"" + (reason?.let { " (\"$it\")" } ?: ""))
        return Result.Ok(finished)
    }

    /** Exact text first, then containing; unfinished goals only. */
    private fun find(what: String): Goal? {
        val w = what.trim().lowercase()
        if (w.isEmpty()) return null
        val a = active()
        return a.firstOrNull { it.what.lowercase() == w } ?: a.firstOrNull { it.what.lowercase().contains(w) || w.contains(it.what.lowercase()) }
    }

    private fun notFound(what: String) = Result.Error("No goal matches \"$what\". Goals: ${describeActive()}.")

    private fun deadlineTimer(id: String) = "$PREFIX$id:deadline"

    private fun update(g: Goal) = save(items.value.map { if (it.id == g.id) g else it })

    private fun save(list: List<Goal>) {
        val (open, closed) = list.partition { it.status == GoalStatus.ACTIVE || it.status == GoalStatus.DUE }
        val kept = open + closed.sortedBy { it.deadline }.takeLast(KEEP_CLOSED)
        items.value = kept
        goalStore.save(kept)
    }

    private fun newId() = "g" + agent.now.toString(36) + (0..2).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

    companion object {
        private const val PREFIX = "goal:"
        const val MAX_ACTIVE = 10
        const val MAX_TEXT = 300
        private const val KEEP_CLOSED = 30
        private const val MAX_ATTEMPTS = 200
    }
}

/** Short facts about the user that every goal can use ("tired after 5 pm"), at most [MAX], each with its source. */
class Profile internal constructor(store: KeyValueStore, private val now: () -> Long) {
    private val notesStore = RecordStore(store, "notes", Note.serializer(), version = 1)
    val notes = Watchable(notesStore.load())

    /** Adds a note; the oldest goes when there are more than [MAX]. Blank or duplicate notes are ignored. */
    fun add(text: String, source: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || notes.value.any { it.text.equals(t, ignoreCase = true) }) return false
        set((notes.value + Note(t.take(200), source, now())).takeLast(MAX))
        return true
    }

    fun remove(text: String): Boolean {
        val before = notes.value.size
        set(notes.value.filterNot { it.text.equals(text.trim(), ignoreCase = true) })
        return notes.value.size < before
    }

    private fun set(list: List<Note>) {
        notes.value = list
        notesStore.save(list)
    }

    companion object {
        const val MAX = 20
    }
}

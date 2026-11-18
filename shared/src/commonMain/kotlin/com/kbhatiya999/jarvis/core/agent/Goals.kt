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
    /**
     * The user skipped it this time. A reason is required, and "I can't" isn't one: the voice session is sent back
     * to ask what got in the way (once). If the user won't say, the session passes "didn't say".
     */
    fun skip(what: String, reason: String): Result {
        val r = reason.trim()
        if (r.isEmpty() || NOT_A_REASON.matches(r.lowercase())) {
            return Result.Error("That's whether, not why. Ask the user once, gently, what got in the way, then call skip_goal " +
                "again with their answer in their words. If they don't want to say, use reason \"didn't say\".")
        }
        return finish(what, GoalStatus.SKIPPED, Attempt.Outcome.SKIPPED, r.takeUnless { it.equals("didn't say", ignoreCase = true) })
    }

    /** A drafted plan, for the voice session to tell the user: its conditions and at most one question. */
    data class Plan(val goal: Goal, val question: String?, val why: String)

    /**
     * Drafts a plan for a goal just made: the conditions that must be true beforehand, each with a checkpoint.
     * Gradual: a goal with no history gets at most one condition; more only once past attempts show real hurdles
     * (up to the rules' limit). Never fails: without the model the goal simply has no plan yet.
     */
    suspend fun draftPlan(goalId: String): Plan? {
        val g = items.value.firstOrNull { it.id == goalId } ?: return null
        val history = similarAttempts(g)
        val hurdles = history.mapNotNull { it.reason }.distinct()
        val limit = if (hurdles.isEmpty()) 1 else minOf(Rules.MAX_CONDITIONS, hurdles.size + 1)
        val d = agent.decide(DecisionJob.PLAN, planContext(g, history, limit))
        val now = agent.now
        val conditions = d.conditions.take(limit).mapNotNull { c ->
            val at = checkTime(c.checkAt, g.deadline) ?: return@mapNotNull null
            if (at <= now || at >= g.deadline || c.what.isBlank()) return@mapNotNull null
            Condition(id = "c" + (0..3).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString(""),
                what = c.what.trim().take(MAX_TEXT), checkAt = at, reason = c.reason.trim().take(MAX_TEXT))
        }
        if (!agent.rules.planSize(conditions.size).allowed) return Plan(g, null, "plan too big")
        val planned = (items.value.firstOrNull { it.id == goalId } ?: return null).copy(conditions = conditions)
        update(planned)
        conditions.forEach { c -> agent.schedule(checkTimer(g.id, c.id), c.checkAt!!) }
        agent.journal("plan for ${g.id}: ${conditions.size} condition(s) " +
            conditions.joinToString { "\"${it.what}\" at ${agent.clock.describe(it.checkAt!!)}" } + " (${d.why})")
        agent.onDecision?.invoke(
            if (conditions.isEmpty()) "Plan for \"${g.what}\": no checks before ${agent.clock.describe(g.deadline)}. Why: ${d.why}"
            else "Plan for \"${g.what}\": I'll check " + conditions.joinToString(" and ") {
                "\"${it.what}\" at ${agent.clock.describe(it.checkAt!!)}"
            } + ". Why: ${d.why}"
        )
        return Plan(planned, d.question?.trim()?.takeIf { it.isNotEmpty() }, d.why)
    }

    /** The user says a condition is fine ("bag's packed") or at risk. */
    fun answer(goal: String, condition: String, ok: Boolean): Result {
        val g = find(goal) ?: return notFound(goal)
        val w = condition.trim().lowercase()
        val c = g.conditions.firstOrNull { it.what.lowercase().contains(w) || w.contains(it.what.lowercase()) }
            ?: g.conditions.singleOrNull()
            ?: return Result.Error("\"${g.what}\" has no condition like \"$condition\". Conditions: ${g.conditions.joinToString { it.what }.ifEmpty { "none" }}.")
        val updated = g.copy(conditions = g.conditions.map { if (it.id == c.id) it.copy(state = if (ok) ConditionState.OK else ConditionState.AT_RISK) else it })
        update(updated)
        agent.journal("condition \"${c.what}\" of ${g.id}: ${if (ok) "ok" else "at risk"}")
        return Result.Ok(updated)
    }

    /** Goals not finished yet (active or past the deadline with no answer), soonest first. */
    fun active(): List<Goal> {
        closeStale()
        return items.value.filter { it.status == GoalStatus.ACTIVE || it.status == GoalStatus.DUE }.sortedBy { it.deadline }
    }

    /**
     * A goal past its deadline with no answer for [STALE_MS] is closed as "no answer" (an attempt), so old goals don't
     * linger and get mixed up with new ones.
     */
    private fun closeStale() {
        val now = agent.now
        val stale = items.value.filter { it.status == GoalStatus.DUE && now - it.deadline > STALE_MS }
        if (stale.isEmpty()) return
        stale.forEach { g ->
            attempts.value = (attempts.value + Attempt(goalId = g.id, at = now, outcome = Attempt.Outcome.NO_ANSWER)).takeLast(MAX_ATTEMPTS)
            agent.journal("goal ${g.id} \"${g.what}\": no answer ${STALE_MS / 3_600_000} h after the deadline, closed")
        }
        attemptStore.save(attempts.value)
        save(items.value.map { g -> if (stale.any { it.id == g.id }) g.copy(status = GoalStatus.SKIPPED) else g })
    }

    fun describeActive(): String = active().joinToString("; ") {
        "\"${it.what}\" by ${agent.clock.describe(it.deadline)}" +
            (if (it.importance == Importance.IMPORTANT) " (important)" else "") +
            (if (it.status == GoalStatus.DUE) " (deadline passed, outcome unknown)" else "")
    }.ifEmpty { "none" }

    // ------------------------------------------------------------------ Feature

    override fun owns(id: String) = id.startsWith(PREFIX)

    override fun handles(trigger: Trigger) = trigger is Trigger.Timer && owns(trigger.id)

    override suspend fun handle(trigger: Trigger): Outcome {
        val t = trigger as Trigger.Timer
        val id = t.id.removePrefix(PREFIX).substringBefore(':')
        val g = items.value.firstOrNull { it.id == id } ?: return Outcome.Silent("goal $id no longer exists")
        if (g.status != GoalStatus.ACTIVE) return Outcome.Silent("goal $id is ${g.status.name.lowercase()}")
        if (":check:" in t.id) return checkpoint(g, t.id.substringAfter(":check:"))
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

    /** A condition's checkpoint: the rules decide whether Jarvis may speak up, the model whether it should. */
    private suspend fun checkpoint(g: Goal, conditionId: String): Outcome {
        val c = g.conditions.firstOrNull { it.id == conditionId } ?: return Outcome.Silent("condition $conditionId is gone")
        if (c.state == ConditionState.OK) return Outcome.Silent("no need to check \"${c.what}\" (${g.what}): you'd already confirmed it", show = true)
        val allowed = agent.rules.nudge(g.id)
        if (!allowed.allowed) return Outcome.Silent("check on \"${c.what}\" (${g.what}) skipped: ${allowed.why}", show = true)
        val d = agent.decide(DecisionJob.CHECKPOINT, checkpointContext(g, c))
        val text = d.text?.trim().orEmpty()
        if (!d.speak || text.isEmpty()) return Outcome.Silent("check on \"${c.what}\" (${g.what}): ${d.why}", show = true)
        return Outcome.Speak(
            prompt = Prompts.goalCheck(g.what, agent.clock.describe(g.deadline), c.what, text, d.question?.trim()?.takeIf { it.isNotEmpty() }),
            cue = "(Checkpoint for the user's goal. Say it.)",
            fallbackTitle = g.what,
            fallbackText = text,
            source = "goal check",
            why = "checkpoint \"${c.what}\": ${d.why}",
        )
    }

    // ------------------------------------------------------------------ context (what the model sees)

    private fun planContext(g: Goal, history: List<Attempt>, limit: Int) = buildString {
        appendLine("Now: ${agent.clock.nowForPrompt()}")
        appendLine("Goal: \"${g.what}\" by ${agent.clock.describe(g.deadline)}" + if (g.importance == Importance.IMPORTANT) " (important to the user)" else "")
        if (g.success.isNotBlank()) appendLine("Counts as done: ${g.success}")
        appendLine("Limit: at most $limit condition(s).")
        appendLine("Past attempts at similar goals: " + history.takeLast(5).joinToString("; ") { a ->
            "${agent.clock.describe(a.at)}: ${a.outcome.name.lowercase()}" + (a.reason?.let { " (\"$it\")" } ?: "")
        }.ifEmpty { "none (first time)" })
        appendLine("About the user: " + agent.profile.notes.value.joinToString("; ") { it.text }.ifEmpty { "nothing yet" })
    }

    private fun checkpointContext(g: Goal, c: Condition) = buildString {
        appendLine("Now: ${agent.clock.nowForPrompt()}")
        appendLine("Goal: \"${g.what}\" by ${agent.clock.describe(g.deadline)}" + if (g.importance == Importance.IMPORTANT) " (important)" else "")
        appendLine("Checking now: \"${c.what}\" (state: ${c.state.name.lowercase()}; why it's on the plan: ${c.reason.ifBlank { "needed for the goal" }})")
        appendLine("Other conditions: " + g.conditions.filter { it.id != c.id }.joinToString("; ") { "\"${it.what}\" (${it.state.name.lowercase()})" }.ifEmpty { "none" })
        appendLine("About the user: " + agent.profile.notes.value.joinToString("; ") { it.text }.ifEmpty { "nothing yet" })
    }

    /** Past attempts at goals that share a meaningful word with [g] ("gym"). */
    private fun similarAttempts(g: Goal): List<Attempt> {
        val words = significant(g.what)
        if (words.isEmpty()) return emptyList()
        val similar = items.value.filter { it.id != g.id && significant(it.what).any { w -> w in words } }.map { it.id }.toSet()
        return attempts.value.filter { it.goalId in similar }
    }

    private fun significant(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 && it !in STOP }.toSet()

    /** "12:00" (on the deadline's day) or "2026-11-18T12:00" → millis. */
    private fun checkTime(text: String, deadline: Long): Long? {
        val t = text.trim()
        agent.clock.parseLocal(t)?.let { return it }
        if (!Regex("^\\d{1,2}:\\d{2}$").matches(t)) return null
        return agent.clock.parseLocal("${agent.clock.localDate(deadline)}T${t.padStart(5, '0')}")
    }

    // ------------------------------------------------------------------ helpers

    private fun finish(what: String, status: GoalStatus, outcome: Attempt.Outcome?, reason: String?): Result {
        val g = find(what) ?: return notFound(what)
        val finished = g.copy(status = status)
        update(finished)
        agent.cancel(deadlineTimer(g.id))
        g.conditions.forEach { agent.cancel(checkTimer(g.id, it.id)) }
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
        // Several goals can match ("take a short walk" twice): prefer the one whose time just came (latest deadline that
        // has passed), then the next one coming up.
        val now = agent.now
        val a = active().sortedWith(compareBy<Goal>({ if (it.deadline <= now) 0 else 1 }, { if (it.deadline <= now) -it.deadline else it.deadline }))
        return a.firstOrNull { it.what.lowercase() == w } ?: a.firstOrNull { it.what.lowercase().contains(w) || w.contains(it.what.lowercase()) }
    }

    private fun notFound(what: String) = Result.Error("No goal matches \"$what\". Goals: ${describeActive()}.")

    private fun deadlineTimer(id: String) = "$PREFIX$id:deadline"

    private fun checkTimer(id: String, conditionId: String) = "$PREFIX$id:check:$conditionId"

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
        /** A due goal with no answer this long after its deadline is closed as "no answer". */
        const val STALE_MS = 6 * 60 * 60_000L

        /** "I can't", "not today", "no", "I won't go out today"…: whether, not why. */
        private val NOT_A_REASON = Regex(
            """^(no|nope|nah|not today|i (can ?not|can't|won't|will not|am not|'m not)( (do|go|make)( it)?)?( (out|for it|there))?( today| now| this time)?|(can ?not|can't|won't)( today)?)[.!]?$"""
        )
        private val STOP = setOf("the", "and", "for", "with", "make", "sure", "get", "out", "into", "this", "that", "today", "tomorrow")
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

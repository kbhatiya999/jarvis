package com.kbhatiya999.jarvis.core.agent

import com.kbhatiya999.jarvis.core.KeyValueStore
import com.kbhatiya999.jarvis.core.Prompts
import com.kbhatiya999.jarvis.core.Watchable
import kotlinx.coroutines.launch

/**
 * Goals: "make sure I go to the gym at 6". Unlike a timed reminder, a goal is something to make happen: in the next
 * steps Jarvis plans conditions and checks on them before the deadline, and checks in after. This step: the goal
 * records, making / moving / cancelling / finishing them by voice, the attempts log, and saying it at the deadline.
 * Main thread only.
 */
class Goals internal constructor(private val agent: Agent, store: KeyValueStore) : Feature {
    private val goalStore = RecordStore(store, "goals", Goal.serializer(), version = 1)
    private val attemptStore = RecordStore(store, "attempts", Attempt.serializer(), version = 1)

    private val seriesStore = RecordStore(store, "series", Series.serializer(), version = 1)
    private val kv = store

    val items = Watchable(goalStore.load())
    val attempts = Watchable(attemptStore.load())
    val series = Watchable(seriesStore.load())

    override val name = "goals"

    sealed interface Result {
        data class Ok(val goal: Goal) : Result
        data class Error(val message: String) : Result
    }

    fun add(what: String, deadlineLocal: String, importance: Importance, success: String = "", seriesId: String? = null): Result {
        val w = what.trim()
        if (w.isEmpty()) return Result.Error("What's the goal?")
        val at = agent.clock.parseLocal(deadlineLocal) ?: return Result.Error("I couldn't read the time \"$deadlineLocal\"; use 2026-11-18T18:00.")
        if (at <= agent.now) return Result.Error("${agent.clock.describe(at)} has already passed.")
        if (active().size >= MAX_ACTIVE) return Result.Error("You already have $MAX_ACTIVE goals going; finish or cancel one first.")
        val g = Goal(id = newId(), what = w.take(MAX_TEXT), deadline = at, importance = importance,
            success = success.trim().take(MAX_TEXT), createdAt = agent.now, seriesId = seriesId)
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
        val now = agent.now
        // Checks the user agreed to after past hurdles come first, as agreed: no model needed for them.
        val agreed = agent.hurdles.acceptedFor(g.what).take(Rules.MAX_CONDITIONS).mapNotNull { h ->
            val at = g.deadline - h.minutesBefore * 60_000L
            if (at <= now) null else Condition(id = newConditionId(), what = h.suggestion, checkAt = at, reason = h.reason)
        }
        val limit = maxOf(agreed.size, if (hurdles.isEmpty()) 1 else minOf(Rules.MAX_CONDITIONS, hurdles.size + 1))
        val room = limit - agreed.size
        val d = if (room > 0) agent.decide(DecisionJob.PLAN, planContext(g, history, room, agreed))
            else Decision(why = "only the checks you agreed to after past hurdles")
        val drafted = d.conditions.take(room).mapNotNull { c ->
            val at = checkTime(c.checkAt, g.deadline) ?: return@mapNotNull null
            if (at <= now || at >= g.deadline || c.what.isBlank()) return@mapNotNull null
            Condition(id = newConditionId(), what = c.what.trim().take(MAX_TEXT), checkAt = at, reason = c.reason.trim().take(MAX_TEXT))
        }
        val conditions = agreed + drafted
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
        agent.questions.answered(g.id, OpenQuestion.Kind.CONDITION, c.id)
        return Result.Ok(updated)
    }

    // ------------------------------------------------------------------ repeating goals

    /**
     * Repeats [what] on [days] at [time] ("18:00"). The goal matching [what], if one is open, becomes the first
     * occurrence; otherwise the next matching day gets one. Each occurrence gets its own plan.
     */
    fun repeat(what: String, days: List<Int>, time: String): Result {
        val d = days.filter { it in 1..7 }.distinct().sorted()
        if (d.isEmpty()) return Result.Error("Which days? e.g. mon,wed,fri, weekdays or every day.")
        if (!Regex("^\\d{1,2}:\\d{2}$").matches(time.trim())) return Result.Error("Which time? e.g. 18:00.")
        val t = time.trim().padStart(5, '0')
        val existing = find(what)
        val name = existing?.what ?: what.trim().ifEmpty { return Result.Error("What should repeat?") }
        // One series per goal: a new one replaces an old one of the same name.
        series.value.filter { it.active && it.what.equals(name, ignoreCase = true) }.forEach { stopSeries(it, "replaced") }
        val s = Series(id = "s" + newId(), what = name, time = t, days = d, importance = existing?.importance ?: Importance.NICE, createdAt = agent.now)
        saveSeries(series.value + s)
        if (existing != null && existing.status == GoalStatus.ACTIVE) update(existing.copy(seriesId = s.id))
        val first = if (existing != null && existing.status == GoalStatus.ACTIVE) existing else nextOccurrence(s)
        agent.decision("Repeating \"$name\" ${describeDays(d)} at $t." + (first?.let { " Next: ${agent.clock.describe(it.deadline)}." } ?: ""))
        return first?.let { Result.Ok(it) } ?: Result.Error("Repeating set, but I couldn't find the next day.")
    }

    /** Stops [what] repeating; upcoming occurrences are cancelled. */
    fun stopRepeating(what: String): Result {
        val w = what.trim().lowercase()
        val s = series.value.filter { it.active }.firstOrNull { it.what.lowercase() == w || it.what.lowercase().contains(w) || w.contains(it.what.lowercase()) }
            ?: return Result.Error("Nothing repeats like \"$what\". Repeating: ${describeSeries()}.")
        stopSeries(s, "you asked")
        agent.decision("Stopped repeating \"${s.what}\".")
        return Result.Ok(Goal(id = s.id, what = s.what, deadline = agent.now))
    }

    fun describeSeries(): String = series.value.filter { it.active }.joinToString("; ") { "\"${it.what}\" ${describeDays(it.days)} at ${it.time}" }.ifEmpty { "none" }

    /** The next occurrence of [s] after now (if none is open yet), with a plan drafted in the background. */
    private fun nextOccurrence(s: Series): Goal? {
        items.value.firstOrNull { it.seriesId == s.id && it.status == GoalStatus.ACTIVE }?.let { return it }
        val now = agent.now
        for (i in 0..7) {
            val day = now + i * 86_400_000L
            if (agent.clock.dayOfWeek(day) !in s.days) continue
            val at = agent.clock.parseLocal("${agent.clock.localDate(day)}T${s.time}") ?: continue
            if (at <= now + MIN_LEAD_MS) continue
            val r = add(s.what, "${agent.clock.localDate(day)}T${s.time}", s.importance, seriesId = s.id)
            if (r is Result.Ok) {
                agent.scope.launch { draftPlan(r.goal.id) }
                return r.goal
            }
            return null
        }
        return null
    }

    private fun stopSeries(s: Series, why: String) {
        saveSeries(series.value.map { if (it.id == s.id) it.copy(active = false) else it })
        items.value.filter { it.seriesId == s.id && it.status == GoalStatus.ACTIVE && it.deadline > agent.now }
            .forEach { finish(it, GoalStatus.CANCELLED, null, null) }
        agent.journal("series ${s.id} stopped ($why)")
    }

    private fun saveSeries(list: List<Series>) {
        val (on, off) = list.partition { it.active }
        val kept = on + off.takeLast(20)
        series.value = kept
        seriesStore.save(kept)
    }

    /** An offer to repeat, after enough successes. */
    data class RepeatOffer(val goal: Goal, val days: List<Int>, val time: String, val successes: Int) {
        val daysText get() = describeDays(days)
    }

    /**
     * Gradual, as agreed: after [REPEAT_AFTER] successes at similar goals within 14 days, if nothing like it repeats
     * yet and no offer was made in the last week, offer to repeat on the days those successes fell on.
     */
    fun repeatOffer(g: Goal): RepeatOffer? {
        if (g.seriesId != null) return null
        val words = significant(g.what)
        if (series.value.any { it.active && significant(it.what).any { w -> w in words } }) return null
        val now = agent.now
        val lastOffer = kv.get(REPEAT_OFFERED)?.toLongOrNull() ?: 0L
        if (now - lastOffer < REPEAT_OFFER_SPACING_MS) return null
        val similar = items.value.filter { significant(it.what).any { w -> w in words } }.associateBy { it.id }
        val wins = attempts.value.filter { it.outcome == Attempt.Outcome.DONE && it.goalId in similar && now - it.at <= 14 * 86_400_000L }
        if (wins.size < REPEAT_AFTER) return null
        val days = wins.mapNotNull { similar[it.goalId]?.deadline }.map { agent.clock.dayOfWeek(it) }.distinct().sorted()
        val time = hhmm(g.deadline)
        kv.set(REPEAT_OFFERED, now.toString())
        agent.journal("offering to repeat \"${g.what}\" ${describeDays(days)} at $time (${wins.size} successes in 14 days)")
        return RepeatOffer(g, days, time, wins.size)
    }

    private fun hhmm(millis: Long): String {
        val h = agent.clock.hour(millis)
        val m = ((millis - (agent.clock.parseLocal("${agent.clock.localDate(millis)}T${h.toString().padStart(2, '0')}:00") ?: millis)) / 60_000L).toInt().coerceIn(0, 59)
        return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
    }

    /** "Stop asking me about this": no more questions about the goal (it stays set). */
    fun stopAsking(what: String): Result {
        val g = find(what) ?: return notFound(what)
        agent.questions.stop(g.id, "you asked me to stop")
        agent.decision("You asked me to stop asking about \"${g.what}\"; I won't, though the goal stays set.")
        return Result.Ok(g)
    }

    // By id, for notification answers (the question knows exactly which goal).
    internal fun answerById(goalId: String, conditionId: String?, ok: Boolean) {
        val g = items.value.firstOrNull { it.id == goalId } ?: return
        val c = g.conditions.firstOrNull { it.id == conditionId } ?: return
        answer(g.what, c.what, ok)
    }

    // For the goals screen (exact ids, no matching by name).
    fun markDone(goalId: String) = doneById(goalId)
    fun cancelById(goalId: String) { items.value.firstOrNull { it.id == goalId }?.let { finish(it, GoalStatus.CANCELLED, null, null) } }
    fun stopSeriesById(seriesId: String) { series.value.firstOrNull { it.id == seriesId && it.active }?.let { stopSeries(it, "stopped on the goals screen") } }

    /** From a notification "Yes": done, and maybe an offer to repeat, as a Yes / No question. */
    internal fun doneById(goalId: String) {
        val g = items.value.firstOrNull { it.id == goalId } ?: return
        finish(g, GoalStatus.DONE, Attempt.Outcome.DONE, null)
        val offer = repeatOffer(g) ?: return
        val q = agent.questions.open(g, OpenQuestion.Kind.REPEAT,
            "That's ${offer.successes} times in two weeks. Shall I set \"${g.what}\" ${offer.daysText} at ${offer.time}?",
            expiresAt = agent.now + SUGGESTION_TTL_MS, firstTryAt = null, repeatDays = offer.days, repeatTime = offer.time)
        agent.questions.askNow(q, spoken = false, how = "after a success")
    }

    /** From a notification reply: record the skip, then reflect and offer a suggestion as a Yes / No question. */
    internal fun skipById(goalId: String, reason: String) {
        val g = items.value.firstOrNull { it.id == goalId } ?: return
        val r = reason.takeUnless { it.equals("didn't say", ignoreCase = true) }
        finish(g, GoalStatus.SKIPPED, Attempt.Outcome.SKIPPED, r)
        if (r == null) return
        agent.scope.launch {
            val h = agent.hurdles.reflect(g, r) ?: return@launch
            val q = agent.questions.open(g, OpenQuestion.Kind.SUGGESTION,
                "Next time, shall I check \"${h.suggestion}\" ${agent.hurdles.describeBefore(h.minutesBefore)}?",
                expiresAt = agent.now + SUGGESTION_TTL_MS, firstTryAt = null, hurdleId = h.id)
            agent.questions.askNow(q, spoken = false, how = "after your reason")
        }
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
        stale.mapNotNull { it.seriesId }.distinct().forEach { sid -> series.value.firstOrNull { it.id == sid && it.active }?.let { nextOccurrence(it) } }
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
        // If the user doesn't answer at the deadline, ask "did you?" later (and again at good moments).
        agent.questions.open(g, OpenQuestion.Kind.OUTCOME, "Did you manage \"${g.what}\"?",
            expiresAt = g.deadline + STALE_MS, firstTryAt = g.deadline + OUTCOME_FIRST_TRY_MS)
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
        // Check the rules before asking the model (no model call if Jarvis couldn't speak anyway); the ask counts it.
        val allowed = agent.rules.nudge(g.id, count = false)
        if (!allowed.allowed) return Outcome.Silent("check on \"${c.what}\" (${g.what}) skipped: ${allowed.why}", show = true)
        val d = agent.decide(DecisionJob.CHECKPOINT, checkpointContext(g, c))
        val text = (d.question?.trim()?.takeIf { it.isNotEmpty() } ?: d.text?.trim()).orEmpty()
        if (!d.speak || text.isEmpty()) return Outcome.Silent("check on \"${c.what}\" (${g.what}): ${d.why}", show = true)
        // An open question: asked now, then again at good moments or on its timer until it no longer helps.
        val expires = minOf(g.deadline, (c.checkAt ?: agent.now) + CONDITION_TTL_MS)
        val q = agent.questions.open(g, OpenQuestion.Kind.CONDITION, text, expires, firstTryAt = null, conditionId = c.id)
        agent.questions.askNow(q, spoken = true, how = "at its checkpoint")
        return Outcome.Silent("checkpoint \"${c.what}\": ${d.why}")
    }

    // ------------------------------------------------------------------ context (what the model sees)

    private fun newConditionId() = "c" + (0..3).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

    private fun planContext(g: Goal, history: List<Attempt>, limit: Int, agreed: List<Condition> = emptyList()) = buildString {
        appendLine("Now: ${agent.clock.nowForPrompt()}")
        appendLine("Goal: \"${g.what}\" by ${agent.clock.describe(g.deadline)}" + if (g.importance == Importance.IMPORTANT) " (important to the user)" else "")
        if (g.success.isNotBlank()) appendLine("Counts as done: ${g.success}")
        appendLine("Limit: at most $limit more condition(s).")
        if (agreed.isNotEmpty()) appendLine("Already planned (agreed by the user after past hurdles; don't repeat): " + agreed.joinToString("; ") { "\"${it.what}\"" })
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

    private fun significant(text: String) = Companion.significant(text)

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
        return finish(g, status, outcome, reason)
    }

    private fun finish(g: Goal, status: GoalStatus, outcome: Attempt.Outcome?, reason: String?): Result {
        val finished = g.copy(status = status)
        update(finished)
        agent.questions.stop(g.id, "goal ${status.name.lowercase()}")
        agent.cancel(deadlineTimer(g.id))
        g.conditions.forEach { agent.cancel(checkTimer(g.id, it.id)) }
        if (outcome != null) {
            val a = Attempt(goalId = g.id, at = agent.now, outcome = outcome, reason = reason)
            attempts.value = (attempts.value + a).takeLast(MAX_ATTEMPTS)
            attemptStore.save(attempts.value)
        }
        agent.journal("goal ${status.name.lowercase()}: ${g.id} \"${g.what}\"" + (reason?.let { " (\"$it\")" } ?: ""))
        g.seriesId?.let { sid -> series.value.firstOrNull { it.id == sid && it.active }?.let { nextOccurrence(it) } }
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

        /** A goal's meaningful words ("go to the gym" → gym), to find similar goals. */
        fun significant(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 && it !in STOP }.toSet()
        /** A due goal with no answer this long after its deadline is closed as "no answer". */
        const val STALE_MS = 6 * 60 * 60_000L
        /** A condition question stays useful this long after its checkpoint (never past the deadline). */
        const val CONDITION_TTL_MS = 60 * 60_000L
        /** "Did you?" is first asked this long after the deadline, if the user didn't answer at the deadline. */
        const val OUTCOME_FIRST_TRY_MS = 30 * 60_000L
        /** A suggestion for next time stays open this long. */
        const val SUGGESTION_TTL_MS = 24 * 60 * 60_000L
        /** Successes at similar goals (within 14 days) before Jarvis offers to repeat. */
        const val REPEAT_AFTER = 3
        /** At most one repeat offer a week. */
        const val REPEAT_OFFER_SPACING_MS = 7 * 86_400_000L
        private const val REPEAT_OFFERED = "repeat_offered_at"
        /** A new occurrence is never made less than this far ahead. */
        private const val MIN_LEAD_MS = 5 * 60_000L

        private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        fun describeDays(days: List<Int>): String = when (days.sorted()) {
            (1..7).toList() -> "every day"
            (1..5).toList() -> "on weekdays"
            listOf(6, 7) -> "on weekends"
            else -> "on " + days.sorted().joinToString(", ") { DAY_NAMES[it - 1] }
        }

        /** "mon,wed,fri", "weekdays", "every day", "weekends", "tuesday and thursday" → ISO days. */
        fun parseDays(text: String): List<Int> {
            val t = text.lowercase()
            return when {
                "every" in t || "daily" in t || "all" in t -> (1..7).toList()
                "weekday" in t -> (1..5).toList()
                "weekend" in t -> listOf(6, 7)
                else -> listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").mapIndexedNotNull { i, d -> if (Regex("\\b$d").containsMatchIn(t)) i + 1 else null }
            }
        }

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

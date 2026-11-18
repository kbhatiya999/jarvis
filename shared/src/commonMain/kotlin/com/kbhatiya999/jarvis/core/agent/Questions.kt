package com.kbhatiya999.jarvis.core.agent

import com.kbhatiya999.jarvis.core.KeyValueStore
import com.kbhatiya999.jarvis.core.Prompts
import com.kbhatiya999.jarvis.core.Watchable
import kotlinx.serialization.Serializable

/** Something Jarvis still needs to know for a goal, asked at good moments until answered or no longer useful. */
@Serializable
data class OpenQuestion(
    val id: String,
    val goalId: String,
    val kind: Kind,
    /** For CONDITION: which condition. */
    val conditionId: String? = null,
    /** For SUGGESTION: which hurdle's suggestion. */
    val hurdleId: String? = null,
    /** The question, written to be asked ("Is your gym bag packed, Sir?"). */
    val text: String,
    val important: Boolean = false,
    val createdAt: Long,
    /** After this the answer no longer helps: the question closes. */
    val expiresAt: Long,
    val tries: Int = 0,
    val maxTries: Int = 1,
    /** When the retry timer fires; null = no timer (moments may still ask while tries remain). */
    val nextTryAt: Long? = null,
    val lastAskedAt: Long? = null,
    val status: Status = Status.OPEN,
) {
    @Serializable
    enum class Kind {
        /** Before a checkpoint: is this condition fine? */
        CONDITION,
        /** After the deadline: did it happen? */
        OUTCOME,
        /** After a "no": what got in the way? */
        REASON,
        /** After a reason: shall Jarvis check this next time? */
        SUGGESTION,
    }

    @Serializable
    enum class Status { OPEN, ANSWERED, EXPIRED, OUT_OF_TRIES, STOPPED }
}

/** A good moment to ask: the user just unlocked the phone, opened an app... */
enum class Moment { UNLOCK, APP_OPEN }

/** How a question reached the user. */
enum class Delivery { JOINED_CONVERSATION, SPOKE, NOTIFIED, NOT_DELIVERED }

/** A notification answer: the buttons, or a typed reply. */
enum class Answer { YES, NO, LATER, RATHER_NOT_SAY, TEXT }

/**
 * The open questions and the asking: at moments (a conversation going, unlock, app open) and on retry timers,
 * within the rules (spacing, per-goal limit, quiet hours). Every ask and every wait is shown as a decision.
 * Main thread only.
 */
class Questions internal constructor(private val agent: Agent, store: KeyValueStore) : Feature {
    private val records = RecordStore(store, "questions", OpenQuestion.serializer(), version = 1)
    val items = Watchable(records.load())

    override val name = "questions"

    // ------------------------------------------------------------------ creating and closing

    /** Opens a question (replacing an open one of the same kind for the same goal/condition) and tries it at [firstTryAt]. */
    internal fun open(goal: Goal, kind: OpenQuestion.Kind, text: String, expiresAt: Long, firstTryAt: Long?, conditionId: String? = null, hurdleId: String? = null): OpenQuestion {
        val important = goal.importance == Importance.IMPORTANT
        val max = when (kind) {
            OpenQuestion.Kind.OUTCOME -> if (important) 3 else 2
            OpenQuestion.Kind.REASON, OpenQuestion.Kind.SUGGESTION -> 2
            OpenQuestion.Kind.CONDITION -> if (important) 3 else 1
        }
        items.value.filter { it.status == OpenQuestion.Status.OPEN && it.goalId == goal.id && it.kind == kind && it.conditionId == conditionId }
            .forEach { close(it, OpenQuestion.Status.STOPPED, null) }
        val q = OpenQuestion(
            id = "q" + agent.now.toString(36) + (0..2).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString(""),
            goalId = goal.id, kind = kind, conditionId = conditionId, hurdleId = hurdleId, text = text.trim(), important = important,
            createdAt = agent.now, expiresAt = expiresAt, maxTries = max, nextTryAt = firstTryAt,
        )
        save(items.value + q)
        firstTryAt?.let { agent.schedule(timer(q.id), it) }
        agent.journal("question ${q.id} (${kind.name.lowercase()}) for ${goal.id}: \"${q.text}\"")
        return q
    }

    /** The answer came (by voice, a button or a reply): close the matching open questions. */
    internal fun answered(goalId: String, kind: OpenQuestion.Kind? = null, conditionId: String? = null) {
        items.value.filter {
            it.status == OpenQuestion.Status.OPEN && it.goalId == goalId && (kind == null || it.kind == kind) &&
                (conditionId == null || it.conditionId == conditionId)
        }.forEach { close(it, OpenQuestion.Status.ANSWERED, null) }
    }

    /** A suggestion was answered (by voice or a button): close its question. */
    internal fun answeredHurdle(hurdleId: String) {
        items.value.filter { it.status == OpenQuestion.Status.OPEN && it.hurdleId == hurdleId }.forEach { close(it, OpenQuestion.Status.ANSWERED, null) }
    }

    /** The goal is finished or cancelled, or the user said "stop asking": nothing more to ask about it. */
    internal fun stop(goalId: String, why: String? = null) {
        // A suggestion outlives its goal (it's about next time), so it isn't stopped with it.
        items.value.filter { it.status == OpenQuestion.Status.OPEN && it.goalId == goalId && it.kind != OpenQuestion.Kind.SUGGESTION }
            .forEach { close(it, OpenQuestion.Status.STOPPED, why) }
    }

    fun open(): List<OpenQuestion> {
        expireOld()
        return items.value.filter { it.status == OpenQuestion.Status.OPEN }
    }

    // ------------------------------------------------------------------ asking

    /**
     * A good moment: asks the most useful open question if the rules allow, through the best channel for it.
     * Returns true if it asked (so e.g. the app-open check-in can stay quiet).
     */
    fun moment(kind: Moment, idleMs: Long = 0): Boolean {
        val q = open().filter { it.tries < it.maxTries && agent.now >= it.createdAt }
            .sortedWith(compareBy({ if (it.important) 0 else 1 }, { it.expiresAt }))
            .firstOrNull() ?: return false
        val spoken = kind == Moment.UNLOCK && idleMs >= IDLE_FOR_SPEAKING_MS
        return ask(q, spoken, "at ${kind.name.lowercase().replace('_', ' ')}") != Delivery.NOT_DELIVERED
    }

    override fun owns(id: String) = id.startsWith(PREFIX)

    override fun handles(trigger: Trigger) = trigger is Trigger.Timer && owns(trigger.id)

    /** A retry timer: ask again (spoken if it's important or about to expire, else a notification). */
    override suspend fun handle(trigger: Trigger): Outcome {
        val id = (trigger as Trigger.Timer).id.removePrefix(PREFIX)
        expireOld()
        val q = items.value.firstOrNull { it.id == id } ?: return Outcome.Silent("question $id is gone")
        if (q.status != OpenQuestion.Status.OPEN) return Outcome.Silent("question $id is ${q.status.name.lowercase()}")
        val spoken = q.important || q.expiresAt - agent.now <= CLOSE_TO_EXPIRY_MS
        ask(q, spoken, "on its timer")
        return Outcome.Silent("asked or waited (see above)")
    }

    /** Asks [q] right away (e.g. at its checkpoint). */
    internal fun askNow(q: OpenQuestion, spoken: Boolean, how: String) = ask(q, spoken, how)

    /** Asks [q] now if the rules allow; records the try and plans the next. */
    private fun ask(q: OpenQuestion, spoken: Boolean, how: String): Delivery {
        val allowed = agent.rules.nudge(q.goalId)
        if (!allowed.allowed) {
            agent.journal("question ${q.id} not asked $how: ${allowed.why}")
            return Delivery.NOT_DELIVERED
        }
        val goal = agent.goals.items.value.firstOrNull { it.id == q.goalId } ?: return Delivery.NOT_DELIVERED.also { close(q, OpenQuestion.Status.STOPPED, "goal gone") }
        val prompt = Prompts.askQuestion(q.text, goal.what, q.kind.name)
        val delivery = agent.output?.ask(q, prompt, spoken) ?: Delivery.NOT_DELIVERED
        if (delivery == Delivery.NOT_DELIVERED) {
            agent.journal("question ${q.id} couldn't be asked $how")
            return delivery
        }
        val tries = q.tries + 1
        val next = if (tries < q.maxTries) (agent.now + BACKOFF_MS[(tries - 1).coerceAtMost(BACKOFF_MS.lastIndex)]).takeIf { it < q.expiresAt } else null
        update(q.copy(tries = tries, lastAskedAt = agent.now, nextTryAt = next))
        if (next != null) agent.schedule(timer(q.id), next) else agent.cancel(timer(q.id))
        val channel = when (delivery) {
            Delivery.JOINED_CONVERSATION -> "in the conversation"
            Delivery.SPOKE -> "out loud"
            Delivery.NOTIFIED -> "with a notification"
            Delivery.NOT_DELIVERED -> ""
        }
        agent.decision("Asked $channel ($how): \"${q.text}\" (try $tries of ${q.maxTries}). " +
            (next?.let { "If there's no answer I'll try again at ${agent.clock.describe(it)}." } ?: "That's the last try."))
        return delivery
    }

    // ------------------------------------------------------------------ notification answers

    /** A button or reply on a question's notification. */
    fun answerFromNotification(questionId: String, answer: Answer, text: String? = null) {
        val q = items.value.firstOrNull { it.id == questionId } ?: return
        if (q.status != OpenQuestion.Status.OPEN) return
        val goal = agent.goals.items.value.firstOrNull { it.id == q.goalId } ?: return
        agent.journal("question ${q.id}: notification answer ${answer.name.lowercase()}")
        when (answer) {
            Answer.LATER -> {
                val next = agent.now + LATER_MS
                if (next < q.expiresAt) {
                    update(q.copy(nextTryAt = next))
                    agent.schedule(timer(q.id), next)
                    agent.decision("You said later: I'll ask \"${q.text}\" again at ${agent.clock.describe(next)}.")
                } else close(q, OpenQuestion.Status.EXPIRED, "later would be too late")
            }
            Answer.YES -> when (q.kind) {
                OpenQuestion.Kind.CONDITION -> agent.goals.answerById(goal.id, q.conditionId, ok = true)
                OpenQuestion.Kind.OUTCOME -> agent.goals.doneById(goal.id)
                OpenQuestion.Kind.SUGGESTION -> agent.hurdles.accept(q.hurdleId)
                OpenQuestion.Kind.REASON -> Unit
            }
            Answer.NO -> when (q.kind) {
                OpenQuestion.Kind.CONDITION -> agent.goals.answerById(goal.id, q.conditionId, ok = false)
                OpenQuestion.Kind.OUTCOME -> {
                    close(q, OpenQuestion.Status.ANSWERED, null)
                    // "No" isn't a reason: ask why, right away, with a reply box.
                    val r = open(goal, OpenQuestion.Kind.REASON, "What got in the way of \"${goal.what}\"?", agent.now + REASON_TTL_MS, null)
                    agent.output?.ask(r, Prompts.askQuestion(r.text, goal.what, r.kind.name), spoken = false)
                    update(r.copy(tries = 1, lastAskedAt = agent.now))
                }
                OpenQuestion.Kind.SUGGESTION -> agent.hurdles.decline(q.hurdleId)
                OpenQuestion.Kind.REASON -> Unit
            }
            Answer.TEXT -> if (q.kind == OpenQuestion.Kind.REASON && !text.isNullOrBlank()) agent.goals.skipById(goal.id, text.trim())
            Answer.RATHER_NOT_SAY -> if (q.kind == OpenQuestion.Kind.REASON) agent.goals.skipById(goal.id, "didn't say")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun expireOld() {
        val now = agent.now
        items.value.filter { it.status == OpenQuestion.Status.OPEN }.forEach { q ->
            when {
                now >= q.expiresAt -> close(q, OpenQuestion.Status.EXPIRED, "no answer in time")
                q.tries >= q.maxTries && q.nextTryAt == null && q.lastAskedAt != null && now - q.lastAskedAt > LAST_TRY_GRACE_MS ->
                    close(q, OpenQuestion.Status.OUT_OF_TRIES, "no answer after ${q.tries} tries")
            }
        }
    }

    private fun close(q: OpenQuestion, status: OpenQuestion.Status, why: String?) {
        update(q.copy(status = status, nextTryAt = null))
        agent.cancel(timer(q.id))
        agent.output?.dismiss(q)
        agent.journal("question ${q.id}: ${status.name.lowercase()}" + (why?.let { " ($it)" } ?: ""))
        if (status == OpenQuestion.Status.EXPIRED || status == OpenQuestion.Status.OUT_OF_TRIES) {
            agent.onDecision?.invoke("Stopped asking \"${q.text}\": ${why ?: status.name.lowercase()}.")
        }
    }

    private fun timer(id: String) = PREFIX + id

    private fun update(q: OpenQuestion) = save(items.value.map { if (it.id == q.id) q else it })

    private fun save(list: List<OpenQuestion>) {
        val (open, closed) = list.partition { it.status == OpenQuestion.Status.OPEN }
        val kept = open + closed.takeLast(KEEP_CLOSED)
        items.value = kept
        records.save(kept)
    }

    companion object {
        private const val PREFIX = "ask:"
        /** Retries after the 1st and 2nd try (the rules' 30-min spacing would block anything shorter). */
        val BACKOFF_MS = listOf(30 * 60_000L, 60 * 60_000L)
        const val LATER_MS = 60 * 60_000L
        const val CLOSE_TO_EXPIRY_MS = 30 * 60_000L
        const val IDLE_FOR_SPEAKING_MS = 2 * 60 * 60_000L
        const val REASON_TTL_MS = 12 * 60 * 60_000L
        /** After the last try, how long an answer may still come before the question counts as unanswered. */
        const val LAST_TRY_GRACE_MS = 60 * 60_000L
        private const val KEEP_CLOSED = 50
    }
}

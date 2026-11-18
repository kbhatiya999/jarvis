package com.kbhatiya999.jarvis.core.agent

import com.kbhatiya999.jarvis.core.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.kbhatiya999.jarvis.core.JarvisLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Jarvis's own small agent framework (see .scratch/plan-agent-framework.md). Step 1: Jarvis can wake itself at a
 * time and act, exactly once per wake-up, and every decision is logged with its reason.
 *
 * Flow per wake-up: Trigger → already handled? → Feature → Outcome → Output (speak / notify / silent) → journal.
 * The platform plugs in the [scheduler], [clock] and [output] (Android: exact alarms, java.time, active mode).
 * Without them (tests use fakes; iOS isn't wired) timers are kept but nothing fires. Main thread only.
 */
class Agent internal constructor(private val platform: Platform, internal val scope: CoroutineScope) {
    var scheduler: Scheduler? = null
    var clock: AgentClock = UtcClock { platform.nowMillis() }
    var output: Output? = null

    /** The model behind decisions (Jarvis: Gemini). Always used through [decide], which guards it. */
    var decider: Decider? = null
    private val guard by lazy { GuardedDecider({ decider }, rules, { clock.now() }) { decision(it) } }

    /** Asks the model, within the rules' budget; never throws: failures come back as a silent decision with why. */
    internal suspend fun decide(job: DecisionJob, context: String): Decision = guard.decide(job, context)

    /** Shows a decision to the user (Jarvis: a line in the chat), for transparency. Main thread. */
    var onDecision: ((String) -> Unit)? = null

    /** A decision worth showing the user: journaled and shown in the chat. */
    internal fun decision(text: String) {
        journal(text)
        onDecision?.invoke(text)
    }

    /** Active mode's quiet hours; the platform sets it (Jarvis: active mode's setting). */
    var isQuietHour: (hour: Int) -> Boolean = { false }

    /** What Jarvis may do on its own (budgets, quiet hours, spacing, plan size). */
    val rules = Rules({ clock }, platform.store("agent_rules")) { isQuietHour(it) }

    private val store = platform.store("agent")
    private val features = mutableListOf<Feature>()

    /** First feature: "remind me at 6 to call Mom". */
    val timedReminders = TimedReminders(this, platform.store("agent_timed_reminders")).also { features += it }

    /** Goals: "make sure I go to the gym at 6". Plans and checkpoints build on these in the next steps. */
    val goals = Goals(this, platform.store("agent_goals")).also { features += it }

    /** Short facts about the user shared by all goals (at most [Profile.MAX] lines), visible and editable. */
    val profile = Profile(platform.store("agent_profile")) { clock.now() }

    internal val now: Long get() = clock.now()

    // ------------------------------------------------------------------ timers

    /** Wakes Jarvis at [atMillis] with [id] (unique per timer; scheduling the same id again replaces it). */
    fun schedule(id: String, atMillis: Long, payload: String = "") {
        savePending(pending().filterNot { it.id == id } + Pending(id, atMillis, payload))
        scheduler?.schedule(id, atMillis, payload) ?: JarvisLog.w(TAG, "no scheduler: $id kept, won't fire until one is set")
        journal("scheduled $id for ${clock.describe(atMillis)}")
    }

    fun cancel(id: String) {
        savePending(pending().filterNot { it.id == id })
        scheduler?.cancel(id)
        journal("cancelled $id")
    }

    /** The platform's alarm for [id] went off. */
    fun onAlarm(id: String) {
        val p = pending().firstOrNull { it.id == id } ?: return journal("alarm $id: not pending (cancelled or done)")
        savePending(pending().filterNot { it.id == id })
        fire(Trigger.Timer(key = "${p.id}@${p.at}", id = p.id, payload = p.payload, scheduledAt = p.at))
    }

    /**
     * After a reboot, an update or a clock change: set every future timer again. One that came due while the phone
     * was off fires if it's at most [LATE_GRACE_MS] late; older ones are reported as missed and dropped.
     */
    fun rearm() {
        val t = now
        val all = pending()
        all.filter { it.at > t }.forEach { scheduler?.schedule(it.id, it.at, it.payload) }
        val due = all.filter { it.at <= t }
        due.forEach { p ->
            if (t - p.at <= LATE_GRACE_MS) onAlarm(p.id)
            else {
                savePending(pending().filterNot { it.id == p.id })
                features.firstOrNull { it.owns(p.id) }?.missed(p.id, p.at)
                decision("Missed something due ${clock.describe(p.at)}: the phone was off")
            }
        }
        journal("rearmed ${all.size - due.size} timer(s)")
    }

    /** Timers waiting to fire, soonest first. */
    fun pending(): List<Pending> = (store.get(PENDING)?.let { parse(it) } ?: emptyList()).sortedBy { it.at }

    // ------------------------------------------------------------------ dispatch

    /** Handles [trigger] once: a repeat of the same key (duplicate alarm, restart mid-way) is ignored. */
    fun fire(trigger: Trigger) {
        if (wasHandled(trigger.key)) return journal("${trigger.key}: already handled, ignored")
        markHandled(trigger.key)
        val feature = features.firstOrNull { it.handles(trigger) } ?: return journal("${trigger.key}: no feature handles it")
        // Features may ask the model (seconds), so each wake-up runs as its own coroutine on the main thread.
        scope.launch {
            val outcome = try {
                feature.handle(trigger)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                JarvisLog.e(TAG, "${trigger.key}: ${feature.name} failed", e)
                return@launch
            }
            deliver(trigger, outcome)
        }
    }

    private fun deliver(trigger: Trigger, outcome: Outcome) {
        when (outcome) {
            is Outcome.Silent -> {
                journal("${trigger.key}: silent (${outcome.why})")
                if (outcome.show) onDecision?.invoke("Stayed quiet: ${outcome.why}")
            }
            is Outcome.Notify -> {
                platform.notify(outcome.title, outcome.text)
                decision("Sent a notification: ${outcome.text} (${outcome.why})")
            }
            is Outcome.Speak -> {
                val spoke = output?.speak(outcome) ?: false
                if (spoke) {
                    journal("${trigger.key}: spoke (${outcome.why})")
                    onDecision?.invoke("Spoke up (${outcome.source}): ${outcome.why}")
                } else {
                    // Active mode off, a call, Do Not Disturb...: the reminder still reaches the user.
                    platform.notify(outcome.fallbackTitle, outcome.fallbackText)
                    journal("${trigger.key}: couldn't speak, notified instead (${outcome.why})")
                    onDecision?.invoke("Couldn't speak (call, Do Not Disturb or active mode off), so sent a notification: ${outcome.fallbackText}")
                }
            }
        }
    }

    /** One line per decision, with its reason: a shared log explains why Jarvis spoke or didn't. */
    internal fun journal(line: String) = JarvisLog.i(TAG, line)

    // ------------------------------------------------------------------ storage

    private fun wasHandled(key: String) = handled().containsKey(key)

    private fun markHandled(key: String) {
        val t = now
        val kept = handled().filterValues { t - it < HANDLED_KEEP_MS } + (key to t)
        store.set(HANDLED, JsonObject(kept.mapValues { JsonPrimitive(it.value) }).toString())
    }

    private fun handled(): Map<String, Long> = try {
        Json.parseToJsonElement(store.get(HANDLED) ?: "{}").jsonObject.mapValues { it.value.jsonPrimitive.long }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun savePending(list: List<Pending>) = store.set(PENDING, JsonArray(list.map {
        JsonObject(mapOf("id" to JsonPrimitive(it.id), "at" to JsonPrimitive(it.at), "payload" to JsonPrimitive(it.payload)))
    }).toString())

    private fun parse(text: String): List<Pending> = try {
        (Json.parseToJsonElement(text) as JsonArray).map {
            val o = it.jsonObject
            Pending(o["id"]!!.jsonPrimitive.content, o["at"]!!.jsonPrimitive.long, o["payload"]?.jsonPrimitive?.content ?: "")
        }
    } catch (e: Exception) {
        emptyList()
    }

    data class Pending(val id: String, val at: Long, val payload: String)

    companion object {
        private const val TAG = "Agent"
        private const val PENDING = "pending"
        private const val HANDLED = "handled"
        /** A wake-up that came due while the phone was off still fires if at most this late. */
        const val LATE_GRACE_MS = 15 * 60_000L
        private const val HANDLED_KEEP_MS = 7 * 24 * 60 * 60_000L
    }
}

/** What woke Jarvis. [key] is unique per occurrence, so the same one is never handled twice. */
sealed interface Trigger {
    val key: String

    data class Timer(override val key: String, val id: String, val payload: String, val scheduledAt: Long) : Trigger
}

/** What a feature decided, always with a reason for the journal. */
sealed interface Outcome {
    val why: String

    /** [show]: tell the user in the chat (Jarvis chose not to speak up); false for bookkeeping (already done...). */
    data class Silent(override val why: String, val show: Boolean = false) : Outcome
    data class Notify(val title: String, val text: String, override val why: String) : Outcome

    /** Start a voice conversation with [prompt] (Jarvis speaks first, prompted by [cue]); if that's not possible, notify. */
    data class Speak(
        val prompt: String, val cue: String, val fallbackTitle: String, val fallbackText: String,
        val source: String, override val why: String,
    ) : Outcome
}

/** A part of Jarvis that reacts to triggers (timed reminders now; goals next). */
interface Feature {
    val name: String
    fun handles(trigger: Trigger): Boolean
    suspend fun handle(trigger: Trigger): Outcome

    /** True if the timer [id] is this feature's. */
    fun owns(id: String): Boolean

    /** The timer [id] came due while the phone was off and is too late to fire. */
    fun missed(id: String, at: Long) {}
}

/** Wake-ups at a time (Android: exact alarms). Must survive Doze; the agent re-arms them after a reboot. */
interface Scheduler {
    fun schedule(id: String, atMillis: Long, payload: String)
    fun cancel(id: String)
}

/** Speaks an [Outcome.Speak] (Android: active mode). False when it can't right now (off, a call, Do Not Disturb). */
fun interface Output {
    fun speak(outcome: Outcome.Speak): Boolean
}

/** Time in the user's own time zone (Android: java.time). */
interface AgentClock {
    fun now(): Long

    /** "2026-11-18T18:00" in the user's time zone → epoch millis, or null if it isn't that format or doesn't exist. */
    fun parseLocal(text: String): Long?

    /** "Wed 18 Nov, 6:00 pm" */
    fun describe(millis: Long): String

    /** For prompts: "Wednesday 18 November 2026, 3:05 pm (Asia/Kolkata)" */
    fun nowForPrompt(): String

    /** Hour of day (0–23) in the user's time zone. */
    fun hour(millis: Long): Int

    /** The user's local date, "2026-11-18" (daily limits reset on it). */
    fun localDate(millis: Long): String
}

/** A clock in UTC: tests and platforms that don't provide their own. */
class UtcClock(private val nowMillis: () -> Long) : AgentClock {
    override fun now() = nowMillis()

    override fun parseLocal(text: String): Long? {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?$""").matchEntire(text.trim()) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        val s = m.groupValues[6].ifEmpty { "0" }.toInt()
        if (mo.toInt() !in 1..12 || d.toInt() !in 1..31 || h.toInt() > 23 || mi.toInt() > 59) return null
        return (daysFromCivil(y.toInt(), mo.toInt(), d.toInt()) * 86_400L + h.toInt() * 3600L + mi.toInt() * 60L + s) * 1000L
    }

    override fun describe(millis: Long): String {
        val days = millis.floorDiv(86_400_000L)
        val secs = millis.mod(86_400_000L) / 1000
        val (y, m, d) = civilFromDays(days)
        fun two(n: Long) = n.toString().padStart(2, '0')
        return "$y-${two(m.toLong())}-${two(d.toLong())} ${two(secs / 3600)}:${two(secs % 3600 / 60)} UTC"
    }

    override fun nowForPrompt() = describe(now())

    override fun hour(millis: Long) = (millis.mod(86_400_000L) / 3_600_000L).toInt()

    override fun localDate(millis: Long) = describe(millis).substringBefore(' ')

    // Howard Hinnant's civil date algorithms (proleptic Gregorian).
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = y.floorDiv(400)
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((yoe + era * 400 + if (m <= 2) 1 else 0).toInt(), m, d)
    }
}

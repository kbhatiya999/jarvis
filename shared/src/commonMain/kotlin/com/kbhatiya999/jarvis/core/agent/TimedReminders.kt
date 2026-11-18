package com.kbhatiya999.jarvis.core.agent

import com.kbhatiya999.jarvis.core.KeyValueStore
import com.kbhatiya999.jarvis.core.Prompts
import com.kbhatiya999.jarvis.core.Watchable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Timed reminders: "remind me at 6 to call Mom". When one is due, Jarvis says it (active mode) or, when it can't
 * speak, sends a notification. Not to be confused with the standing reminders (core/Reminders.kt: wellbeing
 * nudges on app open, no time). Goals with plans come next, on the same framework. Main thread only.
 */
class TimedReminders internal constructor(private val agent: Agent, private val store: KeyValueStore) : Feature {
    enum class Status { PENDING, SAID, CANCELLED, MISSED }

    data class Reminder(val id: String, val text: String, val at: Long, val status: Status)

    val items = Watchable(load())

    override val name = "timed reminders"

    /** Sets a reminder for [whenLocal] ("2026-11-18T18:00", the user's time). Returns it, or an error message. */
    fun add(text: String, whenLocal: String): Result {
        val t = text.trim()
        if (t.isEmpty()) return Result.Error("What should I remind you about?")
        val at = agent.clock.parseLocal(whenLocal) ?: return Result.Error("I couldn't read the time \"$whenLocal\"; use 2026-11-18T18:00.")
        if (at <= agent.now) return Result.Error("${agent.clock.describe(at)} has already passed.")
        if (pending().size >= MAX_PENDING) return Result.Error("You already have $MAX_PENDING timed reminders; cancel one first.")
        val r = Reminder(newId(), t.take(MAX_TEXT), at, Status.PENDING)
        save(items.value + r)
        agent.schedule(timerId(r.id), at)
        return Result.Ok(r)
    }

    /** Moves the reminder matching [what] to [whenLocal]. */
    fun move(what: String, whenLocal: String): Result {
        val r = find(what) ?: return notFound(what)
        val at = agent.clock.parseLocal(whenLocal) ?: return Result.Error("I couldn't read the time \"$whenLocal\".")
        if (at <= agent.now) return Result.Error("${agent.clock.describe(at)} has already passed.")
        val moved = r.copy(at = at)
        save(items.value.map { if (it.id == r.id) moved else it })
        agent.schedule(timerId(r.id), at)
        return Result.Ok(moved)
    }

    fun cancel(what: String): Result {
        val r = find(what) ?: return notFound(what)
        val cancelled = r.copy(status = Status.CANCELLED)
        save(items.value.map { if (it.id == r.id) cancelled else it })
        agent.cancel(timerId(r.id))
        return Result.Ok(cancelled)
    }

    /** Waiting reminders, soonest first. */
    fun pending(): List<Reminder> = items.value.filter { it.status == Status.PENDING }.sortedBy { it.at }

    /** For the prompt and list_timed_reminders: "call Mom at Wed 18 Nov, 6:00 pm; …" */
    fun describePending(): String =
        pending().joinToString("; ") { "\"${it.text}\" at ${agent.clock.describe(it.at)}" }.ifEmpty { "none" }

    // ------------------------------------------------------------------ Feature

    override fun owns(id: String) = id.startsWith(PREFIX)

    override fun handles(trigger: Trigger) = trigger is Trigger.Timer && owns(trigger.id)

    override fun handle(trigger: Trigger): Outcome {
        val id = (trigger as Trigger.Timer).id.removePrefix(PREFIX)
        val r = items.value.firstOrNull { it.id == id } ?: return Outcome.Silent("reminder $id no longer exists")
        if (r.status != Status.PENDING) return Outcome.Silent("reminder $id is ${r.status.name.lowercase()}")
        save(items.value.map { if (it.id == id) it.copy(status = Status.SAID) else it })
        val late = agent.now - r.at
        return Outcome.Speak(
            prompt = Prompts.timedReminder(r.text, agent.clock.nowForPrompt()),
            cue = "(The reminder is due now. Say it.)",
            fallbackTitle = "Reminder",
            fallbackText = r.text,
            source = "timed reminder",
            why = "due ${agent.clock.describe(r.at)}" + if (late > 60_000) " (${late / 60_000} min late)" else "",
        )
    }

    override fun missed(id: String, at: Long) {
        val rid = id.removePrefix(PREFIX)
        save(items.value.map { if (it.id == rid && it.status == Status.PENDING) it.copy(status = Status.MISSED) else it })
    }

    // ------------------------------------------------------------------ helpers

    sealed interface Result {
        data class Ok(val reminder: Reminder) : Result
        data class Error(val message: String) : Result
    }

    /** Exact text first, then a reminder containing [what] (or contained in it). Pending ones only. */
    private fun find(what: String): Reminder? {
        val w = what.trim().lowercase()
        if (w.isEmpty()) return null
        val p = pending()
        return p.firstOrNull { it.text.lowercase() == w } ?: p.firstOrNull { it.text.lowercase().contains(w) || w.contains(it.text.lowercase()) }
    }

    private fun notFound(what: String) = Result.Error("No waiting reminder matches \"$what\". Waiting: ${describePending()}.")

    private fun timerId(id: String) = PREFIX + id

    private fun newId() = agent.now.toString(36) + (0..2).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

    private fun save(list: List<Reminder>) {
        // Keep every waiting reminder, and only the latest finished ones.
        val (waiting, done) = list.partition { it.status == Status.PENDING }
        val kept = waiting + done.sortedBy { it.at }.takeLast(KEEP_DONE)
        items.value = kept
        store.set(KEY, JsonArray(kept.map {
            JsonObject(mapOf(
                "id" to JsonPrimitive(it.id), "text" to JsonPrimitive(it.text),
                "at" to JsonPrimitive(it.at), "status" to JsonPrimitive(it.status.name),
            ))
        }).toString())
    }

    private fun load(): List<Reminder> = try {
        (Json.parseToJsonElement(store.get(KEY) ?: "[]") as JsonArray).map {
            val o = it.jsonObject
            Reminder(
                o["id"]!!.jsonPrimitive.content, o["text"]!!.jsonPrimitive.content, o["at"]!!.jsonPrimitive.long,
                Status.valueOf(o["status"]!!.jsonPrimitive.content),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        private const val KEY = "items"
        private const val PREFIX = "reminder:"
        const val MAX_PENDING = 30
        const val MAX_TEXT = 300
        private const val KEEP_DONE = 20
    }
}

package com.kbhatiya999.jarvis.core.agent

import com.kbhatiya999.jarvis.core.JarvisLog
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the model is asked to do. */
enum class DecisionJob {
    /** A goal was just made: draft the conditions that must be true for it to happen. */
    PLAN,
    /** A checkpoint came up: speak to the user now, or stay quiet? */
    CHECKPOINT,
}

/** A condition the model proposes for a goal's plan, with when to check it ("12:00", the user's time). */
@Serializable
data class DraftCondition(val what: String, val checkAt: String, val reason: String = "")

/** The model's answer. Only the decider produces one; only the rules and the user can make it count. */
@Serializable
data class Decision(
    val speak: Boolean = false,
    /** What to say (CHECKPOINT), written to be spoken. */
    val text: String? = null,
    /** At most one question for the user. */
    val question: String? = null,
    /** Proposed conditions (PLAN). */
    val conditions: List<DraftCondition> = emptyList(),
    /** Why: always written to the journal. */
    val why: String,
)

/** The model behind decisions. Implementations may throw or be slow; [GuardedDecider] takes care of that. */
interface Decider {
    suspend fun decide(job: DecisionJob, context: String): Decision
}

/**
 * The circuit breaker around the model: the rules' daily budget, a time limit, and after [TRIP_AFTER] failures in a
 * row it stops calling for [COOL_OFF_MS]. Every failure becomes `Decision(speak = false, why = …)`, never an
 * exception, so a background wake-up can't crash and Jarvis simply stays quiet.
 */
class GuardedDecider(
    private val inner: () -> Decider?,
    private val rules: Rules,
    private val now: () -> Long,
    /** Tells the user when the model gets paused (shown in the chat). */
    private val onPause: (String) -> Unit = {},
) : Decider {
    private var failures = 0
    private var openUntil = 0L

    override suspend fun decide(job: DecisionJob, context: String): Decision {
        val d = inner() ?: return Decision(why = "no model set up")
        if (now() < openUntil) return Decision(why = "model paused after $TRIP_AFTER failures (until ${(openUntil - now()) / 60_000} min from now)")
        val budget = rules.think()
        if (!budget.allowed) return Decision(why = budget.why)
        val result = try {
            withTimeoutOrNull(TIMEOUT_MS) { d.decide(job, context) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JarvisLog.w("Agent", "model failed: ${e.message}")
            null
        }
        if (result == null) {
            failures++
            if (failures >= TRIP_AFTER) {
                openUntil = now() + COOL_OFF_MS
                failures = 0
                JarvisLog.w("Agent", "model failed $TRIP_AFTER times in a row: pausing it for ${COOL_OFF_MS / 60_000} min")
                onPause("Gemini didn't answer $TRIP_AFTER times in a row, so I'll stop asking it for ${COOL_OFF_MS / 60_000} minutes. Reminders you set still work.")
            }
            return Decision(why = "model unavailable")
        }
        failures = 0
        return result
    }

    companion object {
        const val TIMEOUT_MS = 15_000L
        const val TRIP_AFTER = 3
        const val COOL_OFF_MS = 30 * 60_000L
    }
}

/**
 * Gemini as the decider: one small text call with a fixed JSON answer. Tries a fast model first and a second one
 * when the first is busy (503 / 429). The free-tier key is the same one the voice uses.
 */
class GeminiDecider(private val apiKey: () -> String?) : Decider {
    private val http = HttpClient()

    override suspend fun decide(job: DecisionJob, context: String): Decision {
        val key = apiKey() ?: error("no API key")
        val busy = mutableListOf<String>()
        for (model in MODELS) {
            val body = JsonObject(mapOf(
                "systemInstruction" to JsonObject(mapOf("parts" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive(instructions(job)))))))),
                "contents" to JsonArray(listOf(JsonObject(mapOf(
                    "role" to JsonPrimitive("user"),
                    "parts" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive(context))))),
                )))),
                "generationConfig" to JsonObject(mapOf(
                    "responseMimeType" to JsonPrimitive("application/json"),
                    "responseSchema" to SCHEMA,
                    "temperature" to JsonPrimitive(0.3),
                )),
            ))
            val resp = http.post("$URL/$model:generateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val text = resp.bodyAsText()
            if (resp.status.value == 503 || resp.status.value == 429) {
                busy += "$model ${if (resp.status.value == 429) "over its free limit (429)" else "busy (503)"}"
                continue
            }
            if (resp.status.value !in 200..299) error("$model: HTTP ${resp.status.value}")
            val answer = RecordStore.json.parseToJsonElement(text).jsonObject["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }?.joinToString("")
                ?: error("$model: no answer")
            return RecordStore.json.decodeFromString(Decision.serializer(), answer)
        }
        error(busy.joinToString(", ").ifEmpty { "no model answered" })
    }

    private fun instructions(job: DecisionJob) = when (job) {
        DecisionJob.PLAN -> PLAN_INSTRUCTIONS
        DecisionJob.CHECKPOINT -> CHECKPOINT_INSTRUCTIONS
    }

    companion object {
        private const val URL = "https://generativelanguage.googleapis.com/v1beta/models"
        val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash")

        const val PLAN_INSTRUCTIONS = "You help a voice assistant, Jarvis, make sure its user achieves a goal by a " +
            "deadline. Propose the conditions that must be true beforehand for it to happen, each with the local time " +
            "(HH:MM, before the deadline) at which Jarvis should check it, at the latest moment it can still be fixed. " +
            "Propose no more conditions than the limit given. Only propose a condition tied to a real hurdle (from the " +
            "user's past attempts or notes) or one that is obviously needed (e.g. being free and ready to leave in " +
            "time); never generic advice. Put the hurdle or reason in reason. If one thing you don't know matters a lot, " +
            "put at most one short question for the user in question; otherwise leave it empty. speak and text are " +
            "unused here. Always explain your choice in why."

        const val CHECKPOINT_INSTRUCTIONS = "You help a voice assistant, Jarvis, make sure its user achieves a goal. " +
            "It's checkpoint time for one condition of the plan. Decide whether Jarvis should speak to the user now " +
            "(speak) or stay quiet because it isn't needed (the condition is already known to be fine, or speaking " +
            "wouldn't help). If speaking, write text: one or two short, warm sentences in character as Jarvis, " +
            "addressing the user as Sir, about this condition only. You may add at most one question in question. " +
            "Never nag or lecture. Always explain your choice in why."

        private val SCHEMA = RecordStore.json.parseToJsonElement("""
            {"type":"OBJECT","properties":{
              "speak":{"type":"BOOLEAN"},
              "text":{"type":"STRING"},
              "question":{"type":"STRING"},
              "conditions":{"type":"ARRAY","items":{"type":"OBJECT","properties":{
                "what":{"type":"STRING"},"checkAt":{"type":"STRING"},"reason":{"type":"STRING"}},
                "required":["what","checkAt"]}},
              "why":{"type":"STRING"}},
             "required":["why"]}
        """.trimIndent())
    }
}

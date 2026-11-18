package com.kbhatiya999.jarvis.core.agent

import com.kbhatiya999.jarvis.core.JarvisLog
import com.kbhatiya999.jarvis.core.KeyValueStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The agent's memory, as agreed in .scratch/plan-agent-framework.md: records (facts) and small, visible notes.
// Everything stays on the phone; only what the context builder picks is sent to Gemini.

/** A goal: something the user wants to get done by a time, which Jarvis helps make happen. */
@Serializable
data class Goal(
    val id: String,
    val what: String,
    val deadline: Long,
    val importance: Importance = Importance.NICE,
    /** What counts as done, in the user's words ("went to the gym"); empty = the goal itself. */
    val success: String = "",
    val status: GoalStatus = GoalStatus.ACTIVE,
    val conditions: List<Condition> = emptyList(),
    val notes: List<Note> = emptyList(),
    val createdAt: Long = 0,
    /** The repeating goal this is one occurrence of, if any. */
    val seriesId: String? = null,
)

/** A goal that repeats on set days at a set time ("gym Mon, Wed, Fri at 18:00"); each day gets its own [Goal]. */
@Serializable
data class Series(
    val id: String,
    val what: String,
    /** "18:00", the user's time. */
    val time: String,
    /** ISO days of the week: 1 = Monday … 7 = Sunday. */
    val days: List<Int>,
    val importance: Importance = Importance.NICE,
    val active: Boolean = true,
    val createdAt: Long = 0,
)

@Serializable
enum class Importance { IMPORTANT, NICE }

@Serializable
enum class GoalStatus {
    /** Before the deadline. */
    ACTIVE,
    /** The deadline passed; Jarvis doesn't know yet whether it happened. */
    DUE,
    DONE, SKIPPED, CANCELLED,
}

/** Something that must be true for a goal to happen ("bag packed"), checked before the deadline. */
@Serializable
data class Condition(
    val id: String,
    val what: String,
    val state: ConditionState = ConditionState.UNKNOWN,
    /** When Jarvis checks on it; null = not scheduled. */
    val checkAt: Long? = null,
    /** Why it's on the plan: the hurdle it fixes, in the user's words. */
    val reason: String = "",
)

@Serializable
enum class ConditionState { UNKNOWN, OK, AT_RISK }

/** One try at a goal and how it went. */
@Serializable
data class Attempt(
    val goalId: String,
    val at: Long,
    val outcome: Outcome,
    /** The condition that failed, if known. */
    val failedCondition: String? = null,
    /** What got in the way, in the user's words. */
    val reason: String? = null,
) {
    @Serializable
    enum class Outcome { DONE, SKIPPED, NO_ANSWER }
}

/** A short fact Jarvis keeps (per goal, or in the user profile), with where it came from. Visible and editable. */
@Serializable
data class Note(val text: String, val source: String, val at: Long)

/**
 * A list of records saved as JSON with a format version: `{"v": 2, "items": [...]}`. Older versions are upgraded
 * on load by [upgrades] (from version N to N+1, on the raw JSON); unreadable data is logged and starts empty rather
 * than crashing. Unknown fields are ignored, so a newer app's data still loads in an older one.
 */
class RecordStore<T>(
    private val store: KeyValueStore,
    private val key: String,
    private val serializer: KSerializer<T>,
    private val version: Int = 1,
    private val upgrades: Map<Int, (JsonArray) -> JsonArray> = emptyMap(),
) {
    fun load(): List<T> {
        val raw = store.get(key) ?: return emptyList()
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            var v = root["v"]?.jsonPrimitive?.int ?: 1
            var items = root["items"] as? JsonArray ?: JsonArray(emptyList())
            while (v < version) {
                items = upgrades[v]?.invoke(items) ?: items
                v++
            }
            json.decodeFromJsonElement(ListSerializer(serializer), items)
        } catch (e: Exception) {
            JarvisLog.e("Agent", "couldn't read saved $key; starting empty", e)
            emptyList()
        }
    }

    fun save(items: List<T>) {
        val array: JsonElement = json.encodeToJsonElement(ListSerializer(serializer), items)
        store.set(key, JsonObject(mapOf("v" to JsonPrimitive(version), "items" to array)).toString())
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

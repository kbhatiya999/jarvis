package com.kbhatiya999.jarvis.core

/**
 * What Jarvis looks after in active mode. Each user keeps their own list on their device,
 * edited in the app or by asking Jarvis ("remind me to stretch"). Main thread only.
 */
class Reminders internal constructor(private val store: KeyValueStore) {
    val items = Watchable(store.get(KEY)?.let { stringList(it) } ?: DEFAULTS)

    /** Returns false if it's blank or already there. */
    fun add(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || items.value.any { it.equals(t, ignoreCase = true) }) return false
        set(items.value + t)
        return true
    }

    fun removeAt(index: Int) {
        if (index in items.value.indices) set(items.value.filterIndexed { i, _ -> i != index })
    }

    /** Removes the reminders matching [text] (exact, else containing it). Returns what was removed. */
    fun remove(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val exact = items.value.filter { it.equals(t, ignoreCase = true) }
        val hits = exact.ifEmpty { items.value.filter { it.contains(t, ignoreCase = true) || t.contains(it, ignoreCase = true) } }
        if (hits.isNotEmpty()) set(items.value - hits.toSet())
        return hits
    }

    fun resetToDefaults() = set(DEFAULTS)

    private fun set(list: List<String>) {
        items.value = list
        store.set(KEY, stringListJson(list))
    }

    companion object {
        private const val KEY = "items"
        val DEFAULTS = listOf(
            "staying hydrated (drinking water regularly)",
            "avoiding junk and oily food",
            "keeping warm and not doing anything that could make them catch a cold",
            "being careful not to fall or get hurt by accident",
        )
    }
}

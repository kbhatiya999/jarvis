package com.kbhatiya999.jarvis

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray

/**
 * What Jarvis looks after in active mode. Each user keeps their own list on their device,
 * edited from the Reminders screen or by asking Jarvis ("remind me to stretch"). Main thread only.
 */
object Reminders {
    val items = mutableStateListOf<String>()
    private var prefs: android.content.SharedPreferences? = null

    private val DEFAULTS = listOf(
        "staying hydrated (drinking water regularly)",
        "avoiding junk and oily food",
        "keeping warm and not doing anything that could make them catch a cold",
        "being careful not to fall or get hurt by accident",
    )

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("jarvis_reminders", Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getString(KEY, null)
        if (saved == null) {
            items.addAll(DEFAULTS)
        } else {
            val arr = JSONArray(saved)
            items.addAll((0 until arr.length()).map { arr.getString(it) })
        }
    }

    /** Returns false if it's blank or already there. */
    fun add(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || items.any { it.equals(t, ignoreCase = true) }) return false
        items.add(t)
        save()
        return true
    }

    fun removeAt(index: Int) {
        if (index !in items.indices) return
        items.removeAt(index)
        save()
    }

    /** Removes the reminders matching [text] (exact, else containing it). Returns what was removed. */
    fun remove(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val exact = items.filter { it.equals(t, ignoreCase = true) }
        val hits = exact.ifEmpty { items.filter { it.contains(t, ignoreCase = true) || t.contains(it, ignoreCase = true) } }
        if (hits.isNotEmpty()) {
            items.removeAll(hits.toSet())
            save()
        }
        return hits
    }

    fun resetToDefaults() {
        items.clear()
        items.addAll(DEFAULTS)
        save()
    }

    private fun save() {
        prefs?.edit()?.putString(KEY, JSONArray(items.toList()).toString())?.apply()
    }

    private const val KEY = "items"
}

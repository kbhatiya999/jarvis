package com.kbhatiya999.jarvis

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class Contact(val name: String, val address: String)

/** A message from a Jarvis that isn't in your contacts yet. Accepting adds it and delivers the message. */
data class ContactRequest(val name: String, val address: String, val text: String, val time: Long)

/** Other people's Jarvises this one may message, and your own display name. Main thread only. */
object Contacts {
    val contacts = mutableStateListOf<Contact>()
    val requests = mutableStateListOf<ContactRequest>()
    var myName by mutableStateOf("")
        private set
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("jarvis_contacts", Context.MODE_PRIVATE)
        prefs = p
        myName = p.getString("my_name", "") ?: ""
        p.getString("contacts", null)?.let { s ->
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { contacts.add(Contact(it.getString("name"), it.getString("address"))) }
        }
        p.getString("requests", null)?.let { s ->
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) arr.getJSONObject(i).let {
                requests.add(ContactRequest(it.getString("name"), it.getString("address"), it.getString("text"), it.getLong("time")))
            }
        }
    }

    fun updateMyName(name: String) {
        myName = name.trim()
        prefs?.edit()?.putString("my_name", myName)?.apply()
    }

    /** Adds or renames a contact. Returns an error message, or null on success. */
    fun add(name: String, address: String): String? {
        val n = name.trim()
        val parsed = Identity.Address.parse(extractAddress(address) ?: address) ?: return "That isn't a Jarvis address. It starts with \"jarvis:\"."
        if (n.isEmpty()) return "Give the contact a name."
        contacts.removeAll { it.address == parsed.raw }
        contacts.add(Contact(n, parsed.raw))
        requests.removeAll { it.address == parsed.raw }
        save()
        return null
    }

    /** Text to send a friend so they can add this Jarvis. */
    fun shareText(myAddress: String) =
        "Add my Jarvis${if (myName.isNotBlank()) " ($myName)" else ""}: $myAddress"

    /** Pulls the address (and the name, if present) out of text a friend shared. */
    fun extractAddress(text: String) = Regex("jarvis:[A-Za-z0-9_.-]+").find(text)?.value

    fun extractName(text: String) = Regex("Add my Jarvis \\(([^)]{1,40})\\)").find(text)?.groupValues?.get(1)

    fun remove(contact: Contact) {
        contacts.remove(contact)
        save()
    }

    fun byAddress(address: String) = contacts.firstOrNull { it.address == address }

    /** Finds contacts by spoken name: exact match first, then partial. */
    fun find(name: String): List<Contact> {
        val n = name.trim().removeSuffix("'s Jarvis").removeSuffix("'s jarvis").removeSuffix("'s").trim()
        if (n.isEmpty()) return emptyList()
        return contacts.filter { it.name.equals(n, ignoreCase = true) }
            .ifEmpty { contacts.filter { it.name.contains(n, ignoreCase = true) || n.contains(it.name, ignoreCase = true) } }
    }

    fun addRequest(request: ContactRequest) {
        requests.removeAll { it.address == request.address && it.text == request.text }
        requests.add(request)
        while (requests.size > 20) requests.removeAt(0)
        save()
    }

    fun dismissRequest(request: ContactRequest) {
        requests.remove(request)
        save()
    }

    private fun save() {
        val c = JSONArray()
        contacts.forEach { c.put(JSONObject().put("name", it.name).put("address", it.address)) }
        val r = JSONArray()
        requests.forEach {
            r.put(JSONObject().put("name", it.name).put("address", it.address).put("text", it.text).put("time", it.time))
        }
        prefs?.edit()?.putString("contacts", c.toString())?.putString("requests", r.toString())?.apply()
    }
}

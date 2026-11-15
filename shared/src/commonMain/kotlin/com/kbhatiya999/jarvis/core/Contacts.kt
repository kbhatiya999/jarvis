package com.kbhatiya999.jarvis.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Other people's Jarvises this one may message, and your own display name. Main thread only. */
class Contacts internal constructor(private val store: KeyValueStore) {
    val contacts = Watchable(load("contacts") { Contact(it.str("name"), it.str("address")) })
    val requests = Watchable(load("requests") { ContactRequest(it.str("name"), it.str("address"), it.str("text"), it.long("time")) })
    val myName = Watchable(store.get("my_name") ?: "")

    fun updateMyName(name: String) {
        myName.value = name.trim()
        store.set("my_name", myName.value)
    }

    /** Adds or renames a contact. Returns an error message, or null on success. */
    fun add(name: String, address: String): String? {
        val n = name.trim()
        val parsed = Address.parse(extractAddress(address) ?: address) ?: return "That isn't a Jarvis address. It starts with \"jarvis:\"."
        if (n.isEmpty()) return "Give the contact a name."
        contacts.value = contacts.value.filter { it.address != parsed.raw } + Contact(n, parsed.raw)
        requests.value = requests.value.filter { it.address != parsed.raw }
        save()
        return null
    }

    fun remove(contact: Contact) {
        contacts.value = contacts.value - contact
        save()
    }

    fun byAddress(address: String) = contacts.value.firstOrNull { it.address == address }

    /** Finds contacts by spoken name: exact match first, then partial. */
    fun find(name: String): List<Contact> {
        val n = name.trim().removeSuffix("'s Jarvis").removeSuffix("'s jarvis").removeSuffix("'s").trim()
        if (n.isEmpty()) return emptyList()
        val all = contacts.value
        return all.filter { it.name.equals(n, ignoreCase = true) }
            .ifEmpty { all.filter { it.name.contains(n, ignoreCase = true) || n.contains(it.name, ignoreCase = true) } }
    }

    fun addRequest(request: ContactRequest) {
        requests.value = (requests.value.filter { !(it.address == request.address && it.text == request.text) } + request).takeLast(20)
        save()
    }

    fun dismissRequest(request: ContactRequest) {
        requests.value = requests.value - request
        save()
    }

    /** Text to send a friend so they can add this Jarvis. */
    fun shareText(myAddress: String) = "Add my Jarvis${if (myName.value.isNotBlank()) " (${myName.value})" else ""}: $myAddress"

    /** Pulls the address out of text a friend shared. */
    fun extractAddress(text: String) = Regex("jarvis:[A-Za-z0-9_.-]+").find(text)?.value

    /** Pulls the friend's name out of text they shared, if present. */
    fun extractName(text: String) = Regex("Add my Jarvis \\(([^)]{1,40})\\)").find(text)?.groupValues?.get(1)

    private fun <T> load(key: String, read: (JsonObject) -> T): List<T> = try {
        (parseJson(store.get(key) ?: "[]") as JsonArray).map { read(it as JsonObject) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save() {
        store.set("contacts", JsonArray(contacts.value.map {
            JsonObject(mapOf("name" to JsonPrimitive(it.name), "address" to JsonPrimitive(it.address)))
        }).toString())
        store.set("requests", JsonArray(requests.value.map {
            JsonObject(mapOf("name" to JsonPrimitive(it.name), "address" to JsonPrimitive(it.address),
                "text" to JsonPrimitive(it.text), "time" to JsonPrimitive(it.time)))
        }).toString())
    }
}

package com.kbhatiya999.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Functions Gemini may call during a conversation. Main thread only. */
object JarvisTools {
    val declarations: JSONArray = JSONArray().put(
        JSONObject().put(
            "functionDeclarations", JSONArray()
                .put(
                    fn(
                        "send_message",
                        "Send a message to another person's Jarvis, which will pass it on to them.",
                        "contact" to "Name of the contact, as listed in your contacts.",
                        "message" to "The message to deliver, written for the other person.",
                    )
                )
                .put(fn("add_reminder", "Add a standing reminder you will look after for the user.", "reminder" to "What to remind them about, e.g. \"stretching every hour\"."))
                .put(fn("remove_reminder", "Stop reminding the user about something.", "reminder" to "The reminder to remove, as listed."))
        )
    )

    /** Runs a call and hands the result to [reply], possibly later (sending goes over the network). */
    fun call(context: Context, name: String, args: JSONObject, reply: (JSONObject) -> Unit) {
        when (name) {
            "send_message" -> sendMessage(context, args.optString("contact"), args.optString("message"), reply)
            "add_reminder" -> {
                val r = args.optString("reminder")
                reply(if (Reminders.add(r)) ok("Added. Reminders now: ${Reminders.items.joinToString("; ")}") else error("Empty or already a reminder."))
            }
            "remove_reminder" -> {
                val removed = Reminders.remove(args.optString("reminder"))
                reply(
                    if (removed.isNotEmpty()) ok("Removed: ${removed.joinToString("; ")}")
                    else error("No such reminder. Current reminders: ${Reminders.items.joinToString("; ").ifEmpty { "none" }}")
                )
            }
            else -> reply(error("Unknown function $name"))
        }
    }

    private fun sendMessage(context: Context, contactName: String, text: String, reply: (JSONObject) -> Unit) {
        if (text.isBlank()) return reply(error("The message is empty."))
        val names = Contacts.contacts.joinToString(", ") { it.name }.ifEmpty { "none" }
        val matches = Contacts.find(contactName)
        when {
            matches.isEmpty() -> reply(error("No contact called \"$contactName\". Contacts: $names. New ones are added under Settings › Contacts."))
            matches.size > 1 -> reply(error("\"$contactName\" matches ${matches.joinToString(", ") { it.name }}; ask the user which one."))
            else -> {
                val contact = matches[0]
                Relay.send(context, contact, text.trim()) { err ->
                    if (err == null) {
                        Conversation.addSent(contact.name, text.trim())
                        reply(ok("Delivered to ${contact.name}'s Jarvis."))
                    } else {
                        reply(error("Could not send: $err"))
                    }
                }
            }
        }
    }

    private fun ok(result: String) = JSONObject().put("result", result)
    private fun error(message: String) = JSONObject().put("error", message)

    private fun fn(name: String, description: String, vararg params: Pair<String, String>): JSONObject {
        val props = JSONObject()
        params.forEach { (p, d) -> props.put(p, JSONObject().put("type", "STRING").put("description", d)) }
        return JSONObject().put("name", name).put("description", description).put(
            "parameters", JSONObject().put("type", "OBJECT").put("properties", props)
                .put("required", JSONArray(params.map { it.first }))
        )
    }
}

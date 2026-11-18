package com.kbhatiya999.jarvis.core

import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Functions Gemini may call during a conversation. Main thread only. */
class Tools internal constructor(private val jarvis: Jarvis) {
    val declarations: JsonArray = JsonArray(listOf(JsonObject(mapOf(
        "functionDeclarations" to JsonArray(listOf(
            fn("send_message", "Send a message to another person's Jarvis, which will pass it on to them.",
                "contact" to "Name of the contact, as listed in your contacts.",
                "message" to "The message to deliver, written for the other person."),
            fn("add_reminder", "Add a standing reminder you will look after for the user.",
                "reminder" to "What to remind them about, e.g. \"stretching every hour\"."),
            fn("remove_reminder", "Stop reminding the user about something.",
                "reminder" to "The reminder to remove, as listed."),
            fn(END_CONVERSATION, "End this conversation: the user wants you to stop or is done (\"stop\", \"that's " +
                "enough\", \"never mind\", \"goodbye\", \"power down\"). Say a very short acknowledgement first."),
        ))
    ))))

    /** Runs a call and hands the result to [reply], possibly later (sending goes over the network). */
    fun call(name: String, args: JsonObject, reply: (JsonObject) -> Unit) {
        val reminders = jarvis.reminders
        when (name) {
            "send_message" -> sendMessage(args.str("contact"), args.str("message"), reply)
            "add_reminder" -> reply(
                if (reminders.add(args.str("reminder"))) ok("Added. Reminders now: ${reminders.items.value.joinToString("; ")}")
                else error("Empty or already a reminder.")
            )
            "remove_reminder" -> {
                val removed = reminders.remove(args.str("reminder"))
                reply(
                    if (removed.isNotEmpty()) ok("Removed: ${removed.joinToString("; ")}")
                    else error("No such reminder. Current reminders: ${reminders.items.value.joinToString("; ").ifEmpty { "none" }}")
                )
            }
            else -> reply(error("Unknown function $name"))
        }
    }

    private fun sendMessage(contactName: String, text: String, reply: (JsonObject) -> Unit) {
        if (text.isBlank()) return reply(error("The message is empty."))
        val names = jarvis.contacts.contacts.value.joinToString(", ") { it.name }.ifEmpty { "none" }
        val matches = jarvis.contacts.find(contactName)
        when {
            matches.isEmpty() -> reply(error("No contact called \"$contactName\". Contacts: $names. New ones are added under Contacts in the app."))
            matches.size > 1 -> reply(error("\"$contactName\" matches ${matches.joinToString(", ") { it.name }}; ask the user which one."))
            else -> jarvis.scope.launch {
                val contact = matches[0]
                if (jarvis.bridge.isBridge(contact.address)) {
                    reply(error("${contact.name} is the Gemini app reporting back through Jarvis; it can't receive messages."))
                    return@launch
                }
                val err = jarvis.relay.send(contact, text.trim())
                if (err == null) {
                    jarvis.conversation.addSent(contact.name, text.trim())
                    reply(ok("Delivered to ${contact.name}'s Jarvis."))
                } else {
                    reply(error("Could not send: $err"))
                }
            }
        }
    }

    companion object {
        /** Handled by the session itself (it ends the session), not by [call]. */
        const val END_CONVERSATION = "end_conversation"
    }

    private fun ok(result: String) = JsonObject(mapOf("result" to JsonPrimitive(result)))
    private fun error(message: String) = JsonObject(mapOf("error" to JsonPrimitive(message)))

    private fun fn(name: String, description: String, vararg params: Pair<String, String>) = JsonObject(buildMap {
        put("name", JsonPrimitive(name))
        put("description", JsonPrimitive(description))
        if (params.isNotEmpty()) put("parameters", JsonObject(mapOf(
            "type" to JsonPrimitive("OBJECT"),
            "properties" to JsonObject(params.associate { (p, d) ->
                p to JsonObject(mapOf("type" to JsonPrimitive("STRING"), "description" to JsonPrimitive(d)))
            }),
            "required" to JsonArray(params.map { JsonPrimitive(it.first) }),
        )))
    })
}

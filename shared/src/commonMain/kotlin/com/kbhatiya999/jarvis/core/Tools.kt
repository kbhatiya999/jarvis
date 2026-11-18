package com.kbhatiya999.jarvis.core

import com.kbhatiya999.jarvis.core.agent.Goals
import com.kbhatiya999.jarvis.core.agent.Importance
import com.kbhatiya999.jarvis.core.agent.TimedReminders
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
            fn("add_reminder", "Add a standing reminder: something you look after in general, with no set time " +
                "(e.g. \"drink water\", \"stretching every hour\"). For a specific time, use set_timed_reminder.",
                "reminder" to "What to remind them about, e.g. \"stretching every hour\"."),
            fn("remove_reminder", "Stop a standing reminder.",
                "reminder" to "The reminder to remove, as listed."),
            fn("set_timed_reminder", "Remind the user of something at a set time: Jarvis says it when it's due.",
                "reminder" to "What to remind them, in their words, e.g. \"call Mom\".",
                "time" to "When it's due, local time, as YYYY-MM-DDTHH:MM, worked out from the current local time."),
            fn("move_timed_reminder", "Change when a timed reminder is due.",
                "reminder" to "The reminder, as the user calls it or as listed.",
                "time" to "The new local time, as YYYY-MM-DDTHH:MM."),
            fn("cancel_timed_reminder", "Cancel a timed reminder that hasn't come due yet.",
                "reminder" to "The reminder, as the user calls it or as listed."),
            fn("list_timed_reminders", "The timed reminders still to come, with their times."),
            fn("make_goal", "Help the user make something happen by a time (\"make sure I go to the gym at 6\"). " +
                "Use for things people often fail to do; for a plain nudge use set_timed_reminder.",
                "goal" to "The goal in the user's words, e.g. \"go to the gym\".",
                "time" to "When, local time, as YYYY-MM-DDTHH:MM, worked out from the current local time.",
                "importance" to "\"important\" or \"nice\" (nice to have); \"nice\" if they didn't say."),
            fn("move_goal", "Change a goal's time.",
                "goal" to "The goal, as the user calls it or as listed.",
                "time" to "The new local time, as YYYY-MM-DDTHH:MM."),
            fn("cancel_goal", "Drop a goal the user no longer wants.",
                "goal" to "The goal, as the user calls it or as listed."),
            fn("goal_done", "The user did the goal.",
                "goal" to "The goal, as the user calls it or as listed."),
            fn("skip_goal", "The user isn't doing or didn't do the goal this time. You need the reason first: if " +
                "they only said they can't, ask once what got in the way, then call this with their answer.",
                "goal" to "The goal, as the user calls it or as listed.",
                "reason" to "Why, in their words (\"it's raining\", \"too tired\"); \"didn't say\" if they won't say."),
            fn("list_goals", "The user's goals still going, with their times."),
            fn("answer_condition", "The user says something their goal needs is done or fine (\"bag's packed\"), or is a problem.",
                "goal" to "The goal, as the user calls it or as listed.",
                "condition" to "The condition, as listed or as the user puts it.",
                "ok" to "\"yes\" if it's done or fine, \"no\" if it's a problem."),
            fn(STAY_SILENT, "Say nothing: call this instead of speaking when what you heard wasn't meant for you " +
                "(the user talking to someone else, a TV, noise, a half-heard fragment). It makes no sound."),
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
            "set_timed_reminder" -> reply(timed(jarvis.agent.timedReminders.add(args.str("reminder"), args.str("time")), "Set"))
            "move_timed_reminder" -> reply(timed(jarvis.agent.timedReminders.move(args.str("reminder"), args.str("time")), "Moved"))
            "cancel_timed_reminder" -> reply(timed(jarvis.agent.timedReminders.cancel(args.str("reminder")), "Cancelled"))
            "list_timed_reminders" -> reply(ok("Timed reminders still to come: ${jarvis.agent.timedReminders.describePending()}"))
            "make_goal" -> makeGoal(args, reply)
            "answer_condition" -> reply(goal(jarvis.agent.goals.answer(args.str("goal"), args.str("condition"),
                !args.str("ok").trim().lowercase().startsWith("n")), "Noted"))
            "move_goal" -> reply(goal(jarvis.agent.goals.move(args.str("goal"), args.str("time")), "Moved"))
            "cancel_goal" -> reply(goal(jarvis.agent.goals.cancel(args.str("goal")), "Cancelled"))
            "goal_done" -> reply(goal(jarvis.agent.goals.done(args.str("goal")), "Marked done"))
            "skip_goal" -> reply(goal(jarvis.agent.goals.skip(args.str("goal"), args.str("reason")), "Skipped this time"))
            "list_goals" -> reply(ok("Goals: ${jarvis.agent.goals.describeActive()}"))
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

        /** Handled by the session itself (it mutes Jarvis until the user speaks again), not by [call]. */
        const val STAY_SILENT = "stay_silent"
    }

    private fun timed(r: TimedReminders.Result, verb: String) = when (r) {
        is TimedReminders.Result.Ok -> ok("$verb: \"${r.reminder.text}\" at ${jarvis.agent.clock.describe(r.reminder.at)}.")
        is TimedReminders.Result.Error -> error(r.message)
    }

    /** Makes the goal, then drafts its plan (a few seconds) so Jarvis can tell the user how it'll help. */
    private fun makeGoal(args: JsonObject, reply: (JsonObject) -> Unit) {
        val importance = if (args.str("importance").trim().lowercase().startsWith("imp")) Importance.IMPORTANT else Importance.NICE
        val made = jarvis.agent.goals.add(args.str("goal"), args.str("time"), importance)
        if (made !is Goals.Result.Ok) return reply(goal(made, "Goal set"))
        jarvis.scope.launch {
            val plan = jarvis.agent.goals.draftPlan(made.goal.id)
            val g = plan?.goal ?: made.goal
            val clock = jarvis.agent.clock
            val checks = g.conditions.joinToString("; ") { "\"${it.what}\" at ${clock.describe(it.checkAt!!)}" }
            reply(ok(buildString {
                append("Goal set: \"${g.what}\" by ${clock.describe(g.deadline)}. ")
                append(if (checks.isEmpty()) "No checks planned before it; you'll say it when it's time. " else "You'll check: $checks. ")
                plan?.question?.let { append("Ask the user, briefly: \"$it\" ") }
                append("Tell the user in one or two sentences that you'll help make sure it happens, and when you'll check.")
            }))
        }
    }

    private fun goal(r: Goals.Result, verb: String) = when (r) {
        is Goals.Result.Ok -> ok("$verb: \"${r.goal.what}\" by ${jarvis.agent.clock.describe(r.goal.deadline)}.")
        is Goals.Result.Error -> error(r.message)
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

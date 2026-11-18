package com.kbhatiya999.jarvis.core.agent

import com.kbhatiya999.jarvis.core.AudioIO
import com.kbhatiya999.jarvis.core.MemStore
import com.kbhatiya999.jarvis.core.Platform

/** A simulated phone: a clock we move by hand, alarms we fire by hand, notifications we can read. */
internal class Phone {
    var now = 1_794_960_000_000L // 2026-11-18 01:20 UTC
    val notifications = mutableListOf<Pair<String, String>>()
    val alarms = mutableMapOf<String, Long>()
    val spoken = mutableListOf<Outcome.Speak>()
    var canSpeak = true
    var inConversation = false
    var notificationsOn = true
    data class Ask(val question: OpenQuestion, val prompt: String, val how: Delivery)
    val asks = mutableListOf<Ask>()
    val dismissed = mutableListOf<String>()

    val platform = object : Platform {
        private val stores = mutableMapOf<String, MemStore>()
        override fun store(namespace: String) = stores.getOrPut(namespace) { MemStore() }
        override val secrets = MemStore()
        override fun readFile(name: String): String? = null
        override fun writeFile(name: String, text: String) {}
        override fun deleteFile(name: String) {}
        override fun notify(title: String, text: String) { notifications += title to text }
        override fun createAudio(speakOnly: Boolean): AudioIO = error("no audio")
        override fun nowMillis() = now
        override fun log(message: String) {}
    }

    /** A fresh agent on the same saved data, as after the app restarts. */
    /** What the fake model answers, per job; null = it fails. */
    var answer: (DecisionJob, String) -> Decision? = { _, _ -> Decision(why = "fake: nothing to say") }
    val asked = mutableListOf<Pair<DecisionJob, String>>()

    // Unconfined: coroutines run right away on the test thread, so a wake-up finishes before the next line.
    fun agent() = Agent(platform, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)).also { a ->
        a.decider = object : Decider {
            override suspend fun decide(job: DecisionJob, context: String): Decision {
                asked += job to context
                return answer(job, context) ?: error("fake model failure")
            }
        }
        a.scheduler = object : Scheduler {
            override fun schedule(id: String, atMillis: Long, payload: String) { alarms[id] = atMillis }
            override fun cancel(id: String) { alarms.remove(id) }
        }
        a.output = object : Output {
            override fun speak(outcome: Outcome.Speak) = if (canSpeak) { spoken += outcome; true } else false
            override fun ask(question: OpenQuestion, prompt: String, spoken: Boolean): Delivery {
                val how = when {
                    inConversation -> Delivery.JOINED_CONVERSATION
                    spoken && canSpeak -> Delivery.SPOKE
                    notificationsOn -> Delivery.NOTIFIED
                    else -> Delivery.NOT_DELIVERED
                }
                if (how != Delivery.NOT_DELIVERED) asks += Ask(question, prompt, how)
                return how
            }
            override fun dismiss(question: OpenQuestion) { dismissed += question.id }
        }
    }

    /** Moves the clock to [t] and fires every alarm due by then, like Android would. */
    fun advanceTo(agent: Agent, t: Long) {
        now = t
        alarms.filterValues { it <= t }.keys.toList().forEach { alarms.remove(it); agent.onAlarm(it) }
    }
}

internal fun at(clock: AgentClock, text: String) = clock.parseLocal(text)!!

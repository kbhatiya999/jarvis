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
    fun agent() = Agent(platform).also { a ->
        a.scheduler = object : Scheduler {
            override fun schedule(id: String, atMillis: Long, payload: String) { alarms[id] = atMillis }
            override fun cancel(id: String) { alarms.remove(id) }
        }
        a.output = Output { if (canSpeak) { spoken += it; true } else false }
    }

    /** Moves the clock to [t] and fires every alarm due by then, like Android would. */
    fun advanceTo(agent: Agent, t: Long) {
        now = t
        alarms.filterValues { it <= t }.keys.toList().forEach { alarms.remove(it); agent.onAlarm(it) }
    }
}

internal fun at(clock: AgentClock, text: String) = clock.parseLocal(text)!!

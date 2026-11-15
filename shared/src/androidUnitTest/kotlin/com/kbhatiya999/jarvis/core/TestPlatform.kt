package com.kbhatiya999.jarvis.core

class MemStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun set(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/** In-memory platform for tests. [now] is the clock. */
class FakePlatform(var now: Long = 1_000_000_000_000) : Platform {
    private val stores = mutableMapOf<String, MemStore>()
    override fun store(namespace: String) = stores.getOrPut(namespace) { MemStore() }
    override val secrets = MemStore()
    override fun readFile(name: String): String? = null
    override fun writeFile(name: String, text: String) {}
    override fun deleteFile(name: String) {}
    override fun notify(title: String, text: String) {}
    override fun createAudio(speakOnly: Boolean): AudioIO = error("no audio in tests")
    override fun nowMillis() = now
    override fun log(message: String) {}
}

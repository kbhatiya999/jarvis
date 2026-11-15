package com.kbhatiya999.jarvis.core

/** What each app (Android, iOS) provides to the shared Jarvis logic. */
interface Platform {
    /** A small key-value store (SharedPreferences / UserDefaults), one per namespace. */
    fun store(namespace: String): KeyValueStore

    /** Secrets (Keystore-encrypted prefs / Keychain): "api_key", "identity_private". */
    val secrets: KeyValueStore

    fun readFile(name: String): String?

    /** Replaces the file atomically. */
    fun writeFile(name: String, text: String)
    fun deleteFile(name: String)

    /** Shows a notification about an incoming message. */
    fun notify(title: String, text: String)

    /** A fresh audio device for one voice session. */
    /** Audio for one session. [speakOnly]: no mic, and mix with (duck) other audio, e.g. an iPhone check-in from the background. */
    fun createAudio(speakOnly: Boolean): AudioIO

    fun nowMillis(): Long
    fun log(message: String)
}

interface KeyValueStore {
    fun get(key: String): String?

    /** null removes the key. */
    fun set(key: String, value: String?)
}

/**
 * Mic and speaker for a voice session. Capture delivers 16 kHz mono 16-bit PCM chunks (on any
 * thread); play takes 24 kHz mono 16-bit PCM. All methods may be called from any thread.
 */
interface AudioIO {
    fun startCapture(onChunk: (ByteArray) -> Unit)
    fun stopCapture()
    fun play(pcm: ByteArray)

    /** Drops queued audio (the user interrupted). */
    fun flush()

    /** How much queued audio is still to be heard, in milliseconds. */
    fun remainingMs(): Long
    fun shutdown()
}

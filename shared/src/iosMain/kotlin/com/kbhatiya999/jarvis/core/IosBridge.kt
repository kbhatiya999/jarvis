package com.kbhatiya999.jarvis.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** Helpers for the Swift app: fast ByteArray <-> Data for audio, and callback-style calls. */
object IosBridge {
    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    fun toData(bytes: ByteArray): NSData = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }

    @OptIn(ExperimentalForeignApi::class)
    fun toBytes(data: NSData): ByteArray {
        val out = ByteArray(data.length.toInt())
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return out
    }

    /** Polls the relay (e.g. from a background refresh task); [done] gets true if it was reachable. */
    fun poll(done: (Boolean) -> Unit) {
        MainScope().launch { done(Jarvis.shared.relay.poll()) }
    }

    /** Sends [text] to a contact directly (not through Gemini); [done] gets an error or null. */
    fun send(contact: Contact, text: String, done: (String?) -> Unit) {
        MainScope().launch {
            val err = Jarvis.shared.relay.send(contact, text)
            if (err == null) Jarvis.shared.conversation.addSent(contact.name, text)
            done(err)
        }
    }
}

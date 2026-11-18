package com.kbhatiya999.jarvis.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lets the Gemini app talk through Jarvis. The Jarvis bridge (bridge/ in this repo) is an MCP server; Gemini
 * calls its tool `tell_jarvis` and the bridge sends the text to this Jarvis as an encrypted message from the
 * contact "Gemini", which Jarvis says out loud like any message.
 *
 * Pairing makes a key pair for the bridge, adds its address as the contact "Gemini" and builds the link
 * `<bridge>/mcp/<token>`, where the token carries this Jarvis's address and the bridge's private key (the bridge
 * stores nothing). Anyone with the link can make Jarvis speak as "Gemini", so [newLink] replaces the contact,
 * which turns the old link off at once. The token format is shared with bridge/src/bridge.js.
 */
class GeminiBridge internal constructor(private val jarvis: Jarvis) {
    private val store = jarvis.platform.store("gemini_bridge")

    /** The bridge address the app was built with (release: the deployed bridge). Set by the platform. */
    var defaultUrl: String = ""

    /** The bridge in use: the user's own if they set one, else [defaultUrl]. */
    val baseUrl: String get() = (store.get("url")?.takeIf { it.isNotBlank() } ?: defaultUrl).trimEnd('/')

    /** The user's own bridge address, or "" to use the default. */
    var customUrl: String
        get() = store.get("url") ?: ""
        set(value) = store.set("url", value.trim().trimEnd('/').ifEmpty { null })

    /** The contact the bridge speaks as, when paired. */
    val contact: Contact? get() = store.get("address")?.let { jarvis.contacts.byAddress(it) }

    val connected: Boolean get() = contact != null && jarvis.platform.secrets.get(SECRET) != null

    /** True for messages from the bridge (they can't be replied to). */
    fun isBridge(address: String) = address == store.get("address")

    /** The link to paste into Gemini (pairs first if needed), or null when there is no bridge address. */
    fun link(): String? {
        if (baseUrl.isEmpty()) return null
        if (!connected) pair()
        val token = jarvis.platform.secrets.get(SECRET) ?: return null
        return "$baseUrl/mcp/$token"
    }

    /** Turns the old link off and makes a new one. */
    fun newLink(): String? {
        disconnect()
        return link()
    }

    /** Removes the Gemini contact and forgets the link: the bridge can no longer reach this Jarvis. */
    fun disconnect() {
        contact?.let { jarvis.contacts.remove(it) }
        store.set("address", null)
        jarvis.platform.secrets.set(SECRET, null)
        JarvisLog.i(TAG, "Gemini link removed")
    }

    private fun pair() {
        contact?.let { jarvis.contacts.remove(it) }
        val (address, privateDer) = Identity.newDetached()
        jarvis.contacts.add(NAME, address)?.let { error("bridge contact rejected: $it") }
        store.set("address", address)
        jarvis.platform.secrets.set(SECRET, token(jarvis.identity.address, address, privateDer))
        JarvisLog.i(TAG, "new Gemini link made")
    }

    companion object {
        const val NAME = "Gemini"
        private const val SECRET = "gemini_bridge_token"
        private const val TAG = "Bridge"

        /** base64url(JSON {v, to, from, k}); read by parseToken in bridge/src/bridge.js. */
        fun token(to: String, from: String, privateDer: ByteArray): String = B64.url(
            JsonObject(mapOf(
                "v" to JsonPrimitive(1), "to" to JsonPrimitive(to), "from" to JsonPrimitive(from),
                "k" to JsonPrimitive(B64.url(privateDer)),
            )).toString().encodeToByteArray()
        )

        /** What to save in a Gem so Gemini reports back to Jarvis. */
        const val GEM_INSTRUCTION = "When you finish a task I started from Jarvis, or when I ask you to tell Jarvis " +
            "something, call the tell_jarvis tool with a short summary written to be spoken: one to three plain " +
            "sentences, no markdown or links."
    }
}

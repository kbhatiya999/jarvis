package com.kbhatiya999.jarvis.core

import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Jarvis bridge (bridge/src/bridge.js, WebCrypto) and the apps (Kotlin) must agree on the token and the
 * message format. bridge-fixture.json was written by bridge/test/make-fixture.js.
 */
class BridgeTest {
    private val fixture: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("bridge-fixture.json")!!.readText()
    ).jsonObject

    private fun f(key: String) = fixture[key]!!.jsonPrimitive.content

    @Test
    fun jarvisDecryptsWhatTheBridgeSent() {
        val platform = FakePlatform()
        val address = f("userAddress")
        platform.store("jarvis_identity").set("topic", f("userTopic"))
        platform.store("jarvis_identity").set("pub", address.substringAfter('.'))
        platform.secrets.set("identity_private", f("userPrivatePkcs8"))
        val identity = Identity.load(platform)
        assertEquals(address, identity.address, "the fixture's key must load, not be replaced")

        val (from, plain) = assertNotNull(identity.decrypt(fixture["envelope"]!!.jsonObject))
        assertEquals(f("bridgeAddress"), from.raw)
        assertEquals(f("payload"), plain)
    }

    @Test
    fun bridgeKeysAreTheSameFormatBothWays() {
        // The bridge's key from WebCrypto (PKCS#8) loads as an identity key here...
        val platform = FakePlatform()
        val bridge = f("bridgeAddress")
        platform.store("jarvis_identity").set("topic", bridge.removePrefix("jarvis:").substringBefore('.'))
        platform.store("jarvis_identity").set("pub", bridge.substringAfter('.'))
        platform.secrets.set("identity_private", f("bridgePrivatePkcs8"))
        assertEquals(bridge, Identity.load(platform).address)

        // ...and the key Kotlin makes for a new bridge is plain PKCS#8, what WebCrypto imports.
        val (address, der) = Identity.newDetached()
        assertNotNull(Address.parse(address))
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    @Test
    fun tokenHasTheFieldsTheBridgeReads() {
        val (from, der) = Identity.newDetached()
        val to = f("userAddress")
        val token = GeminiBridge.token(to, from, der)
        assert(Regex("[A-Za-z0-9_-]+").matches(token)) { "token must be URL-safe" }
        val json = Json.parseToJsonElement(B64.unurl(token).decodeToString()).jsonObject
        assertEquals(1, json["v"]!!.jsonPrimitive.content.toInt())
        assertEquals(to, json["to"]!!.jsonPrimitive.content)
        assertEquals(from, json["from"]!!.jsonPrimitive.content)
        assertEquals(der.toList(), B64.unurl(json["k"]!!.jsonPrimitive.content).toList())
    }
}

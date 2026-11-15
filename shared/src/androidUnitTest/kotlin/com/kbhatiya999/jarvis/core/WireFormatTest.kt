package com.kbhatiya999.jarvis.core

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The shared messaging crypto must stay compatible with Jarvis 0.6.0 (Android, plain JCA), and
 * therefore with every other copy. [Legacy] is the 0.6.0 implementation, copied verbatim.
 */
class WireFormatTest {
    /** Jarvis 0.6.0's Identity (Android), minus the Keystore storage. */
    private class Legacy {
        val topic = "jv_" + url(ByteArray(18).also { SecureRandom().nextBytes(it) })
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val address = "jarvis:$topic." + url(pair.public.encoded)

        fun encryptFor(recipient: String, plaintext: String): String {
            val (rTopic, rKey) = recipient.removePrefix("jarvis:").split(".", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, shared(pub(rKey)), GCMParameterSpec(128, iv))
            cipher.updateAAD("$address>$rTopic".toByteArray())
            val ct = cipher.doFinal(plaintext.toByteArray())
            return """{"v":1,"from":"$address","iv":"${url(iv)}","ct":"${url(ct)}"}"""
        }

        fun decrypt(from: String, iv: String, ct: String): String {
            val key = from.removePrefix("jarvis:").split(".", limit = 2)[1]
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, shared(pub(key)), GCMParameterSpec(128, unurl(iv)))
            cipher.updateAAD("$from>$topic".toByteArray())
            return String(cipher.doFinal(unurl(ct)))
        }

        private fun pub(b64: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unurl(b64)))
        private fun shared(peer: PublicKey): SecretKeySpec {
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(pair.private as PrivateKey)
            ka.doPhase(peer, true)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(ka.generateSecret())
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update("jarvis-message-v1".toByteArray())
            mac.update(1)
            return SecretKeySpec(mac.doFinal(), "AES")
        }
    }

    @Test
    fun legacyToShared() {
        val legacy = Legacy()
        val me = Identity.load(FakePlatform())
        val env = parseJson(legacy.encryptFor(me.address, "hello from 0.6.0")) as kotlinx.serialization.json.JsonObject
        val (from, text) = assertNotNull(me.decrypt(env))
        assertEquals(legacy.address, from.raw)
        assertEquals("hello from 0.6.0", text)
    }

    @Test
    fun sharedToLegacy() {
        val legacy = Legacy()
        val me = Identity.load(FakePlatform())
        val env = me.encryptFor(Address.parse(legacy.address)!!, "hello from shared")
        assertEquals("hello from shared", legacy.decrypt(env.str("from"), env.str("iv"), env.str("ct")))
    }

    @Test
    fun identitySurvivesReload() {
        val platform = FakePlatform()
        assertEquals(Identity.load(platform).address, Identity.load(platform).address)
    }

    @Test
    fun tamperedMessageIsRejected() {
        val a = Identity.load(FakePlatform())
        val b = Identity.load(FakePlatform())
        val env = a.encryptFor(Address.parse(b.address)!!, "secret").toMutableMap()
        env["ct"] = kotlinx.serialization.json.JsonPrimitive(env.getValue("ct").toString().trim('"').reversed())
        assertNull(b.decrypt(kotlinx.serialization.json.JsonObject(env)))
    }

    private companion object {
        fun url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        fun unurl(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
    }
}

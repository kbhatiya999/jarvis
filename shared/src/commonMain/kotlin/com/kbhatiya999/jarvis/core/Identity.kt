package com.kbhatiya999.jarvis.core

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val crypto get() = CryptographyProvider.Default
private val ecdh get() = crypto.get(ECDH)

/**
 * This Jarvis's address on the relay, shared with friends so their Jarvis can message it.
 *
 * Address = "jarvis:<inbox topic>.<public key>". The inbox topic is a random relay channel; the
 * public key (EC P-256, X.509 DER, base64url) lets the sender encrypt. Messages are AES-256-GCM
 * encrypted with HKDF-SHA256(ECDH(sender, recipient), info "jarvis-message-v1"), so only the two
 * of them can read a message, and one that decrypts with a contact's key came from that contact.
 * The format is shared by the Android and iOS apps; changing it breaks messaging between versions.
 */
class Identity private constructor(
    val topic: String,
    private val publicDer: ByteArray,
    private val privateKey: ECDH.PrivateKey,
) {
    val address: String = PREFIX + topic + "." + B64.url(publicDer)

    @OptIn(DelicateCryptographyApi::class)
    fun encryptFor(recipient: Address, plaintext: String): JsonObject {
        val iv = CryptographyRandom.nextBytes(12)
        val ct = cipher(recipient.publicDer).encryptWithIvBlocking(iv, plaintext.encodeToByteArray(), aad(address, recipient.topic))
        return JsonObject(mapOf(
            "v" to JsonPrimitive(1), "from" to JsonPrimitive(address),
            "iv" to JsonPrimitive(B64.url(iv)), "ct" to JsonPrimitive(B64.url(ct)),
        ))
    }

    /** The sender's address and the plaintext, or null if it isn't a valid message for us. */
    @OptIn(DelicateCryptographyApi::class)
    fun decrypt(envelope: JsonObject): Pair<Address, String>? = try {
        val from = Address.parse(envelope.str("from"))!!
        val plain = cipher(from.publicDer).decryptWithIvBlocking(B64.unurl(envelope.str("iv")), B64.unurl(envelope.str("ct")), aad(from.raw, topic))
        from to plain.decodeToString()
    } catch (e: Exception) {
        null
    }

    private fun cipher(peerDer: ByteArray): dev.whyoleg.cryptography.operations.IvAuthenticatedCipher {
        val peer = ecdh.publicKeyDecoder(EC.Curve.P256).decodeFromByteArrayBlocking(EC.PublicKey.Format.DER, peerDer)
        val shared = privateKey.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(peer)
        val keyBytes = crypto.get(HKDF).secretDerivation(SHA256, 256.bits, ByteArray(32), "jarvis-message-v1".encodeToByteArray())
            .deriveSecretToByteArrayBlocking(shared)
        return crypto.get(AES.GCM).keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, keyBytes).cipher()
    }

    companion object {
        internal const val PREFIX = "jarvis:"

        /** Loads this device's identity, creating it on first use. Kept in store "jarvis_identity" + secret "identity_private". */
        internal fun load(platform: Platform): Identity {
            val store = platform.store("jarvis_identity")
            try {
                val topic = store.get("topic")!!
                val pub = B64.unurl(store.get("pub")!!)
                val priv = ecdh.privateKeyDecoder(EC.Curve.P256)
                    .decodeFromByteArrayBlocking(EC.PrivateKey.Format.DER, B64.unurl(platform.secrets.get("identity_private")!!))
                return Identity(topic, pub, priv)
            } catch (e: Exception) {
                // First run, or restored from a backup on another device: make a new one.
            }
            val pair = ecdh.keyPairGenerator(EC.Curve.P256).generateKeyBlocking()
            val pub = pair.publicKey.encodeToByteArrayBlocking(EC.PublicKey.Format.DER)
            val topic = "jv_" + B64.url(CryptographyRandom.nextBytes(18))
            platform.secrets.set("identity_private", B64.url(pair.privateKey.encodeToByteArrayBlocking(EC.PrivateKey.Format.DER)))
            store.set("topic", topic)
            store.set("pub", B64.url(pub))
            return Identity(topic, pub, pair.privateKey)
        }

        private fun aad(senderAddress: String, recipientTopic: String) = "$senderAddress>$recipientTopic".encodeToByteArray()

        /**
         * A fresh identity that lives elsewhere (the Gemini bridge): its address, and its private key as PKCS#8
         * DER (what WebCrypto imports). Nothing is stored.
         */
        fun newDetached(): Pair<String, ByteArray> {
            val pair = ecdh.keyPairGenerator(EC.Curve.P256).generateKeyBlocking()
            val pub = pair.publicKey.encodeToByteArrayBlocking(EC.PublicKey.Format.DER)
            val topic = "jv_" + B64.url(CryptographyRandom.nextBytes(18))
            return (PREFIX + topic + "." + B64.url(pub)) to pair.privateKey.encodeToByteArrayBlocking(EC.PrivateKey.Format.DER)
        }
    }
}

/** A parsed friend's address. */
class Address private constructor(val raw: String, val topic: String, internal val publicDer: ByteArray) {
    companion object {
        private val TOPIC = Regex("jv_[A-Za-z0-9_-]{16,40}")

        fun parse(text: String): Address? = try {
            val s = text.trim()
            require(s.startsWith(Identity.PREFIX))
            val (topic, key) = s.removePrefix(Identity.PREFIX).split(".", limit = 2)
            require(TOPIC.matches(topic))
            val der = B64.unurl(key)
            ecdh.publicKeyDecoder(EC.Curve.P256).decodeFromByteArrayBlocking(EC.PublicKey.Format.DER, der) // validates
            Address(s, topic, der)
        } catch (e: Exception) {
            null
        }
    }
}

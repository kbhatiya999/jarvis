package com.kbhatiya999.jarvis

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * This Jarvis's address on the relay, shared with friends so their Jarvis can message it.
 *
 * Address = "jarvis:<inbox topic>.<public key>". The inbox topic is a random relay channel; the
 * public key (EC P-256) lets the sender encrypt. Messages are encrypted with a key derived from
 * ECDH between the sender's and recipient's keys, so only the two of them can read a message,
 * and a message that decrypts with a contact's key can only have come from that contact.
 */
class Identity private constructor(
    val topic: String,
    private val publicKey: PublicKey,
    private val privateKey: PrivateKey,
) {
    val address: String = PREFIX + topic + "." + b64url(publicKey.encoded)

    fun encryptFor(recipient: Address, plaintext: String): JSONObject {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sharedKey(recipient.publicKey), GCMParameterSpec(128, randomBytes(12)))
        cipher.updateAAD(aad(address, recipient.topic))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return JSONObject().put("v", 1).put("from", address).put("iv", b64url(cipher.iv)).put("ct", b64url(ct))
    }

    /** The sender's address and the plaintext, or null if it isn't a valid message for us. */
    fun decrypt(envelope: JSONObject): Pair<Address, String>? = try {
        val from = Address.parse(envelope.getString("from"))!!
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, sharedKey(from.publicKey), GCMParameterSpec(128, unb64url(envelope.getString("iv"))))
        cipher.updateAAD(aad(from.raw, topic))
        from to String(cipher.doFinal(unb64url(envelope.getString("ct"))), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun sharedKey(peer: PublicKey): SecretKey {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peer, true)
        return SecretKeySpec(hkdf(ka.generateSecret(), "jarvis-message-v1".toByteArray()), "AES")
    }

    /** A parsed friend's address. */
    class Address(val raw: String, val topic: String, val publicKey: PublicKey) {
        companion object {
            fun parse(text: String): Address? = try {
                val s = text.trim()
                require(s.startsWith(PREFIX))
                val (topic, key) = s.removePrefix(PREFIX).split(".", limit = 2)
                require(TOPIC.matches(topic))
                val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64url(key)))
                Address(s, topic, pub)
            } catch (e: Exception) {
                null
            }
        }
    }

    companion object {
        private const val PREFIX = "jarvis:"
        private val TOPIC = Regex("jv_[A-Za-z0-9_-]{16,40}")
        private const val VAULT_ALIAS = "jarvis_identity_key"
        @Volatile private var cached: Identity? = null

        /** Loads this device's identity, creating it on first use. */
        @Synchronized
        fun get(context: Context): Identity {
            cached?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences("jarvis_identity", Context.MODE_PRIVATE)
            val loaded = try {
                val topic = prefs.getString("topic", null)!!
                val kf = KeyFactory.getInstance("EC")
                val pub = kf.generatePublic(X509EncodedKeySpec(unb64url(prefs.getString("pub", null)!!)))
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(open(prefs.getString("priv", null)!!)))
                Identity(topic, pub, priv)
            } catch (e: Exception) {
                null // first run, or restored from a backup on another device: make a new one
            }
            val id = loaded ?: run {
                val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
                val pair = gen.generateKeyPair()
                val topic = "jv_" + b64url(randomBytes(18))
                prefs.edit()
                    .putString("topic", topic)
                    .putString("pub", b64url(pair.public.encoded))
                    .putString("priv", seal(pair.private.encoded))
                    .apply()
                Identity(topic, pair.public, pair.private)
            }
            cached = id
            return id
        }

        private fun aad(senderAddress: String, recipientTopic: String) =
            "$senderAddress>$recipientTopic".toByteArray(Charsets.UTF_8)

        private fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(info)
            mac.update(1)
            return mac.doFinal() // 32 bytes: AES-256
        }

        // The private key is stored encrypted with a key that lives in the Android Keystore.
        private fun seal(data: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, vaultKey())
            return b64url(cipher.iv) + "." + b64url(cipher.doFinal(data))
        }

        private fun open(sealed: String): ByteArray {
            val (iv, ct) = sealed.split(".", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, vaultKey(), GCMParameterSpec(128, unb64url(iv)))
            return cipher.doFinal(unb64url(ct))
        }

        private fun vaultKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(VAULT_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(VAULT_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            return gen.generateKey()
        }

        private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
        private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        private fun unb64url(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

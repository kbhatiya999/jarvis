package com.kbhatiya999.jarvis

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the Gemini API key on the device only, encrypted with a key held in the
 * Android Keystore (the key itself never leaves secure hardware where available).
 */
class ApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_secure", Context.MODE_PRIVATE)

    fun get(): String? {
        val ct = prefs.getString(CT, null) ?: return null
        val iv = prefs.getString(IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, b64(iv)))
            String(cipher.doFinal(b64(ct)), Charsets.UTF_8)
        } catch (e: Exception) {
            null // e.g. restored from backup on another device: ask for the key again
        }
    }

    fun set(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun b64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val ALIAS = "jarvis_api_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val CT = "ct"
        const val IV = "iv"
    }
}

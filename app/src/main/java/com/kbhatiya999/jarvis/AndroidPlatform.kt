package com.kbhatiya999.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.kbhatiya999.jarvis.core.JarvisLog
import androidx.core.app.NotificationCompat
import com.kbhatiya999.jarvis.core.AudioIO
import com.kbhatiya999.jarvis.core.Jarvis
import com.kbhatiya999.jarvis.core.KeyValueStore
import com.kbhatiya999.jarvis.core.Platform
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android side of the shared Jarvis logic. Storage keeps the layout of earlier versions
 * (same SharedPreferences files, keys and Keystore aliases), so updating keeps your API key,
 * memory, contacts and Jarvis address.
 */
class AndroidPlatform private constructor(private val context: Context) : Platform {
    override fun store(namespace: String): KeyValueStore {
        val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        return object : KeyValueStore {
            override fun get(key: String) = prefs.getString(key, null)
            override fun set(key: String, value: String?) {
                prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        }
    }

    override val secrets: KeyValueStore = object : KeyValueStore {
        // api_key: prefs "jarvis_secure" (ct, iv), Keystore alias "jarvis_api_key".
        // identity_private: prefs "jarvis_identity" key "priv" as "iv.ct" (base64url), alias "jarvis_identity_key";
        //   the plaintext is the PKCS8 private key, which the shared code passes as base64url.
        override fun get(key: String): String? = try {
            when (key) {
                "api_key" -> {
                    val p = context.getSharedPreferences("jarvis_secure", Context.MODE_PRIVATE)
                    val ct = p.getString("ct", null) ?: return null
                    val iv = p.getString("iv", null) ?: return null
                    String(decrypt("jarvis_api_key", b64(iv, Base64.NO_WRAP), b64(ct, Base64.NO_WRAP)), Charsets.UTF_8)
                }
                "identity_private" -> {
                    val sealed = context.getSharedPreferences("jarvis_identity", Context.MODE_PRIVATE).getString("priv", null) ?: return null
                    val (iv, ct) = sealed.split(".", limit = 2)
                    Base64.encodeToString(decrypt("jarvis_identity_key", b64(iv, URL), b64(ct, URL)), URL)
                }
                else -> null
            }
        } catch (e: Exception) {
            null // e.g. restored from a backup on another device
        }

        override fun set(key: String, value: String?) {
            when (key) {
                "api_key" -> {
                    val p = context.getSharedPreferences("jarvis_secure", Context.MODE_PRIVATE).edit()
                    if (value == null) p.remove("ct").remove("iv").apply()
                    else {
                        val (iv, ct) = encrypt("jarvis_api_key", value.toByteArray(Charsets.UTF_8))
                        p.putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                            .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
                    }
                }
                "identity_private" -> {
                    val p = context.getSharedPreferences("jarvis_identity", Context.MODE_PRIVATE).edit()
                    if (value == null) p.remove("priv").apply()
                    else {
                        val (iv, ct) = encrypt("jarvis_identity_key", Base64.decode(value, URL))
                        p.putString("priv", Base64.encodeToString(iv, URL) + "." + Base64.encodeToString(ct, URL)).apply()
                    }
                }
            }
        }
    }

    override fun readFile(name: String): String? = File(context.filesDir, name).takeIf { it.exists() }?.readText()

    override fun writeFile(name: String, text: String) {
        val tmp = File(context.filesDir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(context.filesDir, name))
    }

    override fun deleteFile(name: String) {
        File(context.filesDir, name).delete()
    }

    override fun notify(title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages from other Jarvises", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100_000).toInt() + 100, n)
    }

    override fun createAudio(speakOnly: Boolean): AudioIO = AndroidAudio() // active mode opens the mic itself
    override fun nowMillis() = System.currentTimeMillis()
    override fun log(message: String) {
        JarvisLog.i("Platform", message)
    }

    private fun key(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(alias: String, data: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(alias))
        return c.iv to c.doFinal(data)
    }

    private fun decrypt(alias: String, iv: ByteArray, ct: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, iv))
        return c.doFinal(ct)
    }

    private fun b64(s: String, flags: Int) = Base64.decode(s, flags)

    companion object {
        private const val CHANNEL = "messages"
        private const val URL = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

        /** The shared Jarvis for this process; safe to call from anywhere on the main thread. */
        fun jarvis(context: Context): Jarvis {
            AppLog.init(context)
            return Jarvis.setUp(AndroidPlatform(context.applicationContext)).also {
                it.languageCode = AppLog.languageCode()
                it.bridge.defaultUrl = BuildConfig.BRIDGE_URL
            }
        }
    }
}

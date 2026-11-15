package com.kbhatiya999.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Carries encrypted messages between Jarvises through ntfy.sh, a free public pub/sub relay
 * (no account). Each Jarvis has a random inbox topic; the relay only ever sees ciphertext.
 * The relay keeps messages for about 12 hours, so a phone that is offline longer misses them.
 *
 * Messages arrive three ways: a live stream while the app or active mode is running, a poll
 * whenever the app opens, and a background check every 15 minutes (WorkManager).
 */
object Relay {
    private const val TAG = "JarvisRelay"
    const val SERVER = "https://ntfy.sh"
    const val MAX_TEXT = 2000

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val streamHttp = http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build() // ntfy sends keepalives every 45 s

    /** Encrypts [text] for [contact] and posts it. [done] runs on the main thread with an error, or null. */
    fun send(context: Context, contact: Contact, text: String, done: (String?) -> Unit) {
        val app = context.applicationContext
        val myName = Contacts.myName.ifBlank { "A friend" }
        Thread {
            val error = try {
                val to = Identity.Address.parse(contact.address) ?: throw IllegalStateException("bad address")
                val payload = JSONObject().put("name", myName).put("text", text.take(MAX_TEXT)).put("t", System.currentTimeMillis())
                val body = Identity.get(app).encryptFor(to, payload.toString()).toString()
                val req = Request.Builder().url("$SERVER/${to.topic}")
                    .post(body.toRequestBody("text/plain".toMediaType())).build()
                http.newCall(req).execute().use { if (it.isSuccessful) null else "relay returned HTTP ${it.code}" }
            } catch (e: Exception) {
                Log.w(TAG, "send failed", e)
                e.message ?: e.javaClass.simpleName
            }
            main.post { done(error) }
        }.apply { name = "jarvis-relay-send" }.start()
    }

    /** Fetch anything waiting, in the background. */
    fun pollAsync(context: Context) {
        val app = context.applicationContext
        Thread { pollBlocking(app) }.apply { name = "jarvis-relay-poll" }.start()
    }

    /** Fetch anything waiting and deliver it; returns once delivered. Not on the main thread. */
    fun pollBlocking(context: Context): Boolean {
        val app = context.applicationContext
        return try {
            val topic = Identity.get(app).topic
            val req = Request.Builder().url("$SERVER/$topic/json?poll=1&since=${Inbox.since(app)}").build()
            val lines = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                resp.body?.string().orEmpty().lines()
            }
            val latch = CountDownLatch(1)
            main.post {
                lines.forEach { handleLine(app, it) }
                latch.countDown()
            }
            latch.await(30, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "poll failed", e)
            false
        }
    }

    // Live stream, shared by whoever needs it (the open app, active mode).
    private var streamUsers = 0
    private var streamThread: Thread? = null
    @Volatile private var call: Call? = null

    fun acquireStream(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            streamUsers++
            if (streamThread?.isAlive == true) return
            streamThread = Thread { streamLoop(app) }.apply { name = "jarvis-relay-stream"; isDaemon = true; start() }
        }
    }

    fun releaseStream() {
        synchronized(this) {
            streamUsers = maxOf(0, streamUsers - 1)
            if (streamUsers == 0) call?.cancel()
        }
    }

    private fun wanted() = synchronized(this) { streamUsers > 0 }

    private fun streamLoop(app: Context) {
        var backoff = 2_000L
        val topic = try { Identity.get(app).topic } catch (e: Exception) { return }
        while (wanted()) {
            try {
                val req = Request.Builder().url("$SERVER/$topic/json?since=${Inbox.since(app)}").build()
                val c = streamHttp.newCall(req).also { call = it }
                if (!wanted()) break
                c.execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    backoff = 2_000L
                    val source = resp.body!!.source()
                    while (wanted()) {
                        val line = source.readUtf8Line() ?: break
                        main.post { handleLine(app, line) }
                    }
                }
            } catch (e: Exception) {
                if (!wanted()) break
                Log.w(TAG, "stream dropped: ${e.message}")
                try { Thread.sleep(backoff) } catch (ie: InterruptedException) { break }
                backoff = minOf(backoff * 2, 60_000L)
            }
        }
        synchronized(this) { streamThread = null }
        // Someone may have asked again while this thread was winding down.
        if (wanted()) synchronized(this) {
            if (streamThread == null) streamThread = Thread { streamLoop(app) }.apply { name = "jarvis-relay-stream"; isDaemon = true; start() }
        }
    }

    private fun handleLine(app: Context, line: String) {
        if (line.isBlank()) return
        val event = try { JSONObject(line) } catch (e: Exception) { return }
        if (event.optString("event") != "message") return
        Inbox.deliver(app, event.optString("id"), event.optString("message"))
    }

    /** Background check every 15 minutes, so messages arrive even when Jarvis isn't open. */
    fun schedule(context: Context) {
        val work = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("relay-poll", ExistingPeriodicWorkPolicy.KEEP, work)
    }

    class PollWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = if (pollBlocking(applicationContext)) Result.success() else Result.retry()
    }
}

/** Incoming messages: decrypt, check the sender, remember, notify, and hand to Jarvis to say. Main thread only. */
object Inbox {
    private const val MAX_AGE_MS = 2 * 24 * 3600 * 1000L
    private const val CHANNEL = "messages"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("jarvis_inbox", Context.MODE_PRIVATE)

    /** Relay position to resume from. */
    fun since(context: Context): String = prefs(context).getString("last_id", null) ?: "12h"

    fun deliver(context: Context, id: String, body: String) {
        if (id.isEmpty() || !markSeen(context, id)) return
        Conversation.init(context)
        val envelope = try { JSONObject(body) } catch (e: Exception) { return }
        val (from, plain) = Identity.get(context).decrypt(envelope) ?: return
        val payload = try { JSONObject(plain) } catch (e: Exception) { return }
        val text = payload.optString("text").trim().take(Relay.MAX_TEXT)
        if (text.isEmpty() || abs(System.currentTimeMillis() - payload.optLong("t")) > MAX_AGE_MS) return
        val claimedName = payload.optString("name").trim().take(40).ifEmpty { "Someone" }

        val contact = Contacts.byAddress(from.raw)
        if (contact == null) {
            Contacts.addRequest(ContactRequest(claimedName, from.raw, text, System.currentTimeMillis()))
            notify(context, "$claimedName's Jarvis wants to connect", "Open Jarvis › Contacts to accept or ignore.")
            return
        }
        receive(context, contact.name, text)
    }

    /** A message from a known contact (or a request you just accepted). */
    fun receive(context: Context, name: String, text: String) {
        Conversation.addReceived(name, text)
        addPending(context, "$name's Jarvis: $text")
        notify(context, "Message from $name's Jarvis", text)
        ActiveModeService.onMessageArrived()
    }

    /** Messages Jarvis hasn't said out loud yet; taking them marks them as said. */
    fun takePending(context: Context): List<String> {
        val p = prefs(context)
        val arr = JSONArray(p.getString("pending", "[]"))
        p.edit().remove("pending").apply()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun hasPending(context: Context) = JSONArray(prefs(context).getString("pending", "[]")).length() > 0

    private fun addPending(context: Context, line: String) {
        val p = prefs(context)
        val arr = JSONArray(p.getString("pending", "[]")).put(line)
        while (arr.length() > 10) arr.remove(0)
        p.edit().putString("pending", arr.toString()).apply()
    }

    /** False if this relay message was already handled. */
    private fun markSeen(context: Context, id: String): Boolean {
        val p = prefs(context)
        val seen = JSONArray(p.getString("seen", "[]"))
        for (i in 0 until seen.length()) if (seen.getString(i) == id) return false
        seen.put(id)
        while (seen.length() > 200) seen.remove(0)
        p.edit().putString("seen", seen.toString()).putString("last_id", id).apply()
        return true
    }

    private fun notify(context: Context, title: String, text: String) {
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
}

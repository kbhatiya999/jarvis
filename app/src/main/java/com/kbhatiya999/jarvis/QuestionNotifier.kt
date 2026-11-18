package com.kbhatiya999.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.kbhatiya999.jarvis.core.JarvisLog
import com.kbhatiya999.jarvis.core.agent.Answer
import com.kbhatiya999.jarvis.core.agent.OpenQuestion

/**
 * A question from Jarvis as a quiet notification you can answer without talking: Yes / No / Later, or for "what got
 * in the way?" a reply box and "Rather not say". Answers go to the agent (core/agent/Questions).
 */
object QuestionNotifier {
    private const val CHANNEL = "questions"
    private const val KEY_REPLY = "reply"

    /** False if notifications are off for Jarvis. */
    fun show(context: Context, q: OpenQuestion): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Questions from Jarvis", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Short questions about your goals, answered with a tap"
        })
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Jarvis asks")
            .setContentText(q.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(q.text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        if (q.kind == OpenQuestion.Kind.REASON) {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel("What got in the way?").build()
            b.addAction(NotificationCompat.Action.Builder(0, "Reply", intent(context, q, Answer.TEXT, mutable = true)).addRemoteInput(input).build())
            b.addAction(0, "Rather not say", intent(context, q, Answer.RATHER_NOT_SAY))
        } else {
            b.addAction(0, "Yes", intent(context, q, Answer.YES))
            b.addAction(0, "No", intent(context, q, Answer.NO))
            b.addAction(0, "Later", intent(context, q, Answer.LATER))
        }
        nm.notify(id(q.id), b.build())
        return true
    }

    fun dismiss(context: Context, questionId: String) =
        context.getSystemService(NotificationManager::class.java).cancel(id(questionId))

    private fun id(questionId: String) = 20_000 + (questionId.hashCode() and 0xFFFF)

    // Each button gets its own PendingIntent (the answer is in the data URI); a reply box needs a mutable one.
    private fun intent(context: Context, q: OpenQuestion, answer: Answer, mutable: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, QuestionReceiver::class.java).setData(Uri.parse("jarvis-question:${q.id}/${answer.name}")),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE),
        )

    internal fun reply(intent: Intent): String? = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
}

/** A tap on a question notification's button, or a typed reply. */
class QuestionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (qid, name) = intent.data?.schemeSpecificPart?.split('/', limit = 2)?.takeIf { it.size == 2 } ?: return
        val answer = try { Answer.valueOf(name) } catch (e: Exception) { return }
        QuestionNotifier.dismiss(context, qid)
        JarvisLog.i("Agent", "question $qid answered from the notification: ${answer.name.lowercase()}")
        AndroidPlatform.jarvis(context).agent.questions.answerFromNotification(qid, answer, QuestionNotifier.reply(intent))
    }
}

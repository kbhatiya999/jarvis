package com.kbhatiya999.jarvis

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.kbhatiya999.jarvis.core.JarvisLog
import com.kbhatiya999.jarvis.core.agent.Agent
import com.kbhatiya999.jarvis.core.agent.AgentClock
import com.kbhatiya999.jarvis.core.agent.Output
import com.kbhatiya999.jarvis.core.agent.Scheduler
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Android side of the agent framework (core/agent): exact alarms, the phone's time zone, speaking via active mode. */
object AgentAndroid {
    private var armed = false

    /** Plugs Android in; on the first call in this process also re-arms timers (a force-stop clears alarms). */
    fun attach(context: Context, agent: Agent) {
        val app = context.applicationContext
        agent.clock = PhoneClock
        agent.scheduler = AlarmScheduler(app)
        agent.output = object : Output {
            override fun speak(outcome: com.kbhatiya999.jarvis.core.agent.Outcome.Speak) = ActiveModeService.speak(outcome)
            override fun ask(question: com.kbhatiya999.jarvis.core.agent.OpenQuestion, prompt: String, spoken: Boolean) =
                ActiveModeService.ask(app, question, prompt, spoken)
            override fun dismiss(question: com.kbhatiya999.jarvis.core.agent.OpenQuestion) = QuestionNotifier.dismiss(app, question.id)
        }
        if (!armed) {
            armed = true
            agent.rearm()
        }
    }
}

/** The phone's own time zone, so "6 pm" means 6 pm where the user is. */
object PhoneClock : AgentClock {
    private val zone get() = ZoneId.systemDefault()
    private val short = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)
    private val long = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH)

    override fun now() = System.currentTimeMillis()

    override fun parseLocal(text: String): Long? = try {
        val t = LocalDateTime.parse(text.trim().replace(' ', 'T').let { if (it.length == 16) "$it:00" else it })
        t.atZone(zone).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    override fun describe(millis: Long): String = short.format(Instant.ofEpochMilli(millis).atZone(zone)).lowercaseAmPm()

    override fun nowForPrompt(): String = long.format(Instant.ofEpochMilli(now()).atZone(zone)).lowercaseAmPm() + " (${zone.id})"

    override fun hour(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).hour

    override fun localDate(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()

    override fun dayOfWeek(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).dayOfWeek.value

    private fun String.lowercaseAmPm() = replace("AM", "am").replace("PM", "pm")
}

/**
 * Exact alarms (USE_EXACT_ALARM is granted to reminder apps on install; SCHEDULE_EXACT_ALARM on Android 12
 * only). If exact alarms aren't allowed, an inexact one (may be a few minutes late), logged.
 */
class AlarmScheduler(private val context: Context) : Scheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    override fun schedule(id: String, atMillis: Long, payload: String) {
        val pi = intent(id)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            JarvisLog.w("Agent", "exact alarms not allowed: $id may fire a few minutes late")
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    override fun cancel(id: String) = alarms.cancel(intent(id))

    // The id is in the data URI so each timer has its own PendingIntent (extras don't make them distinct).
    private fun intent(id: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, AgentAlarmReceiver::class.java).setData(Uri.parse("jarvis-alarm:" + Uri.encode(id))),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** An agent alarm went off. */
class AgentAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.schemeSpecificPart ?: return
        val jarvis = AndroidPlatform.jarvis(context)
        jarvis.agent.onAlarm(id)
    }
}

/** Reboot, app update, clock or time-zone change: Android dropped or shifted our alarms, so set them again. */
class AgentRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        JarvisLog.i("Agent", "re-arming after ${intent.action?.substringAfterLast('.')}")
        AndroidPlatform.jarvis(context).agent.rearm()
    }
}

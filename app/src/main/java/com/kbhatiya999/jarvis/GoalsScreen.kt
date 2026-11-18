package com.kbhatiya999.jarvis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kbhatiya999.jarvis.core.Watchable
import com.kbhatiya999.jarvis.core.agent.Attempt
import com.kbhatiya999.jarvis.core.agent.ConditionState
import com.kbhatiya999.jarvis.core.agent.GoalStatus
import com.kbhatiya999.jarvis.core.agent.Goals
import com.kbhatiya999.jarvis.core.agent.Hurdle
import com.kbhatiya999.jarvis.core.agent.Importance
import com.kbhatiya999.jarvis.core.agent.OpenQuestion

/**
 * Menu › Goals: everything Jarvis keeps for goals, visible and editable: goals and their plans, what it's still
 * asking, repeating goals, checks agreed for next time, notes about you, and recent attempts.
 */
@Composable
fun GoalsScreen(vm: JarvisViewModel) {
    val agent = vm.jarvis.agent
    val clock = agent.clock
    val goals by agent.goals.items.state()
    val attempts by agent.goals.attempts.state()
    val series by agent.goals.series.state()
    val questions by agent.questions.items.state()
    val hurdles by agent.hurdles.items.state()
    val notes by agent.profile.notes.state()
    BackHandler { vm.screen = null }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.screen = null }) { Text("Back") }
            Text("Goals", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text("What Jarvis is helping with, and everything it keeps to do that. All of it stays on this phone.", color = Color.LightGray)

        Section("Goals")
        val open = goals.filter { it.status == GoalStatus.ACTIVE || it.status == GoalStatus.DUE }.sortedBy { it.deadline }
        if (open.isEmpty()) Hint("None. Say \"make sure I go to the gym at 6\".")
        open.forEach { g ->
            Card {
                Text(g.what, fontWeight = FontWeight.Bold)
                Text(
                    (if (g.status == GoalStatus.DUE) "Was due " else "By ") + clock.describe(g.deadline) +
                        (if (g.importance == Importance.IMPORTANT) " · important" else "") +
                        (if (g.seriesId != null) " · repeats" else ""),
                    color = Color.LightGray, fontSize = 13.sp,
                )
                g.conditions.forEach { c ->
                    val state = when (c.state) { ConditionState.OK -> "fine"; ConditionState.AT_RISK -> "a problem"; ConditionState.UNKNOWN -> "not known yet" }
                    Text("• ${c.what}" + (c.checkAt?.let { " · check ${clock.describe(it)}" } ?: "") + " · $state", fontSize = 13.sp)
                    if (c.reason.isNotBlank()) Text("   because: ${c.reason}", color = Color.Gray, fontSize = 12.sp)
                }
                if (g.conditions.isEmpty()) Text("No checks planned", color = Color.Gray, fontSize = 12.sp)
                Row {
                    TextButton(onClick = { agent.goals.markDone(g.id) }) { Text("Done") }
                    TextButton(onClick = { agent.goals.cancelById(g.id) }) { Text("Cancel") }
                }
            }
        }

        Section("Still asking")
        val asking = questions.filter { it.status == OpenQuestion.Status.OPEN }
        if (asking.isEmpty()) Hint("Nothing.")
        asking.forEach { q ->
            Card {
                Text(q.text)
                Text(
                    "Asked ${q.tries} of ${q.maxTries} times" +
                        (q.nextTryAt?.let { " · next ${clock.describe(it)}" } ?: "") + " · until ${clock.describe(q.expiresAt)}",
                    color = Color.Gray, fontSize = 12.sp,
                )
                TextButton(onClick = { agent.questions.stopQuestion(q.id) }) { Text("Stop asking") }
            }
        }

        Section("Repeating")
        val repeating = series.filter { it.active }
        if (repeating.isEmpty()) Hint("Nothing repeats yet. After a few successes Jarvis may offer it.")
        repeating.forEach { s ->
            Card {
                Text("${s.what} · ${Goals.describeDays(s.days)} at ${s.time}")
                TextButton(onClick = { agent.goals.stopSeriesById(s.id) }) { Text("Stop repeating") }
            }
        }

        Section("Checks for next time")
        val checks = hurdles.filter { it.status != Hurdle.Status.DECLINED }.sortedByDescending { it.createdAt }
        if (checks.isEmpty()) Hint("None yet. When something gets in the way, Jarvis suggests one; it only keeps it if you agree.")
        checks.forEach { h ->
            Card {
                Text("Check \"${h.suggestion}\" ${agent.hurdles.describeBefore(h.minutesBefore)}")
                Text("For goals like \"${h.goalWhat}\" · because: ${h.reason}", color = Color.Gray, fontSize = 12.sp)
                Row {
                    if (h.status == Hurdle.Status.PROPOSED) {
                        TextButton(onClick = { agent.hurdles.accept(h.id) }) { Text("Yes, do it") }
                        TextButton(onClick = { agent.hurdles.decline(h.id) }) { Text("No") }
                    } else TextButton(onClick = { agent.hurdles.remove(h.id) }) { Text("Remove") }
                }
            }
        }

        Section("About you")
        if (notes.isEmpty()) Hint("Nothing yet. Jarvis notes general things you tell it (\"tired after work\"), at most 20.")
        notes.asReversed().forEach { n ->
            Card {
                Text(n.text)
                Text(n.source, color = Color.Gray, fontSize = 12.sp)
                TextButton(onClick = { agent.profile.remove(n.text) }) { Text("Delete") }
            }
        }

        Section("Recent tries")
        if (attempts.isEmpty()) Hint("None yet.")
        attempts.takeLast(10).asReversed().forEach { a ->
            val what = goals.firstOrNull { it.id == a.goalId }?.what ?: "a goal"
            val outcome = when (a.outcome) { Attempt.Outcome.DONE -> "done"; Attempt.Outcome.SKIPPED -> "skipped"; Attempt.Outcome.NO_ANSWER -> "no answer" }
            Text("${clock.describe(a.at)} · $what · $outcome" + (a.reason?.let { " (\"$it\")" } ?: ""), fontSize = 13.sp, color = Color.LightGray)
        }
    }
}

@Composable
private fun Section(title: String) = Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Hint(text: String) = Text(text, color = Color.Gray, fontSize = 13.sp)

@Composable
private fun Card(content: @Composable () -> Unit) = Column(
    Modifier.fillMaxWidth().background(Color(0xFF161C2A), RoundedCornerShape(10.dp)).padding(12.dp, 8.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
) { content() }

/** A shared [Watchable] as Compose state. */
@Composable
private fun <T> Watchable<T>.state(): State<T> {
    val s = remember(this) { mutableStateOf(value) }
    DisposableEffect(this) {
        val sub = watch { s.value = it }
        onDispose { sub.cancel() }
    }
    return s
}

package com.kbhatiya999.jarvis

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.kbhatiya999.jarvis.core.Watchable
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** Checks the relay every 15 minutes, so messages arrive even when Jarvis isn't open. */
class RelayPollWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val relay = runBlocking(kotlinx.coroutines.Dispatchers.Main) { AndroidPlatform.jarvis(applicationContext).relay }
        return if (runBlocking { relay.poll() }) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<RelayPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("relay-poll", ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}

/** Reads a shared [Watchable] as Compose state. */
@Composable
fun <T> Watchable<T>.observe(): T {
    var v by remember(this) { mutableStateOf(value) }
    DisposableEffect(this) {
        val s = watch { v = it }
        onDispose { s.cancel() }
    }
    return v
}

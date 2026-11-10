package dev.s400bridge

import android.app.Application
import android.content.*
import androidx.work.*
import java.time.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

class BridgeApp: Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store by lazy { Store(this) }
    override fun onCreate() {
        super.onCreate()
        // Persistent WorkManager requests survive process death and reboot.
        scope.launch { runCatching { Scheduler.ensure(this@BridgeApp, store.read()) } }
    }
}
object Scheduler {
    private fun request(s: AppState, afterAttempt: Boolean): OneTimeWorkRequest {
        val next = Timing.next(Instant.now(), ZoneId.systemDefault(), s.settings, s.completedWindow, afterAttempt)
        return OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf("generation" to s.settings.generation))
            .setInitialDelay(maxOf(0, Duration.between(Instant.now(), next).toMillis()), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("automatic").build()
    }
    fun ensure(context: Context, s: AppState) {
        if (s.settings.automatic) WorkManager.getInstance(context).enqueueUniqueWork("automatic", ExistingWorkPolicy.KEEP, request(s, false))
    }
    fun replace(context: Context, s: AppState) {
        val manager = WorkManager.getInstance(context)
        if (s.settings.automatic) manager.enqueueUniqueWork("automatic", ExistingWorkPolicy.REPLACE, request(s, false))
        else manager.cancelUniqueWork("automatic")
    }
    fun next(context: Context, s: AppState) {
        if (s.settings.automatic) WorkManager.getInstance(context).enqueueUniqueWork("automatic", ExistingWorkPolicy.APPEND_OR_REPLACE, request(s, true))
    }
    fun manual(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork("manual", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf("manual" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
}
class SyncWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = (applicationContext as BridgeApp).store
        val manual = inputData.getBoolean("manual", false)
        val generation = inputData.getString("generation")
        try {
            withTimeout(8 * 60_000L) { SyncEngine(store, HealthSink(applicationContext)).run(manual, generation) }
        } catch (_: TimeoutCancellationException) {
            store.update { it.copy(status = "Synchronization timed out. Pending records will be retried in the next window check.") }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { return Result.failure() }
        if (!manual && !isStopped) {
            val s = store.read()
            if (s.settings.generation == generation) Scheduler.next(applicationContext, s)
        }
        return Result.success()
    }
}
class ClockReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_BOOT_COMPLETED)) return
        val pending = goAsync(); val app = context.applicationContext as BridgeApp
        app.scope.launch {
            try {
                app.store.update { it.copy(settings = it.settings.copy(generation = java.util.UUID.randomUUID().toString())) }
                Scheduler.replace(app, app.store.read())
            } finally { pending.finish() }
        }
    }
}

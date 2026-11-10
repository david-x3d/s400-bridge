package dev.s400bridge

import java.time.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WindowClosed: Exception()
object AppLock { val mutex = Mutex() }

/** Queue acknowledgements happen only after the sink succeeds. Replays use identical client IDs. */
suspend fun drainQueue(
    state: AppState, sink: RecordSink, allowed: () -> Boolean,
    acknowledge: suspend (Measurement) -> Unit
): List<Measurement> {
    if (!sink.ready()) throw SecurityException("Health Connect permissions are required. Measurements remain queued.")
    val written = mutableListOf<Measurement>()
    for (m in state.measurements.filter { eligible(it, state.settings) }) {
        if (!allowed()) throw WindowClosed()
        sink.write(m)
        acknowledge(m)
        written += m
    }
    return written
}
class SyncEngine(private val store: Store, private val sink: RecordSink) {
    suspend fun run(manual: Boolean, generation: String? = null) = AppLock.mutex.withLock {
        val initial = store.read(); val s = initial.settings
        val now = Instant.now(); val window = Timing.window(now, ZoneId.systemDefault(), s)
        if (!manual && (!s.automatic || s.generation != generation || !window.contains(now) || (!s.keepLooking && initial.completedWindow == window.key))) return@withLock
        val allowed = { manual || window.contains(Instant.now()) }
        val cloud = XiaomiCloud(allowed)
        try {
            var session = initial.session ?: throw AuthFailure("Connect your Xiaomi account first.")
            if (s.profile == null) throw ProtocolFailure("Select a Xiaomi profile before synchronizing.")
            // Persisted pending records are retried even if the cloud is currently unavailable.
            var completed = false
            suspend fun drain() {
                val sent = drainQueue(store.read(), sink, allowed) { m ->
                    store.update { st -> st.copy(measurements = st.measurements.map { if (it.id == m.id) it.copy(sentRevision = m.revision) else it }, lastSync = System.currentTimeMillis()) }
                    if (endsMorning(m, window)) {
                        completed = true
                        store.update { it.copy(completedWindow = window.key) }
                    }
                }
                if (sent.isNotEmpty()) store.update { it.copy(status = "Measurements transferred to Health Connect.") }
            }
            drain()
            if (!manual && completed && !s.keepLooking) return@withLock
            val cutoff = if (s.fullImport) 0 else maxOf(s.since, initial.lastSync?.minus(Duration.ofDays(2).toMillis()) ?: s.since)
            val history = try { cloud.history(session, s.region, cutoff) } catch (_: AuthFailure) {
                session = cloud.refresh(session)
                store.update { it.copy(session = session) }
                cloud.history(session, s.region, cutoff)
            }
            store.update { it.copy(settings = it.settings.copy(fullImport = false), profiles = (it.profiles + history.first).distinctBy { p -> p.key }, measurements = mergeMeasurements(it.measurements, history.second), status = "Cloud checked. No new measurements to transfer.") }
            drain()
        } catch (_: WindowClosed) {
            store.update { it.copy(status = "Time window ended. Pending measurements will be retried in the next window.") }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            store.update { it.copy(status = safeError(e)) }
        }
    }
}
fun safeError(e: Exception): String = when(e) {
    is VerificationRequired -> "Xiaomi requires account verification. Open sign-in and verify your account."
    is AuthFailure -> e.message ?: "Reconnect your Xiaomi account."
    is ProtocolFailure -> e.message ?: "Unexpected Xiaomi protocol response."
    is SecurityException -> "Health Connect permission is missing. Your measurements remain queued."
    is java.io.IOException -> "Network request failed. Pending measurements will be retried."
    else -> "Operation failed (${e.javaClass.simpleName}). Your local queue has been preserved."
}

package dev.s400bridge

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.withLock

class BridgeViewModel(app: Application): AndroidViewModel(app) {
    private val app = app as BridgeApp
    private val store = this.app.store
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val challenge = MutableStateFlow<String?>(null)
    internal val browserLogin = MutableStateFlow<BrowserLogin?>(null)
    val browserQr = MutableStateFlow<ByteArray?>(null)
    val browserStatus = MutableStateFlow<String?>(null)
    private var browserJob: Job? = null
    val healthReady = MutableStateFlow(false)
    val state = store.states.catch { error.value = "Encrypted storage cannot be opened. No data was overwritten. Restore access to your device key or clear app storage to reconnect." }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppState())
    fun refreshHealth() { viewModelScope.launch { healthReady.value = runCatching { HealthSink(app).ready() }.getOrDefault(false) } }
    private fun operation(action: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true; error.value = null
            try { AppLock.mutex.withLock { action() } }
            catch (e: VerificationRequired) { challenge.value = e.url; error.value = e.message }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error.value = safeError(e) }
            finally { busy.value = false }
        }
    }
    fun connect(username: String, password: String, region: String) = operation {
        val client = XiaomiCloud()
        val session = client.login(username.trim(), password)
        acceptSession(client, session, region)
    }
    private suspend fun acceptSession(client: XiaomiCloud, session: Session, region: String) {
        // Save a verified session even when history retrieval fails.
        store.update { it.copy(session = session, profiles = emptyList(), settings = it.settings.copy(region = region, profile = null, automatic = false, generation = UUID.randomUUID().toString()), status = "Connected. Load profiles to continue.") }
        Scheduler.replace(app, store.read())
        val (profiles, records) = client.history(session, region)
        store.update { it.copy(profiles = profiles, measurements = mergeMeasurements(it.measurements, records), status = if (profiles.isEmpty()) "No S400 EU history found in this region. Check Xiaomi Home and your selected region." else "Choose the Xiaomi profile to synchronize.") }
        challenge.value = null
    }
    fun startBrowserLogin(region: String) {
        if (browserJob?.isActive == true || busy.value) return
        browserJob = viewModelScope.launch {
            busy.value = true; error.value = null
            browserStatus.value = "Creating a secure Xiaomi sign-in session…"
            try {
                val client = XiaomiCloud()
                val login = client.startBrowserLogin()
                browserLogin.value = login
                browserStatus.value = "Open Xiaomi sign-in below and choose Google. Return here after approving access."
                try { browserQr.value = client.browserQr(login) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* Browser URL remains usable without the optional QR image. */ }
                val session = client.awaitBrowserLogin(login)
                coroutineContext.ensureActive()
                browserLogin.value = null; browserQr.value = null
                browserStatus.value = "Xiaomi sign-in approved. Loading your profiles…"
                AppLock.mutex.withLock { acceptSession(client, session, region) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error.value = safeError(e) }
            finally {
                browserLogin.value = null; browserQr.value = null; browserStatus.value = null; busy.value = false
            }
        }
    }
    fun cancelBrowserLogin() {
        browserJob?.cancel()
        browserLogin.value = null; browserQr.value = null
        browserStatus.value = "Cancelling sign-in…"
    }
    fun discover() = operation {
        val state = store.read(); var session = state.session ?: throw AuthFailure()
        val client = XiaomiCloud()
        val data = try { client.history(session, state.settings.region) } catch (_: AuthFailure) {
            session = client.refresh(session); store.update { it.copy(session = session) }; client.history(session, state.settings.region)
        }
        store.update { it.copy(profiles = data.first, measurements = mergeMeasurements(it.measurements, data.second), status = "Profiles refreshed. Select only your own profile.") }
    }
    fun disconnect() = operation {
        store.update { it.copy(session = null, profiles = emptyList(), measurements = emptyList(), completedWindow = null, lastSync = null,
            settings = it.settings.copy(profile = null, automatic = false, generation = UUID.randomUUID().toString()), status = "Disconnected. Local Xiaomi data and tokens removed; existing Health Connect records remain.") }
        Scheduler.replace(app, store.read())
    }
    fun settings(change: (Settings) -> Settings) = operation {
        store.update {
            val next = change(it.settings)
            require(next.start != next.end) { "Start and end must differ." }
            require(next.interval in listOf(15, 30, 60))
            it.copy(settings = next.copy(generation = UUID.randomUUID().toString()))
        }
        Scheduler.replace(app, store.read())
    }
    fun select(profile: String, importHistory: Boolean) = operation {
        store.update { it.copy(settings = it.settings.copy(profile = profile, since = if (importHistory) 0 else System.currentTimeMillis(), fullImport = importHistory, generation = UUID.randomUUID().toString()), completedWindow = null, status = "Profile selected. You can now sync or enable automatic synchronization.") }
        Scheduler.replace(app, store.read())
    }
    fun importHistory() = operation {
        store.update { it.copy(settings = it.settings.copy(since = 0, fullImport = true), status = "Historical import enabled for the selected profile.") }
        Scheduler.manual(app)
    }
    fun syncNow() { Scheduler.manual(app) }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.s400bridge

import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity: ComponentActivity() {
    private val model by viewModels<BridgeViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                Surface(Modifier.fillMaxSize()) { BridgeScreen(model) }
            }
        }
    }
}
@Composable private fun BridgeScreen(vm: BridgeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val ready by vm.healthReady.collectAsStateWithLifecycle()
    val challenge by vm.challenge.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val work by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("manual").collectAsStateWithLifecycle(emptyList())
    val syncing = work.any { !it.state.isFinished }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refreshHealth() }
        lifecycle.addObserver(observer); vm.refreshHealth(); onDispose { lifecycle.removeObserver(observer) }
    }
    val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.refreshHealth() }
    var settings by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val activity = context as? MainActivity
        privacy = activity?.intent?.action in listOf("android.intent.action.VIEW_PERMISSION_USAGE", "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE")
    }
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.brand_logo), "S400 Bridge", Modifier.size(52.dp))
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("S400 Bridge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Your morning, connected.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { settings = !settings }) { Text(if (settings) "Home" else "Settings") }
                }
                if (busy || syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Note(it, true) }
                challenge?.let { url ->
                    Note("Xiaomi verification is required. Complete it in your browser, return here, and retry sign-in. Some challenges are session-bound; see the README if verification repeats.")
                    Button(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }) { Text("Open Xiaomi verification") }
                }
                if (!settings) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusCard("Xiaomi", if (state.session == null) "Not connected" else "Connected", Modifier.weight(1f))
                        StatusCard("Health Connect", if (ready) "Ready" else "Permission needed", Modifier.weight(1f))
                    }
                    MeasurementCard(state)
                    Section("Synchronization") {
                        Text(state.status, style = MaterialTheme.typography.bodyLarge)
                        Text("Last transfer: ${formatTime(state.lastSync)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val s = state.settings
                        Text(if (s.automatic) "Automatic · every ${s.interval} minutes" else "Automatic synchronization is off")
                        Text("Window: ${clock(s.start)}–${clock(s.end)} · ${ZoneId.systemDefault().id}")
                        if (s.automatic) Text("Next check, best effort: ${formatTime(Timing.next(Instant.now(), ZoneId.systemDefault(), s, state.completedWindow, false).toEpochMilli())}")
                        val queued = state.measurements.count { eligible(it, s) }
                        Text("$queued measurements pending", style = MaterialTheme.typography.labelLarge)
                        Button(onClick = vm::syncNow, enabled = !busy && !syncing && state.session != null && s.profile != null && ready, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(24.dp)) {
                            Text(if (syncing) "Synchronizing…" else "Sync now", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Manual sync works at any time. Automatic checks only start inside your time window.", style = MaterialTheme.typography.bodySmall)
                        if (state.session == null || s.profile == null) FilledTonalButton(onClick = { settings = true }, modifier = Modifier.fillMaxWidth()) { Text("Set up your connection") }
                    }
                } else {
                    AccountSettings(state, busy, vm)
                    Section("Health Connect") {
                        Text(if (ready) "Weight and body fat write access granted." else "Allow weight and body fat to be written. No read access is requested.")
                        Button(onClick = {
                            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) permissions.launch(HealthSink.permissions)
                            else vm.error.value = "Health Connect is unavailable on this device. Check Android system updates."
                        }) { Text(if (ready) "Review permissions" else "Grant permissions") }
                        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) } }) { Text("Open Health Connect settings") }
                    }
                    Section("Automatic synchronization") {
                        Toggle("Enable automatic sync", state.settings.automatic, enabled = !busy && state.session != null && state.settings.profile != null) { value -> vm.settings { it.copy(automatic = value) } }
                        TimeSetting("Window starts", state.settings.start, !busy) { time -> vm.settings { it.copy(start = time) } }
                        TimeSetting("Window ends", state.settings.end, !busy) { time -> vm.settings { it.copy(end = time) } }
                        Text("Interval", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 60).forEach { minutes -> FilterChip(selected = state.settings.interval == minutes, onClick = { vm.settings { it.copy(interval = minutes) } }, enabled = !busy, label = { Text("$minutes min") }) }
                        }
                        Toggle("Keep looking after a complete transfer", state.settings.keepLooking, !busy) { value -> vm.settings { it.copy(keepLooking = value) } }
                        Text("Battery-optimized mode", style = MaterialTheme.typography.titleSmall)
                        Text("Always on. WorkManager follows Android battery policies. Checks may be delayed or skipped during Doze; 15 minutes is the minimum interval. No exact alarms, foreground service, Bluetooth scan, or battery exemption is used.", style = MaterialTheme.typography.bodySmall)
                        Text("A complete measurement means weight and body fat measured during this window. After transfer, polling stops until the next window. Overnight windows are supported.", style = MaterialTheme.typography.bodySmall)
                    }
                    Section("Import & diagnostics") {
                        Text("Import all available history for the selected profile. Existing records from this installation are matched by stable IDs.")
                        OutlinedButton(onClick = vm::importHistory, enabled = !busy && ready && state.settings.profile != null) { Text("Import existing measurements") }
                        Text(state.status)
                        Text("Model: ${ScaleParser.MODEL}\nRegion: ${state.settings.region}\nApp: ${BuildConfig.VERSION_NAME}\nAndroid: ${android.os.Build.VERSION.RELEASE}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!ready && !settings) FilledTonalButton(onClick = { permissions.launch(HealthSink.permissions) }, modifier = Modifier.fillMaxWidth()) { Text("Allow Health Connect") }
                TextButton(onClick = { privacy = true }) { Text("Privacy & data use") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (privacy) AlertDialog(onDismissRequest = { privacy = false }, title = { Text("Your data stays yours") }, text = {
        Text("S400 Bridge connects directly to Xiaomi to fetch your scale history and writes only weight and body fat for your selected profile to Health Connect. Tokens and cached measurements are encrypted locally using Android Keystore. Your password is used only for sign-in and is never saved. No analytics, advertising, or intermediary servers. Backups are disabled. Disconnect removes local Xiaomi data; manage previously written records in Health Connect. Xiaomi's private API may change. Google apps must separately be allowed to read these records.")
    }, confirmButton = { TextButton(onClick = { privacy = false }) { Text("Got it") } })
}
@Composable private fun MeasurementCard(state: AppState) {
    val latest = state.measurements.lastOrNull { it.profile == state.settings.profile }
    Card(shape = RoundedCornerShape(36.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("LATEST MEASUREMENT", style = MaterialTheme.typography.labelLarge)
            AnimatedContent(latest?.kg, label = "weight") { weight ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(weight?.let { "%.1f".format(java.util.Locale.ENGLISH, it) } ?: "—", fontSize = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp)
                    Text(" kg", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
            Text(latest?.fat?.let { "%.1f%% body fat".format(java.util.Locale.ENGLISH, it) } ?: "Body fat not available", style = MaterialTheme.typography.titleMedium)
            Text(if (latest == null) "Your first measurement will appear here." else formatTime(latest.time), style = MaterialTheme.typography.bodyMedium)
            latest?.let { Text(if (it.sentRevision >= it.revision) "Transferred to Health Connect" else "Not yet transferred", style = MaterialTheme.typography.labelMedium) }
        }
    }
}
@Composable private fun AccountSettings(state: AppState, busy: Boolean, vm: BridgeViewModel) {
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var region by remember(state.settings.region) { mutableStateOf(state.settings.region) }
    var import by remember { mutableStateOf(false) }
    val browserLogin by vm.browserLogin.collectAsStateWithLifecycle()
    val browserQr by vm.browserQr.collectAsStateWithLifecycle()
    val browserStatus by vm.browserStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Section("Xiaomi account") {
        Text("Use the same Xiaomi account and server region as Xiaomi Home. Region cannot be inferred reliably from your phone's locale.")
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !busy) { Text("Region: ${regionName(region)}") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                listOf("de", "tw", "ru", "us", "sg", "i2").forEach { code -> DropdownMenuItem(text = { Text(regionName(code)) }, onClick = { region = code; expanded = false }) }
            }
        }
        Button(onClick = { password = ""; vm.startBrowserLogin(region) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Sign in with Google via Xiaomi")
        }
        Text("Opens Xiaomi's official sign-in in your browser. Choose Google there, approve access, then return. You can also authorize using Xiaomi Home's QR scanner. Your Google password never enters S400 Bridge.", style = MaterialTheme.typography.bodySmall)
        browserStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        browserLogin?.let { login ->
            FilledTonalButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, login.browserUrl.toUri())) }
                    .onFailure { vm.error.value = "No browser could open Xiaomi sign-in. Install or enable a browser, then retry." }
            }, modifier = Modifier.fillMaxWidth()) { Text("Open Xiaomi sign-in · choose Google") }
            val bitmap = remember(browserQr) { browserQr?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
            if (bitmap != null) {
                Image(bitmap, "Xiaomi sign-in QR code. Scan with Xiaomi Home to approve this session.", Modifier.fillMaxWidth().height(220.dp))
                Text("Alternatively scan this code with Xiaomi Home on another device. Use your existing Google-linked Xiaomi account. Codes expire automatically.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (browserStatus != null) TextButton(onClick = vm::cancelBrowserLogin) { Text("Cancel sign-in") }
        HorizontalDivider()
        Text("Or use a Xiaomi account password", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(username, { username = it }, label = { Text("Xiaomi account email or phone") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password · never saved") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Text("This uses Xiaomi's private sign-in protocol. Verification may open in your browser. No official third-party scale-history OAuth integration was verified.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { val value = password; password = ""; vm.connect(username, value, region) }, enabled = !busy && username.isNotBlank() && password.isNotEmpty()) { Text(if (state.session == null) "Connect Xiaomi account" else "Reconnect Xiaomi account") }
        if (state.session != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::discover, enabled = !busy) { Text("Refresh profiles") }
                TextButton(onClick = vm::disconnect, enabled = !busy) { Text("Disconnect") }
            }
            Text("Only the profile you explicitly select will be transferred.", style = MaterialTheme.typography.bodyMedium)
            Toggle("Include existing history on selection", import, !busy) { import = it }
            state.profiles.forEach { profile ->
                OutlinedCard(onClick = { vm.select(profile.key, import) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.settings.profile == profile.key, onClick = null)
                        Text(profile.name, Modifier.padding(start = 12.dp))
                    }
                }
            }
            if (state.profiles.isEmpty()) Text("No profiles loaded. We will never guess a profile from a name or weight.")
        }
    }
}
@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); content() }
    }
}
@Composable private fun StatusCard(title: String, value: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.labelLarge); Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}
@Composable private fun Note(message: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) { Text(message, Modifier.padding(16.dp)) }
}
@Composable private fun Toggle(label: String, value: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f).padding(end = 8.dp)); Switch(value, onCheckedChange = change, enabled = enabled) }
}
@Composable private fun TimeSetting(label: String, value: Int, enabled: Boolean, change: (Int) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> change(h * 60 + m) }, value / 60, value % 60, true).show() }, enabled = enabled) { Text(clock(value)) }
    }
}
private fun clock(value: Int) = "%02d:%02d".format(java.util.Locale.ENGLISH, value / 60, value % 60)
private fun formatTime(value: Long?): String = value?.let { DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", java.util.Locale.ENGLISH).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "Not yet"
private fun regionName(region: String) = when(region) { "de" -> "Europe (de)"; "tw" -> "Taiwan (Singapore server)"; "ru" -> "Russia (ru)"; "us" -> "United States (us)"; "sg" -> "Singapore (sg)"; else -> "India (i2)" }

package dev.s400bridge

// Protocol ported from SmartScaleConnect pkg/xiaomi (MIT); see THIRD_PARTY_NOTICES.txt.
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.*
import okhttp3.*

object XiaomiCrypto {
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun decode(s: String): ByteArray = Base64.getDecoder().decode(s)
    fun digest(kind: String, b: ByteArray): ByteArray = MessageDigest.getInstance(kind).digest(b)
    fun nonce(nowMillis: Long = System.currentTimeMillis(), random: ByteArray = ByteArray(8).also { SecureRandom().nextBytes(it) }): ByteArray =
        ByteBuffer.allocate(12).put(random).putInt((nowMillis / 60000).toInt()).array()
    fun signed(security: String, nonce: ByteArray) = digest("SHA-256", decode(security) + nonce)
    fun signature(path: String, values: Map<String, String>, key: ByteArray): String =
        b64(digest("SHA-1", ("POST&$path&" + values.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" } + "&${b64(key)}").toByteArray()))
    // RC4-drop1024 is mandated by Xiaomi's private wire protocol, never used for local storage.
    fun crypt(key: ByteArray, input: ByteArray): ByteArray {
        val s = IntArray(256) { it }; var j = 0
        for (i in 0..255) { j = (j + s[i] + (key[i % key.size].toInt() and 255)) and 255; val t = s[i]; s[i] = s[j]; s[j] = t }
        var i = 0; j = 0
        fun next(): Int { i = (i + 1) and 255; j = (j + s[i]) and 255; val t = s[i]; s[i] = s[j]; s[j] = t; return s[(s[i] + s[j]) and 255] }
        repeat(1024) { next() }
        return ByteArray(input.size) { (input[it].toInt() xor next()).toByte() }
    }
}
class XiaomiCloud(private val allowed: () -> Boolean = { true }) {
    private val cookieJar = mutableListOf<Cookie>()
    private val client = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).cookieJar(object: CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { c -> cookieJar.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; cookieJar += c }
        }
        override fun loadForRequest(url: HttpUrl) = cookieJar.filter { it.matches(url) }
    }).build()
    private fun execute(request: Request): String {
        if (!allowed()) throw WindowClosed()
        return client.newCall(request).execute().use {
        if (it.code == 401 || it.code == 403) throw AuthFailure()
        if (!it.isSuccessful) throw java.io.IOException("Xiaomi HTTP ${it.code}")
        it.body.string()
        }
    }
    private fun loginJson(body: String): JsonObject {
        if (!body.startsWith("&&&START&&&")) throw ProtocolFailure("Unexpected Xiaomi sign-in response.")
        return Json.parseToJsonElement(body.removePrefix("&&&START&&&")).jsonObject
    }
    suspend fun login(username: String, password: String): Session = withContext(Dispatchers.IO) {
        val first = loginJson(execute(Request.Builder().url("https://account.xiaomi.com/pass/serviceLogin?_json=true&sid=xiaomiio").build()))
        val hash = XiaomiCrypto.digest("MD5", password.toByteArray()).joinToString("") { "%02X".format(it) }
        val form = FormBody.Builder().add("_json", "true").add("hash", hash).add("sid", "xiaomiio")
            .add("callback", first.text("callback")).add("_sign", first.text("_sign")).add("qs", first.text("qs")).add("user", username).build()
        val result = loginJson(execute(Request.Builder().url("https://account.xiaomi.com/pass/serviceLoginAuth2")
            .header("Cookie", "deviceId=" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)).post(form).build()))
        finish(result, null)
    }
    suspend fun refresh(session: Session): Session = withContext(Dispatchers.IO) {
        val result = loginJson(execute(Request.Builder().url("https://account.xiaomi.com/pass/serviceLogin?_json=true&sid=xiaomiio")
            .header("Cookie", "userId=${session.userId}; passToken=${session.passToken}").build()))
        finish(result, session)
    }
    // Port of QrCodeXiaomiCloudConnector from Xiaomi-cloud-tokens-extractor (MIT).
    // The login URL is deliberately opened in the system browser: Google owns its UI.
    internal suspend fun startBrowserLogin(): BrowserLogin = withContext(Dispatchers.IO) {
        val url = "https://account.xiaomi.com/longPolling/loginUrl".toHttpUrl().newBuilder()
            .addQueryParameter("_qrsize", "480")
            .addQueryParameter("qs", "%3Fsid%3Dxiaomiio%26_json%3Dtrue")
            .addQueryParameter("callback", "https://sts.api.io.mi.com/sts")
            .addQueryParameter("_hasLogo", "false").addQueryParameter("sid", "xiaomiio")
            .addQueryParameter("serviceParam", "").addQueryParameter("_locale", "en_GB")
            .addQueryParameter("_dc", System.currentTimeMillis().toString()).build()
        val response = execute(Request.Builder().url(url).build())
        BrowserLogin.parse(parseXiaomiJson(response))
    }
    internal suspend fun browserQr(login: BrowserLogin): ByteArray = withContext(Dispatchers.IO) {
        // Avoid loading or persisting session QR material through an image cache.
        client.newCall(Request.Builder().url(login.imageUrl).build()).execute().use {
            if (!it.isSuccessful) throw java.io.IOException("Xiaomi QR HTTP ${it.code}")
            val bytes = it.body.bytes()
            if (bytes.size > 1_000_000) throw ProtocolFailure("Xiaomi returned an oversized QR image.")
            bytes
        }
    }
    internal suspend fun awaitBrowserLogin(login: BrowserLogin): Session = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + login.timeoutSeconds * 1_000_000_000L
        val pollClient = client.newBuilder().callTimeout(12, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        var pollingUrl = login.pollingUrl
        while (System.nanoTime() < deadline) {
            coroutineContext.ensureActive()
            try {
                val result = pollClient.newCall(Request.Builder().url(pollingUrl).build()).execute().use { response ->
                    if (response.code in listOf(408, 504)) null
                    else {
                        if (!response.isSuccessful) throw AuthFailure("Xiaomi browser sign-in failed (HTTP ${response.code}). Start a new sign-in.")
                        parseXiaomiJson(response.body.string())
                    }
                }
                coroutineContext.ensureActive()
                if (result != null) {
                    if (result.text("passToken").isNotBlank() && result.text("passToken") != "null") return@withContext finish(result, null)
                    val code = result.text("code").toIntOrNull()
                    // Code 0 may indicate a scanned but not yet approved session.
                    if (code != null && code != 0) throw AuthFailure("Xiaomi browser sign-in ended (code $code). Start a new sign-in.")
                    val next = result.text("lp")
                    if (next.isNotBlank()) {
                        if (!safeAccountUrl(next)) throw ProtocolFailure("Unexpected Xiaomi polling host.")
                        pollingUrl = next
                    }
                }
            } catch (_: java.io.InterruptedIOException) {
                coroutineContext.ensureActive() // Normal long-poll timeout; await approval again.
            }
            delay(1000)
        }
        throw AuthFailure("Xiaomi sign-in expired. Tap the browser sign-in button to try again.")
    }
    private fun finish(result: JsonObject, old: Session?): Session {
        if (result.text("captchaUrl").isNotBlank() && result.text("captchaUrl") != "null") throw ProtocolFailure("Xiaomi requires an image CAPTCHA. This sign-in variant is not supported; verify your account in Xiaomi Home and retry.")
        val challenge = result.text("notificationUrl").takeUnless { it == "null" }.orEmpty()
        if (challenge.isNotBlank()) {
            val url = if (challenge.startsWith("/")) "https://account.xiaomi.com$challenge" else challenge
            if (safeXiaomiUrl(url)) throw VerificationRequired(url)
            throw ProtocolFailure("Xiaomi supplied an unexpected verification host.")
        }
        if (result.text("ssecurity").isBlank() || result.text("location").isBlank())
            throw AuthFailure("Xiaomi sign-in failed (code ${result.text("code").toIntOrNull() ?: -1}). Check your account or verification requirements.")
        val location = result.text("location")
        if (!safeXiaomiUrl(location)) throw ProtocolFailure("Unexpected Xiaomi sign-in redirect.")
        execute(Request.Builder().url(location).build())
        val token = cookieJar.lastOrNull { it.name == "serviceToken" }?.value ?: throw AuthFailure("Xiaomi did not issue a service token.")
        val uid = result.text("userId").ifBlank { old?.userId.orEmpty() }
        val pass = result.text("passToken").ifBlank { old?.passToken.orEmpty() }
        if (!uid.matches(Regex("[0-9]+")) || pass.isBlank()) throw AuthFailure()
        return Session(uid, pass, result.text("ssecurity"), "userId=$uid; serviceToken=$token; yetAnotherServiceToken=$token")
    }
    suspend fun history(session: Session, region: String, cutoff: Long = 0): Pair<List<Profile>, List<Measurement>> = withContext(Dispatchers.IO) {
        require(region in listOf("de", "tw", "ru", "us", "sg", "i2"))
        val path = "/eco/common/scale/getUserDataByPage"
        val profiles = linkedMapOf<String, Profile>(); val records = linkedMapOf<String, Measurement>()
        var cursor = System.currentTimeMillis()
        repeat(2000) {
            coroutineContext.ensureActive()
            val params = buildJsonObject {
                put("endTime", 1); put("beginTime", cursor); put("model", ScaleParser.MODEL)
                put("uid", session.userId); put("did", 0); put("accountId", 0)
            }.toString()
            val nonce = XiaomiCrypto.nonce(); val key = XiaomiCrypto.signed(session.security, nonce)
            val values = linkedMapOf("data" to params)
            values["rc4_hash__"] = XiaomiCrypto.signature(path, values, key)
            values.keys.toList().forEach { values[it] = XiaomiCrypto.b64(XiaomiCrypto.crypt(key, values.getValue(it).toByteArray())) }
            values["signature"] = XiaomiCrypto.signature(path, values, key)
            values["_nonce"] = XiaomiCrypto.b64(nonce)
            val form = FormBody.Builder().apply { values.forEach { (k, v) -> add(k, v) } }.build()
            val body = execute(Request.Builder().url("https://${xiaomiServer(region)}.api.io.mi.com/app$path").header("Cookie", session.cookies)
                .header("MIOT-REQUEST-MODEL", ScaleParser.MODEL).header("MIOT-ENCRYPT-ALGORITHM", "ENCRYPT-RC4").post(form).build())
            val envelope = try { Json.parseToJsonElement(XiaomiCrypto.crypt(key, XiaomiCrypto.decode(body.trim())).toString(Charsets.UTF_8)).jsonObject }
            catch (_: Exception) { throw ProtocolFailure("Xiaomi response could not be decrypted. Reconnect your account.") }
            val code = envelope.text("code").toIntOrNull() ?: throw ProtocolFailure("Missing Xiaomi response code.")
            if (code in listOf(-3, -6, -10000)) throw AuthFailure()
            if (code != 0) throw ProtocolFailure("Xiaomi history API returned code $code.")
            val page = envelope["result"] as? JsonArray ?: throw ProtocolFailure("Xiaomi history format changed: expected an array.")
            val (p, m) = ScaleParser.parse(page, region, session.userId)
            p.forEach { profiles[it.key] = it }; m.forEach { records[it.id] = it }
            if (page.size < 20 || page.any { (it.jsonObject.text("createTime").toLongOrNull() ?: Long.MAX_VALUE) < cutoff }) return@withContext profiles.values.toList() to records.values.toList()
            val next = page.map { it.jsonObject.text("createTime").toLongOrNull() ?: throw ProtocolFailure("Missing pagination timestamp.") }.min()
            if (next >= cursor || next <= 0) throw ProtocolFailure("Xiaomi pagination stopped advancing; import stopped safely.")
            cursor = next
        }
        throw ProtocolFailure("Xiaomi history exceeded the safety limit; import stopped without discarding the existing queue.")
    }
}
fun safeXiaomiUrl(value: String): Boolean = runCatching {
    val u = java.net.URI(value)
    u.scheme == "https" && (u.host == "xiaomi.com" || u.host.endsWith(".xiaomi.com") || u.host == "mi.com" || u.host.endsWith(".mi.com")) && u.userInfo == null
}.getOrDefault(false)

// Xiaomi Home country-to-server mapping: Taiwan is hosted in Singapore.
internal fun xiaomiServer(region: String): String {
    require(region in listOf("de", "tw", "ru", "us", "sg", "i2"))
    return if (region == "tw") "sg" else region
}

internal fun parseXiaomiJson(body: String): JsonObject = try {
    Json.parseToJsonElement(body.removePrefix("&&&START&&&")).jsonObject
} catch (_: Exception) {
    throw ProtocolFailure("Unexpected Xiaomi sign-in response. Start a new sign-in.")
}

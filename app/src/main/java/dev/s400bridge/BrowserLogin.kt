package dev.s400bridge

import java.net.URI
import kotlinx.serialization.json.*

/** Short-lived sign-in material, held only in ViewModel memory, never DataStore or logs. */
internal data class BrowserLogin(
    val browserUrl: String, val pollingUrl: String, val imageUrl: String,
    val timeoutSeconds: Long
) {
    companion object {
        fun parse(result: JsonObject): BrowserLogin {
            val code = result.text("code").toIntOrNull()
            if (code != 0) throw AuthFailure("Xiaomi could not start browser sign-in (code ${code ?: -1}).")
            val browser = result.text("loginUrl")
            val polling = result.text("lp")
            val image = result.text("qr")
            if (!safeAccountUrl(browser) || !safeAccountUrl(polling) || !safeAccountUrl(image))
                throw ProtocolFailure("Xiaomi supplied an unexpected browser sign-in host.")
            val timeout = result.text("timeout").toLongOrNull()
            if (timeout == null || timeout !in 1..900) throw ProtocolFailure("Invalid Xiaomi sign-in expiry.")
            return BrowserLogin(browser, polling, image, timeout)
        }
    }
}
internal fun safeAccountUrl(value: String): Boolean = runCatching {
    val u = URI(value)
    u.scheme == "https" && (u.host == "account.xiaomi.com" || u.host.endsWith(".account.xiaomi.com")) &&
        u.userInfo == null && u.port in listOf(-1, 443)
}.getOrDefault(false)

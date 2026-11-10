package dev.s400bridge

import kotlinx.serialization.json.*

class ProtocolFailure(message: String): Exception(message)
class AuthFailure(message: String = "Xiaomi session expired. Please reconnect."): Exception(message)
class VerificationRequired(val url: String): Exception("Xiaomi requires verification. Complete it in your browser, then retry sign-in.")

object ScaleParser {
    const val MODEL = "yunmai.scales.ms104"
    fun parse(items: JsonArray, region: String, owner: String): Pair<List<Profile>, List<Measurement>> {
        val profiles = linkedMapOf<String, Profile>()
        val measurements = mutableListOf<Measurement>()
        items.forEach { item ->
            val row = item.jsonObject
            if (row.text("model") != MODEL) return@forEach
            if (row.text("uid") != owner) return@forEach
            val source = row.text("fromSource").toIntOrNull() ?: throw ProtocolFailure("Missing Xiaomi source format.")
            if (source !in 1..3) throw ProtocolFailure("Unsupported Xiaomi source format $source. No data was guessed.")
            val d = Json.parseToJsonElement(row.text("data")).jsonObject
            val user = d["user"] as? JsonObject
            // Never infer a person's identity from their name or weight.
            val account = row.text("accountId").ifBlank { user?.text("accountId").orEmpty() }
            if (account.isBlank() || account == "null") throw ProtocolFailure("History has no stable profile ID. Synchronization stopped to protect other users.")
            val profile = "$region:$owner:$account"
            val name = user?.text("name")?.ifBlank { null } ?: "Profile $account"
            profiles[profile] = Profile(profile, "$name · $account")
            val time = if (source == 3) d.text("time").toLongOrNull() else row.text("createTime").toLongOrNull()
            if (time == null || time < 1_000_000_000_000L) throw ProtocolFailure("Unsupported measurement timestamp; expected milliseconds.")
            val kg = d.text("weight").toDoubleOrNull()
            if (kg == null || !kg.isFinite() || kg !in 1.0..500.0) throw ProtocolFailure("Invalid or missing cloud weight.")
            val body = if (source == 3) {
                d.text("bodyResData").takeIf { it.isNotBlank() && it != "null" }?.let { Json.parseToJsonElement(it).jsonObject }
            } else d
            val fat = body?.text("bfp")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 100 }
            val id = stableId("$profile:${row.text("did")}:$time")
            measurements += Measurement(id, profile, time, kg, fat)
        }
        return profiles.values.toList() to measurements
    }
}
fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

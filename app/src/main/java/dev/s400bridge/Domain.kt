package dev.s400bridge

import java.time.*
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable data class Session(val userId: String, val passToken: String, val security: String, val cookies: String)
@Serializable data class Profile(val key: String, val name: String)
@Serializable data class Measurement(
    val id: String, val profile: String, val time: Long, val kg: Double, val fat: Double?,
    val revision: Long = 1, val sentRevision: Long = 0
)
@Serializable data class Settings(
    val automatic: Boolean = false, val start: Int = 360, val end: Int = 450,
    val interval: Int = 15, val keepLooking: Boolean = false,
    val region: String = "de", val profile: String? = null, val since: Long = Long.MAX_VALUE,
    val fullImport: Boolean = false, val generation: String = "initial"
)
@Serializable data class AppState(
    val settings: Settings = Settings(), val session: Session? = null,
    val profiles: List<Profile> = emptyList(), val measurements: List<Measurement> = emptyList(),
    val completedWindow: String? = null, val lastSync: Long? = null,
    val status: String = "Connect your Xiaomi account to get started."
)

data class Window(val start: ZonedDateTime, val end: ZonedDateTime) {
    val key: String get() = start.toInstant().toString()
    fun contains(now: Instant) = !now.isBefore(start.toInstant()) && now.isBefore(end.toInstant())
}
object Timing {
    fun window(now: Instant, zone: ZoneId, s: Settings): Window {
        require(s.start in 0..1439 && s.end in 0..1439 && s.start != s.end)
        val local = now.atZone(zone)
        var date = local.toLocalDate()
        if (s.end < s.start && local.toLocalTime() < LocalTime.ofSecondOfDay(s.end * 60L)) date = date.minusDays(1)
        return on(date, zone, s)
    }
    private fun on(date: LocalDate, zone: ZoneId, s: Settings): Window {
        val start = date.atTime(LocalTime.ofSecondOfDay(s.start * 60L)).atZone(zone)
        val end = date.plusDays(if (s.end <= s.start) 1 else 0).atTime(LocalTime.ofSecondOfDay(s.end * 60L)).atZone(zone)
        return Window(start, end)
    }
    fun next(now: Instant, zone: ZoneId, s: Settings, completed: String?, afterAttempt: Boolean): Instant {
        val w = window(now, zone, s)
        if (now < w.start.toInstant()) return w.start.toInstant()
        if (w.contains(now) && (s.keepLooking || completed != w.key)) {
            val candidate = if (afterAttempt) now.plusSeconds(s.interval * 60L) else now
            if (candidate < w.end.toInstant()) return candidate
        }
        return on(w.start.toLocalDate().plusDays(1), zone, s).start.toInstant()
    }
}
fun stableId(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
fun mergeMeasurements(old: List<Measurement>, incoming: List<Measurement>): List<Measurement> {
    val merged = old.associateBy { it.id }.toMutableMap()
    incoming.forEach { m ->
        val previous = merged[m.id]
        merged[m.id] = when {
            previous == null -> m
            previous.kg == m.kg && previous.fat == m.fat -> previous
            else -> m.copy(revision = previous.revision + 1, sentRevision = previous.sentRevision)
        }
    }
    return merged.values.sortedBy { it.time }
}
fun eligible(m: Measurement, s: Settings) = m.profile == s.profile && m.time >= s.since && m.revision > m.sentRevision
fun endsMorning(m: Measurement, window: Window) = m.fat != null && window.contains(Instant.ofEpochMilli(m.time))

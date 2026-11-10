package dev.s400bridge

import java.time.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimingTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val s = Settings(automatic = true)
    private fun at(value: String) = ZonedDateTime.parse(value).toInstant()
    @Test fun startInclusiveEndExclusive() {
        val w = Timing.window(at("2026-11-10T06:00+01:00[Europe/Berlin]"), zone, s)
        assertTrue(w.contains(at("2026-11-10T06:00+01:00[Europe/Berlin]")))
        assertFalse(w.contains(at("2026-11-10T07:30+01:00[Europe/Berlin]")))
        assertFalse(w.contains(w.start.toInstant().minusSeconds(1)))
    }
    @Test fun schedulesAtStartBeforeWindow() {
        val now = at("2026-11-10T05:00+01:00[Europe/Berlin]")
        assertEquals(at("2026-11-10T06:00+01:00[Europe/Berlin]"), Timing.next(now, zone, s, null, false))
    }
    @Test fun intervalInsideWindow() {
        val now = at("2026-11-10T06:01+01:00[Europe/Berlin]")
        assertEquals(now.plusSeconds(900), Timing.next(now, zone, s, null, true))
    }
    @Test fun intervalCannotLeakPastEnd() {
        val now = at("2026-11-10T07:25+01:00[Europe/Berlin]")
        assertEquals(at("2026-11-11T06:00+01:00[Europe/Berlin]"), Timing.next(now, zone, s, null, true))
    }
    @Test fun completeMorningMovesToTomorrow() {
        val now = at("2026-11-10T06:20+01:00[Europe/Berlin]")
        val window = Timing.window(now, zone, s)
        assertEquals(at("2026-11-11T06:00+01:00[Europe/Berlin]"), Timing.next(now, zone, s, window.key, true))
        assertEquals(now.plusSeconds(900), Timing.next(now, zone, s.copy(keepLooking = true), window.key, true))
    }
    @Test fun overnightWindowBelongsToPreviousDay() {
        val now = at("2026-11-11T00:30+01:00[Europe/Berlin]")
        val w = Timing.window(now, zone, s.copy(start = 23 * 60, end = 60))
        assertEquals(LocalDate.of(2026, 11, 10), w.start.toLocalDate())
        assertTrue(w.contains(now))
    }
    @Test fun overnightEndSchedulesTonight() {
        val now = at("2026-11-11T01:00+01:00[Europe/Berlin]")
        assertEquals(at("2026-11-11T23:00+01:00[Europe/Berlin]"), Timing.next(now, zone, s.copy(start = 1380, end = 60), null, false))
    }
    @Test fun springDstUsesLocalMorning() {
        val now = at("2026-03-28T08:00+01:00[Europe/Berlin]")
        assertEquals(at("2026-03-29T06:00+02:00[Europe/Berlin]"), Timing.next(now, zone, s, null, false))
    }
    @Test fun autumnDstUsesLocalMorning() {
        val now = at("2026-10-24T08:00+02:00[Europe/Berlin]")
        assertEquals(at("2026-10-25T06:00+01:00[Europe/Berlin]"), Timing.next(now, zone, s, null, false))
    }
    @Test fun dstGapAndOverlapHaveFiniteWindow() {
        listOf("2026-03-29T00:00:00Z", "2026-10-25T00:00:00Z").forEach {
            val w = Timing.window(Instant.parse(it), zone, s.copy(start = 150, end = 240))
            assertTrue(w.start.toInstant() < w.end.toInstant())
        }
    }
    @Test(expected = IllegalArgumentException::class) fun equalTimesRejected() { Timing.window(Instant.now(), zone, s.copy(end = s.start)) }
    @Test fun timezoneChangeUsesNewLocalTime() {
        val now = Instant.parse("2026-11-10T04:00:00Z")
        assertEquals(Instant.parse("2026-11-10T06:00:00Z"), Timing.next(now, ZoneOffset.UTC, s, null, false))
    }
}
class ParserTest {
    private fun row(source: Int, data: JsonObject, account: String = "42", uid: String = "99", model: String = ScaleParser.MODEL) = buildJsonObject {
        put("model", model); put("uid", uid); put("accountId", account); put("did", "scale-device")
        put("createTime", 1_794_290_400_000L); put("fromSource", source); put("data", data.toString())
    }
    private fun data(fat: String? = "25.6", source: Int = 1) = buildJsonObject {
        put("weight", "68.4"); put("time", "1794290400000")
        if (source == 3) put("bodyResData", buildJsonObject { fat?.let { put("bfp", it) } }.toString())
        else fat?.let { put("bfp", it) }
        put("user", buildJsonObject { put("name", "Alice") })
    }
    @Test fun mapsEachObservedSourceFormat() {
        (1..3).forEach { source ->
            val (p, m) = ScaleParser.parse(JsonArray(listOf(row(source, data(source = source)))), "de", "99")
            assertEquals("de:99:42", p.single().key); assertEquals(68.4, m.single().kg, 0.0); assertEquals(25.6, m.single().fat!!, 0.0)
            assertEquals(1_794_290_400_000L, m.single().time)
        }
    }
    @Test fun missingFatIsNeverFabricated() {
        assertNull(ScaleParser.parse(JsonArray(listOf(row(1, data(null)))), "de", "99").second.single().fat)
    }
    @Test fun zeroFatIsNotACompleteMeasurement() {
        assertNull(ScaleParser.parse(JsonArray(listOf(row(1, data("0")))), "de", "99").second.single().fat)
    }
    @Test fun ignoresOtherModelsAndOwners() {
        assertTrue(ScaleParser.parse(JsonArray(listOf(row(1, data(), uid = "100"), row(1, data(), model = "other"))), "de", "99").second.isEmpty())
    }
    @Test fun idsDoNotDependOnMutableValues() {
        val a = ScaleParser.parse(JsonArray(listOf(row(1, data()))), "de", "99").second.single()
        val b = ScaleParser.parse(JsonArray(listOf(row(1, data("26")))) , "de", "99").second.single()
        assertEquals(a.id, b.id)
    }
    @Test(expected = ProtocolFailure::class) fun unknownSourceFailsClosed() { ScaleParser.parse(JsonArray(listOf(row(8, data()))), "de", "99") }
    @Test(expected = ProtocolFailure::class) fun missingIdentityFailsClosed() { ScaleParser.parse(JsonArray(listOf(row(1, data(), account = ""))), "de", "99") }
    @Test fun originalType3TimeWinsOverCreateTime() {
        val d = JsonObject(data(source = 3) + ("time" to JsonPrimitive("1794200000000")))
        assertEquals(1_794_200_000_000L, ScaleParser.parse(JsonArray(listOf(row(3, d))), "de", "99").second.single().time)
    }
}
class QueueTest {
    private val m = Measurement("stable-id", "me", 1_794_290_400_000L, 68.4, 25.6)
    private fun state(vararg records: Measurement) = AppState(settings = Settings(profile = "me", since = 0), measurements = records.toList())
    private class Sink: RecordSink {
        var permission = true; var fail = false
        val records = mutableMapOf<String, Measurement>(); var calls = 0
        override suspend fun ready() = permission
        override suspend fun write(m: Measurement) { calls++; if (fail) throw java.io.IOException(); records[m.id] = m }
    }
    @Test fun acknowledgementFollowsSuccessfulWrite() = runTest {
        val sink = Sink(); var ack = false
        drainQueue(state(m), sink, { true }) { assertTrue(sink.records.containsKey(it.id)); ack = true }
        assertTrue(ack)
    }
    @Test fun failedWriteRemainsPendingAndCanRetry() = runTest {
        val sink = Sink(); sink.fail = true; var ack = 0
        try { drainQueue(state(m), sink, { true }) { ack++ }; fail() } catch (_: java.io.IOException) { }
        assertEquals(0, ack); sink.fail = false
        drainQueue(state(m), sink, { true }) { ack++ }; assertEquals(1, ack)
    }
    @Test fun crashBetweenWriteAndAckReplaysSameId() = runTest {
        val sink = Sink()
        try { drainQueue(state(m), sink, { true }) { throw java.io.IOException() } } catch (_: java.io.IOException) { }
        drainQueue(state(m), sink, { true }) { }
        assertEquals(2, sink.calls); assertEquals(1, sink.records.size)
    }
    @Test fun permissionDenialPreservesQueue() = runTest {
        val sink = Sink(); sink.permission = false
        try { drainQueue(state(m), sink, { true }) { fail() }; fail() } catch (_: SecurityException) { }
        assertEquals(0, sink.calls)
    }
    @Test fun acknowledgedRecordsAndOtherProfilesAreExcluded() = runTest {
        val sink = Sink()
        drainQueue(state(m.copy(sentRevision = 1), m.copy(id = "other", profile = "family")), sink, { true }) { fail() }
        assertEquals(0, sink.calls)
    }
    @Test fun endsAtWindowBoundaryBeforeWriting() = runTest {
        val sink = Sink()
        try { drainQueue(state(m), sink, { false }) { fail() }; fail() } catch (_: WindowClosed) { }
        assertEquals(0, sink.calls)
    }
    @Test fun missingFatDoesNotStopMorning() {
        val w = Window(Instant.ofEpochMilli(m.time - 1000).atZone(ZoneOffset.UTC), Instant.ofEpochMilli(m.time + 1000).atZone(ZoneOffset.UTC))
        assertTrue(endsMorning(m, w)); assertFalse(endsMorning(m.copy(fat = null), w)); assertFalse(endsMorning(m.copy(time = m.time - 10000), w))
    }
    @Test fun mergeTracksCorrectionsAndKeepsUnchangedAcknowledgement() {
        val sent = m.copy(sentRevision = 1)
        assertEquals(sent, mergeMeasurements(listOf(sent), listOf(m)).single())
        val revised = mergeMeasurements(listOf(sent), listOf(m.copy(fat = 26.0))).single()
        assertEquals(2L, revised.revision); assertEquals(1L, revised.sentRevision); assertTrue(eligible(revised, state().settings))
    }
    @Test fun baselineExcludesOlderMeasurements() { assertFalse(eligible(m, Settings(profile = "me", since = m.time + 1))) }
}
class CryptoTest {
    @Test fun nonceUsesBigEndianMinutes() {
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,0,0,0,2), XiaomiCrypto.nonce(120000, byteArrayOf(1,2,3,4,5,6,7,8)))
    }
    @Test fun rc4Drop1024MatchesIndependentVector() {
        // Generated independently with Python's RC4 KSA/PRGA and a 1024-byte drop.
        val encrypted = XiaomiCrypto.crypt("test-key".toByteArray(), "hello scale".toByteArray())
        assertEquals("2abe9acc8e34e2080ec6dd", encrypted.joinToString("") { "%02x".format(it) })
        assertEquals("hello scale", XiaomiCrypto.crypt("test-key".toByteArray(), encrypted).toString(Charsets.UTF_8))
    }
    @Test fun signatureMatchesKnownSha1() {
        assertEquals("rZ8gmqB28U3Y4WXCAikgeQ8JO8o=", XiaomiCrypto.signature("/eco/test", mapOf("data" to "{}"), byteArrayOf(1,2,3)))
    }
    @Test fun verificationUrlMustBeHttpsAndXiaomiOwned() {
        assertTrue(safeXiaomiUrl("https://account.xiaomi.com/verify")); assertFalse(safeXiaomiUrl("https://xiaomi.com.evil.org/")); assertFalse(safeXiaomiUrl("http://account.xiaomi.com/")); assertFalse(safeXiaomiUrl("https://account.xiaomi.com@evil.org/"))
    }
}

class RegionRoutingTest {
    @Test fun taiwanUsesSingaporeHost() { assertEquals("sg", xiaomiServer("tw")) }
    @Test fun existingRegionsKeepTheirHosts() { listOf("de", "ru", "us", "sg", "i2").forEach { assertEquals(it, xiaomiServer(it)) } }
    @Test(expected = IllegalArgumentException::class) fun unrecognizedRegionCannotBecomeAHostname() { xiaomiServer("untrusted.example") }
}

class BrowserLoginTest {
    private fun response(browser: String = "https://eu.account.xiaomi.com/longPolling/login?sid=session", polling: String = "https://eu.lp.account.xiaomi.com/lp/s?sid=session", timeout: String = "300") = buildJsonObject {
        put("code", 0); put("loginUrl", browser); put("lp", polling)
        put("qr", "https://account.xiaomi.com/pass/qr/login?sid=session"); put("timeout", timeout)
    }
    @Test fun parsesActualAccountHostShapes() {
        val login = BrowserLogin.parse(response())
        assertEquals(300L, login.timeoutSeconds)
        assertTrue(login.browserUrl.startsWith("https://eu.account.xiaomi.com/"))
    }
    @Test fun acceptsPrefixedAndPlainLoginResponses() {
        val text = response().toString()
        assertEquals(parseXiaomiJson(text), parseXiaomiJson("&&&START&&&$text"))
    }
    @Test fun rejectsDeceptiveHostsAndInsecureTransport() {
        listOf("https://account.xiaomi.com.evil.org/", "https://evilaccount.xiaomi.com/", "http://account.xiaomi.com/", "https://account.xiaomi.com@evil.org/", "https://account.xiaomi.com:8443/", "javascript:alert(1)").forEach { assertFalse(it, safeAccountUrl(it)) }
    }
    @Test(expected = ProtocolFailure::class) fun cannotPollAnUntrustedHost() { BrowserLogin.parse(response(polling = "https://example.org/steal")) }
    @Test(expected = ProtocolFailure::class) fun rejectsMissingPollingUrl() { BrowserLogin.parse(JsonObject(response() - "lp")) }
    @Test(expected = ProtocolFailure::class) fun rejectsUnboundedExpiry() { BrowserLogin.parse(response(timeout = "999999")) }
    @Test fun rejectedResponseDoesNotExposeItsContent() {
        try { parseXiaomiJson("private-token-value"); fail() } catch (e: ProtocolFailure) { assertFalse(e.message!!.contains("private-token-value")) }
    }
    @Test(expected = AuthFailure::class) fun failedStartIsNotTreatedAsConnected() { BrowserLogin.parse(JsonObject(response() + ("code" to JsonPrimitive(70001)))) }
}

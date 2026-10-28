package com.jw6ventures.headlink

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream

/**
 * One HTTP GET, injected so the host tests can prove it is never called.
 * [UrlConnectionHttp] is the real one.
 */
fun interface TrafficHttp {
    /** Sends a GET for [url] with [headers]. The response's body must be closed. */
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): TrafficResponse
}

/**
 * A response: its status, its `Content-Encoding` (gzip or not), and the body as it came
 * off the wire. [close] releases the connection.
 */
class TrafficResponse(
    val status: Int,
    val contentEncoding: String?,
    val body: InputStream,
    private val release: () -> Unit = {},
) : Closeable {
    override fun close() {
        try {
            body.close()
        } finally {
            release()
        }
    }
}

/**
 * The platform's `HttpURLConnection` (no new library). Redirects are not
 * followed, so a request can never be handed on to a host other than the one asked; no
 * cache, since HERE sends no cache headers and nothing may be kept.
 */
class UrlConnectionHttp(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : TrafficHttp {
    override fun get(url: String, headers: Map<String, String>): TrafficResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            val status = connection.responseCode
            // An error's body comes from the error stream; getInputStream would throw
            // with the URL, key included, in its message.
            val body = (if (status >= 400) connection.errorStream else connection.inputStream)
                ?: ByteArrayInputStream(ByteArray(0))
            return TrafficResponse(status, connection.getHeaderField("Content-Encoding"), body) { connection.disconnect() }
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }
}

/** Why nothing was sent. */
enum class TrafficSkip(val message: String) {
    OFF("Live traffic is off"),
    NO_KEY("No HERE API key is saved"),
    /** The message the caller shows is [TrafficStatus.limitReached], which names the month. */
    LIMIT("Monthly request limit reached"),
}

/** What one [TrafficClient.fetch] came to. */
sealed class TrafficFetch {
    /** Both responses, read. */
    class Fetched(val snapshot: TrafficSnapshot) : TrafficFetch()

    /** Nothing was sent, because of [why]; [message] is the card's words for it. */
    class Skipped(val why: TrafficSkip, val message: String) : TrafficFetch()

    /**
     * Sent, and it went wrong: HERE's own error in words, or why HERE could not be reached
     * or read. [httpStatus] is HERE's status, or null when there was no answer.
     */
    class Failed(val message: String, val httpStatus: Int?) : TrafficFetch()
}

/** The card's Test: [works], and "works" or what went wrong. */
class TrafficCheck(val works: Boolean, val message: String)

/**
 * The HTTP side of live traffic: the gate, the count, the two requests and
 * the parsing. It decides nothing about *when* to fetch; the caller does that, while a
 * route is followed.
 *
 * **Threading:** every call blocks on the network and on the count's synchronous write,
 * so it must be made off the main thread. Calls on several threads are safe: the count's
 * check and increment are one atomic [TrafficLedger.update].
 *
 * Its guarantees, each host-tested (TrafficClientTest):
 * - with live traffic off, or on with no key, [http] is never called;
 * - at the monthly limit it is never called, until the next calendar month;
 * - every request is counted, and stored, before it is sent;
 * - a snapshot is only the box it was asked for: nothing is kept between calls, so no
 *   response is ever reused for another box or held beyond the next (HERE terms, 6.4 j).
 *
 * The key goes only in the request's `apiKey` parameter, to HERE's host (or, in a
 * debuggable build only, the lab's stand-in). It is never in a message this returns.
 */
class TrafficClient(
    private val settings: () -> TrafficSettings,
    private val ledger: TrafficLedger,
    private val http: TrafficHttp,
    /** `SystemClock.elapsedRealtime` in the app; stamped on each snapshot. */
    private val elapsed: () -> Long,
    /** Wall clock, for the month counted and the last fetch time. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Whether [TrafficSettings.baseUrl] is honoured: a debuggable build only. */
    private val debuggable: Boolean = false,
    private val zone: ZoneId = ZoneOffset.UTC,
) {
    /** Why a call needing [need] requests would send nothing now, or null when it may go ahead. */
    fun gate(need: Int = FETCH_REQUESTS): TrafficSkip? = gate(settings(), need)

    /**
     * Flow and incidents for [box]: exactly two GETs, flow first, each counted
     * before it is sent. Nothing is sent unless a whole fetch fits under the limit. When
     * flow fails, incidents are not asked for.
     */
    fun fetch(box: TrafficBox): TrafficFetch {
        val raw = settings()
        gate(raw, FETCH_REQUESTS)?.let { return skipped(it) }
        val s = raw.repaired()
        val base = base(s)
        val flowUrl = "$base/v7/flow?in=${box.query()}&locationReferencing=shape&functionalClasses=1,2,3&apiKey=${encode(s.apiKey)}"
        val incidentsUrl = "$base/v7/incidents?in=${box.query()}&locationReferencing=shape&apiKey=${encode(s.apiKey)}"
        val flow = when (val got = request(s, flowUrl, FETCH_REQUESTS) { TrafficJson.flow(it) }) {
            is Outcome.Ok -> got.value
            is Outcome.Stopped -> return got.result
        }
        val incidents = when (val got = request(s, incidentsUrl, 1) { TrafficJson.incidents(it) }) {
            is Outcome.Ok -> got.value
            is Outcome.Stopped -> return got.result
        }
        ledger.update { it.copy(lastFetchMs = now(), lastError = null) }
        return TrafficFetch.Fetched(TrafficSnapshot(box, elapsed(), flow, incidents))
    }

    /**
     * The card's **Test**: one incidents request for [TEST_BOX], read whole.
     * Counted like any other, and gated the same way, so it too sends nothing while the
     * switch is off, which is the switch's guarantee.
     */
    fun test(): TrafficCheck {
        val raw = settings()
        gate(raw, 1)?.let { return TrafficCheck(false, skipped(it).message) }
        val s = raw.repaired()
        val url = "${base(s)}/v7/incidents?in=${TEST_BOX.query()}&locationReferencing=shape&apiKey=${encode(s.apiKey)}"
        return when (val got = request(s, url, 1) { TrafficJson.incidents(it) }) {
            is Outcome.Ok -> TrafficCheck(true, "works")
            is Outcome.Stopped -> TrafficCheck(false, (got.result as? TrafficFetch.Failed)?.message
                ?: (got.result as TrafficFetch.Skipped).message)
        }
    }

    private fun gate(s: TrafficSettings, need: Int): TrafficSkip? = when {
        !s.enabled -> TrafficSkip.OFF
        TrafficSettings.cleanKey(s.apiKey) == null -> TrafficSkip.NO_KEY
        ledger.status().reserve(month(), s.repaired().monthlyLimit, 0, need) == null -> TrafficSkip.LIMIT
        else -> null
    }

    private fun skipped(why: TrafficSkip) = TrafficFetch.Skipped(
        why, if (why == TrafficSkip.LIMIT) TrafficStatus.limitReached(month()) else why.message,
    )

    private sealed class Outcome<out T> {
        class Ok<T>(val value: T) : Outcome<T>()
        class Stopped(val result: TrafficFetch) : Outcome<Nothing>()
    }

    /**
     * One GET: counted first (room for [need] requests, one counted), then sent, then read
     * by [parse] off the decompressed body. Failures are recorded as the last error.
     */
    private fun <T> request(s: TrafficSettings, url: String, need: Int, parse: (InputStreamReader) -> T): Outcome<T> {
        // The gate again, with the settings as they are now: traffic turned off or the key
        // removed while the flow request was out sends no incidents request after it.
        gate(settings(), need)?.let { return Outcome.Stopped(skipped(it)) }
        val counted = try {
            ledger.update { it.reserve(month(), s.monthlyLimit, 1, need) }
        } catch (e: RuntimeException) {
            return Outcome.Stopped(TrafficFetch.Failed("Could not count the request, so it was not sent", null))
        }
        if (counted == null) return Outcome.Stopped(skipped(TrafficSkip.LIMIT))
        var status: Int? = null
        return try {
            http.get(url, HEADERS).use { response ->
                status = response.status
                val body = decoded(response)
                if (response.status !in 200..299) {
                    val text = readText(body, ERROR_BODY_LIMIT)
                    val what = TrafficJson.error(text) ?: "no explanation given"
                    fail(s, "HERE ${response.status}: $what", response.status)
                } else {
                    Outcome.Ok(parse(InputStreamReader(body, Charsets.UTF_8)))
                }
            }
        } catch (e: TrafficParseException) {
            fail(s, "HERE's answer could not be read (${e.message})", status)
        } catch (e: IOException) {
            fail(s, "Could not reach HERE: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}", status)
        } catch (e: RuntimeException) {
            // HttpURLConnection throws unchecked exceptions too (a malformed answer, a
            // security refusal): a failed request like any other, never a crash on the
            // caller's thread. Its own words are left out; they may hold the URL.
            fail(s, "Could not reach HERE: ${e.javaClass.simpleName}", status)
        }
    }

    private fun fail(s: TrafficSettings, message: String, status: Int?): Outcome.Stopped {
        val shown = shorten(redact(message, s.apiKey))
        try {
            ledger.update { it.copy(lastError = shown) }
        } catch (e: RuntimeException) {
            // The error could not be stored: it is still the answer.
        }
        return Outcome.Stopped(TrafficFetch.Failed(shown, status))
    }

    /**
     * HERE's host, or in a debuggable build the lab's stand-in when one is set and its host
     * is one of [LAB_HOSTS]. Any other override is ignored: `lab_traffic_url` reaches the
     * saved settings through an exported activity, and must never send the key elsewhere.
     */
    private fun base(s: TrafficSettings): String = labBase(s, debuggable) ?: HERE_BASE

    private fun month(): YearMonth = YearMonth.from(Instant.ofEpochMilli(now()).atZone(zone))

    companion object {
        const val HERE_BASE = "https://data.traffic.hereapi.com"
        /** A fetch is flow and incidents. */
        const val FETCH_REQUESTS = 2
        /**
         * Test's box: about 1 km square in downtown Dallas, the same for everyone, so a
         * test says nothing about where the phone is.
         */
        val TEST_BOX = TrafficBox(south = 32.775, west = -96.805, north = 32.785, east = -96.795)

        /** Flow is six to seven times smaller gzipped. */
        val HEADERS = mapOf("Accept-Encoding" to "gzip", "Accept" to "application/json")

        private const val ERROR_BODY_LIMIT = 16 * 1024
        private const val MESSAGE_LIMIT = 240

        private fun encode(key: String): String = URLEncoder.encode(key, "UTF-8")

        /**
         * The lab's stand-in hosts (P9), the only ones a base URL override may name: the
         * emulator's alias for the lab host, and the loopback an `adb reverse` port arrives on.
         * The debug build's network security config allows cleartext to exactly these.
         */
        val LAB_HOSTS = setOf("10.0.2.2", "127.0.0.1", "localhost")

        /**
         * The override [s] names, when [debuggable] and its host is one of [LAB_HOSTS]; null
         * when requests go to HERE. The Traffic card shows it while it is in force.
         */
        fun labBase(s: TrafficSettings, debuggable: Boolean): String? {
            if (!debuggable) return null
            val base = s.repaired().baseUrl.takeIf { it.isNotEmpty() } ?: return null
            // No user part: "http://x@y" is read differently by different URL parsers.
            val host = try {
                java.net.URI(base).takeIf { it.rawUserInfo == null && !base.substringAfter("://").contains('@') }?.host?.lowercase()
            } catch (e: java.net.URISyntaxException) {
                null
            }
            return base.takeIf { host != null && host in LAB_HOSTS }
        }

        /**
         * The body decompressed when it is gzip: by its `Content-Encoding`, or by gzip's
         * magic bytes, since a stand-in server or a proxy may not say.
         */
        fun decoded(response: TrafficResponse): InputStream {
            val raw = BufferedInputStream(response.body)
            val saysGzip = response.contentEncoding?.contains("gzip", ignoreCase = true) == true
            raw.mark(2)
            val first = raw.read()
            val second = raw.read()
            raw.reset()
            return if (saysGzip || (first == 0x1f && second == 0x8b)) GZIPInputStream(raw) else raw
        }

        private fun readText(body: InputStream, limit: Int): String {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (out.size() < limit) {
                val n = body.read(chunk, 0, minOf(chunk.size, limit - out.size()))
                if (n < 0) break
                out.write(chunk, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }

        /** [message] with the key, and anything after an `apiKey=`, taken out. */
        fun redact(message: String, key: String): String {
            var text = message
            if (key.isNotEmpty()) text = text.replace(key, "[key]")
            return text.replace(Regex("apiKey=[^&\\s]*"), "apiKey=[key]")
        }

        private fun shorten(message: String): String =
            message.replace(Regex("\\s+"), " ").trim().let { if (it.length > MESSAGE_LIMIT) it.take(MESSAGE_LIMIT - 1) + "…" else it }
    }
}

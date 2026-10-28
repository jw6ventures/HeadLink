package com.jw6ventures.headlink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.URLEncoder

/**
 * Settings → Home Assistant: one button on the dashboard that calls one Home Assistant
 * service, such as `cover.open_cover` on `cover.garage_door`, on the operator's own server.
 *
 * Off by default, and the button is shown only while [usable]: on, with an address, a
 * token and a service. The target is optional, since a script or a scene can be called
 * with none. [repaired] keeps whatever cannot be sent out of the stored form, so a stored
 * value is always one [HomeAssistantClient] can use as it stands.
 *
 * Pure Kotlin, like [TrafficSettings]: [HomeAssistantPrefs] moves [toStored]'s map in and
 * out of its own preferences file, encrypting the token there, so the host test checks
 * the defaults, the repair and the round trip. The token is a secret: [toString] hides it,
 * and it never reaches a note, the report or a message [HomeAssistantClient] returns.
 */
data class HomeAssistantSettings(
    /** The dashboard button on. Shown only while [usable]. */
    val enabled: Boolean = false,
    /** The server's address, `https://home.example.com:8123`, or "" for none ([cleanUrl]). */
    val serverUrl: String = "",
    /** A long-lived access token from the operator's Home Assistant profile, or "". */
    val token: String = "",
    /** The service the button calls, `domain.service` ([cleanService]), or "". */
    val service: String = "",
    /** The entity or entities it is called on, comma separated ([cleanTarget]), or "" for none. */
    val target: String = "",
) {
    /** Everything a call needs is saved. */
    val configured: Boolean get() = serverUrl.isNotEmpty() && token.isNotEmpty() && service.isNotEmpty()

    /** The button is shown and a press is sent: on and [configured]. */
    val usable: Boolean get() = enabled && configured

    /**
     * This value with anything that cannot be sent cleared, its fields tidied, and off when
     * not [configured], so no stored combination is on and unusable.
     */
    fun repaired(): HomeAssistantSettings {
        val tidy = copy(
            serverUrl = cleanUrl(serverUrl) ?: "",
            token = cleanToken(token) ?: "",
            service = cleanService(service) ?: "",
            target = cleanTarget(target) ?: "",
        )
        return tidy.copy(enabled = tidy.enabled && tidy.configured)
    }

    /** Every field under its stored key. Never renamed once shipped. */
    fun toStored(): Map<String, Any> {
        val s = repaired()
        return linkedMapOf(
            ENABLED to s.enabled,
            SERVER_URL to s.serverUrl,
            TOKEN to s.token,
            SERVICE to s.service,
            TARGET to s.target,
        )
    }

    /** Hides the token, so a stray log line or exception cannot print it. */
    override fun toString(): String =
        "HomeAssistantSettings(enabled=$enabled, serverUrl=$serverUrl, token=${if (token.isEmpty()) "none" else "set"}, " +
            "service=$service, target=$target)"

    companion object {
        private val ID = Regex("[a-z0-9_]+\\.[a-z0-9_]+")

        /**
         * The server's address as typed, trimmed, without a trailing `/` or `/api`, or null
         * when it is not one: not https, or with a user, a query or a fragment. https only,
         * because the token goes with every request and Android refuses plain http to any
         * host in a release build. A typed host with no scheme is taken as https.
         */
        fun cleanUrl(text: String): String? {
            var url = text.trim()
            if (url.isEmpty() || url.length > 512 || url.any { it.isWhitespace() || it.isISOControl() }) return null
            if (!url.contains("://")) url = "https://$url"
            url = url.trimEnd('/').removeSuffix("/api").trimEnd('/')
            val uri = try {
                URI(url)
            } catch (e: URISyntaxException) {
                return null
            }
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null || url.substringAfter("://").contains('@')) return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            return url
        }

        /**
         * A token as typed or pasted, trimmed, or null when it cannot be one: empty, far
         * longer than Home Assistant's (about 180 characters), or holding a space or
         * control character (a paste that caught more than the token). A pasted
         * "Bearer " in front is dropped.
         */
        fun cleanToken(text: String): String? {
            val token = text.trim().removePrefix("Bearer ").trim()
            if (token.isEmpty() || token.length > 2048) return null
            if (token.any { it.isWhitespace() || it.isISOControl() }) return null
            return token
        }

        /** `domain.service` as typed, trimmed and lower case, or null when it is not one. */
        fun cleanService(text: String): String? = text.trim().lowercase().takeIf { ID.matches(it) }

        /**
         * One entity or several, comma separated, as typed: trimmed, lower case and joined by
         * ", ". "" for none, and null when any of them is not an entity id.
         */
        fun cleanTarget(text: String): String? {
            val ids = text.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (ids.any { !ID.matches(it) }) return null
            return ids.distinct().joinToString(", ")
        }

        /** The entity ids [target] holds, in order. */
        fun targets(target: String): List<String> = target.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        /** Reads what [toStored] wrote; a missing or mistyped value reads as its default. */
        fun fromStored(stored: Map<String, *>): HomeAssistantSettings {
            val d = HomeAssistantSettings()
            return HomeAssistantSettings(
                enabled = stored[ENABLED] as? Boolean ?: d.enabled,
                serverUrl = stored[SERVER_URL] as? String ?: d.serverUrl,
                token = stored[TOKEN] as? String ?: d.token,
                service = stored[SERVICE] as? String ?: d.service,
                target = stored[TARGET] as? String ?: d.target,
            ).repaired()
        }

        // Stored keys.
        const val ENABLED = "ha_enabled"
        const val SERVER_URL = "ha_server_url"
        const val TOKEN = "ha_token"
        const val SERVICE = "ha_service"
        const val TARGET = "ha_target"
    }
}

/** One request to Home Assistant, injected so the host tests can stand in for the server. */
fun interface HomeAssistantHttp {
    /** Sends [method] to [url] with [headers] and [body] (null for none); the reply, read whole. */
    @Throws(IOException::class)
    fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HomeAssistantReply
}

/** A reply: its status and at most [HomeAssistantClient.BODY_LIMIT] bytes of its body as text. */
class HomeAssistantReply(val status: Int, val body: String)

/**
 * The platform's `HttpURLConnection`, as [UrlConnectionHttp] is for traffic: no new library,
 * redirects not followed, so the token is never handed on to a host other than the one
 * typed, and nothing cached.
 */
class UrlConnectionHomeAssistant(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : HomeAssistantHttp {
    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HomeAssistantReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = method
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = (if (status >= 400) connection.errorStream else connection.inputStream)
                ?: ByteArrayInputStream(ByteArray(0))
            return HomeAssistantReply(status, stream.use { read(it) })
        } finally {
            connection.disconnect()
        }
    }

    private fun read(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < HomeAssistantClient.BODY_LIMIT) {
            val n = input.read(buffer, 0, minOf(buffer.size, HomeAssistantClient.BODY_LIMIT - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }
}

/** What a press or the card's Check came to: [ok], and the card's words for it. */
class HomeAssistantResult(val ok: Boolean, val message: String)

/**
 * The HTTP side of the Home Assistant button: [press] calls the saved service through
 * Home Assistant's REST API (`POST /api/services/<domain>/<service>`), and [check] proves
 * the address, the token, the service and the target without calling anything.
 *
 * **Threading:** both block on the network, so they must be called off the main thread.
 *
 * Each guarantee is host-tested (HomeAssistantTest): with the button off or not configured,
 * [http] is never called; a press is exactly one POST, to the saved server, with the token
 * only in its `Authorization` header; [check] sends only GETs. The token is never in a
 * message this returns.
 */
class HomeAssistantClient(
    private val settings: () -> HomeAssistantSettings,
    private val http: HomeAssistantHttp,
) {
    /** Calls the saved service on the saved target, once. */
    fun press(): HomeAssistantResult {
        val s = settings().repaired()
        if (!s.enabled) return HomeAssistantResult(false, "The Home Assistant button is off")
        if (!s.configured) return HomeAssistantResult(false, NOT_CONFIGURED)
        val (domain, service) = s.service.split('.', limit = 2)
        val url = "${s.serverUrl}/api/services/${encode(domain)}/${encode(service)}"
        val reply = try {
            http.send("POST", url, headers(s.token, json = true), body(s.target))
        } catch (e: IOException) {
            return HomeAssistantResult(false, unreachable(e))
        }
        return when (reply.status) {
            in 200..299 -> HomeAssistantResult(true, "Called ${s.service}" + (if (s.target.isEmpty()) "" else " on ${s.target}"))
            401, 403 -> HomeAssistantResult(false, REFUSED_TOKEN)
            // Home Assistant answers an unknown service, or a target it cannot take, with 400.
            400 -> HomeAssistantResult(false, "Home Assistant could not call ${s.service}" + detail(reply) + ". Check the service and the target.")
            else -> HomeAssistantResult(false, "Home Assistant answered ${reply.status}" + detail(reply))
        }
    }

    /**
     * The card's Check: the API answers with this token, the service is one Home Assistant
     * offers, and each target is an entity it knows. GETs only, so nothing is opened, run
     * or switched. Works whether the button is on or off, so it can be checked first.
     */
    fun check(): HomeAssistantResult {
        val s = settings().repaired()
        if (!s.configured) return HomeAssistantResult(false, NOT_CONFIGURED)
        try {
            val api = http.send("GET", "${s.serverUrl}/api/", headers(s.token, json = false), null)
            when (api.status) {
                in 200..299 -> Unit
                401, 403 -> return HomeAssistantResult(false, REFUSED_TOKEN)
                else -> return HomeAssistantResult(false, "Home Assistant answered ${api.status}" + detail(api))
            }
            val services = http.send("GET", "${s.serverUrl}/api/services", headers(s.token, json = false), null)
            if (services.status !in 200..299) {
                return HomeAssistantResult(false, "Home Assistant answered ${services.status} for its services" + detail(services))
            }
            val offered = try {
                serviceNames(services.body)
            } catch (e: IOException) {
                return HomeAssistantResult(false, "Home Assistant's list of services could not be read")
            }
            if (s.service !in offered) {
                return HomeAssistantResult(false, "Home Assistant has no service ${s.service}" + suggestion(s.service, offered))
            }
            for (id in HomeAssistantSettings.targets(s.target)) {
                val state = http.send("GET", "${s.serverUrl}/api/states/${encode(id)}", headers(s.token, json = false), null)
                when (state.status) {
                    in 200..299 -> Unit
                    404 -> return HomeAssistantResult(false, "Home Assistant has no entity $id")
                    else -> return HomeAssistantResult(false, "Home Assistant answered ${state.status} for $id")
                }
            }
        } catch (e: IOException) {
            return HomeAssistantResult(false, unreachable(e))
        }
        val on = if (s.target.isEmpty()) "" else " on ${s.target}"
        return HomeAssistantResult(true, "Works: ${s.service}$on is ready. Nothing was called.")
    }

    companion object {
        const val BODY_LIMIT = 512 * 1024
        private const val MESSAGE_LIMIT = 160
        private const val NOT_CONFIGURED = "Save the address, the token and the service first"
        private const val REFUSED_TOKEN = "Home Assistant refused the token. Make a new long-lived access token and save it."

        private fun headers(token: String, json: Boolean): Map<String, String> {
            val headers = linkedMapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
            if (json) headers["Content-Type"] = "application/json"
            return headers
        }

        /**
         * `{"entity_id": [...]}`, or `{}` for no target. The ids are [HomeAssistantSettings.cleanTarget]'s,
         * letters, digits, `_` and `.` only, so nothing in them needs escaping.
         */
        fun body(target: String): ByteArray {
            val ids = HomeAssistantSettings.targets(target)
            val json = if (ids.isEmpty()) "{}" else ids.joinToString(",", "{\"entity_id\":[", "]}") { "\"$it\"" }
            return json.toByteArray(Charsets.UTF_8)
        }

        /**
         * Every `domain.service` in `GET /api/services`'s answer:
         * `[{"domain": "cover", "services": {"open_cover": {...}, ...}}, ...]`.
         */
        @Throws(IOException::class)
        fun serviceNames(body: String): Set<String> {
            val names = HashSet<String>()
            val json = JsonStream(body.reader())
            json.forEachItem {
                var domain: String? = null
                val services = ArrayList<String>()
                json.forEachField { field ->
                    when (field) {
                        "domain" -> domain = json.optString()
                        "services" -> json.forEachField { name ->
                            services.add(name)
                            json.skipValue()
                        }
                        else -> json.skipValue()
                    }
                }
                domain?.let { d -> services.forEach { names.add("$d.$it") } }
            }
            return names
        }

        /** " Did you mean cover.open_cover?" when one service of the same domain starts with the typed one. */
        private fun suggestion(service: String, offered: Set<String>): String {
            val near = offered.filter { it.startsWith(service) }.sorted()
            return if (near.isEmpty()) "" else ". Did you mean ${near.first()}?"
        }

        /** Home Assistant's own words from an error body, `{"message": "..."}`, short, or "". */
        private fun detail(reply: HomeAssistantReply): String {
            val message = try {
                var found: String? = null
                val json = JsonStream(reply.body.reader())
                json.forEachField { name -> if (name == "message") found = json.optString() else json.skipValue() }
                found
            } catch (e: IOException) {
                null
            }
            return message?.trim()?.takeIf { it.isNotEmpty() }?.let { ": " + it.take(MESSAGE_LIMIT) } ?: ""
        }

        /** Why the server could not be reached, by kind; the exception's text can carry the URL. */
        private fun unreachable(e: IOException): String = when (e) {
            is java.net.UnknownHostException -> "The server's address could not be found"
            is java.net.SocketTimeoutException -> "The server did not answer in time"
            is javax.net.ssl.SSLException -> "The server's https certificate was not accepted"
            is java.net.ConnectException -> "The server could not be reached"
            else -> "The server could not be reached (${e.javaClass.simpleName})"
        }

        private fun encode(part: String): String = URLEncoder.encode(part, "UTF-8")
    }
}

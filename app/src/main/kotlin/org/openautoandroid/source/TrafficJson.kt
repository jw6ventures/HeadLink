package org.openautoandroid.source

import java.io.IOException
import java.io.Reader

/*
 * Live traffic (P9, docs/p9-plan.md §4 "What the responses hold"): HERE's flow, incident
 * and error bodies read into TrafficModel's types.
 *
 * The plan names org.json. It is not used, for two reasons: a 1° flow response is 9 MB of
 * JSON, which org.json would hold as one string and then as a tree, and neither org.json
 * nor android.util.JsonReader is on the host tests' classpath, so nothing built on them
 * could be host-tested. [JsonStream] is a small pull reader in plain Kotlin instead, read
 * straight off the (decompressed) response stream. No new library (docs/p9-1-results.md).
 *
 * Every field the plan lists is read by name; anything else, including fields HERE may add
 * later, is skipped whole. Optional fields that are missing take the defaults written
 * beside them, and a result with no geometry is dropped rather than failing the response.
 */

/** A body that is not the JSON HERE sends. An [IOException], so a fetch treats it as one. */
class TrafficParseException(message: String) : IOException(message)

/**
 * A forward-only JSON reader over a [Reader], shaped like android.util.JsonReader: peek at
 * the next token, then step into it, read it, or skip it. It keeps one small buffer and a
 * stack of open containers, never the document.
 */
class JsonStream(private val input: Reader) {
    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END }

    private val buffer = CharArray(8192)
    private var pos = 0
    private var limit = 0
    private var stack = IntArray(32)
    private var depth = 1
    private var peeked: Token? = null
    private val text = StringBuilder()

    init {
        stack[0] = EMPTY_DOCUMENT
    }

    fun peek(): Token {
        peeked?.let { return it }
        val token = when (stack[depth - 1]) {
            EMPTY_ARRAY -> {
                stack[depth - 1] = NONEMPTY_ARRAY
                if (next() == ']'.code) Token.END_ARRAY else { pos--; value() }
            }
            NONEMPTY_ARRAY -> when (next()) {
                ']'.code -> Token.END_ARRAY
                ','.code -> value()
                else -> fail("expected , or ]")
            }
            EMPTY_OBJECT, NONEMPTY_OBJECT -> {
                val empty = stack[depth - 1] == EMPTY_OBJECT
                stack[depth - 1] = DANGLING_NAME
                var c = next()
                if (c == '}'.code) {
                    Token.END_OBJECT
                } else {
                    if (!empty) {
                        if (c != ','.code) fail("expected , or }")
                        c = next()
                    }
                    if (c != '"'.code) fail("expected a name")
                    Token.NAME
                }
            }
            DANGLING_NAME -> {
                stack[depth - 1] = NONEMPTY_OBJECT
                if (next() != ':'.code) fail("expected :")
                value()
            }
            EMPTY_DOCUMENT -> {
                stack[depth - 1] = NONEMPTY_DOCUMENT
                value()
            }
            else -> if (nextOrEnd() == -1) Token.END else fail("more than one value")
        }
        peeked = token
        return token
    }

    fun hasNext(): Boolean = peek().let { it != Token.END_OBJECT && it != Token.END_ARRAY && it != Token.END }

    fun beginObject() = open(Token.BEGIN_OBJECT, EMPTY_OBJECT)
    fun beginArray() = open(Token.BEGIN_ARRAY, EMPTY_ARRAY)
    fun endObject() = close(Token.END_OBJECT)
    fun endArray() = close(Token.END_ARRAY)

    fun nextName(): String {
        expect(Token.NAME)
        return quoted()
    }

    /** A string, or a number's text. */
    fun nextString(): String = when (peek()) {
        Token.STRING -> { peeked = null; quoted() }
        Token.NUMBER -> { peeked = null; number() }
        else -> fail("expected a string, found ${peek()}")
    }

    /** A number, or a string holding one. */
    fun nextDouble(): Double {
        val raw = nextString()
        return raw.toDoubleOrNull() ?: fail("not a number: $raw")
    }

    fun nextBoolean(): Boolean {
        expect(Token.BOOLEAN)
        return when (next()) {
            't'.code -> { literal("rue"); true }
            else -> { literal("alse"); false }
        }
    }

    fun nextNull() {
        expect(Token.NULL)
        literal("null")
    }

    /** Steps over the next value whole, however deep. */
    fun skipValue() {
        var open = 0
        do {
            when (peek()) {
                Token.BEGIN_OBJECT -> { beginObject(); open++ }
                Token.BEGIN_ARRAY -> { beginArray(); open++ }
                Token.END_OBJECT -> { endObject(); open-- }
                Token.END_ARRAY -> { endArray(); open-- }
                Token.NAME -> nextName()
                Token.STRING, Token.NUMBER -> nextString()
                Token.BOOLEAN -> nextBoolean()
                Token.NULL -> nextNull()
                Token.END -> fail("unexpected end")
            }
        } while (open > 0)
    }

    /** The next value as a double, or null when it is null or not a number; skipped either way. */
    fun optDouble(): Double? = when (peek()) {
        // Finite only: "NaN", "Infinity" and 1e999 are no number HERE means, and a NaN or an
        // infinity that reached a no-go point would fail RouteRequest's check on the main thread.
        Token.NUMBER, Token.STRING -> nextString().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> { skipValue(); null }
    }

    /** The next value as a string, or null when it is anything else; skipped either way. */
    fun optString(): String? = when (peek()) {
        Token.STRING, Token.NUMBER -> nextString()
        else -> { skipValue(); null }
    }

    /** The next value as a boolean, or null when it is anything else; skipped either way. */
    fun optBoolean(): Boolean? = when (peek()) {
        Token.BOOLEAN -> nextBoolean()
        else -> { skipValue(); null }
    }

    /** Calls [field] for each name of the object that comes next; a null or non-object is skipped. */
    inline fun forEachField(field: (String) -> Unit) {
        if (peek() != Token.BEGIN_OBJECT) { skipValue(); return }
        beginObject()
        while (hasNext()) field(nextName())
        endObject()
    }

    /** Calls [item] for each element of the array that comes next; a null or non-array is skipped. */
    inline fun forEachItem(item: () -> Unit) {
        if (peek() != Token.BEGIN_ARRAY) { skipValue(); return }
        beginArray()
        while (hasNext()) item()
        endArray()
    }

    private fun open(token: Token, scope: Int) {
        expect(token)
        if (depth == stack.size) stack = stack.copyOf(depth * 2)
        stack[depth++] = scope
    }

    private fun close(token: Token) {
        expect(token)
        depth--
    }

    private fun expect(token: Token) {
        if (peek() != token) fail("expected $token, found ${peek()}")
        peeked = null
    }

    /** The token a value starts with; its first character is left unread except for a quote. */
    private fun value(): Token = when (val c = next()) {
        '{'.code -> Token.BEGIN_OBJECT
        '['.code -> Token.BEGIN_ARRAY
        '"'.code -> Token.STRING
        't'.code, 'f'.code -> { pos--; Token.BOOLEAN }
        'n'.code -> { pos--; Token.NULL }
        else -> if (c == '-'.code || c in '0'.code..'9'.code) { pos--; Token.NUMBER } else fail("unexpected character")
    }

    private fun quoted(): String {
        text.setLength(0)
        while (true) {
            if (pos == limit && !fill()) fail("unterminated string")
            val c = buffer[pos++]
            when (c) {
                '"' -> return text.toString()
                '\\' -> {
                    if (pos == limit && !fill()) fail("unterminated escape")
                    when (val e = buffer[pos++]) {
                        'n' -> text.append('\n')
                        't' -> text.append('\t')
                        'r' -> text.append('\r')
                        'b' -> text.append('\b')
                        'f' -> text.append('\u000c')
                        'u' -> {
                            var code = 0
                            repeat(4) {
                                if (pos == limit && !fill()) fail("unterminated escape")
                                val digit = Character.digit(buffer[pos++], 16)
                                if (digit < 0) fail("bad \\u escape")
                                code = code * 16 + digit
                            }
                            text.append(code.toChar())
                        }
                        else -> text.append(e)
                    }
                }
                else -> text.append(c)
            }
        }
    }

    private fun number(): String {
        text.setLength(0)
        while (pos < limit || fill()) {
            val c = buffer[pos]
            if (c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                text.append(c)
                pos++
            } else {
                break
            }
        }
        return text.toString()
    }

    private fun literal(rest: String) {
        for (expected in rest) {
            if (pos == limit && !fill()) fail("unexpected end")
            if (buffer[pos++] != expected) fail("bad literal")
        }
    }

    /** The next character that is not white space. */
    private fun next(): Int = nextOrEnd().also { if (it == -1) fail("unexpected end") }

    private fun nextOrEnd(): Int {
        while (pos < limit || fill()) {
            val c = buffer[pos++]
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return c.code
        }
        return -1
    }

    private fun fill(): Boolean {
        val read = input.read(buffer, 0, buffer.size)
        if (read <= 0) return false
        pos = 0
        limit = read
        return true
    }

    private fun fail(why: String): Nothing = throw TrafficParseException("not HERE's JSON: $why")

    private companion object {
        const val EMPTY_ARRAY = 1
        const val NONEMPTY_ARRAY = 2
        const val EMPTY_OBJECT = 3
        const val DANGLING_NAME = 4
        const val NONEMPTY_OBJECT = 5
        const val EMPTY_DOCUMENT = 6
        const val NONEMPTY_DOCUMENT = 7
    }
}

/** HERE's flow, incident and error bodies (plan §4). */
object TrafficJson {
    /** A flow response's `results[]`. */
    @Throws(IOException::class)
    fun flow(input: Reader): List<FlowStretch> {
        val json = JsonStream(input)
        val stretches = ArrayList<FlowStretch>()
        json.forEachField { name ->
            if (name == "results") json.forEachItem { flowResult(json)?.let(stretches::add) } else json.skipValue()
        }
        return stretches
    }

    /** An incidents response's `results[]`. */
    @Throws(IOException::class)
    fun incidents(input: Reader): List<TrafficIncident> {
        val json = JsonStream(input)
        val incidents = ArrayList<TrafficIncident>()
        json.forEachField { name ->
            if (name == "results") json.forEachItem { incident(json)?.let(incidents::add) } else json.skipValue()
        }
        return incidents
    }

    /**
     * An error body in words for the card's status line, or null when it is neither of
     * HERE's two shapes (plan §4): `{title, status, code, cause, action, correlationId}`
     * for a bad request, and `{error, error_description}` for a bad key (401). The
     * correlation id is left out: it is for HERE's support, and too long for a status line.
     */
    fun error(body: String): String? {
        val fields = HashMap<String, String>()
        try {
            val json = JsonStream(body.reader())
            json.forEachField { name -> json.optString()?.let { fields[name] = it } }
        } catch (e: IOException) {
            return null
        }
        val title = fields["title"]
        val cause = fields["cause"]
        val code = fields["code"]
        val error = fields["error"]
        val description = fields["error_description"]
        return when {
            title != null || cause != null ->
                listOfNotNull(title, cause).joinToString(": ") + (code?.let { " ($it)" } ?: "")
            error != null || description != null -> listOfNotNull(error, description).joinToString(": ")
            else -> null
        }
    }

    /** One flow result, or null when it has no geometry or no `currentFlow`. */
    private fun flowResult(json: JsonStream): FlowStretch? {
        var location: Location? = null
        var flow: Figures? = null
        json.forEachField { name ->
            when (name) {
                "location" -> location = location(json)
                "currentFlow" -> flow = figures(json, top = true)
                else -> json.skipValue()
            }
        }
        val where = location ?: return null
        val figures = flow ?: return null
        if (where.latitudes.isEmpty()) return null
        val length = where.length ?: where.linkLength
        val subs = figures.subSegments
        val spans = if (subs.isNullOrEmpty()) {
            listOf(figures.span(0.0, length, null))
        } else {
            // Laid end to end by their own lengths, in order (plan §4); a sub-segment that
            // leaves a figure out takes its stretch's, except speed, which HERE leaves out
            // only where it is closed.
            var from = 0.0
            subs.map { sub ->
                val to = from + (sub.length ?: 0.0)
                sub.span(from, to, figures).also { from = to }
            }
        }
        return FlowStretch(where.description, length, where.latitudes, where.longitudes, where.functionalClass, spans)
    }

    private fun incident(json: JsonStream): TrafficIncident? {
        var location: Location? = null
        var id: String? = null
        var originalId: String? = null
        var type: String? = null
        var criticality: String? = null
        var roadClosed = false
        var summary: String? = null
        var description: String? = null
        json.forEachField { name ->
            when (name) {
                "location" -> location = location(json)
                "incidentDetails" -> json.forEachField { field ->
                    when (field) {
                        "id" -> id = json.optString()
                        "originalId" -> originalId = json.optString()
                        "type" -> type = json.optString()
                        "criticality" -> criticality = json.optString()
                        "roadClosed" -> roadClosed = json.optBoolean() ?: false
                        "summary" -> summary = value(json)
                        "description" -> description = value(json)
                        else -> json.skipValue()
                    }
                }
                else -> json.skipValue()
            }
        }
        val where = location ?: return null
        if (where.latitudes.isEmpty()) return null
        return TrafficIncident(
            id = id ?: originalId ?: "",
            type = type ?: "unknown",
            criticality = criticality,
            roadClosed = roadClosed,
            summary = summary?.takeIf { it.isNotBlank() } ?: description?.takeIf { it.isNotBlank() },
            latitudes = where.latitudes,
            longitudes = where.longitudes,
        )
    }

    /** A `{value, language}` text's value. */
    private fun value(json: JsonStream): String? {
        var text: String? = null
        json.forEachField { name -> if (name == "value") text = json.optString() else json.skipValue() }
        return text
    }

    private class Location(
        val description: String?,
        val length: Double?,
        val linkLength: Double,
        val latitudes: DoubleArray,
        val longitudes: DoubleArray,
        val functionalClass: Int,
    )

    /**
     * `location`: its links' points joined in order into one polyline, the point a link
     * repeats from the one before dropped; the lowest `functionalClass` among them (5, the
     * most minor, when none says).
     */
    private fun location(json: JsonStream): Location {
        var description: String? = null
        var length: Double? = null
        var linkLength = 0.0
        val lats = Points()
        val lons = Points()
        var functionalClass = Int.MAX_VALUE
        json.forEachField { name ->
            when (name) {
                "description" -> description = json.optString()
                "length" -> length = json.optDouble()
                "shape" -> json.forEachField { shape ->
                    if (shape == "links") {
                        json.forEachItem {
                            json.forEachField { link ->
                                when (link) {
                                    "points" -> json.forEachItem { point(json, lats, lons) }
                                    "length" -> linkLength += json.optDouble() ?: 0.0
                                    "functionalClass" -> json.optDouble()?.let { functionalClass = minOf(functionalClass, it.toInt()) }
                                    else -> json.skipValue()
                                }
                            }
                        }
                    } else {
                        json.skipValue()
                    }
                }
                else -> json.skipValue()
            }
        }
        return Location(
            description?.takeIf { it.isNotBlank() }, length, linkLength,
            lats.toArray(), lons.toArray(),
            if (functionalClass == Int.MAX_VALUE) 5 else functionalClass.coerceIn(1, 5),
        )
    }

    private fun point(json: JsonStream, lats: Points, lons: Points) {
        var lat: Double? = null
        var lng: Double? = null
        json.forEachField { name ->
            when (name) {
                "lat" -> lat = json.optDouble()
                "lng" -> lng = json.optDouble()
                else -> json.skipValue()
            }
        }
        val a = lat ?: return
        val b = lng ?: return
        // A point off the globe is dropped, not the whole stretch: the rest of its line is good.
        if (a !in -90.0..90.0 || b !in -180.0..180.0) return
        if (lats.size > 0 && lats.last() == a && lons.last() == b) return
        lats.add(a)
        lons.add(b)
    }

    /** `currentFlow`, or one of its `subSegments` (which have a `length` and no sub-segments of their own). */
    private class Figures(
        val length: Double?,
        val speed: Double?,
        val freeFlow: Double?,
        val jamFactor: Double?,
        val traversability: Traversability?,
        val subSegments: List<Figures>?,
    ) {
        fun span(from: Double, to: Double, parent: Figures?) = FlowSpan(
            fromM = from,
            toM = to,
            speedMps = speed,
            freeFlowMps = freeFlow ?: parent?.freeFlow,
            jamFactor = jamFactor ?: parent?.jamFactor ?: 0.0,
            traversability = traversability ?: parent?.traversability ?: Traversability.OPEN,
        )
    }

    private fun figures(json: JsonStream, top: Boolean): Figures {
        var length: Double? = null
        var speed: Double? = null
        var freeFlow: Double? = null
        var jamFactor: Double? = null
        var traversability: Traversability? = null
        var subs: MutableList<Figures>? = null
        json.forEachField { name ->
            when (name) {
                "length" -> length = json.optDouble()
                "speed" -> speed = json.optDouble()
                "freeFlow" -> freeFlow = json.optDouble()
                "jamFactor" -> jamFactor = json.optDouble()
                "traversability" -> traversability = traversability(json.optString())
                "subSegments" -> if (top) {
                    val list = ArrayList<Figures>()
                    json.forEachItem { list.add(figures(json, top = false)) }
                    subs = list
                } else {
                    json.skipValue()
                }
                else -> json.skipValue()
            }
        }
        return Figures(length, speed, freeFlow, jamFactor, traversability, subs)
    }

    /**
     * HERE's three values. One it may add later reads as open: an unknown word must not
     * close a road to routing (plan §7's hard nogos are for what HERE says is closed).
     */
    fun traversability(value: String?): Traversability? = when (value) {
        null -> null
        "closed" -> Traversability.CLOSED
        "reversibleNotRoutable" -> Traversability.REVERSIBLE_NOT_ROUTABLE
        else -> Traversability.OPEN
    }

    /** A growable array of doubles, so a long stretch is not boxed point by point. */
    private class Points {
        private var values = DoubleArray(16)
        var size = 0
            private set

        fun add(value: Double) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun last(): Double = values[size - 1]

        fun toArray(): DoubleArray = values.copyOf(size)
    }
}

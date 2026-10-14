package org.openautoandroid.source

import java.text.Normalizer

/**
 * A house number as typed: "123", "123B", "123 1/2". [value] is the leading integer, [suffix]
 * what follows it, folded ("b", "1/2"); [text] is how a label shows it ("123B", "123 1/2").
 */
class HouseNumber(val value: Int, val suffix: String) {
    val text: String
        get() = when {
            suffix.isEmpty() -> value.toString()
            suffix.length == 1 -> value.toString() + suffix.uppercase()
            else -> "$value $suffix"
        }

    /** Same number, compared the way two house numbers are equal on a street. */
    fun sameAs(other: HouseNumber): Boolean = value == other.value && suffix == other.suffix

    /** Ordering along a street: by [value], then the bare number before any suffix. */
    fun compareTo(other: HouseNumber): Int =
        if (value != other.value) value.compareTo(other.value) else suffix.compareTo(other.suffix)

    override fun toString(): String = text

    companion object {
        private val NUMBER = Regex("""^(\d{1,6})(?:\s*-?\s*([a-z])(?![a-z0-9])|\s*(1/2|1/4|3/4)(?![0-9]))?""")

        /**
         * A house number as a file stores it ("123", "123B", "123-125", "12 1/2"): its
         * leading integer and a one-letter or fraction suffix; null when it starts with no
         * digit. A range like "123-125" reads as 123.
         */
        fun parse(stored: String): HouseNumber? {
            val text = AddressText.fold(stored).replace("½", " 1/2").trim()
            val m = NUMBER.find(text) ?: return null
            val value = m.groupValues[1].toIntOrNull() ?: return null
            val suffix = m.groupValues[2].ifEmpty { m.groupValues[3] }
            return HouseNumber(value, suffix)
        }
    }
}

/**
 * One reading of what was typed into the search box (plan §4). The parser doesn't guess
 * where the street ends and the city begins: [AddressQuery.parse] returns every plausible
 * reading, best first, and the search runs them all.
 *
 * Every text field is normalised by [AddressText.normalize], so it compares directly with a
 * normalised name from a file.
 */
class AddressQuery(
    /** The leading house number, or null. */
    val houseNumber: HouseNumber?,
    /** The street part, normalised ("congress ave", "fm 1431"); "" when none was typed. */
    val street: String,
    /** The street part lower-cased with punctuation removed but no word rewritten, for partly typed words ("congress aven"). */
    val streetRaw: String,
    /** The city part, normalised; null when this reading has no city ("use cities near me"). */
    val city: String?,
    /** True when a comma marked where the city starts, so the split is not a guess. */
    val cityFromComma: Boolean,
    /** A trailing state, as its lower-case full name with underscores ("texas", "new_york"). */
    val state: String?,
    /** A trailing 5-digit ZIP. */
    val zip: String?,
    /** The whole text minus ZIP and state, folded: what a POI name search uses. */
    val nameText: String,
) {
    /** Only a city (or the start of one) was typed: "Austin", "Au". */
    val cityOnly: Boolean get() = houseNumber == null && street.isEmpty() && city != null

    override fun toString(): String =
        "AddressQuery(number=$houseNumber, street='$street', city=$city, comma=$cityFromComma, state=$state, zip=$zip)"

    companion object {
        /** Most trailing words tried as the city: "Round Rock", "Lago Vista", "Sunset Valley", "Horseshoe Bay Ranch". */
        const val MAX_CITY_WORDS = 3

        /**
         * Every plausible reading of [text], best first. Commas, case and punctuation don't
         * matter; a comma, when present, decides where the city starts. A trailing two-letter
         * state code is read both as a state and as part of the city ("Oak Ct" is not
         * Connecticut), and with no comma each of the last [MAX_CITY_WORDS] words may start
         * the city. Empty for empty text.
         */
        fun parse(text: String): List<AddressQuery> {
            val folded = AddressText.fold(text).replace("½", " 1/2")
            // Comma-separated segments of raw words, punctuation already gone from each word.
            val segments = folded.split(',', ';').map(AddressText::words).filter { it.isNotEmpty() }
            if (segments.isEmpty()) return emptyList()
            val all = segments.flatten()
            val nameText = all.joinToString(" ")
            // Where each segment starts, in word positions.
            val segmentStarts = ArrayList<Int>()
            var at = 0
            for (s in segments) { segmentStarts.add(at); at += s.size }

            var end = all.size
            var zip: String? = null
            if (end >= 1 && ZIP.matches(all[end - 1])) {
                zip = all[end - 1].substring(0, 5)
                end--
            }
            // State, then a ZIP typed after it was handled above; also "78701 TX" is rare enough to skip.
            val stateReadings = ArrayList<Pair<String?, Int>>()
            val stateFull = trailingStateName(all, end)
            if (stateFull != null) {
                stateReadings.add(stateFull.first to end - stateFull.second)
            } else if (end >= 1 && end - 1 > 0 && STATE_CODES.containsKey(all[end - 1])) {
                stateReadings.add(STATE_CODES.getValue(all[end - 1]) to end - 1)
                stateReadings.add(null to end) // the code may be a word of the name: "Oak Ct", "Main St Al".
            } else {
                stateReadings.add(null to end)
            }
            // A state being typed ("Austin T", "Austin Te"): also read without that word, so
            // the suggestions don't vanish for a keystroke or two.
            if (stateFull == null && end - 1 > 0 && all[end - 1].length <= 4 && zip == null &&
                STATE_PREFIXES.any { it.startsWith(all[end - 1]) && it != all[end - 1] }) {
                stateReadings.add(null to end - 1)
            }

            val out = ArrayList<AddressQuery>()
            val seen = HashSet<String>()
            for ((state, stop) in stateReadings) {
                val words = all.subList(0, stop)
                var start = 0
                var number: HouseNumber? = null
                // A leading number: "123", "123b", "123 1/2", "123-b"; never the only word (that is a ZIP or a number being typed).
                if (words.isNotEmpty() && words[0].all(Char::isDigit) && words[0].length <= 6) {
                    val first = words[0].toInt()
                    when {
                        words.size >= 2 && words[1] in FRACTIONS -> { number = HouseNumber(first, words[1]); start = 2 }
                        else -> { number = HouseNumber(first, ""); start = 1 }
                    }
                } else if (words.isNotEmpty()) {
                    LETTER_NUMBER.matchEntire(words[0])?.let {
                        number = HouseNumber(it.groupValues[1].toInt(), it.groupValues[2]); start = 1
                    }
                }
                if (number != null && words.size == start && zip == null) {
                    // Fewer than 3 digits narrows nothing: no reading at all.
                    if (words[0].length < 3 || !words[0].all(Char::isDigit)) continue
                    // Only a number so far: "1100" is a house number being typed, or a ZIP's start.
                    // Keep it as a postcode prefix reading; the search decides.
                    val n = words[0]
                    add(out, seen, AddressQuery(null, "", "", n, false, state, null, nameText))
                    continue
                }
                val rest = words.subList(start, words.size)
                // Where the first comma after the number falls, if any, inside rest.
                val comma = segmentStarts.firstOrNull { it > start && it < stop }?.minus(start)
                if (rest.isEmpty()) {
                    // Only a ZIP (and maybe a state): the postcode itself.
                    if (zip != null) add(out, seen, AddressQuery(number, "", "", null, false, state, zip, nameText))
                    continue
                }
                if (comma != null) {
                    val street = rest.subList(0, comma)
                    val city = rest.subList(comma, rest.size)
                    add(out, seen, reading(number, street, city, true, state, zip, nameText))
                    continue
                }
                // No comma: no city (cities near the phone, or the ZIP's), then 1..3 trailing words as the city.
                add(out, seen, reading(number, rest, emptyList(), false, state, zip, nameText))
                for (k in 1..minOf(MAX_CITY_WORDS, rest.size - 1)) {
                    add(out, seen, reading(number, rest.subList(0, rest.size - k), rest.subList(rest.size - k, rest.size), false, state, zip, nameText))
                }
                // No number and short: the whole thing may be only a city ("Austin", "Round Rock", "Au").
                if (number == null && rest.size <= MAX_CITY_WORDS) {
                    add(out, seen, reading(null, emptyList(), rest, false, state, zip, nameText))
                }
            }
            return out
        }

        private fun reading(
            number: HouseNumber?, street: List<String>, city: List<String>, comma: Boolean,
            state: String?, zip: String?, nameText: String,
        ): AddressQuery = AddressQuery(
            houseNumber = number,
            street = AddressText.normalizeWords(street),
            streetRaw = street.joinToString(" "),
            city = if (city.isEmpty()) null else AddressText.normalizeWords(city),
            cityFromComma = comma,
            state = state,
            zip = zip,
            nameText = nameText,
        )

        private fun add(out: MutableList<AddressQuery>, seen: MutableSet<String>, q: AddressQuery) {
            if (seen.add(q.toString())) out.add(q)
        }

        /** A full state name ending at [end] ("texas", "new mexico"): its key and word count. */
        private fun trailingStateName(words: List<String>, end: Int): Pair<String, Int>? {
            for (n in 3 downTo 1) {
                if (end - n < 1) continue // never the whole query
                val name = words.subList(end - n, end).joinToString(" ")
                STATE_NAMES[name]?.let { return it to n }
            }
            return null
        }

        private val ZIP = Regex("""\d{5}(\d{4})?""")
        private val LETTER_NUMBER = Regex("""(\d{1,6})([a-z])""")
        private val FRACTIONS = setOf("1/2", "1/4", "3/4")

        private val STATES = listOf(
            "al" to "alabama", "ak" to "alaska", "az" to "arizona", "ar" to "arkansas", "ca" to "california",
            "co" to "colorado", "ct" to "connecticut", "de" to "delaware", "dc" to "district of columbia",
            "fl" to "florida", "ga" to "georgia", "hi" to "hawaii", "id" to "idaho", "il" to "illinois",
            "in" to "indiana", "ia" to "iowa", "ks" to "kansas", "ky" to "kentucky", "la" to "louisiana",
            "me" to "maine", "md" to "maryland", "ma" to "massachusetts", "mi" to "michigan", "mn" to "minnesota",
            "ms" to "mississippi", "mo" to "missouri", "mt" to "montana", "ne" to "nebraska", "nv" to "nevada",
            "nh" to "new hampshire", "nj" to "new jersey", "nm" to "new mexico", "ny" to "new york",
            "nc" to "north carolina", "nd" to "north dakota", "oh" to "ohio", "ok" to "oklahoma", "or" to "oregon",
            "pa" to "pennsylvania", "ri" to "rhode island", "sc" to "south carolina", "sd" to "south dakota",
            "tn" to "tennessee", "tx" to "texas", "ut" to "utah", "vt" to "vermont", "va" to "virginia",
            "wa" to "washington", "wv" to "west virginia", "wi" to "wisconsin", "wy" to "wyoming",
        )
        /** Two-letter code to the key a file name carries ("tx" to "texas"). */
        private val STATE_CODES: Map<String, String> = STATES.associate { (code, name) -> code to name.replace(' ', '_') }
        private val STATE_NAMES: Map<String, String> = STATES.associate { (_, name) -> name to name.replace(' ', '_') }
        /** Codes and one-word names, for a state half typed. */
        private val STATE_PREFIXES: List<String> = STATES.flatMap { (code, name) -> listOf(code, name.substringBefore(' ')) }
    }
}

/**
 * The text folding both sides of a comparison go through: what was typed, and every city
 * and street name read from a file. Street type and direction words become one canonical
 * short form each way round ("Street" and "St" both "st"), and numbered roads one prefix
 * ("Farm to Market Road 1431" and "FM 1431" both "fm 1431"; "I-35", "IH 35" and
 * "Interstate 35" all "i 35").
 */
object AddressText {
    /** Lower case, accents dropped, apostrophes removed; other punctuation kept for the caller. */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            if (c == '\'' || c == '’' || c == '`') continue
            sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * Words of already-[fold]ed text: letters and digits, split at anything else, except
     * that "1/2" stays one word.
     */
    fun words(folded: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < folded.length) {
            val c = folded[i]
            if (c.isLetterOrDigit()) {
                sb.append(c)
            } else if (c == '/' && sb.isNotEmpty() && sb.all(Char::isDigit) && i + 1 < folded.length && folded[i + 1].isDigit()) {
                sb.append(c)
            } else if (sb.isNotEmpty()) {
                out.add(sb.toString()); sb.setLength(0)
            }
            i++
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** [text] folded, split into words and rewritten to canonical words, joined by single spaces. */
    fun normalize(text: String): String = normalizeWords(words(fold(text)))

    /** Already-split words rewritten to canonical words, joined by single spaces. */
    fun normalizeWords(words: List<String>): String = canonical(words).joinToString(" ")

    /** The canonical words of already-split words. */
    fun canonical(words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var matched = false
            for ((phrase, canon, needsNumber) in PHRASES) {
                val n = phrase.size
                if (i + n > words.size) continue
                if ((0 until n).any { words[i + it] != phrase[it] }) continue
                // Route prefixes only before a number: "US 183" yes, "Us" alone no; "I 35" yes, "I" alone no.
                if (needsNumber && !(i + n < words.size && words[i + n].firstOrNull()?.isDigit() == true)) continue
                out.add(canon)
                i += n
                matched = true
                break
            }
            if (matched) continue
            val w = words[i]
            out.add(SINGLE[w] ?: w)
            i++
        }
        return out
    }

    /** True for a canonical direction word ("n", "sw"). */
    fun isDirection(word: String): Boolean = word in DIRECTIONS

    /** True for a canonical street type word ("st", "ave"). */
    fun isType(word: String): Boolean = word in TYPES

    private data class Phrase(val words: List<String>, val canon: String, val needsNumber: Boolean)

    private val DIRECTIONS = setOf("n", "s", "e", "w", "ne", "nw", "se", "sw")
    private val TYPES = HashSet<String>()

    private val SINGLE: Map<String, String>
    private val PHRASES: List<Phrase>

    init {
        val single = HashMap<String, String>()
        fun type(canon: String, vararg forms: String) {
            TYPES.add(canon)
            single[canon] = canon
            for (f in forms) single[f] = canon
        }
        type("st", "street", "str")
        type("ave", "avenue", "av", "avn")
        type("rd", "road")
        type("dr", "drive", "drv")
        type("blvd", "boulevard", "blv", "boul")
        type("ln", "lane")
        type("pkwy", "parkway", "pky", "pkway")
        type("hwy", "highway", "hiway", "hy")
        type("fwy", "freeway", "frwy")
        type("expy", "expressway", "expwy", "expressway", "exp")
        type("ct", "court", "crt")
        type("cir", "circle", "circ")
        type("trl", "trail", "tr")
        type("pl", "place")
        type("way")
        type("loop", "lp")
        type("ter", "terrace", "terr")
        type("cv", "cove")
        type("xing", "crossing")
        type("pass")
        type("path")
        type("run")
        type("sq", "square")
        type("plz", "plaza")
        type("holw", "hollow")
        type("bnd", "bend")
        type("vw", "view")
        type("spur")
        val dirs = mapOf(
            "north" to "n", "south" to "s", "east" to "e", "west" to "w",
            "northeast" to "ne", "northwest" to "nw", "southeast" to "se", "southwest" to "sw",
        )
        for ((long, short) in dirs) { single[long] = short; single[short] = short }
        SINGLE = single

        val phrases = ArrayList<Phrase>()
        fun route(canon: String, vararg forms: String) {
            for (f in forms) phrases.add(Phrase(f.split(' '), canon, true))
        }
        route("fm", "farm to market road", "farm to market rd", "farm to market", "farm road", "fm road", "fm rd", "fm")
        route("rm", "ranch to market road", "ranch to market rd", "ranch to market", "ranch road", "ranch rd", "rm road", "rm", "rr")
        route("sh", "texas state highway", "state highway", "state hwy", "tx highway", "texas highway", "sh", "tx", "tsh")
        route("us", "united states highway", "us highway", "us hwy", "u s highway", "u s", "us", "usa highway")
        route("i", "interstate highway", "interstate hwy", "interstate", "ih", "i")
        route("cr", "county road", "county rd", "co road", "co rd", "cr")
        route("loop", "state loop", "loop")
        route("spur", "state spur", "spur")
        route("pr", "park road")
        route("rec", "recreational road")
        // Longest first, so "farm to market road" wins over "farm to market".
        phrases.sortByDescending { it.words.size }
        PHRASES = phrases
    }
}

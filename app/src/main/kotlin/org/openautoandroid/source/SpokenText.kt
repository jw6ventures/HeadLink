package org.openautoandroid.source

import kotlin.math.roundToLong

/**
 * How a turn prompt is said (P8 plan §5.3): distances in words, road names expanded so a
 * speech engine does not spell them out, and the maneuver sentence built from the same
 * [Maneuvers.instruction] the turn card and the cluster show.
 *
 * Pure and total, for the host tests. Everything here is for the voice only: nothing on
 * the card or the cluster is ever passed through [road], so what the driver reads stays
 * what OSM calls the road.
 */
object SpokenText {
    private const val METRES_PER_FOOT = 0.3048
    private const val METRES_PER_MILE = 1609.344
    /** Up to and including this many feet, a distance is said in feet. */
    private const val FEET_UP_TO = 1000.0

    /**
     * [metres] as a driver would say it, in the system [unit] belongs to: up to 1000 feet
     * as feet to the hundred ("500 feet"); then "a quarter mile", "half a mile",
     * "three quarters of a mile", "1 mile"; then to the half mile ("1 and a half miles").
     * Metric: metres to the fifty below a kilometre, then kilometres to the half.
     *
     * Feet reach 1000 rather than the plan's first 0.1 mi (P8.2 deviation, §5.3): a 30 mph
     * Prepare is about 200 m, 0.12 mi, and quarters would round that up to "a quarter mile".
     *
     * Never zero: a prompt is always about something ahead, so the least it says is
     * "100 feet" or "50 metres".
     */
    fun distance(metres: Double, unit: SpeedUnit): String {
        val m = if (metres.isFinite()) metres.coerceAtLeast(0.0) else 0.0
        return when (unit) {
            SpeedUnit.MPH -> imperial(m)
            SpeedUnit.KMH -> metric(m)
        }
    }

    private fun imperial(m: Double): String {
        val feet = m / METRES_PER_FOOT
        if (feet <= FEET_UP_TO) return "${(feet / 100.0).roundToLong().coerceAtLeast(1) * 100} feet"
        val miles = m / METRES_PER_MILE
        // Quarters up to the mile; 1.125 is where the half-mile steps round to 1 anyway.
        if (miles < 1.125) {
            return when ((miles * 4.0).roundToLong().coerceIn(1, 4)) {
                1L -> "a quarter mile"
                2L -> "half a mile"
                3L -> "three quarters of a mile"
                else -> "1 mile"
            }
        }
        return halves((miles * 2.0).roundToLong(), "mile", "miles")
    }

    private fun metric(m: Double): String {
        val rounded = (m / 50.0).roundToLong().coerceAtLeast(1) * 50
        if (rounded < 1000) return "$rounded metres"
        return halves((m / 500.0).roundToLong().coerceAtLeast(2), "kilometre", "kilometres")
    }

    /** [count] half units, at least two, as "1 mile", "1 and a half miles", "2 miles". */
    private fun halves(count: Long, one: String, many: String): String {
        val whole = count / 2
        return when {
            count % 2 == 1L -> "$whole and a half $many"
            whole == 1L -> "1 $one"
            else -> "$whole $many"
        }
    }

    /**
     * Route-number prefixes a speech engine would read letter by letter, and what is said
     * instead. Texas's, because the real map is Texas (plan §5.3); `IH` is how TxDOT itself
     * writes an interstate. `Loop` is a word already and is not here.
     */
    private val PREFIXES = mapOf(
        "I" to "Interstate",
        "IH" to "Interstate",
        "US" to "U S",
        "SH" to "State Highway",
        "TX" to "State Highway",
        "FM" to "Farm to Market",
        "RM" to "Ranch to Market",
        "CR" to "County Road",
    )

    /** A prefix before its number (`I 35`, `I-35`), or `US` before `Highway`. */
    private val PREFIX = Regex("""\b(I|IH|US|SH|TX|FM|RM|CR)(?:[ -](?=\d)|(?= Highway\b))""")
    private val DIRECTIONS = mapOf("N" to "North", "S" to "South", "E" to "East", "W" to "West")
    private val TRAILING_DIRECTION = Regex("""(?<=\S) ([NSEW])$""")

    /**
     * [name] as it should be spoken: `I 35` → "Interstate 35", `US 290 W` → "U S 290
     * West", `FM 1826` → "Farm to Market 1826". Anything it does not recognise passes
     * through unchanged, so a name it cannot improve is at worst read as the card shows it.
     */
    fun road(name: String): String {
        val prefixed = PREFIX.replace(name) { match ->
            val words = PREFIXES.getValue(match.groupValues[1])
            if (match.value.length > match.groupValues[1].length) "$words " else words
        }
        return TRAILING_DIRECTION.replace(prefixed) { " ${DIRECTIONS.getValue(it.groupValues[1])}" }
    }

    /** 1st, 2nd, 3rd, 4th … 11th, 12th, 13th … 21st: how an exit is counted aloud. */
    fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /**
     * The maneuver alone, as spoken: its own instruction, except a roundabout counts its
     * exit as an ordinal ("At the roundabout, take the 2nd exit") where the card says
     * "take exit 2".
     */
    fun maneuver(maneuver: Maneuver): Maneuver = when {
        maneuver.exit > 0 && (
            maneuver.type == Maneuvers.ROUNDABOUT_ENTER_AND_EXIT_CCW ||
                maneuver.type == Maneuvers.ROUNDABOUT_ENTER_AND_EXIT_CW
            ) -> Maneuver(maneuver.type, maneuver.exit, "At the roundabout, take the ${ordinal(maneuver.exit)} exit")
        else -> maneuver
    }

    /**
     * The sentence for [step], without a distance: "Turn right onto Main Street", or just
     * "Turn right" with [streetNames] off.
     *
     * Built by [Maneuvers.instruction], as the card's is, with the road names spoken. The
     * card's instruction was built with the road *before* the maneuver, which [NextStep]
     * does not carry; whether it said "to stay on" is read back from the card's own text,
     * so the two sentences can only differ in how a name is pronounced.
     */
    fun instruction(step: NextStep, streetNames: Boolean): String {
        val spoken = maneuver(step.maneuver)
        val onto = step.onto?.takeIf { streetNames }
            ?: return Maneuvers.instruction(spoken, null, null)
        val stays = step.instruction.endsWith(" to stay on $onto")
        val said = road(onto)
        return Maneuvers.instruction(spoken, said, if (stays) said else null)
    }

    /** A maneuver past the next one, as the tail of a chained prompt: no road, no distance. */
    fun laterInstruction(maneuver: Maneuver): String = maneuver(maneuver).instruction

    /** "In 500 feet, turn right onto Main Street". */
    fun inDistance(metres: Double, unit: SpeedUnit, instruction: String): String =
        "In ${distance(metres, unit)}, ${lowerFirst(instruction)}"

    /** Where Play a sample's turn is: 500 feet, which metric says as "150 metres". */
    const val SAMPLE_METRES = 500 * METRES_PER_FOOT

    /**
     * **Play a sample**'s words (P8.3, plan §7): "In 500 feet, turn right onto Main
     * Street", built as a Prepare prompt is, so it follows the speed unit's system and the
     * Say street names switch ("In 500 feet, turn right" with it off).
     */
    fun sample(unit: SpeedUnit, streetNames: Boolean): String {
        val right = Maneuver(Maneuvers.TURN_NORMAL_RIGHT, 0, "Turn right")
        return inDistance(SAMPLE_METRES, unit, Maneuvers.instruction(right, if (streetNames) road("Main Street") else null, null))
    }

    /** The first letter lowered, so an instruction can follow a comma. */
    fun lowerFirst(text: String): String =
        if (text.isEmpty()) text else text.substring(0, 1).lowercase() + text.substring(1)
}

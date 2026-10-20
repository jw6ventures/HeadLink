package com.jw6ventures.headlink

/**
 * What one field's raw integer could mean: the divisors worth trying, and the range a
 * plausible result falls in.
 *
 * The two belong together, and in one place, because [Scaled.infer] and [ScaleVotes]
 * both decide with them — and two copies that drifted apart would have a drive settle on
 * a scale its own readings were never checked against.
 */
data class ScaleRule(
    val range: ClosedFloatingPointRange<Double>,
    val candidates: List<Double>,
    /**
     * Divisors a real drive has confirmed, which the dashboard draws without the "scale
     * inferred" mark. A divisor that is only range-checked is still a guess.
     */
    val confirmed: List<Double> = listOf(1.0),
) {
    companion object {
        /**
         * 1e6 is Ford SYNC 4's bearing, confirmed on 22 September 2026: the operator drove
         * the truck and the header's compass and the map's arrow matched the direction of
         * travel by eye. 1e3 is the lab receiver's and is only inferred.
         */
        val BEARING = ScaleRule(0.0..360.0, listOf(1.0, 1e3, 1e6), confirmed = listOf(1.0, 1e6))
        val SPEED = ScaleRule(0.0..90.0, listOf(1.0, 1e3))
        val DEGREES = ScaleRule(-180.0..180.0, listOf(1e6, 1e7))
    }
}

/**
 * One interpretation of a raw sensor integer: the value in a real unit, the divisor that
 * produced it, and whether the result survived a range check.
 *
 * The head unit sends unscaled integers and the wire says nothing about their units. P5.2
 * measured Ford sending `bearing=103553459`, which is 103.55° at a 1e6 scale and nonsense
 * at every other — so a scale can be *inferred* from a plausible result, but inferring is
 * not measuring, and this type keeps the two apart. [validated] false means the dashboard
 * drew the number with a mark on it rather than pretending to know.
 */
data class Scaled(
    val value: Double,
    val divisor: Double,
    val validated: Boolean,
    /** The integer the head unit sent, kept so a later reading can re-read this one. */
    val raw: Int,
) {
    companion object {
        /**
         * The first candidate divisor whose result falls inside `range`, or null when
         * none does.
         *
         * **Candidates are tried smallest-first, and the order is the whole point.** A
         * large divisor will bring almost any value into range — 1790 over 1e6 is
         * 0.0018, which is a perfectly valid bearing and a completely wrong reading of
         * a head unit that meant 1.79°. Going up from 1 takes the least-scaled reading
         * that fits: 1790 fails at 1 (out of 0..360) and lands at 1e3, while Ford's
         * 103553459 fails at 1 and at 1e3 and lands at 1e6. Both come out right, and
         * the divisor is reported either way rather than assumed.
         */
        fun infer(raw: Int?, range: ClosedFloatingPointRange<Double>, candidates: List<Double>): Scaled? {
            val value = raw ?: return null
            for (divisor in candidates) {
                val scaled = value / divisor
                if (scaled in range) return Scaled(scaled, divisor, true, value)
            }
            // Out of range under every candidate: still shown, still marked, because a
            // number nobody can explain is evidence and hiding it loses the evidence.
            // The largest divisor is the least-implausible guess to draw.
            val fallback = candidates.max()
            return Scaled(value / fallback, fallback, false, value)
        }

        /**
         * `raw` read at the divisor the drive has settled on, rather than at the one
         * this value alone implies.
         *
         * **A scale is a property of the head unit, not of a message**, and one value
         * often cannot identify it: at the lab's 1e3 bearing scale every raw value from
         * 1 to 360 is also a plausible whole-degree bearing, and `0` — what a vehicle
         * pointing north or standing still sends — is the same number at every scale
         * there is. A drive settles what a fix cannot, and P6.1's track is the first
         * thing here to produce a second value to settle it with.
         *
         * [validated] still describes *this* value. A reading that does not land in
         * range at the session's divisor is drawn and marked, exactly as an
         * unexplainable one always was, rather than quietly rescaling the session
         * around itself.
         */
        fun at(raw: Int?, divisor: Double?, rule: ScaleRule): Scaled? {
            val value = raw ?: return null
            val scale = divisor ?: return null
            val scaled = value / scale
            return Scaled(scaled, scale, scaled in rule.range, value)
        }

        /** Degrees, 0..360. Ford's measured value fits 1e6; the lab's fits 1e3. */
        val BEARING = ScaleRule.BEARING.candidates

        /** Metres per second, 0..90 (~325 km/h) — wide enough not to reject a real road. */
        val SPEED = ScaleRule.SPEED.candidates

        /** Degrees; Android Auto's convention for a position is 1e7. */
        val DEGREES = ScaleRule.DEGREES.candidates
    }
}

/**
 * Everything the head unit has said about where the vehicle is and how it is moving,
 * parsed from the `Vehicle` app event into units the dashboard can draw.
 *
 * Nothing here is authoritative. Each field records whether it could be interpreted at
 * all, and [DashboardState] decides between this and the phone's own fix.
 */
data class VehicleReading(
    val latitude: Scaled? = null,
    val longitude: Scaled? = null,
    val bearing: Scaled? = null,
    val gpsSpeed: Scaled? = null,
    val carSpeed: Scaled? = null,
    val accuracyMetres: Int? = null,
    val altitudeMetres: Int? = null,
    val gear: Int? = null,
    val parkingBrake: Boolean? = null,
    val satelliteCount: Int? = null,
    val satellitesUsed: Int? = null,
) {
    /** A position both axes of which scaled into range, or null. */
    fun position(): Pair<Double, Double>? {
        val lat = latitude?.takeIf { it.validated } ?: return null
        val lon = longitude?.takeIf { it.validated } ?: return null
        // 0,0 is in the Gulf of Guinea and is what an unpopulated fix looks like; no
        // vehicle this project will see is there, and following it would swing the map
        // across the Atlantic every time the head unit sends an empty location.
        if (lat.value == 0.0 && lon.value == 0.0) return null
        return lat.value to lon.value
    }

    /**
     * `car_speed`, else `gps_speed`, in metres a second, each only where a candidate scale
     * brought it into range; else null. Read from this one message: [Dashboard.guidanceSpeed]
     * is the same order at the scale the whole drive supports, and is preferred to this.
     */
    fun speed(): Double? = carSpeed?.takeIf { it.validated }?.value ?: gpsSpeed?.takeIf { it.validated }?.value

    /**
     * The gear's name, or the raw number when it is not one the enum knows.
     *
     * `Drive`, `Park` and `Reverse` are 100, 101 and 102 rather than continuing the 1..10
     * sequence, which is why a measured `gear=101` was first read as nonsense: it is Park,
     * and Park is exactly what a stationary vehicle reports.
     */
    fun gearName(): String = when (val g = gear) {
        null -> "-"
        0 -> "N"
        in 1..10 -> "$g"
        100 -> "D"
        101 -> "P"
        102 -> "R"
        else -> "?$g"
    }

    /**
     * This reading re-read at the scale the drive so far implies (P6.1).
     *
     * Only the three fields whose raw values are small enough to be ambiguous: a
     * position is hundreds of millions at either candidate scale and cannot be mistaken,
     * while a bearing, a GPS speed and a car speed all pass through values a divisor of
     * 1 would also explain. A stationary vehicle sends `bearing=0` and `speed=0`, which
     * are 0 at every scale, so a session that ends parked would otherwise report the
     * scale as 1 however much driving came before it.
     */
    fun at(evidence: ScaleEvidence): VehicleReading = copy(
        bearing = Scaled.at(bearing?.raw, evidence.bearing.settled(), ScaleRule.BEARING) ?: bearing,
        gpsSpeed = Scaled.at(gpsSpeed?.raw, evidence.gpsSpeed.settled(), ScaleRule.SPEED) ?: gpsSpeed,
        carSpeed = Scaled.at(carSpeed?.raw, evidence.carSpeed.settled(), ScaleRule.SPEED) ?: carSpeed,
    )

    companion object {
        /** Parses the `key=value` fields of a [NativeBridge.EVENT_VEHICLE] payload. */
        fun parse(fields: Map<String, String>): VehicleReading {
            fun int(key: String): Int? = fields[key]?.takeIf { it != "-" }?.toIntOrNull()
            return VehicleReading(
                latitude = Scaled.infer(int("latitude"), -90.0..90.0, Scaled.DEGREES),
                longitude = Scaled.infer(int("longitude"), -180.0..180.0, Scaled.DEGREES),
                bearing = Scaled.infer(int("bearing"), 0.0..360.0, Scaled.BEARING),
                gpsSpeed = Scaled.infer(int("gps_speed"), 0.0..90.0, Scaled.SPEED),
                carSpeed = Scaled.infer(int("car_speed"), 0.0..90.0, Scaled.SPEED),
                accuracyMetres = int("accuracy"),
                altitudeMetres = int("altitude"),
                gear = int("gear"),
                parkingBrake = fields["parking_brake"]?.takeIf { it != "-" }?.toBooleanStrictOrNull(),
                satelliteCount = int("satellite_count"),
                satellitesUsed = int("satellites_used"),
            )
        }
    }
}

/**
 * Which divisor a drive supports for one field, and how much of the drive supports it.
 *
 * The rule is the smallest scale that explains all but a fraction of what the head unit
 * has sent — [Scaled.infer]'s smallest-first preference, applied to a whole drive
 * instead of to one message. Counting rather than keeping the largest value seen is what
 * makes it survive one bad reading: a single value that only a coarser scale can explain
 * would otherwise rescale every reading around it, silently and for the rest of the
 * drive, where here it stays the one thing that looks wrong.
 */
data class ScaleVotes(
    private val counts: Map<Double, Int> = emptyMap(),
    private val readings: Int = 0,
) {
    /**
     * This tally with `raw`'s vote added — for **every** candidate that could explain
     * it, not only for the smallest.
     *
     * That is what makes a tally worth keeping. `bearing=0` is a perfectly good reading
     * at every scale there is, so counting only its smallest would have a vehicle
     * pointing north argue that its head unit sends whole degrees; endorsing all of them
     * leaves it saying nothing, which is the truth. A value no candidate explains
     * endorses nothing and is not counted as a reading either: it is evidence of a
     * problem, not evidence about a scale.
     */
    fun and(raw: Int?, rule: ScaleRule): ScaleVotes {
        val value = raw ?: return this
        val endorsed = rule.candidates.filter { value / it in rule.range }
        if (endorsed.isEmpty()) return this
        return ScaleVotes(
            counts + endorsed.associateWith { (counts[it] ?: 0) + 1 },
            readings + 1,
        )
    }

    /** The divisor the drive supports, or null before any value has been read. */
    fun settled(): Double? {
        if (readings == 0) return null
        return counts.entries.filter { it.value >= readings * AGREEMENT }.minOfOrNull { it.key }
            // Nothing explains enough of the drive to be called its scale. Fall back to
            // whatever explains the most of it, smallest first, which is what a single
            // reading would have done on its own.
            ?: counts.entries
                .sortedWith(compareByDescending<Map.Entry<Double, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()
                ?.key
    }

    private companion object {
        /**
         * How much of a drive a scale has to explain to be taken as the drive's.
         *
         * Four readings in five. Fixes arrive several times a second — the vehicle
         * measured in September sent 208 positions in one 40 s session — so a fifth of
         * them disagreeing is a real disagreement and not a glitch, while one value in
         * eleven is exactly the glitch this threshold exists to outvote.
         */
        const val AGREEMENT = 0.8
    }
}

/**
 * What a drive has shown about the head unit's scales, for the fields whose raw values
 * are small enough to be ambiguous.
 *
 * Kept per field rather than per vehicle: a head unit is free to scale its bearing and
 * its speed differently, and this project has already met one whose bearing is 1e6 while
 * nothing else it sends is. Positions are not here — hundreds of millions at either
 * candidate scale, with no reading of them that a smaller divisor could explain.
 */
data class ScaleEvidence(
    val bearing: ScaleVotes = ScaleVotes(),
    val gpsSpeed: ScaleVotes = ScaleVotes(),
    val carSpeed: ScaleVotes = ScaleVotes(),
) {
    /** This evidence with `reading`'s vote added. */
    fun and(reading: VehicleReading): ScaleEvidence = ScaleEvidence(
        bearing = bearing.and(reading.bearing?.raw, ScaleRule.BEARING),
        gpsSpeed = gpsSpeed.and(reading.gpsSpeed?.raw, ScaleRule.SPEED),
        carSpeed = carSpeed.and(reading.carSpeed?.raw, ScaleRule.SPEED),
    )
}

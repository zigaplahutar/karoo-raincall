package si.plahutar.raincall.forecast

import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.radar.DbzPalette

/**
 * What RainCall knows about the rider's immediate weather future.
 *
 * [nearest] and [encounter] are deliberately separate numbers, not one derived from the
 * other. A cell 1.2 km away moving away faster than the rider closes will never be
 * reached, and a cell 15 km away closing head-on arrives in ten minutes. Collapsing
 * them into a single "rain in N minutes" would be wrong in both directions, and the
 * difference between them is the most interesting thing the app can say.
 */
data class RainForecast(
    /** Closest precipitation right now, regardless of where the rider is going. */
    val nearest: NearestRain?,

    /**
     * The first time the rider's projected path meets precipitation, if it does.
     *
     * Null means either that nothing intersects within the horizon, or that there is no
     * usable heading to project along. [confidence] distinguishes the two.
     */
    val encounter: Encounter?,

    /** How much the projection can be trusted. */
    val confidence: RiderState.Confidence,

    /** How far ahead this forecast looked, in minutes. */
    val horizonMinutes: Double,

    /**
     * Age of the radar frame this was built from, in seconds.
     *
     * Surfaced rather than hidden because it bounds everything else: a forecast from a
     * twelve-minute-old frame is a different proposition from one built on fresh data.
     */
    val frameAgeSeconds: Long,
) {
    /** True when there is nothing to warn about at all. */
    val isClear: Boolean get() = nearest == null && encounter == null

    companion object {
        /**
         * Used when there is no radar data rather than no rain.
         *
         * "We cannot see" must not render as "you are clear", which is why this carries
         * [RiderState.Confidence.NONE] rather than an empty but confident result.
         */
        fun unavailable(frameAgeSeconds: Long = 0) = RainForecast(
            nearest = null,
            encounter = null,
            confidence = RiderState.Confidence.NONE,
            horizonMinutes = 0.0,
            frameAgeSeconds = frameAgeSeconds,
        )
    }
}

/**
 * The nearest precipitation to the rider's current position.
 *
 * Always available whenever there is radar coverage, because it needs no assumption
 * about where the rider is going. That makes it the most robust thing to show, and it
 * is what the smallest data field falls back to when no ETA can be computed.
 */
data class NearestRain(
    val distanceMetres: Double,
    /** Direction from the rider to it, degrees, 0 = north. */
    val bearingDegrees: Double,
    /** Eight-point compass label for that bearing. */
    val compass: String,
    val intensity: DbzPalette.Intensity,
    val type: DbzPalette.PrecipType,
    val possibleHail: Boolean,
) {
    val distanceKm: Double get() = distanceMetres / 1000.0
}

/**
 * A predicted meeting between the rider and precipitation.
 */
data class Encounter(
    /** Minutes from now until the leading edge is reached. */
    val etaMinutes: Double,

    /**
     * How long the rider would stay in it, in minutes, or null if it extends past the
     * horizon.
     *
     * Null is meaningfully different from a large number: "at least this long, we
     * stopped looking" rather than "exactly this long".
     */
    val durationMinutes: Double?,

    /** Worst intensity met during the encounter, not the intensity at first contact. */
    val intensity: DbzPalette.Intensity,

    val type: DbzPalette.PrecipType,

    val possibleHail: Boolean,

    /**
     * Fraction of the cone's width that was wet at first contact, 0..1.
     *
     * A value near 1 means the rain spans the whole spread of plausible paths and is
     * genuinely unavoidable by continuing. A small value means only some paths through
     * the cone get wet — the rider might miss it entirely, and the evasion logic in a
     * later step has something to work with.
     */
    val coneCoverage: Double,
) {
    /** True when the rain fills the cone, so continuing is very likely to get wet. */
    val unavoidableOnCurrentPath: Boolean get() = coneCoverage >= 0.8
}

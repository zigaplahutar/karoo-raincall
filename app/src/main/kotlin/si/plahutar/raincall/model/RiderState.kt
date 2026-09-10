package si.plahutar.raincall.model

import si.plahutar.raincall.radar.TileMath

/**
 * Everything RainCall knows about the rider at one instant: where, how fast, which way,
 * and how much of that is trustworthy.
 *
 * Deliberately immutable and free of Android types so the prediction code in later
 * steps can be tested without a device.
 *
 * Nullable fields mean "not known", never "zero". A missing heading and a heading of
 * due north are different facts, and collapsing them would let the app state a
 * confident ETA built on a direction nobody measured.
 */
data class RiderState(
    /** Device clock, epoch milliseconds. */
    val timestampMillis: Long,

    val latitude: Double,
    val longitude: Double,

    /**
     * Reported horizontal accuracy in metres, or null if the platform did not say.
     *
     * Worth having: a 50 m fix should not drive a minute-precise ETA, and without this
     * there is no way to tell a good fix from a bad one.
     */
    val accuracyMetres: Double? = null,

    /**
     * Ground speed in metres per second, or null when not streaming.
     *
     * Units are an inference, not a documented guarantee — see [SpeedSanity].
     */
    val speedMetresPerSecond: Double? = null,

    /** Smoothed direction of travel in degrees, 0 = north. Null when not determinable. */
    val headingDegrees: Double? = null,

    /**
     * How much the path has been bending recently, 0..1.
     *
     * 0 is dead straight. Drives how wide the uncertainty cone opens and how far ahead
     * it is honest to predict.
     */
    val pathCurvature: Double = 1.0,

    /** Whether the ride is actively recording, as opposed to paused or not started. */
    val riding: Boolean = false,
) {

    /**
     * Below this the rider is treated as stationary.
     *
     * 0.5 m/s is under 2 km/h — slower than walking a bike. Standing at a light, GPS
     * noise alone can fake a metre per second of movement, so the threshold has to sit
     * above the noise rather than at zero.
     */
    val isStationary: Boolean
        get() = (speedMetresPerSecond ?: 0.0) < STATIONARY_SPEED_MPS

    /**
     * Whether we know enough to project the rider forward at all.
     *
     * Needs a direction and real movement. Without both, the honest output is a plain
     * "nearest rain is 1.2 km NW" with no ETA attached — which is still useful, and is
     * what the small data field falls back to.
     */
    val canProject: Boolean
        get() = headingDegrees != null && !isStationary

    /**
     * Confidence in any forward projection, as one of three bands.
     *
     * This is surfaced to the rider rather than hidden, because a wrong ETA presented
     * confidently costs more trust than a vague one presented honestly.
     */
    val confidence: Confidence
        get() = when {
            !canProject -> Confidence.NONE
            accuracyMetres != null && accuracyMetres > POOR_FIX_METRES -> Confidence.LOW
            pathCurvature >= HIGH_CURVATURE -> Confidence.LOW
            pathCurvature >= MODERATE_CURVATURE -> Confidence.MEDIUM
            accuracyMetres != null && accuracyMetres > MEDIOCRE_FIX_METRES -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }

    /**
     * How far ahead it is defensible to predict, in minutes.
     *
     * Shrinks as the path gets twisty: there is no point projecting 20 minutes ahead
     * when we cannot tell which way the rider will turn in the next three.
     */
    val horizonMinutes: Double
        get() = when (confidence) {
            Confidence.NONE -> 0.0
            Confidence.LOW -> MIN_HORIZON_MINUTES
            else -> (MAX_HORIZON_MINUTES * (1.0 - pathCurvature))
                .coerceIn(MIN_HORIZON_MINUTES, MAX_HORIZON_MINUTES)
        }

    /**
     * Half-angle of the uncertainty cone in degrees.
     *
     * Opens with curvature. The floor is not zero even on a dead-straight road: the
     * rider can still turn off, and a zero-width cone would claim we know exactly where
     * they will be, which we never do.
     */
    val coneHalfAngleDegrees: Double
        get() = (MIN_CONE_HALF_ANGLE + CONE_ANGLE_GAIN * pathCurvature)
            .coerceIn(MIN_CONE_HALF_ANGLE, MAX_CONE_HALF_ANGLE)

    /** Position projected [minutes] ahead along the current heading. Null if unknown. */
    fun projectedPosition(minutes: Double): Pair<Double, Double>? {
        val heading = headingDegrees ?: return null
        val speed = speedMetresPerSecond ?: return null
        val distance = speed * minutes * 60.0
        return TileMath.destination(longitude, latitude, heading, distance)
    }

    enum class Confidence {
        /** No usable projection: stationary, or no heading. */
        NONE,
        LOW,
        MEDIUM,
        HIGH;

        /**
         * Short word for the rider-facing text. Deliberately terse — this shares a
         * line with the actual warning on a screen being read at speed.
         */
        fun label(): String = when (this) {
            NONE -> "no heading"
            LOW -> "uncertain"
            MEDIUM -> "approx"
            HIGH -> "likely"
        }
    }

    companion object {
        const val STATIONARY_SPEED_MPS = 0.5

        /** A fix worse than this cannot support a confident answer. */
        const val POOR_FIX_METRES = 50.0

        /** Between this and [POOR_FIX_METRES], confidence is capped at medium. */
        const val MEDIOCRE_FIX_METRES = 20.0

        const val MODERATE_CURVATURE = 0.05
        const val HIGH_CURVATURE = 0.25

        const val MIN_HORIZON_MINUTES = 5.0
        const val MAX_HORIZON_MINUTES = 30.0

        const val MIN_CONE_HALF_ANGLE = 5.0
        const val MAX_CONE_HALF_ANGLE = 60.0
        const val CONE_ANGLE_GAIN = 75.0
    }
}

/**
 * Guards against the one thing about the Karoo speed stream that is not documented.
 *
 * The SDK never states the unit for speed. The strong hint is that `UserProfile.weight`
 * is documented as kilograms regardless of the rider's display preference, so raw data
 * types appear to carry SI units and `preferredUnit` governs display only. That makes
 * metres per second overwhelmingly likely — but it is an inference, and if it is wrong
 * every ETA is out by a factor of 3.6, which is exactly the kind of error that looks
 * plausible on screen.
 *
 * Deciding the unit is [SpeedUnitCalibrator]'s job, and it does it by cross-checking the
 * stream against GPS displacement rather than by magnitude — because magnitude cannot:
 * a rider at 20 km/h reports the number 20, which is a perfectly plausible m/s value.
 * What is left for this object is the narrower task of throwing out readings no bicycle
 * could produce in any unit: a NaN, a negative, a GPS jump.
 */
object SpeedSanity {

    /**
     * Fastest plausible bicycle speed in m/s. 30 m/s is 108 km/h.
     *
     * Generous on purpose. This is no longer the unit detector — [SpeedUnitCalibrator]
     * settles that against GPS displacement, which a magnitude check cannot — so all
     * this has to do is reject genuine nonsense. Set at 20 m/s it rejected any descent
     * over 72 km/h, which turned `speed` to null, `canProject` to false and confidence
     * to NONE: the app went blind on exactly the sort of long alpine descent where a
     * rider is covering ground fastest and most wants to know what is ahead.
     */
    const val MAX_PLAUSIBLE_MPS = 30.0

    /** True when the value sits in the range a bicycle can actually reach. */
    fun isPlausibleMetresPerSecond(value: Double): Boolean =
        value.isFinite() && value >= 0.0 && value <= MAX_PLAUSIBLE_MPS

    const val KMH_TO_MPS = 1.0 / 3.6
}

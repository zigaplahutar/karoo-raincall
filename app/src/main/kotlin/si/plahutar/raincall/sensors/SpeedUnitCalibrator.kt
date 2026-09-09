package si.plahutar.raincall.sensors

import si.plahutar.raincall.radar.TileMath
import kotlin.math.abs

/**
 * Works out whether the Karoo speed stream is in metres per second or kilometres per
 * hour, by comparing it against the speed implied by successive GPS positions.
 *
 * ## Why this exists
 *
 * The SDK does not document the unit. The circumstantial evidence points to m/s —
 * `UserProfile.weight` is documented in kilograms regardless of display preference, and
 * Hammerhead's own sample delegates formatting to Karoo via `formatDataTypeId`, both of
 * which suggest raw values are unformatted SI. The sample's own colour thresholds
 * (`< 1` red, `< 5` yellow) also only make sense as m/s: 5 m/s is 18 km/h, the boundary
 * between pottering and riding, whereas 5 km/h is walking pace.
 *
 * That is a strong inference, not a fact. And a naive magnitude check does not save us:
 * if the stream were km/h, a rider at 20 km/h would report the number 20, which sits
 * comfortably inside the range of plausible m/s values (20 m/s = 72 km/h, a fast
 * descent). The error would pass silently and every ETA would be wrong by 3.6x.
 *
 * ## How it decides
 *
 * GPS positions give an independent speed: distance between fixes divided by elapsed
 * time. That number is noisy, which is exactly why it is not used for the actual
 * calculations — but averaged over enough samples it is more than accurate enough to
 * tell 1.0 from 3.6.
 *
 * The decision latches once made, so the interpretation cannot flip mid-ride.
 */
class SpeedUnitCalibrator {

    companion object {
        /** Ratios cluster near 1.0 for m/s. */
        const val EXPECTED_RATIO_MPS = 1.0

        /** Ratios cluster near 3.6 for km/h. */
        const val EXPECTED_RATIO_KMH = 3.6

        /**
         * How close a ratio must sit to a candidate to count as a vote for it.
         *
         * 0.6 leaves a wide dead band between 1.0 and 3.6, so noisy samples land in
         * neither camp rather than being forced into the nearer one.
         */
        const val RATIO_TOLERANCE = 0.6

        /** Votes needed before the decision latches. */
        const val VOTES_REQUIRED = 8

        /**
         * Only compare while genuinely moving. Below about 10 km/h the GPS-derived
         * speed is dominated by noise and the ratio means nothing.
         */
        const val MIN_GPS_SPEED_MPS = 3.0

        /** Above this the GPS-derived speed is more likely a jump than a rider. */
        const val MAX_GPS_SPEED_MPS = 25.0

        /** Fixes closer together than this give too short a baseline. */
        const val MIN_INTERVAL_MILLIS = 900L

        /** Fixes further apart than this may straddle a corner or a stop. */
        const val MAX_INTERVAL_MILLIS = 10_000L

        /** A fix worse than this is not a usable baseline. */
        const val MAX_ACCURACY_METRES = 25.0
    }

    enum class Unit {
        /** Not yet determined. Callers should treat readings as provisional. */
        UNKNOWN,
        METRES_PER_SECOND,
        KILOMETRES_PER_HOUR,
    }

    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    private var lastTimestampMillis: Long = 0

    private var votesMps = 0
    private var votesKmh = 0

    var unit: Unit = Unit.UNKNOWN
        private set

    /** Samples that produced a usable ratio. Exposed for logging and diagnostics. */
    var comparisonCount: Int = 0
        private set

    /**
     * Feed one position fix together with the speed the stream reported at that moment.
     *
     * @param rawSpeed the stream value, in whatever unit it happens to be.
     * @param accuracyMetres fix accuracy, or null if unknown.
     * @return the unit decision after this sample.
     */
    fun observe(
        timestampMillis: Long,
        latitude: Double,
        longitude: Double,
        rawSpeed: Double?,
        accuracyMetres: Double? = null,
    ): Unit {
        val previousLat = lastLatitude
        val previousLon = lastLongitude
        val previousAt = lastTimestampMillis

        // Always advance the baseline, even when this sample cannot be used, so a
        // single bad fix does not leave us comparing against an ancient position.
        lastLatitude = latitude
        lastLongitude = longitude
        lastTimestampMillis = timestampMillis

        if (unit != Unit.UNKNOWN) return unit
        if (previousLat == null || previousLon == null) return unit
        if (rawSpeed == null || !rawSpeed.isFinite() || rawSpeed <= 0.0) return unit
        if (accuracyMetres != null && accuracyMetres > MAX_ACCURACY_METRES) return unit

        val intervalMillis = timestampMillis - previousAt
        if (intervalMillis < MIN_INTERVAL_MILLIS || intervalMillis > MAX_INTERVAL_MILLIS) {
            return unit
        }

        val distance = TileMath.distanceMetres(previousLon, previousLat, longitude, latitude)
        val gpsSpeed = distance / (intervalMillis / 1000.0)
        if (gpsSpeed < MIN_GPS_SPEED_MPS || gpsSpeed > MAX_GPS_SPEED_MPS) return unit

        comparisonCount++
        val ratio = rawSpeed / gpsSpeed

        when {
            abs(ratio - EXPECTED_RATIO_MPS) <= RATIO_TOLERANCE -> votesMps++
            abs(ratio - EXPECTED_RATIO_KMH) <= RATIO_TOLERANCE -> votesKmh++
            // Anything else is noise — a corner cut between fixes, a lost satellite.
            // It votes for nothing rather than being forced towards the nearer answer.
        }

        unit = when {
            votesMps >= VOTES_REQUIRED -> Unit.METRES_PER_SECOND
            votesKmh >= VOTES_REQUIRED -> Unit.KILOMETRES_PER_HOUR
            else -> Unit.UNKNOWN
        }
        return unit
    }

    /**
     * Convert a raw reading to metres per second using the decision so far.
     *
     * While the unit is still [Unit.UNKNOWN] this assumes m/s, which is the documented
     * best guess. Getting it wrong for the first few seconds of a ride costs one
     * slightly-off ETA; refusing to answer at all until calibration finished would mean
     * the field sat blank exactly when the rider first looked at it.
     */
    fun toMetresPerSecond(rawSpeed: Double): Double = when (unit) {
        Unit.KILOMETRES_PER_HOUR -> rawSpeed / 3.6
        Unit.METRES_PER_SECOND, Unit.UNKNOWN -> rawSpeed
    }

    /** Whether the decision has latched. Surfaced so diagnostics can show it. */
    val isCalibrated: Boolean get() = unit != Unit.UNKNOWN

    /** Reset, e.g. when a ride ends. */
    fun reset() {
        lastLatitude = null
        lastLongitude = null
        lastTimestampMillis = 0
        votesMps = 0
        votesKmh = 0
        comparisonCount = 0
        unit = Unit.UNKNOWN
    }
}

package si.plahutar.raincall.forecast

import si.plahutar.raincall.radar.DbzPalette
import kotlin.math.roundToInt

/**
 * Tracks recent rain at the rider's position so the roads can be flagged as wet after it
 * stops.
 *
 * A road stays slippery well after the rain ends, and the moment of highest risk is
 * often the first dry corner — the rider has stopped thinking about rain, and the
 * surface has not caught up. The app already knows when they were in it, so this costs
 * almost nothing.
 *
 * The drying time is a rough function of how hard it rained. Temperature and sunshine
 * matter as much in reality, and we have neither, so the numbers are conservative and
 * the wording says "likely" rather than asserting a fact.
 */
class WetRoadTracker {

    companion object {
        /** Minutes a road stays notably wet after light rain. */
        const val DRYING_LIGHT_MINUTES = 20.0

        /** After moderate rain. */
        const val DRYING_MODERATE_MINUTES = 30.0

        /** After heavy rain. Standing water lasts well beyond this in shaded corners. */
        const val DRYING_HEAVY_MINUTES = 45.0

        /** Below this speed the rider is probably not cornering; no need to mention it. */
        const val MIN_RELEVANT_SPEED_MPS = 3.0
    }

    private var lastWetAtMillis: Long? = null
    private var lastIntensity: DbzPalette.Intensity = DbzPalette.Intensity.NONE

    /** Record whether the rider is in precipitation right now. */
    fun observe(nowMillis: Long, intensity: DbzPalette.Intensity) {
        if (intensity == DbzPalette.Intensity.NONE) {
            // Long enough since the last wetting that the roads have dried and the worst
            // intensity seen should no longer set the window for the next shower.
            val wetAt = lastWetAtMillis ?: return
            if ((nowMillis - wetAt) / 60_000.0 > dryingWindowMinutes()) reset()
            return
        }
        lastWetAtMillis = nowMillis
        if (intensity.ordinal > lastIntensity.ordinal) lastIntensity = intensity
    }

    /**
     * Whether to warn about grip, and for how much longer.
     *
     * Returns null while it is still raining — the rider can see that for themselves,
     * and the line is better spent on the forecast.
     *
     * A pure query. It used to clear its own state once the window expired, which meant
     * asking twice could give two different answers; expiry now happens in [observe],
     * where state changes belong.
     */
    fun advice(nowMillis: Long, speedMetresPerSecond: Double?, rainingNow: Boolean): String? {
        if (rainingNow) return null
        val wetAt = lastWetAtMillis ?: return null
        if ((speedMetresPerSecond ?: 0.0) < MIN_RELEVANT_SPEED_MPS) return null

        val elapsed = (nowMillis - wetAt) / 60_000.0
        if (elapsed < 0) return null
        if (elapsed > dryingWindowMinutes()) return null

        return "wet roads ~${(dryingWindowMinutes() - elapsed).roundToInt()} min"
    }

    private fun dryingWindowMinutes(): Double = when (lastIntensity) {
        DbzPalette.Intensity.HEAVY -> DRYING_HEAVY_MINUTES
        DbzPalette.Intensity.MODERATE -> DRYING_MODERATE_MINUTES
        else -> DRYING_LIGHT_MINUTES
    }

    fun reset() {
        lastWetAtMillis = null
        lastIntensity = DbzPalette.Intensity.NONE
    }
}

/**
 * Accumulates what actually happened, for a summary at the end of the ride.
 *
 * Not for making decisions mid-ride. Its value is over time: a rider who can see that
 * the forecast was right the last five times will believe the sixth. That is worth more
 * than any single feature, and the data is already in hand.
 */
class RideSummaryTracker {

    private var samples = 0
    private var wetSamples = 0
    private var maxIntensity = DbzPalette.Intensity.NONE
    private var hailSeen = false
    private var snowSeen = false

    /** Predictions made, paired with whether they turned out to be right. */
    private var predictions = 0
    private var predictionsCorrect = 0

    /** Sample interval in minutes, used to turn counts into durations. */
    private var intervalMinutes = 1.0

    fun setIntervalMinutes(minutes: Double) {
        if (minutes > 0) intervalMinutes = minutes
    }

    fun observe(atRider: DbzPalette.Sample) {
        samples++
        if (!atRider.isMeaningful) return
        wetSamples++
        if (atRider.intensity.ordinal > maxIntensity.ordinal) maxIntensity = atRider.intensity
        if (atRider.possibleHail) hailSeen = true
        if (atRider.type == DbzPalette.PrecipType.SNOW) snowSeen = true
    }

    /**
     * Record whether a warning came true.
     *
     * @param warnedWithinMinutes the ETA that had been predicted.
     * @param actuallyWet whether the rider was in precipitation when that time arrived.
     */
    fun recordPrediction(warnedWithinMinutes: Double, actuallyWet: Boolean) {
        if (warnedWithinMinutes <= 0) return
        predictions++
        if (actuallyWet) predictionsCorrect++
    }

    data class Summary(
        val wetMinutes: Double,
        val totalMinutes: Double,
        val maxIntensity: DbzPalette.Intensity,
        val possibleHail: Boolean,
        val snow: Boolean,
        /** Fraction of warnings that came true, or null if none were made. */
        val forecastAccuracy: Double?,
    ) {
        val stayedDry: Boolean get() = wetMinutes <= 0.0

        /** One or two lines for the end-of-ride screen. */
        fun lines(): List<String> = buildList {
            if (stayedDry) {
                add("Stayed dry")
            } else {
                val minutes = wetMinutes.roundToInt()
                val what = if (snow) "snow" else "rain"
                add("$minutes min in $what, ${maxIntensity.label()} at worst")
                if (possibleHail) add("possible hail")
            }
            forecastAccuracy?.let { accuracy ->
                // Shown plainly, including when it is poor. A tool that only reports its
                // successes is not worth believing about anything.
                add("forecast right ${(accuracy * 100).roundToInt()}% of the time")
            }
        }
    }

    fun summary(): Summary = Summary(
        wetMinutes = wetSamples * intervalMinutes,
        totalMinutes = samples * intervalMinutes,
        maxIntensity = maxIntensity,
        possibleHail = hailSeen,
        snow = snowSeen,
        forecastAccuracy = if (predictions > 0) {
            predictionsCorrect.toDouble() / predictions
        } else {
            null
        },
    )

    fun reset() {
        samples = 0
        wetSamples = 0
        maxIntensity = DbzPalette.Intensity.NONE
        hailSeen = false
        snowSeen = false
        predictions = 0
        predictionsCorrect = 0
    }
}

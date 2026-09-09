package si.plahutar.raincall.sensors

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Keeps a short rolling history of headings and turns it into a smoothed direction of
 * travel plus a measure of how much the path has been bending.
 *
 * ## Why not just average the numbers
 *
 * Heading is an angle on a circle. The ordinary mean of 350 and 10 is 180 — exactly
 * backwards. Averaging unit vectors instead gives the right answer and, as a bonus,
 * hands us a curvature measure at no extra cost: the resultant length R of those
 * averaged vectors is 1.0 when every sample points the same way and falls towards 0 as
 * they scatter. `1 - R` is "how wiggly has the last minute been", which is precisely
 * what the uncertainty cone needs to decide how wide to open and how far ahead it is
 * honest to predict.
 *
 * ## Why recency weighting
 *
 * Unweighted, a rider who turned 90 degrees ten seconds ago still reads as pointing 45
 * degrees — half way through a turn that already finished. An exponential half-life
 * lets the estimate catch up while still rejecting single-sample GPS jitter.
 *
 * Not thread-safe; the provider that owns it confines it to one coroutine.
 */
class HeadingTracker(
    /** How much history to keep. Older samples are dropped. */
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    /** Weight halves every this many milliseconds of age. */
    private val halfLifeMillis: Double = DEFAULT_HALF_LIFE_MILLIS,
) {

    companion object {
        /**
         * 20 seconds of history. Long enough to ride out GPS jitter at a standstill,
         * short enough that a completed turn stops influencing the estimate quickly.
         */
        const val DEFAULT_WINDOW_MILLIS = 20_000L

        /** 5 seconds; see the class note on why unweighted lags badly. */
        const val DEFAULT_HALF_LIFE_MILLIS = 5_000.0

        /**
         * Below this resultant length the samples disagree so much that no mean
         * direction is meaningful — a switchback climb, or a rider circling a car park.
         * Reporting a heading here would be inventing one.
         */
        const val MIN_USABLE_RESULTANT = 0.10

        /** Fewer samples than this and we have not seen enough to smooth anything. */
        const val MIN_SAMPLES = 3
    }

    private data class Sample(val timestampMillis: Long, val headingDegrees: Double)

    private val samples = ArrayDeque<Sample>()

    /**
     * Result of smoothing.
     *
     * [headingDegrees] is null when the history is too short or too scattered to
     * support an answer. That is deliberately distinct from "heading is 0": callers
     * must handle "we do not know" rather than being handed a plausible fiction.
     */
    data class Smoothed(
        val headingDegrees: Double?,
        /** Resultant length, 0..1. Higher means the samples agree. */
        val resultant: Double,
        val sampleCount: Int,
    ) {
        /**
         * How much the path has been bending, 0..1.
         *
         * 0 is dead straight; towards 1 the recent headings disagree completely.
         * Feeds the cone width and prediction horizon in step 4.
         */
        val curvature: Double get() = (1.0 - resultant).coerceIn(0.0, 1.0)

        val isUsable: Boolean get() = headingDegrees != null

        companion object {
            val UNKNOWN = Smoothed(headingDegrees = null, resultant = 0.0, sampleCount = 0)
        }
    }

    /**
     * Add a heading observation.
     *
     * @param headingDegrees compass heading, any range; normalised internally.
     */
    fun add(timestampMillis: Long, headingDegrees: Double) {
        val normalised = ((headingDegrees % 360.0) + 360.0) % 360.0
        samples.addLast(Sample(timestampMillis, normalised))
        prune(timestampMillis)
    }

    /** Drop anything older than the window, relative to [nowMillis]. */
    fun prune(nowMillis: Long) {
        while (samples.isNotEmpty() &&
            nowMillis - samples.first().timestampMillis > windowMillis
        ) {
            samples.removeFirst()
        }
    }

    /** Forget everything. Used when the ride is reset or the fix is lost for a while. */
    fun clear() = samples.clear()

    val sampleCount: Int get() = samples.size

    /**
     * Current smoothed heading and curvature.
     *
     * @param nowMillis used to age-weight the samples; pass the same clock that fed
     *        [add], otherwise the weights are meaningless.
     */
    fun smoothed(nowMillis: Long): Smoothed {
        prune(nowMillis)
        if (samples.size < MIN_SAMPLES) {
            return Smoothed(headingDegrees = null, resultant = 0.0, sampleCount = samples.size)
        }

        var sumSin = 0.0
        var sumCos = 0.0
        var sumWeight = 0.0

        for (sample in samples) {
            val ageMillis = (nowMillis - sample.timestampMillis).coerceAtLeast(0L)
            val weight = 0.5.pow(ageMillis / halfLifeMillis)
            val radians = Math.toRadians(sample.headingDegrees)
            sumSin += weight * sin(radians)
            sumCos += weight * cos(radians)
            sumWeight += weight
        }

        if (sumWeight <= 0.0) {
            return Smoothed(headingDegrees = null, resultant = 0.0, sampleCount = samples.size)
        }

        val meanSin = sumSin / sumWeight
        val meanCos = sumCos / sumWeight
        val resultant = hypot(meanSin, meanCos).coerceIn(0.0, 1.0)

        if (resultant < MIN_USABLE_RESULTANT) {
            // Samples cancel out: a hairpin, or riding in circles. There is no honest
            // single direction to report here.
            return Smoothed(
                headingDegrees = null,
                resultant = resultant,
                sampleCount = samples.size,
            )
        }

        // atan2(sin, cos) rather than the usual (y, x): compass bearings run clockwise
        // from north, so the sine component is the "x" of the ordinary formula.
        val mean = (Math.toDegrees(kotlin.math.atan2(meanSin, meanCos)) + 360.0) % 360.0
        return Smoothed(
            headingDegrees = mean,
            resultant = resultant,
            sampleCount = samples.size,
        )
    }
}

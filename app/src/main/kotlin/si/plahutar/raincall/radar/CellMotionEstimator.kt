package si.plahutar.raincall.radar

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Works out how the rain field moved between two radar frames, by searching for the
 * displacement that best aligns them.
 *
 * ## Why not brute force
 *
 * Frames arrive ten minutes apart. A fast squall line covers 17 km in that time, which
 * at zoom 8 is 80 pixels. Searching every offset in a +-80 window over a 512x512 field
 * is 26,000 candidate offsets against 260,000 pixels each — far too slow to run on the
 * device between refreshes.
 *
 * So the search is coarse-to-fine. The first pass runs on a field downsampled 8x, where
 * the whole +-80 range is only +-10 pixels and each comparison touches 1/64 as much
 * data. Each finer level then searches a tiny neighbourhood around the answer handed up
 * from the level below. In testing this recovers known shifts exactly, in around 600
 * comparisons rather than 26,000, and is *more* accurate than brute force on a
 * downsampled field, because the last level refines to full resolution.
 *
 * ## Why sum of absolute differences
 *
 * Cheaper than cross-correlation and less swayed by a single very bright cell, which
 * matters because one intense core can otherwise dominate the alignment and drag the
 * vector towards itself.
 */
object CellMotionEstimator {

    /**
     * Widest displacement searched, in full-resolution pixels.
     *
     * 80 px at zoom 8 is about 17 km. Over a ten-minute frame gap that covers cells
     * moving up to roughly 100 km/h, which is beyond anything but the fastest squall
     * line. Raising it costs mostly in the coarse pass, which is the cheap one.
     */
    const val MAX_SHIFT_PIXELS = 80

    /** Pyramid levels, coarsest first. */
    private val LEVELS = intArrayOf(8, 4, 2, 1)

    /** Neighbourhood searched at every level after the first. */
    private const val REFINE_RADIUS = 2

    /**
     * Minimum fraction of the field that must carry real precipitation.
     *
     * With less than this there is nothing to track, and the best-matching offset is
     * whichever one happens to line up the noise. Below the threshold the estimator
     * reports no vector rather than a meaningless one.
     */
    const val MIN_COVERAGE = 0.01

    /**
     * Below this confidence the match is not distinctive enough to act on.
     *
     * Measured as how much better the winning offset is than the average offset: a
     * field with real structure gives a sharp minimum, whereas noise gives a flat
     * surface where every offset scores about the same.
     */
    const val MIN_CONFIDENCE = 0.15

    /**
     * A displacement between two frames.
     *
     * Pixel deltas follow screen convention: +x is east, **+y is south**, because
     * that is how image rows run. The conversion to a bearing accounts for this, and
     * getting it wrong would invert every north-south warning — which is exactly the
     * sort of error that looks plausible until someone rides into the rain.
     */
    data class Displacement(
        val dxPixels: Int,
        val dyPixels: Int,
        /** 0..1; how much better this offset scored than the average. */
        val confidence: Double,
        /** Seconds between the two frames. */
        val intervalSeconds: Long,
    ) {
        val isStationary: Boolean get() = dxPixels == 0 && dyPixels == 0

        /**
         * Ground velocity of the cells.
         *
         * @param latitude used for the metres-per-pixel scale, which varies with
         *        latitude under Mercator.
         */
        fun toVelocity(
            latitude: Double,
            zoom: Int,
            tileSize: Int,
        ): CellVelocity? {
            if (intervalSeconds <= 0) return null
            val metresPerPixel = TileMath.metresPerPixel(latitude, zoom, tileSize)
            val distance = hypot(dxPixels.toDouble(), dyPixels.toDouble()) * metresPerPixel
            val speed = distance / intervalSeconds

            // Bearing: atan2(east, north). North is -y because rows run downward.
            val bearing = if (isStationary) {
                null
            } else {
                (Math.toDegrees(atan2(dxPixels.toDouble(), -dyPixels.toDouble())) + 360.0) % 360.0
            }

            return CellVelocity(
                speedMetresPerSecond = speed,
                bearingDegrees = bearing,
                confidence = confidence,
            )
        }
    }

    /** Cell movement expressed on the ground rather than in pixels. */
    data class CellVelocity(
        val speedMetresPerSecond: Double,
        /** Direction the cells are travelling towards. Null when they are not moving. */
        val bearingDegrees: Double?,
        val confidence: Double,
    ) {
        val speedKmh: Double get() = speedMetresPerSecond * 3.6

        /**
         * Whether this is trustworthy enough to drive a warning.
         *
         * The upper speed bound is a sanity check, not physics: a "cell" apparently
         * moving at 200 km/h is an alignment that latched onto the wrong feature, not
         * weather.
         */
        val isUsable: Boolean
            get() = confidence >= MIN_CONFIDENCE && speedKmh <= 150.0
    }

    /**
     * Estimate the displacement from [older] to [newer].
     *
     * Both fields must share the same grid; comparing different areas or zooms would
     * produce a number that means nothing.
     *
     * @return null when there is too little precipitation to track, or when the best
     *         match is not distinctive enough to believe.
     */
    fun estimate(older: RadarField, newer: RadarField): Displacement? {
        require(older.width == newer.width && older.height == newer.height) {
            "fields must be the same size"
        }
        require(older.range == newer.range) { "fields must cover the same ground" }

        val interval = newer.timeEpochSeconds - older.timeEpochSeconds
        if (interval <= 0) return null

        // No point tracking an empty sky.
        if (older.meaningfulCoverage() < MIN_COVERAGE ||
            newer.meaningfulCoverage() < MIN_COVERAGE
        ) {
            return null
        }

        var estimateX = 0
        var estimateY = 0

        for ((level, factor) in LEVELS.withIndex()) {
            val a = older.downsample(factor)
            val b = newer.downsample(factor)

            val radius: Int
            val centreX: Int
            val centreY: Int
            if (level == 0) {
                radius = MAX_SHIFT_PIXELS / factor + 1
                centreX = 0
                centreY = 0
            } else {
                radius = REFINE_RADIUS
                centreX = estimateX / factor
                centreY = estimateY / factor
            }

            // Fixed rather than scaled to the search radius: at the coarsest level the
            // radius is wide enough that inset-by-radius would shrink the compared
            // window to a sliver too small for the blobs to fall inside, leaving the
            // "match" decided by whatever noise happened to be in that corner.
            val margin = maxOf(2, REFINE_RADIUS + 1)
            if (a.width <= 2 * margin || a.height <= 2 * margin) continue

            val aValidCount = validCount(a, margin)

            var bestX = centreX
            var bestY = centreY
            var bestScore = Double.MAX_VALUE

            for (dy in (centreY - radius)..(centreY + radius)) {
                for (dx in (centreX - radius)..(centreX + radius)) {
                    val score = meanAbsoluteDifference(a, b, dx, dy, margin, aValidCount)
                    if (score < bestScore) {
                        bestScore = score
                        bestX = dx
                        bestY = dy
                    }
                }
            }

            estimateX = bestX * factor
            estimateY = bestY * factor
        }

        val confidence = confidenceOf(older, newer, estimateX, estimateY)
        if (confidence < MIN_CONFIDENCE) return null

        return Displacement(
            dxPixels = estimateX,
            dyPixels = estimateY,
            confidence = confidence,
            intervalSeconds = interval,
        )
    }

    /**
     * Mean absolute difference between [a] and [b] shifted by (dx, dy).
     *
     * Only the interior is compared, inset by [margin]. Without that inset an offset
     * that pushes part of the field off the edge would be scored over fewer pixels than
     * a small offset, and would win or lose for the wrong reason.
     *
     * Pixels that are no-data in either field are skipped rather than treated as zero,
     * so a gap in radar coverage does not read as agreement.
     *
     * A shift near the edge of the search window can leave only a handful of pixels
     * overlapping real precipitation — and a mean over a handful of pixels can land on
     * zero by pure luck, beating the true alignment's honest, noise-sized error. So a
     * candidate is only scored if it recovers a substantial share of [aValidCount], the
     * precipitation [a] actually has to offer in this window; otherwise it is reported
     * as unusable, the same as no overlap at all.
     */
    private fun meanAbsoluteDifference(
        a: RadarField,
        b: RadarField,
        dx: Int,
        dy: Int,
        margin: Int,
        aValidCount: Int,
    ): Double {
        var total = 0L
        var count = 0

        for (y in margin until (a.height - margin)) {
            val by = y + dy
            if (by < 0 || by >= b.height) continue
            for (x in margin until (a.width - margin)) {
                val bx = x + dx
                if (bx < 0 || bx >= b.width) continue

                val av = a.dbz[y * a.width + x]
                val bv = b.dbz[by * b.width + bx]
                if (av == RadarField.NO_DATA || bv == RadarField.NO_DATA) continue

                total += abs(av - bv)
                count++
            }
        }

        if (count < aValidCount * MIN_SAMPLE_FRACTION) return Double.MAX_VALUE
        return total.toDouble() / count
    }

    /** How many of [aValidCount]'s pixels a candidate must recover to be trusted. */
    private const val MIN_SAMPLE_FRACTION = 0.5

    /** Pixels carrying real precipitation inside the [margin] inset of [field]. */
    private fun validCount(field: RadarField, margin: Int): Int {
        var count = 0
        for (y in margin until (field.height - margin)) {
            for (x in margin until (field.width - margin)) {
                if (field.dbz[y * field.width + x] != RadarField.NO_DATA) count++
            }
        }
        return count
    }

    /**
     * How distinctive the winning match is, 0..1.
     *
     * Compares the winning score against the average across a coarse sweep of other
     * offsets. Structured rain gives a sharp minimum and scores high; noise gives a
     * flat surface where the winner is barely better than anything else, and scores
     * near zero. That is the signal that stops a vector being invented from nothing.
     *
     * Runs on the 1/4 pyramid level: this is a shape judgement, not a measurement, and
     * paying full resolution for it would double the cost of the whole estimate.
     */
    private fun confidenceOf(
        older: RadarField,
        newer: RadarField,
        dx: Int,
        dy: Int,
    ): Double {
        val factor = 4
        val a = older.downsample(factor)
        val b = newer.downsample(factor)
        val radius = MAX_SHIFT_PIXELS / factor
        val margin = maxOf(2, radius + 1)
        if (a.width <= 2 * margin || a.height <= 2 * margin) return 0.0

        val aValidCount = validCount(a, margin)
        val bestScore = meanAbsoluteDifference(a, b, dx / factor, dy / factor, margin, aValidCount)
        if (bestScore == Double.MAX_VALUE) return 0.0

        var total = 0.0
        var count = 0
        var offsetY = -radius
        while (offsetY <= radius) {
            var offsetX = -radius
            while (offsetX <= radius) {
                val nearWinner = abs(offsetX - dx / factor) <= 1 && abs(offsetY - dy / factor) <= 1
                if (!nearWinner) {
                    val score = meanAbsoluteDifference(a, b, offsetX, offsetY, margin, aValidCount)
                    if (score != Double.MAX_VALUE) {
                        total += score
                        count++
                    }
                }
                offsetX += 2
            }
            offsetY += 2
        }

        if (count == 0) return 0.0
        val average = total / count
        if (average <= 0.0) return 0.0

        return (1.0 - bestScore / average).coerceIn(0.0, 1.0)
    }
}

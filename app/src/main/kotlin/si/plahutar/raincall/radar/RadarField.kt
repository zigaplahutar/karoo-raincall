package si.plahutar.raincall.radar

/**
 * A decoded, geo-referenced grid of radar reflectivity for one instant.
 *
 * Produced by stitching the tiles around the rider and running every pixel through
 * [DbzPalette]. From here on nothing needs to know about tiles, PNGs or colour schemes —
 * the motion estimator and the cone maths work on this and nothing else, which is what
 * makes both of them testable without a network or a device.
 *
 * ## Storage
 *
 * dBZ is held as a [ByteArray]. The values run -32..95, so a byte is ample, and at a
 * typical 512x512 analysis window that is 256 kB rather than the 1 MB an `IntArray`
 * would take. On a device that also has a map and a ride recording in memory, that
 * difference is worth having.
 *
 * [NO_DATA] is a distinct sentinel rather than a low dBZ. "The radar sees nothing here"
 * and "the radar sees a very weak echo here" are different facts: the first must not
 * count as clear sky when it is really a gap in coverage, and the second must not
 * trigger a warning.
 */
class RadarField(
    /** Grid width in pixels. */
    val width: Int,
    /** Grid height in pixels. */
    val height: Int,
    /** dBZ per pixel, row-major, or [NO_DATA]. */
    val dbz: ByteArray,
    /** Precipitation type per pixel, parallel to [dbz]. */
    val type: ByteArray,
    /** Where this grid sits in the world. */
    val range: TileMath.TileRange,
    /** Observation time of the source frame, epoch seconds. */
    val timeEpochSeconds: Long,
) {

    init {
        require(dbz.size == width * height) {
            "dbz array is ${dbz.size}, expected ${width * height}"
        }
        require(type.size == width * height) {
            "type array is ${type.size}, expected ${width * height}"
        }
    }

    companion object {
        /** No radar return, or outside coverage. Not a dBZ value. */
        const val NO_DATA: Byte = Byte.MIN_VALUE

        const val TYPE_NONE: Byte = 0
        const val TYPE_RAIN: Byte = 1
        const val TYPE_SNOW: Byte = 2

        fun encodeType(type: DbzPalette.PrecipType): Byte = when (type) {
            DbzPalette.PrecipType.NONE -> TYPE_NONE
            DbzPalette.PrecipType.RAIN -> TYPE_RAIN
            DbzPalette.PrecipType.SNOW -> TYPE_SNOW
        }

        fun decodeType(value: Byte): DbzPalette.PrecipType = when (value) {
            TYPE_RAIN -> DbzPalette.PrecipType.RAIN
            TYPE_SNOW -> DbzPalette.PrecipType.SNOW
            else -> DbzPalette.PrecipType.NONE
        }

        /** Build an empty field, used as a starting point and in tests. */
        fun empty(
            width: Int,
            height: Int,
            range: TileMath.TileRange,
            timeEpochSeconds: Long,
        ): RadarField = RadarField(
            width = width,
            height = height,
            dbz = ByteArray(width * height) { NO_DATA },
            type = ByteArray(width * height) { TYPE_NONE },
            range = range,
            timeEpochSeconds = timeEpochSeconds,
        )
    }

    private fun index(x: Int, y: Int) = y * width + x

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** Raw dBZ at a pixel, or null for no data or out of bounds. */
    fun dbzAt(x: Int, y: Int): Int? {
        if (!inBounds(x, y)) return null
        val v = dbz[index(x, y)]
        return if (v == NO_DATA) null else v.toInt()
    }

    /** Full decoded sample at a pixel. */
    fun sampleAt(x: Int, y: Int): DbzPalette.Sample {
        if (!inBounds(x, y)) return DbzPalette.Sample.EMPTY
        val v = dbz[index(x, y)]
        if (v == NO_DATA) return DbzPalette.Sample.EMPTY
        return DbzPalette.Sample(v.toInt(), decodeType(type[index(x, y)]))
    }

    /** Write one pixel. */
    fun set(x: Int, y: Int, sample: DbzPalette.Sample) {
        if (!inBounds(x, y)) return
        val i = index(x, y)
        val value = sample.dbz
        if (value == null) {
            dbz[i] = NO_DATA
            type[i] = TYPE_NONE
        } else {
            // Clamp rather than overflow: a byte cannot hold anything outside this and
            // silently wrapping would turn extreme reflectivity into a negative value.
            dbz[i] = value.coerceIn(-127, 95).toByte()
            type[i] = encodeType(sample.type)
        }
    }

    /** The sample at a geographic position, or empty if outside this field. */
    fun sampleAt(longitude: Double, latitude: Double): DbzPalette.Sample {
        val global = TileMath.lonLatToGlobalPixel(
            longitude, latitude, range.zoom, range.tileSize,
        )
        val mosaic = range.toMosaicPixel(global) ?: return DbzPalette.Sample.EMPTY
        return sampleAt(mosaic.first, mosaic.second)
    }

    /**
     * What this field can actually say about a position: wet, dry, or nothing at all.
     *
     * [sampleAt] cannot answer this, because it collapses "outside the window we
     * downloaded" and "inside it, no echo" into the same empty sample. For a single
     * reading that is harmless, but every simulator in the app steps a point forward
     * through time and asks repeatedly — and a path that leaves the window would
     * otherwise score as a run of dry minutes, which is how "turn back, it saves you
     * 48 minutes" gets said about ground the app never looked at.
     *
     * The distinction only extends to the edge of the mosaic. Inside it, RainViewer's
     * scheme 0 encodes "no rain here" and "no radar coverage here" identically as
     * transparent, so [DRY] there is the honest limit of what the source supports.
     */
    fun observe(longitude: Double, latitude: Double): Observation {
        val global = TileMath.lonLatToGlobalPixel(
            longitude, latitude, range.zoom, range.tileSize,
        )
        val mosaic = range.toMosaicPixel(global) ?: return Observation.UNOBSERVED
        return if (sampleAt(mosaic.first, mosaic.second).isMeaningful) {
            Observation.WET
        } else {
            Observation.DRY
        }
    }

    /** What the field knows about one position. */
    enum class Observation {
        /** Precipitation worth reporting. */
        WET,

        /** Looked, and there is nothing there. */
        DRY,

        /**
         * Outside the downloaded window: no opinion.
         *
         * Callers must not fold this into [DRY]. "We did not look" is not "it is clear",
         * and treating it as such is the difference between honest advice and confident
         * invention.
         */
        UNOBSERVED,
    }

    /** Geographic position of the centre of a pixel. */
    fun positionOf(x: Int, y: Int): Pair<Double, Double> {
        val global = range.toGlobalPixel(x, y)
        return TileMath.globalPixelToLonLat(global, range.zoom, range.tileSize)
    }

    /** Ground metres per pixel at this field's latitude. */
    fun metresPerPixel(latitude: Double): Double =
        TileMath.metresPerPixel(latitude, range.zoom, range.tileSize)

    /**
     * Fraction of pixels carrying precipitation worth reporting.
     *
     * Used to decide whether motion estimation is worth attempting at all: a nearly
     * empty sky has no features to track, and a vector derived from it would be noise
     * dressed up as a measurement.
     */
    fun meaningfulCoverage(): Double {
        var count = 0
        for (v in dbz) {
            if (v != NO_DATA && v >= DbzPalette.MIN_MEANINGFUL_DBZ) count++
        }
        return count.toDouble() / (width * height)
    }

    /**
     * Downsample by an integer factor, averaging over each block.
     *
     * Used to build the pyramid for motion estimation. [NO_DATA] pixels are skipped in
     * the average rather than counted as zero, so a block straddling the edge of radar
     * coverage does not get pulled towards a fictitious weak echo. A block that is
     * entirely no-data stays no-data.
     */
    fun downsample(factor: Int): RadarField {
        require(factor >= 1) { "factor must be positive" }
        if (factor == 1) return this

        val nw = width / factor
        val nh = height / factor
        val outDbz = ByteArray(nw * nh)
        val outType = ByteArray(nw * nh)

        for (y in 0 until nh) {
            for (x in 0 until nw) {
                var sum = 0
                var count = 0
                var rainVotes = 0
                var snowVotes = 0
                for (j in 0 until factor) {
                    val sy = y * factor + j
                    for (i in 0 until factor) {
                        val sx = x * factor + i
                        val v = dbz[sy * width + sx]
                        if (v == NO_DATA) continue
                        sum += v.toInt()
                        count++
                        when (type[sy * width + sx]) {
                            TYPE_RAIN -> rainVotes++
                            TYPE_SNOW -> snowVotes++
                        }
                    }
                }
                val i = y * nw + x
                if (count == 0) {
                    outDbz[i] = NO_DATA
                    outType[i] = TYPE_NONE
                } else {
                    outDbz[i] = (sum / count).coerceIn(-127, 95).toByte()
                    outType[i] = if (snowVotes > rainVotes) TYPE_SNOW else TYPE_RAIN
                }
            }
        }

        return RadarField(
            width = nw,
            height = nh,
            dbz = outDbz,
            type = outType,
            // The range describes the same ground; only the sampling changed. Callers
            // must scale pixel offsets by the factor themselves, which the motion
            // estimator does explicitly at each pyramid level.
            range = range,
            timeEpochSeconds = timeEpochSeconds,
        )
    }
}

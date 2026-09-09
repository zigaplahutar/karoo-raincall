package si.plahutar.raincall.radar

/**
 * Turns a RainViewer tile pixel into a radar reflectivity value in dBZ, and dBZ into
 * the rider-facing categories (light / moderate / heavy, rain vs snow, hail risk).
 *
 * ## Why colour scheme 0
 *
 * RainViewer offers several colour schemes. Scheme 0 ("Black and White") is not a
 * pretty map palette at all — it is a *linear encoding* of the underlying value:
 *
 *   rain pixels use grey 1..127   ->  dBZ = grey - 32
 *   snow pixels use grey 129..255 ->  dBZ = grey - 160
 *   fully transparent             ->  no data
 *
 * Verified against every sampled row of RainViewer's published colour table. This
 * matters a great deal: decoding is exact rather than a nearest-colour guess, and the
 * rain/snow split comes free, which is one of the features the plan wanted anyway.
 *
 * Two things must be right in the tile URL for this to hold:
 *   - colour scheme must be 0
 *   - smoothing must be OFF (smoothing interpolates between values and destroys the
 *     exact mapping; a smoothed pixel is a blend, not a measurement)
 *
 * ## The fallback
 *
 * If scheme 0 ever comes back empty or unavailable, [decodeUniversalBlue] does a
 * reverse lookup against scheme 2. It is lossy above dBZ 64 (several dBZ values share
 * the same colour up there) and cannot distinguish snow, so it is strictly a fallback.
 */
object DbzPalette {

    /** Offset applied to grey values in the rain half of scheme 0. */
    private const val RAIN_OFFSET = 32

    /** Offset applied to grey values in the snow half of scheme 0. */
    private const val SNOW_OFFSET = 160

    /** Grey values at or above this belong to the snow half of the encoding. */
    private const val SNOW_BOUNDARY = 128

    /** Below this alpha a pixel is treated as "no echo" rather than a weak one. */
    private const val MIN_ALPHA = 8

    /**
     * dBZ below which we report nothing at all.
     *
     * Radar picks up plenty at very low reflectivity that never reaches the ground —
     * dust, insects, ground clutter. Around 15 dBZ is the usual practical floor for
     * "actual precipitation you would notice", and using it keeps RainCall from
     * warning about rain that does not exist.
     */
    const val MIN_MEANINGFUL_DBZ = 15

    /** Upper bound of "light" precipitation. Roughly < 2.5 mm/h. */
    const val LIGHT_MAX_DBZ = 30

    /** Upper bound of "moderate" precipitation. Roughly < 10 mm/h. */
    const val MODERATE_MAX_DBZ = 45

    /**
     * dBZ at or above which we flag possible hail.
     *
     * Chosen as the top colour class of RainViewer's Universal Blue palette: at 54 dBZ
     * the palette is dark red, at 55 it jumps to magenta and stays in the magenta band
     * to the top of the scale. That jump is the palette's own "this is now extreme"
     * boundary and it sits inside the 55-60 dBZ range conventionally associated with
     * hail.
     *
     * Caveat worth remembering: real hail detection also uses the height of the
     * freezing level, which a 2D reflectivity mosaic cannot tell us. High reflectivity
     * from a very heavy warm-season downpour will trip this too. Treat it as "possible
     * hail", never as a confirmed report — the wording in the UI reflects that.
     */
    const val HAIL_DBZ_THRESHOLD = 55

    /** What kind of precipitation a pixel represents. */
    enum class PrecipType { NONE, RAIN, SNOW }

    /** Rider-facing intensity band. */
    enum class Intensity {
        NONE, LIGHT, MODERATE, HEAVY;

        /** Lowercase word used in the message text ("moderate", "heavy"). */
        fun label(): String = when (this) {
            NONE -> "none"
            LIGHT -> "light"
            MODERATE -> "moderate"
            HEAVY -> "heavy"
        }
    }

    /**
     * One decoded radar pixel.
     *
     * [dbz] is null when there is no echo, which is deliberately distinct from a dBZ of
     * zero — "the radar sees nothing here" and "the radar sees a very weak return here"
     * are different facts and the cone maths treats them differently.
     */
    data class Sample(
        val dbz: Int?,
        val type: PrecipType,
    ) {
        val hasEcho: Boolean get() = dbz != null

        val isMeaningful: Boolean
            get() = dbz != null && dbz >= MIN_MEANINGFUL_DBZ

        val intensity: Intensity
            get() = when {
                dbz == null || dbz < MIN_MEANINGFUL_DBZ -> Intensity.NONE
                dbz <= LIGHT_MAX_DBZ -> Intensity.LIGHT
                dbz <= MODERATE_MAX_DBZ -> Intensity.MODERATE
                else -> Intensity.HEAVY
            }

        /**
         * Hail is only ever flagged for rain echoes. A very high dBZ in the snow half
         * of the encoding is wet snow or graupel, not the hail a rider needs warning
         * about, and calling it hail would be a false alarm.
         */
        val possibleHail: Boolean
            get() = type == PrecipType.RAIN && dbz != null && dbz >= HAIL_DBZ_THRESHOLD

        companion object {
            val EMPTY = Sample(dbz = null, type = PrecipType.NONE)
        }
    }

    /**
     * Decode one ARGB pixel from a colour-scheme-0 tile.
     *
     * @param argb packed 0xAARRGGBB, as returned by Android's Bitmap.getPixel.
     */
    fun decodeLinear(argb: Int): Sample {
        val alpha = (argb ushr 24) and 0xFF
        if (alpha < MIN_ALPHA) return Sample.EMPTY

        // Scheme 0 is greyscale, so R, G and B carry the same number. We read red and
        // do not average: averaging would silently paper over a tile that is not
        // actually scheme 0, and we would rather that failure be visible.
        val grey = (argb ushr 16) and 0xFF
        if (grey == 0) return Sample.EMPTY

        return if (grey < SNOW_BOUNDARY) {
            Sample(dbz = grey - RAIN_OFFSET, type = PrecipType.RAIN)
        } else {
            Sample(dbz = grey - SNOW_OFFSET, type = PrecipType.SNOW)
        }
    }

    /**
     * Sanity check that a tile really is colour scheme 0.
     *
     * A scheme-0 tile is greyscale everywhere. If we find a pixel whose channels differ,
     * the server gave us something else and [decodeLinear] would produce nonsense
     * numbers rather than failing loudly. Cheap insurance, run once per tile fetch.
     *
     * @param pixels a sample of pixels from the tile; a few hundred is plenty.
     * @return true when every opaque pixel examined is greyscale.
     */
    fun looksLikeLinearScheme(pixels: IntArray): Boolean {
        for (argb in pixels) {
            val alpha = (argb ushr 24) and 0xFF
            if (alpha < MIN_ALPHA) continue
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            if (r != g || g != b) return false
        }
        // An empty tile tells us nothing either way, and returning true there is the
        // right call: clear sky is the common case and must not trigger a fallback.
        return true
    }

    /**
     * Fallback decoder for colour scheme 2 (Universal Blue).
     *
     * Exact match only. RainViewer's palette is a discrete step function, so with
     * smoothing off every pixel should hit the table exactly; anything that does not is
     * either a blend artefact or a colour outside the rain ramp, and guessing at the
     * nearest entry would invent precision we do not have.
     */
    fun decodeUniversalBlue(argb: Int): Sample {
        val alpha = (argb ushr 24) and 0xFF
        if (alpha < MIN_ALPHA) return Sample.EMPTY
        val dbz = UNIVERSAL_BLUE_RAIN[argb] ?: return Sample.EMPTY
        return Sample(dbz = dbz, type = PrecipType.RAIN)
    }

    /**
     * Reverse lookup for Universal Blue, rain ramp only, keyed by packed AARRGGBB.
     *
     * Generated from RainViewer's published colour table and checked for uniqueness at
     * generation time. Stops at dBZ 65: above that the palette saturates to white and
     * then to green, so several dBZ values share a colour and the mapping stops being
     * invertible. Anything that high is already far past every threshold we use.
     */
    private val UNIVERSAL_BLUE_RAIN: Map<Int, Int> = mapOf(
        0x14636159.toInt() to -10,
        0x1966635a.toInt() to -9,
        0x1e69665c.toInt() to -8,
        0x246c685d.toInt() to -7,
        0x296f6b5f.toInt() to -6,
        0x2e726e61.toInt() to -5,
        0x34757062.toInt() to -4,
        0x39787364.toInt() to -3,
        0x3e7c7565.toInt() to -2,
        0x447f7867.toInt() to -1,
        0x49827b69.toInt() to 0,
        0x4e857d6a.toInt() to 1,
        0x5488806c.toInt() to 2,
        0x598b826d.toInt() to 3,
        0x5e8e856f.toInt() to 4,
        0x64928871.toInt() to 5,
        0x6e9e9375.toInt() to 6,
        0x78aa9e79.toInt() to 7,
        0x82b6a97e.toInt() to 8,
        0x8cc2b482.toInt() to 9,
        0x96cec087.toInt() to 10,
        0xa0d2c48b.toInt() to 11,
        0xaad6c88f.toInt() to 12,
        0xb4dacc93.toInt() to 13,
        0xbeded097.toInt() to 14,
        0xff88ddee.toInt() to 15,
        0xff6cd1eb.toInt() to 16,
        0xff51c5e8.toInt() to 17,
        0xff36bae5.toInt() to 18,
        0xff1baee2.toInt() to 19,
        0xff00a3e0.toInt() to 20,
        0xff009ad5.toInt() to 21,
        0xff0091ca.toInt() to 22,
        0xff0088bf.toInt() to 23,
        0xff007fb4.toInt() to 24,
        0xff0077aa.toInt() to 25,
        0xff0070a3.toInt() to 26,
        0xff00699c.toInt() to 27,
        0xff006295.toInt() to 28,
        0xff005b8e.toInt() to 29,
        0xff005588.toInt() to 30,
        0xff005180.toInt() to 31,
        0xff004e78.toInt() to 32,
        0xff004a70.toInt() to 33,
        0xff004768.toInt() to 34,
        0xffffee00.toInt() to 35,
        0xffffe000.toInt() to 36,
        0xffffd200.toInt() to 37,
        0xffffc500.toInt() to 38,
        0xffffb700.toInt() to 39,
        0xffffaa00.toInt() to 40,
        0xffff9f00.toInt() to 41,
        0xffff9500.toInt() to 42,
        0xffff8b00.toInt() to 43,
        0xffff8100.toInt() to 44,
        0xffff4400.toInt() to 45,
        0xfff23600.toInt() to 46,
        0xffe62800.toInt() to 47,
        0xffd91b00.toInt() to 48,
        0xffcd0d00.toInt() to 49,
        0xffc10000.toInt() to 50,
        0xffa80000.toInt() to 51,
        0xff8f0000.toInt() to 52,
        0xff760000.toInt() to 53,
        0xff5d0000.toInt() to 54,
        0xffffaaff.toInt() to 55,
        0xffff9fff.toInt() to 56,
        0xffff95ff.toInt() to 57,
        0xffff8bff.toInt() to 58,
        0xffff81ff.toInt() to 59,
        0xffff77ff.toInt() to 60,
        0xffff6cff.toInt() to 61,
        0xffff62ff.toInt() to 62,
        0xffff58ff.toInt() to 63,
        0xffff4eff.toInt() to 64,
        0xffffffff.toInt() to 65,
    )
}

package si.plahutar.raincall.radar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data model and URL construction for the RainViewer Weather Maps API.
 *
 * The API is free, needs no key and no registration. It publishes a small JSON index
 * at [INDEX_URL] listing the frames currently available — roughly two hours of past
 * radar at 10-minute steps, plus 30 minutes of nowcast — and each frame carries a path
 * that gets pasted into a tile URL.
 *
 * Attribution is mandatory under RainViewer's free terms: the app must show
 * "Weather data by RainViewer" with a link back to rainviewer.com somewhere visible.
 * See [ATTRIBUTION_TEXT] and [ATTRIBUTION_URL]; the settings screen uses both.
 *
 * They ask clients to cache rather than poll hard, and publish no SLA. RainCall
 * refreshes on the same 5-minute cadence the source data updates on, and every caller
 * is expected to cope with a fetch simply failing.
 */
object RainViewerApi {

    const val INDEX_URL = "https://api.rainviewer.com/public/weather-maps.json"

    const val ATTRIBUTION_TEXT = "Weather data by RainViewer"
    const val ATTRIBUTION_URL = "https://www.rainviewer.com/"

    /**
     * Tile edge in pixels. RainViewer serves 256 and 512; 512 halves the number of
     * HTTP requests for the same ground coverage, which matters when everything goes
     * over Bluetooth to the phone.
     */
    const val TILE_SIZE = 512

    /**
     * Zoom level used for all analysis.
     *
     * At Slovenian latitudes z8 is about 212 m per pixel, and a 60 km radius fits in at
     * most 3x3 tiles. Going finer would be false precision: the underlying radar mosaic
     * is nearer 1 km resolution, so z9 and beyond mostly interpolate data that was
     * never measured, while multiplying the tile count.
     */
    const val ANALYSIS_ZOOM = 8

    /**
     * Colour scheme 0, the linear dBZ encoding. See [DbzPalette] for why this one.
     */
    const val COLOR_SCHEME_LINEAR = 0

    /** Colour scheme 2, Universal Blue. Fallback only. */
    const val COLOR_SCHEME_UNIVERSAL_BLUE = 2

    /**
     * Smoothing must stay off.
     *
     * Smoothed tiles interpolate between palette steps, so a pixel becomes a blend of
     * neighbouring values rather than a measurement. That is fine for a map a human
     * looks at and fatal for reading numbers back out.
     */
    const val SMOOTH_OFF = 0

    /**
     * Ask for snow to be rendered in its own value range rather than folded into rain.
     *
     * With scheme 0 this is what puts snow into greys 129..255, which is how
     * [DbzPalette] tells the two apart.
     */
    const val SNOW_ON = 1

    /** One available radar frame. */
    @Serializable
    data class Frame(
        /** Unix epoch seconds (UTC) that this frame represents. */
        @SerialName("time") val time: Long,
        /** Path fragment to splice into the tile URL. */
        @SerialName("path") val path: String,
    )

    @Serializable
    data class Radar(
        @SerialName("past") val past: List<Frame> = emptyList(),
        @SerialName("nowcast") val nowcast: List<Frame> = emptyList(),
    )

    @Serializable
    data class Index(
        @SerialName("version") val version: String = "",
        /** When RainViewer generated this index, epoch seconds. */
        @SerialName("generated") val generated: Long = 0,
        /** Host to prefix tile paths with, e.g. https://tilecache.rainviewer.com */
        @SerialName("host") val host: String = "",
        @SerialName("radar") val radar: Radar = Radar(),
    ) {
        /** Most recent observed frame, or null when the index is empty. */
        val latestPast: Frame? get() = radar.past.maxByOrNull { it.time }

        /**
         * The [count] most recent past frames, oldest first.
         *
         * Ordered oldest-first because the motion estimator consumes them as a time
         * series and reversing at the call site is easy to get wrong.
         */
        fun recentPast(count: Int): List<Frame> =
            radar.past.sortedBy { it.time }.takeLast(count)

        /** Nowcast frames, soonest first. */
        fun nowcastFrames(): List<Frame> = radar.nowcast.sortedBy { it.time }

        /**
         * Whether this index looks usable at all.
         *
         * A structurally valid but empty response is possible — RainViewer explicitly
         * declines to guarantee availability — and callers must treat it as a failure
         * rather than quietly analysing nothing.
         */
        val isUsable: Boolean get() = host.isNotBlank() && radar.past.isNotEmpty()

        /** True when nowcast frames came back, which the free tier does not promise. */
        val hasNowcast: Boolean get() = radar.nowcast.isNotEmpty()
    }

    /**
     * Build the URL for one tile.
     *
     * Shape is {host}{path}/{size}/{z}/{x}/{y}/{colour}/{smooth}_{snow}.png
     */
    fun tileUrl(
        host: String,
        framePath: String,
        zoom: Int,
        tileX: Int,
        tileY: Int,
        tileSize: Int = TILE_SIZE,
        colorScheme: Int = COLOR_SCHEME_LINEAR,
        smooth: Int = SMOOTH_OFF,
        snow: Int = SNOW_ON,
    ): String = buildString {
        append(host.trimEnd('/'))
        append(framePath)
        append('/').append(tileSize)
        append('/').append(zoom)
        append('/').append(tileX)
        append('/').append(tileY)
        append('/').append(colorScheme)
        append('/').append(smooth).append('_').append(snow)
        append(".png")
    }

    /**
     * How stale a frame is, in seconds, relative to [nowEpochSeconds].
     *
     * Radar frames are stamped with the observation time, so by the time we read one it
     * is already a few minutes old. Every downstream calculation has to account for
     * that — a cell's "current" position is its observed position carried forward by
     * this much — so the age is surfaced explicitly rather than assumed small.
     */
    fun frameAgeSeconds(frame: Frame, nowEpochSeconds: Long): Long =
        nowEpochSeconds - frame.time

    /**
     * Frames older than this are not worth using.
     *
     * Past two hours the index itself stops carrying them, but a stalled feed can leave
     * an old frame sitting at the top of the list, and extrapolating a 40-minute-old
     * cell position would produce confident nonsense.
     */
    const val MAX_USABLE_FRAME_AGE_SECONDS = 20 * 60L
}

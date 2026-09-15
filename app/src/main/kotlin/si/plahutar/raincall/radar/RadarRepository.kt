package si.plahutar.raincall.radar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Where decoded radar frames come from.
 *
 * An interface purely so the pipeline that drives it can be tested. Everything
 * interesting about the polling loop — that a new index is fetched every cycle, that a
 * ride ending clears the state — is timing and control flow, which cannot be checked at
 * all while the only implementation insists on real HTTP and real bitmaps.
 */
interface RadarSource {
    suspend fun fetchIndex(): RainViewerApi.Index?

    suspend fun fetchField(
        index: RainViewerApi.Index,
        frame: RainViewerApi.Frame,
        longitude: Double,
        latitude: Double,
    ): RadarField?

    fun clear()
}

/**
 * Fetches RainViewer tiles and turns them into [RadarField]s.
 *
 * This is the only part of RainCall that touches the network or Android graphics.
 * Everything downstream works on a decoded grid, which is what lets the maths be tested
 * without a device.
 *
 * Every request goes through [KarooSystemService]'s own HTTP bridge ([httpGet]) rather
 * than a direct connection. Karoo has no cellular modem, so on a ride with no WiFi that
 * bridge — relayed over Bluetooth to the Companion app on the rider's phone — is the
 * only way to reach the network at all, and it already prefers WiFi when the device has
 * it, so there is no separate direct-connection path to maintain.
 *
 * The frames are cached by their observation time. RainViewer publishes a new frame
 * every ten minutes and asks clients to cache rather than poll hard, and the motion
 * estimator needs the *previous* frame anyway — refetching it every cycle would triple
 * the traffic for no new information.
 */
class RadarRepository(
    private val karooSystem: KarooSystemService,
    private val zoom: Int = RainViewerApi.ANALYSIS_ZOOM,
    private val tileSize: Int = RainViewerApi.TILE_SIZE,
    /** How far around the rider to fetch. */
    private val radiusMetres: Double = 60_000.0,
) : RadarSource {

    companion object {
        private const val TAG = "RadarRepository"

        /** Frames kept in memory. Two is the minimum the motion estimator needs. */
        private const val CACHE_SIZE = 3

        /**
         * Pixels sampled to check the colour scheme really is the linear one.
         *
         * A few hundred is plenty to catch a tile that came back in a different scheme,
         * and checking every pixel would double the decode cost for no extra certainty.
         */
        private const val SCHEME_CHECK_SAMPLES = 256
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Decoded frames, keyed by observation time. */
    private val cache = LinkedHashMap<Long, RadarField>()

    /** Set once a tile comes back in something other than colour scheme 0. */
    private var useFallbackScheme = false

    /** Whether the last fetch fell back to reverse colour lookup. Surfaced for logging. */
    val usingFallbackScheme: Boolean get() = useFallbackScheme

    /** Fetch the frame index. Returns null on any failure. */
    override suspend fun fetchIndex(): RainViewerApi.Index? = withContext(Dispatchers.IO) {
        runCatching {
            val body = get(RainViewerApi.INDEX_URL)
            json.decodeFromString<RainViewerApi.Index>(body)
        }.onFailure {
            Log.w(TAG, "index fetch failed", it)
        }.getOrNull()?.takeIf { it.isUsable }
    }

    /**
     * Fetch and decode one frame around a position.
     *
     * Returns the cached field when the frame has already been decoded, which is the
     * common case for the previous frame in a motion pair.
     */
    override suspend fun fetchField(
        index: RainViewerApi.Index,
        frame: RainViewerApi.Frame,
        longitude: Double,
        latitude: Double,
    ): RadarField? {
        cache[frame.time]?.let { cached ->
            // Only reuse it if it still covers where the rider is now; a long ride can
            // leave the cached window behind.
            val global = TileMath.lonLatToGlobalPixel(longitude, latitude, zoom, tileSize)
            if (cached.range.toMosaicPixel(global) != null) return cached
        }

        val range = TileMath.tileRangeAround(longitude, latitude, radiusMetres, zoom, tileSize)
        val field = withContext(Dispatchers.IO) { decodeFrame(index, frame, range) } ?: return null

        cache[frame.time] = field
        while (cache.size > CACHE_SIZE) {
            cache.remove(cache.keys.first())
        }
        return field
    }

    private suspend fun decodeFrame(
        index: RainViewerApi.Index,
        frame: RainViewerApi.Frame,
        range: TileMath.TileRange,
    ): RadarField? {
        val width = range.width * tileSize
        val height = range.height * tileSize
        val field = RadarField.empty(width, height, range, frame.time)

        var anyTileLoaded = false

        for ((tileX, tileY) in range.tiles()) {
            val url = RainViewerApi.tileUrl(
                host = index.host,
                framePath = frame.path,
                zoom = zoom,
                tileX = tileX,
                tileY = tileY,
                tileSize = tileSize,
                colorScheme = if (useFallbackScheme) {
                    RainViewerApi.COLOR_SCHEME_UNIVERSAL_BLUE
                } else {
                    RainViewerApi.COLOR_SCHEME_LINEAR
                },
            )

            val bitmap = runCatching { downloadBitmap(url) }.getOrNull()
            if (bitmap == null) {
                // A missing tile is a hole in coverage, not a reason to abandon the
                // frame: the rest of the mosaic is still worth having, and the hole
                // stays NO_DATA rather than reading as clear sky.
                Log.w(TAG, "tile $tileX/$tileY unavailable")
                continue
            }
            anyTileLoaded = true

            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()

            if (!useFallbackScheme && !schemeLooksLinear(pixels)) {
                // The tile is not greyscale, so it is not colour scheme 0 and the linear
                // decode would produce confident nonsense. Switch and start again.
                Log.w(TAG, "tiles are not colour scheme 0; falling back to Universal Blue")
                useFallbackScheme = true
                return decodeFrame(index, frame, range)
            }

            writeTile(field, pixels, bitmap.width, tileX, tileY, range)
        }

        return if (anyTileLoaded) field else null
    }

    private fun writeTile(
        field: RadarField,
        pixels: IntArray,
        tileWidth: Int,
        tileX: Int,
        tileY: Int,
        range: TileMath.TileRange,
    ) {
        val maxTiles = TileMath.tilesPerAxis(zoom)
        // tiles() wraps X into valid index space, so recover the unwrapped column this
        // tile occupies in the mosaic.
        val column = ((tileX - Math.floorMod(range.minTileX, maxTiles)) + maxTiles) % maxTiles
        val originX = column * tileSize
        val originY = (tileY - range.minTileY) * tileSize

        for (y in 0 until tileSize) {
            val destY = originY + y
            if (destY < 0 || destY >= field.height) continue
            for (x in 0 until tileSize) {
                val destX = originX + x
                if (destX < 0 || destX >= field.width) continue
                val argb = pixels[y * tileWidth + x]
                val sample = if (useFallbackScheme) {
                    DbzPalette.decodeUniversalBlue(argb)
                } else {
                    DbzPalette.decodeLinear(argb)
                }
                field.set(destX, destY, sample)
            }
        }
    }

    private fun schemeLooksLinear(pixels: IntArray): Boolean {
        val stride = maxOf(1, pixels.size / SCHEME_CHECK_SAMPLES)
        val sample = IntArray((pixels.size + stride - 1) / stride)
        var index = 0
        var i = 0
        while (i < pixels.size && index < sample.size) {
            sample[index++] = pixels[i]
            i += stride
        }
        return DbzPalette.looksLikeLinearScheme(sample)
    }

    private suspend fun downloadBitmap(url: String): Bitmap {
        val bytes = karooSystem.httpGet(url)
        val options = BitmapFactory.Options().apply {
            // ARGB_8888 so the exact byte values survive. A 565 config would quantise
            // the grey channel and destroy the linear dBZ encoding.
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("undecodable tile at $url")
    }

    private suspend fun get(url: String): String = karooSystem.httpGet(url).decodeToString()

    /**
     * Drop cached frames and any decoding decisions. Called when a ride ends.
     *
     * The fallback flag is cleared too: it latches on a single odd tile, and without
     * this one anomaly would keep every later ride on the lossy Universal Blue decoder —
     * no snow, lossy above 64 dBZ — for as long as the service stayed alive.
     */
    override fun clear() {
        cache.clear()
        useFallbackScheme = false
    }
}

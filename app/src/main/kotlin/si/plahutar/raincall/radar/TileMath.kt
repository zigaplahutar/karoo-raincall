package si.plahutar.raincall.radar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator (EPSG:3857) tile arithmetic.
 *
 * RainViewer serves radar as standard slippy-map tiles, so everything here is the
 * ordinary OSM/Google tile scheme. Two jobs:
 *
 *  1. Turn the rider's GPS position into "which tile, and which pixel inside it".
 *  2. Turn a pixel back into a lat/lon, so a detected rain cell has a real position
 *     we can measure distance and bearing against.
 *
 * Everything is pure arithmetic with no Android dependency, so it is unit-testable
 * on the JVM without a device.
 */
object TileMath {

    /** Equatorial circumference of the earth in metres (WGS84). */
    const val EARTH_CIRCUMFERENCE_M = 40075016.686

    /** Mean earth radius in metres, used for great-circle distance. */
    const val EARTH_RADIUS_M = 6371008.8

    /**
     * Web Mercator cannot represent the poles; the standard scheme clamps here.
     * Irrelevant for cycling but keeps the maths from producing infinities.
     */
    const val MAX_LATITUDE = 85.05112878

    /**
     * A position in "global pixel" space: the whole world rendered as one image at a
     * given zoom, measured in pixels from the top-left (180 W, 85.05 N).
     *
     * Kept as Double rather than Int because the sub-pixel part matters — the rider is
     * almost never exactly on a pixel centre, and rounding early loses ~100 m at z8.
     */
    data class GlobalPixel(val x: Double, val y: Double)

    /** Which tile a global pixel falls in, plus the pixel offset inside that tile. */
    data class TileRef(
        val zoom: Int,
        val tileX: Int,
        val tileY: Int,
        val pixelX: Int,
        val pixelY: Int,
    )

    /** Number of tiles along one edge of the world at this zoom. */
    fun tilesPerAxis(zoom: Int): Int = 1 shl zoom

    /** Width of the whole world in pixels at this zoom. */
    fun worldSizePx(zoom: Int, tileSize: Int): Double =
        tileSize.toDouble() * tilesPerAxis(zoom)

    /**
     * Ground distance covered by one pixel, in metres.
     *
     * Depends on latitude: Mercator stretches as you move away from the equator, so a
     * pixel covers less ground the further north you are. At Slovenian latitudes
     * (~46 N) this is about 30% finer than the equatorial figure, which is why we
     * compute it rather than hard-coding a constant.
     */
    fun metresPerPixel(latitude: Double, zoom: Int, tileSize: Int): Double =
        EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(latitude)) / worldSizePx(zoom, tileSize)

    /**
     * Project a WGS84 lon/lat onto global pixel coordinates.
     *
     * The y term is the standard Mercator projection written with [asinh], which is
     * both shorter and numerically better behaved than the ln(tan(...) + sec(...))
     * form usually quoted.
     */
    fun lonLatToGlobalPixel(
        longitude: Double,
        latitude: Double,
        zoom: Int,
        tileSize: Int,
    ): GlobalPixel {
        val n = worldSizePx(zoom, tileSize)
        val clampedLat = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val x = (longitude + 180.0) / 360.0 * n
        val y = (1.0 - asinh(tan(Math.toRadians(clampedLat))) / PI) / 2.0 * n
        return GlobalPixel(x, y)
    }

    /** Inverse of [lonLatToGlobalPixel]. */
    fun globalPixelToLonLat(
        pixel: GlobalPixel,
        zoom: Int,
        tileSize: Int,
    ): Pair<Double, Double> {
        val n = worldSizePx(zoom, tileSize)
        val longitude = pixel.x / n * 360.0 - 180.0
        val latitude = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * pixel.y / n))))
        return longitude to latitude
    }

    /** Split a global pixel into its tile index and the offset within that tile. */
    fun globalPixelToTileRef(
        pixel: GlobalPixel,
        zoom: Int,
        tileSize: Int,
    ): TileRef {
        val max = tilesPerAxis(zoom)
        // Longitude wraps around the antimeridian; latitude does not, so y is clamped.
        val tileX = Math.floorMod(floor(pixel.x / tileSize).toInt(), max)
        val tileY = floor(pixel.y / tileSize).toInt().coerceIn(0, max - 1)
        val pixelX = (pixel.x - floor(pixel.x / tileSize) * tileSize).toInt()
            .coerceIn(0, tileSize - 1)
        val pixelY = (pixel.y - floor(pixel.y / tileSize) * tileSize).toInt()
            .coerceIn(0, tileSize - 1)
        return TileRef(zoom, tileX, tileY, pixelX, pixelY)
    }

    /** Convenience: lon/lat straight to a tile reference. */
    fun lonLatToTileRef(
        longitude: Double,
        latitude: Double,
        zoom: Int,
        tileSize: Int,
    ): TileRef = globalPixelToTileRef(
        lonLatToGlobalPixel(longitude, latitude, zoom, tileSize), zoom, tileSize
    )

    /**
     * The rectangle of tiles needed to cover [radiusMetres] around a position.
     *
     * Returned as inclusive tile index ranges. Used to decide which tiles to fetch:
     * at z8 a 60 km radius is at most 3x3 tiles, which is a modest download.
     */
    fun tileRangeAround(
        longitude: Double,
        latitude: Double,
        radiusMetres: Double,
        zoom: Int,
        tileSize: Int,
    ): TileRange {
        val centre = lonLatToGlobalPixel(longitude, latitude, zoom, tileSize)
        val mPerPx = metresPerPixel(latitude, zoom, tileSize)
        val radiusPx = radiusMetres / mPerPx

        val max = tilesPerAxis(zoom)
        val minTileX = floor((centre.x - radiusPx) / tileSize).toInt()
        val maxTileX = floor((centre.x + radiusPx) / tileSize).toInt()
        val minTileY = floor((centre.y - radiusPx) / tileSize).toInt().coerceIn(0, max - 1)
        val maxTileY = floor((centre.y + radiusPx) / tileSize).toInt().coerceIn(0, max - 1)

        return TileRange(
            zoom = zoom,
            tileSize = tileSize,
            minTileX = minTileX,
            maxTileX = maxTileX,
            minTileY = minTileY,
            maxTileY = maxTileY,
        )
    }

    /**
     * An inclusive rectangle of tiles.
     *
     * X indices are deliberately *not* wrapped into 0..2^zoom-1 here, so that a range
     * straddling the antimeridian stays contiguous and iterable. [tiles] wraps them
     * only at the moment it emits each concrete tile.
     */
    data class TileRange(
        val zoom: Int,
        val tileSize: Int,
        val minTileX: Int,
        val maxTileX: Int,
        val minTileY: Int,
        val maxTileY: Int,
    ) {
        val width: Int get() = maxTileX - minTileX + 1
        val height: Int get() = maxTileY - minTileY + 1
        val tileCount: Int get() = width * height

        /** Every tile in the rectangle, with X wrapped into valid index space. */
        fun tiles(): List<Pair<Int, Int>> {
            val max = TileMath.tilesPerAxis(zoom)
            val out = ArrayList<Pair<Int, Int>>(tileCount)
            for (ty in minTileY..maxTileY) {
                for (tx in minTileX..maxTileX) {
                    out.add(Math.floorMod(tx, max) to ty)
                }
            }
            return out
        }

        /**
         * Where a global pixel sits inside the stitched mosaic of this range,
         * measured from the top-left of the top-left tile. Returns null if the
         * position falls outside the range.
         */
        fun toMosaicPixel(pixel: GlobalPixel): Pair<Int, Int>? {
            val originX = minTileX.toDouble() * tileSize
            val originY = minTileY.toDouble() * tileSize
            val mx = (pixel.x - originX).toInt()
            val my = (pixel.y - originY).toInt()
            if (mx < 0 || my < 0 || mx >= width * tileSize || my >= height * tileSize) {
                return null
            }
            return mx to my
        }

        /** Inverse of [toMosaicPixel]: mosaic pixel back to a global pixel. */
        fun toGlobalPixel(mosaicX: Int, mosaicY: Int): GlobalPixel = GlobalPixel(
            x = minTileX.toDouble() * tileSize + mosaicX,
            y = minTileY.toDouble() * tileSize + mosaicY,
        )
    }

    /**
     * Great-circle distance in metres between two WGS84 positions.
     *
     * Uses the haversine formula. Over the tens of kilometres RainCall cares about the
     * error against a proper geodesic solution is well under a metre — far below the
     * radar's own resolution — so the extra complexity of Vincenty buys nothing here.
     */
    fun distanceMetres(
        lon1: Double, lat1: Double,
        lon2: Double, lat2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /**
     * Initial bearing in degrees (0 = north, 90 = east) from point 1 to point 2.
     */
    fun bearingDegrees(
        lon1: Double, lat1: Double,
        lon2: Double, lat2: Double,
    ): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = kotlin.math.sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * kotlin.math.sin(lat2Rad) -
            kotlin.math.sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Compass label for a bearing, as one of 8 points.
     *
     * Used in rider-facing text ("nearest rain: 1.2 km NW"), so it stays to 8 points
     * rather than 16 — "NNW" is more precision than the underlying data justifies and
     * is harder to read at a glance on a bike.
     */
    fun compassPoint(bearingDegrees: Double): String {
        val normalised = ((bearingDegrees % 360.0) + 360.0) % 360.0
        val index = ((normalised + 22.5) / 45.0).toInt() % 8
        return arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[index]
    }

    /**
     * Smallest signed difference between two bearings, in degrees, within -180..180.
     * Positive means [to] is clockwise of [from].
     *
     * Needed for heading smoothing and for the cone maths, where naive subtraction
     * breaks whenever the heading crosses north.
     */
    fun bearingDelta(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /**
     * Move [distanceMetres] from a position along a [bearingDegrees] heading.
     * Standard destination-point formula on a sphere.
     */
    fun destination(
        longitude: Double,
        latitude: Double,
        bearingDegrees: Double,
        distanceMetres: Double,
    ): Pair<Double, Double> {
        val angular = distanceMetres / EARTH_RADIUS_M
        val bearing = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(latitude)
        val lon1 = Math.toRadians(longitude)

        val lat2 = kotlin.math.asin(
            kotlin.math.sin(lat1) * cos(angular) +
                cos(lat1) * kotlin.math.sin(angular) * cos(bearing)
        )
        val lon2 = lon1 + kotlin.math.atan2(
            kotlin.math.sin(bearing) * kotlin.math.sin(angular) * cos(lat1),
            cos(angular) - kotlin.math.sin(lat1) * kotlin.math.sin(lat2)
        )
        // Normalise longitude back into -180..180.
        val lon2Deg = ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0
        return lon2Deg to Math.toDegrees(lat2)
    }

    /** True when two doubles are equal to within [epsilon]. Test helper. */
    fun approxEquals(a: Double, b: Double, epsilon: Double = 1e-9): Boolean =
        abs(a - b) < epsilon
}

package si.plahutar.raincall.model

import si.plahutar.raincall.radar.TileMath

/**
 * A route the rider is following, decoded into something the forecast can use.
 *
 * Why this changes the answer: without a route the forecaster projects a widening cone,
 * because it genuinely does not know whether the rider will turn left in three minutes.
 * With a route it does know, to the metre. The cone exists to model an uncertainty that
 * a loaded route removes, and continuing to use it would be throwing away the best
 * information the app ever gets.
 *
 * @property points the route as (longitude, latitude) pairs, in the order the rider will
 *           ride them — already un-reversed, so callers never have to think about it.
 */
data class RouteContext(
    val points: List<Pair<Double, Double>>,
    /** Name of the route or destination, for the rider-facing text. */
    val name: String? = null,
) {

    /** Where the route ends: the place the rider is actually going. */
    val destination: Pair<Double, Double>? get() = points.lastOrNull()

    val isUsable: Boolean get() = points.size >= 2

    /**
     * The rider's position along the route, as an index into [points].
     *
     * Nearest point rather than anything cleverer. A route that doubles back on itself
     * can put two legs within a few hundred metres of each other and the nearest match
     * may pick the wrong one, but the alternative — tracking progress statefully — goes
     * wrong far worse when the rider skips a section or restarts the app mid-ride.
     */
    fun nearestIndex(longitude: Double, latitude: Double): Int {
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        for (i in points.indices) {
            val (lon, lat) = points[i]
            val d = TileMath.distanceMetres(longitude, latitude, lon, lat)
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
            }
        }
        return bestIndex
    }

    /** How far the rider is from the route, in metres. */
    fun deviationMetres(longitude: Double, latitude: Double): Double {
        if (points.isEmpty()) return Double.MAX_VALUE
        val (lon, lat) = points[nearestIndex(longitude, latitude)]
        return TileMath.distanceMetres(longitude, latitude, lon, lat)
    }

    /**
     * Walk [metres] along the route from [fromIndex], and report where that lands.
     *
     * Returns the route's end once the distance runs past it, which is the honest
     * answer: the rider cannot be further along than the finish.
     */
    fun advance(fromIndex: Int, metres: Double): Pair<Double, Double> {
        if (points.isEmpty()) return 0.0 to 0.0
        if (metres <= 0.0) return points[fromIndex.coerceIn(points.indices)]

        var remaining = metres
        var i = fromIndex.coerceIn(points.indices)

        while (i < points.size - 1) {
            val (lon1, lat1) = points[i]
            val (lon2, lat2) = points[i + 1]
            val segment = TileMath.distanceMetres(lon1, lat1, lon2, lat2)

            if (segment >= remaining) {
                if (segment <= 0.0) return points[i + 1]
                // Interpolate within the segment rather than snapping to its end: at a
                // half-minute step a rider covers a few hundred metres, and route points
                // can sit a kilometre apart on a straight road.
                val fraction = remaining / segment
                val bearing = TileMath.bearingDegrees(lon1, lat1, lon2, lat2)
                return TileMath.destination(lon1, lat1, bearing, segment * fraction)
            }

            remaining -= segment
            i++
        }

        return points.last()
    }

    /** Distance from [fromIndex] to the end of the route, in metres. */
    fun remainingMetres(fromIndex: Int): Double {
        var total = 0.0
        for (i in fromIndex.coerceIn(points.indices) until points.size - 1) {
            val (lon1, lat1) = points[i]
            val (lon2, lat2) = points[i + 1]
            total += TileMath.distanceMetres(lon1, lat1, lon2, lat2)
        }
        return total
    }

    companion object {
        /**
         * Build from an encoded polyline.
         *
         * @param reversed whether the Karoo is running the route backwards, in which
         *        case the destination is the polyline's *start*. Ignoring this would
         *        send every home-advice answer to the wrong end of the ride.
         */
        fun fromPolyline(
            encoded: String,
            reversed: Boolean = false,
            name: String? = null,
        ): RouteContext? {
            val decoded = PolylineCodec.decode(encoded)
            if (decoded.size < 2) return null
            return RouteContext(
                points = if (reversed) decoded.asReversed() else decoded,
                name = name?.takeIf { it.isNotBlank() },
            )
        }
    }
}

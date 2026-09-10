package si.plahutar.raincall.model

/**
 * Decoder for Google's encoded-polyline format, which is how the Karoo hands over a
 * loaded route.
 *
 * `OnNavigationState.NavigatingRoute.routePolyline` is a string, not a list of points,
 * and neither the SDK nor anything already in this project can turn it back into
 * coordinates. It is thirty lines of well-specified arithmetic, so it is written here
 * rather than pulled in as a dependency — and written here it can be tested against the
 * reference strings Google publishes, which a dependency would not have been.
 *
 * ## The format
 *
 * Each coordinate is stored as a *delta* from the previous one, in units of 1e-5 degrees,
 * zig-zag encoded so negative numbers stay small, then split into five-bit chunks with
 * the high bit set on every chunk but the last, and finally offset by 63 to land in
 * printable ASCII. Deltas mean a long route costs a couple of bytes per point rather
 * than twenty.
 */
object PolylineCodec {

    /** Standard precision: five decimal places, about a metre. */
    const val DEFAULT_PRECISION = 5

    /**
     * Decode to a list of (longitude, latitude) pairs.
     *
     * Longitude first, matching [si.plahutar.raincall.radar.TileMath.destination] and
     * `RadarField.sampleAt` — everything in this project passes coordinates that way
     * round, and switching convention halfway is how a route ends up plotted in the
     * Indian Ocean.
     *
     * Malformed input yields whatever decoded cleanly before the damage rather than an
     * exception: a truncated route is still worth following as far as it goes, and a
     * crash here would take the whole extension down mid-ride.
     */
    fun decode(encoded: String, precision: Int = DEFAULT_PRECISION): List<Pair<Double, Double>> {
        if (encoded.isEmpty()) return emptyList()

        val factor = Math.pow(10.0, precision.toDouble())
        val points = ArrayList<Pair<Double, Double>>(encoded.length / 4)

        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            val latDelta = readSignedValue(encoded, index) ?: break
            index = latDelta.second
            lat += latDelta.first

            val lngDelta = readSignedValue(encoded, index) ?: break
            index = lngDelta.second
            lng += lngDelta.first

            points.add((lng / factor) to (lat / factor))
        }

        return points
    }

    /**
     * Read one zig-zag encoded varint starting at [start].
     *
     * @return the decoded value and the index just past it, or null if the string ran
     *         out mid-number.
     */
    private fun readSignedValue(encoded: String, start: Int): Pair<Int, Int>? {
        var index = start
        var shift = 0
        var result = 0
        var byte: Int

        do {
            if (index >= encoded.length) return null
            byte = encoded[index++].code - 63
            if (byte < 0) return null
            result = result or ((byte and 0x1f) shl shift)
            shift += 5
            // A value needs at most six chunks; more than that is corrupt input rather
            // than a very large delta, and shifting on would overflow silently.
            if (shift > 30) return null
        } while (byte >= 0x20)

        // Zig-zag: the low bit is the sign, so odd values are negative.
        val value = if (result and 1 != 0) (result shr 1).inv() else (result shr 1)
        return value to index
    }
}

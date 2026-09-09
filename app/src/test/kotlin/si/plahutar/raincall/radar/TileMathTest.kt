package si.plahutar.raincall.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected values here were computed independently against a reference implementation
 * of the Web Mercator formulas, not read back out of this code. That is the point of
 * the exercise: a test that only confirms the code agrees with itself would pass
 * happily with the projection inverted.
 */
class TileMathTest {

    private val tileSize = 512

    // Ljubljana
    private val ljLon = 14.5058
    private val ljLat = 46.0569

    // Maribor
    private val mbLon = 15.6459
    private val mbLat = 46.5547

    @Test
    fun `tile reference for Ljubljana at zoom 8`() {
        val ref = TileMath.lonLatToTileRef(ljLon, ljLat, zoom = 8, tileSize = tileSize)
        assertEquals(8, ref.zoom)
        assertEquals(138, ref.tileX)
        assertEquals(91, ref.tileY)
        assertEquals(161, ref.pixelX)
        assertEquals(8, ref.pixelY)
    }

    @Test
    fun `projection round-trips to sub-millimetre accuracy`() {
        val points = listOf(
            14.5058 to 46.0569,   // Ljubljana
            15.6459 to 46.5547,   // Maribor
            13.7302 to 45.5481,   // Koper
            16.1667 to 46.6583,   // Murska Sobota
            13.7856 to 46.4847,   // Kranjska Gora
        )
        for (zoom in listOf(7, 8, 9, 10)) {
            for ((lon, lat) in points) {
                val px = TileMath.lonLatToGlobalPixel(lon, lat, zoom, tileSize)
                val (lon2, lat2) = TileMath.globalPixelToLonLat(px, zoom, tileSize)
                val error = TileMath.distanceMetres(lon, lat, lon2, lat2)
                assertTrue(
                    "round-trip error at z$zoom was $error m",
                    error < 0.001,
                )
            }
        }
    }

    @Test
    fun `metres per pixel matches the reference figures`() {
        // At Slovenian latitudes; the cosine term makes these ~30% finer than the
        // equatorial values usually quoted for these zoom levels.
        assertEquals(424.344, TileMath.metresPerPixel(46.0569, 7, tileSize), 0.01)
        assertEquals(212.172, TileMath.metresPerPixel(46.0569, 8, tileSize), 0.01)
        assertEquals(106.086, TileMath.metresPerPixel(46.0569, 9, tileSize), 0.01)
    }

    @Test
    fun `distance and bearing Ljubljana to Maribor`() {
        val distance = TileMath.distanceMetres(ljLon, ljLat, mbLon, mbLat)
        assertEquals(103601.0, distance, 50.0)

        val bearing = TileMath.bearingDegrees(ljLon, ljLat, mbLon, mbLat)
        assertEquals(57.29, bearing, 0.1)
        assertEquals("NE", TileMath.compassPoint(bearing))
    }

    @Test
    fun `compass points snap at the 22 point 5 degree boundaries`() {
        assertEquals("N", TileMath.compassPoint(0.0))
        assertEquals("N", TileMath.compassPoint(22.4))
        assertEquals("NE", TileMath.compassPoint(22.6))
        assertEquals("NE", TileMath.compassPoint(45.0))
        assertEquals("E", TileMath.compassPoint(90.0))
        assertEquals("S", TileMath.compassPoint(180.0))
        assertEquals("W", TileMath.compassPoint(270.0))
        assertEquals("NW", TileMath.compassPoint(337.4))
        // Wraps back to N rather than falling off the end of the array.
        assertEquals("N", TileMath.compassPoint(337.6))
        assertEquals("N", TileMath.compassPoint(359.9))
        // Out-of-range input must normalise rather than crash.
        assertEquals("E", TileMath.compassPoint(450.0))
        assertEquals("W", TileMath.compassPoint(-90.0))
    }

    @Test
    fun `destination point is self-consistent with distance and bearing`() {
        val (lon, lat) = TileMath.destination(ljLon, ljLat, 90.0, 10000.0)
        assertEquals(10000.0, TileMath.distanceMetres(ljLon, ljLat, lon, lat), 0.5)
        assertEquals(90.0, TileMath.bearingDegrees(ljLon, ljLat, lon, lat), 0.001)
        assertEquals(14.635395, lon, 1e-5)
        assertEquals(46.056827, lat, 1e-5)
    }

    @Test
    fun `bearing delta takes the short way round north`() {
        assertEquals(20.0, TileMath.bearingDelta(350.0, 10.0), 1e-9)
        assertEquals(-20.0, TileMath.bearingDelta(10.0, 350.0), 1e-9)
        assertEquals(0.0, TileMath.bearingDelta(180.0, 180.0), 1e-9)
        // 180 is the boundary; either sign is defensible, magnitude must be right.
        assertEquals(180.0, kotlin.math.abs(TileMath.bearingDelta(0.0, 180.0)), 1e-9)
    }

    @Test
    fun `60 km radius around Ljubljana fits in a small tile rectangle`() {
        val range = TileMath.tileRangeAround(
            ljLon, ljLat, radiusMetres = 60_000.0, zoom = 8, tileSize = tileSize,
        )
        assertEquals(137, range.minTileX)
        assertEquals(138, range.maxTileX)
        assertEquals(90, range.minTileY)
        assertEquals(91, range.maxTileY)
        assertEquals(4, range.tileCount)
        assertEquals(4, range.tiles().size)
    }

    @Test
    fun `mosaic pixel maps back to the same global pixel`() {
        val range = TileMath.tileRangeAround(
            ljLon, ljLat, radiusMetres = 60_000.0, zoom = 8, tileSize = tileSize,
        )
        val global = TileMath.lonLatToGlobalPixel(ljLon, ljLat, 8, tileSize)
        val mosaic = range.toMosaicPixel(global)
        assertNotNull("rider must fall inside their own tile range", mosaic)

        val (mx, my) = mosaic!!
        val back = range.toGlobalPixel(mx, my)
        // Integer mosaic coordinates lose the sub-pixel part, so allow one pixel.
        assertTrue(kotlin.math.abs(back.x - global.x) < 1.0)
        assertTrue(kotlin.math.abs(back.y - global.y) < 1.0)
    }

    @Test
    fun `positions outside the tile range are rejected rather than clamped`() {
        val range = TileMath.tileRangeAround(
            ljLon, ljLat, radiusMetres = 20_000.0, zoom = 8, tileSize = tileSize,
        )
        // Far outside Slovenia; must come back null, not a nonsense in-range pixel.
        val faraway = TileMath.lonLatToGlobalPixel(2.3522, 48.8566, 8, tileSize)
        assertNull(range.toMosaicPixel(faraway))
    }

    @Test
    fun `tile indices stay in range near the antimeridian`() {
        val range = TileMath.tileRangeAround(
            longitude = 179.9, latitude = 0.0,
            radiusMetres = 200_000.0, zoom = 8, tileSize = tileSize,
        )
        val max = TileMath.tilesPerAxis(8)
        for ((tx, ty) in range.tiles()) {
            assertTrue("tileX $tx out of range", tx in 0 until max)
            assertTrue("tileY $ty out of range", ty in 0 until max)
        }
    }

    @Test
    fun `extreme latitudes are clamped instead of producing infinities`() {
        val ref = TileMath.lonLatToTileRef(0.0, 89.9, zoom = 8, tileSize = tileSize)
        assertTrue(ref.tileY in 0 until TileMath.tilesPerAxis(8))
        assertTrue(ref.pixelY in 0 until tileSize)
    }
}

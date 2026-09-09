package si.plahutar.raincall.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

/**
 * The estimator is tested by building a field, shifting it by a known amount, and
 * checking the shift comes back. Anything less than an exact answer on clean synthetic
 * data would mean the search is broken, because there is no ambiguity to lose to.
 */
class CellMotionEstimatorTest {

    private val zoom = 8
    private val tileSize = 512
    private val size = 256

    private val range = TileMath.TileRange(
        zoom = zoom,
        tileSize = tileSize,
        minTileX = 137,
        maxTileX = 138,
        minTileY = 90,
        maxTileY = 91,
    )

    private data class Blob(val cx: Double, val cy: Double, val amp: Double, val sigma: Double)

    /** A field of Gaussian blobs, optionally shifted and with noise added. */
    private fun field(
        blobs: List<Blob>,
        shiftX: Int = 0,
        shiftY: Int = 0,
        noise: Double = 0.0,
        seed: Int = 1,
        timeEpochSeconds: Long = 0,
    ): RadarField {
        val random = Random(seed)
        val f = RadarField.empty(size, size, range, timeEpochSeconds)
        for (y in 0 until size) {
            for (x in 0 until size) {
                var value = 0.0
                for (b in blobs) {
                    val dx = (x - shiftX) - b.cx
                    val dy = (y - shiftY) - b.cy
                    value += b.amp * exp(-(dx * dx + dy * dy) / (2 * b.sigma * b.sigma))
                }
                if (noise > 0) value += (random.nextDouble() * 2 - 1) * noise
                val dbz = value.toInt()
                f.set(
                    x, y,
                    if (dbz <= 0) DbzPalette.Sample.EMPTY
                    else DbzPalette.Sample(dbz, DbzPalette.PrecipType.RAIN),
                )
            }
        }
        return f
    }

    private val standardBlobs = listOf(
        Blob(80.0, 90.0, 45.0, 18.0),
        Blob(170.0, 150.0, 55.0, 12.0),
        Blob(120.0, 200.0, 30.0, 22.0),
    )

    @Test
    fun `a known shift is recovered exactly`() {
        for ((dx, dy) in listOf(0 to 0, 12 to -8, -25 to 30, 35 to 35)) {
            val older = field(standardBlobs, noise = 1.0, seed = 1, timeEpochSeconds = 0)
            val newer = field(
                standardBlobs, shiftX = dx, shiftY = dy,
                noise = 1.0, seed = 2, timeEpochSeconds = 600,
            )

            val result = CellMotionEstimator.estimate(older, newer)
            assertNotNull("no vector for shift ($dx,$dy)", result)
            assertEquals("dx for shift ($dx,$dy)", dx, result!!.dxPixels)
            assertEquals("dy for shift ($dx,$dy)", dy, result.dyPixels)
            assertEquals(600L, result.intervalSeconds)
        }
    }

    @Test
    fun `large shifts within the search radius are still found`() {
        val older = field(standardBlobs, noise = 1.0, seed = 1, timeEpochSeconds = 0)
        val newer = field(
            standardBlobs, shiftX = -72, shiftY = 40,
            noise = 1.0, seed = 2, timeEpochSeconds = 600,
        )

        val result = CellMotionEstimator.estimate(older, newer)!!
        assertEquals(-72, result.dxPixels)
        assertEquals(40, result.dyPixels)
    }

    @Test
    fun `structured rain produces high confidence`() {
        val older = field(standardBlobs, noise = 1.0, seed = 1, timeEpochSeconds = 0)
        val newer = field(
            standardBlobs, shiftX = 18, shiftY = -22,
            noise = 1.0, seed = 2, timeEpochSeconds = 600,
        )

        val result = CellMotionEstimator.estimate(older, newer)!!
        assertTrue("confidence was ${result.confidence}", result.confidence > 0.5)
    }

    @Test
    fun `an empty sky yields no vector at all`() {
        val older = RadarField.empty(size, size, range, 0)
        val newer = RadarField.empty(size, size, range, 600)

        assertNull(
            "there is nothing to track in a clear sky",
            CellMotionEstimator.estimate(older, newer),
        )
    }

    @Test
    fun `sparse drizzle below the coverage floor yields no vector`() {
        // A handful of pixels is not a trackable feature, and whatever offset happens
        // to line them up would be noise presented as a measurement.
        val tiny = listOf(Blob(128.0, 128.0, 40.0, 1.0))
        val older = field(tiny, timeEpochSeconds = 0)
        val newer = field(tiny, shiftX = 10, timeEpochSeconds = 600)

        assertNull(CellMotionEstimator.estimate(older, newer))
    }

    @Test
    fun `pure noise does not produce a confident vector`() {
        val older = field(emptyList(), noise = 30.0, seed = 11, timeEpochSeconds = 0)
        val newer = field(emptyList(), noise = 30.0, seed = 22, timeEpochSeconds = 600)

        val result = CellMotionEstimator.estimate(older, newer)
        // Either rejected outright, or returned with confidence too low to act on.
        assertTrue(
            "noise must not yield a usable vector",
            result == null || result.confidence < CellMotionEstimator.MIN_CONFIDENCE + 0.1,
        )
    }

    @Test
    fun `frames out of order or simultaneous are rejected`() {
        val a = field(standardBlobs, timeEpochSeconds = 600)
        val b = field(standardBlobs, timeEpochSeconds = 600)
        assertNull("identical timestamps give no interval", CellMotionEstimator.estimate(a, b))

        val older = field(standardBlobs, timeEpochSeconds = 600)
        val newer = field(standardBlobs, timeEpochSeconds = 0)
        assertNull("reversed order", CellMotionEstimator.estimate(older, newer))
    }

    @Test
    fun `mismatched grids are refused rather than silently compared`() {
        val a = field(standardBlobs, timeEpochSeconds = 0)
        val b = RadarField.empty(128, 128, range, 600)
        try {
            CellMotionEstimator.estimate(a, b)
            org.junit.Assert.fail("expected a size mismatch to be refused")
        } catch (expected: IllegalArgumentException) {
            // Comparing different areas would produce a number that means nothing.
        }
    }

    @Test
    fun `pixel displacement converts to the right ground velocity and bearing`() {
        // 25 px east over 10 minutes at zoom 8, 46 N.
        val d = CellMotionEstimator.Displacement(
            dxPixels = 25, dyPixels = 0, confidence = 0.8, intervalSeconds = 600,
        )
        val v = d.toVelocity(46.0569, zoom, tileSize)!!

        assertEquals(8.84, v.speedMetresPerSecond, 0.05)
        assertEquals(31.8, v.speedKmh, 0.2)
        assertEquals(90.0, v.bearingDegrees!!, 0.01)
        assertTrue(v.isUsable)
    }

    @Test
    fun `a southward move on screen reads as south, not north`() {
        // Rows run downward, so +y is south. Getting this backwards would invert every
        // north-south warning in the app.
        val south = CellMotionEstimator.Displacement(0, 25, 0.8, 600)
            .toVelocity(46.0569, zoom, tileSize)!!
        assertEquals(180.0, south.bearingDegrees!!, 0.01)

        val north = CellMotionEstimator.Displacement(0, -25, 0.8, 600)
            .toVelocity(46.0569, zoom, tileSize)!!
        assertEquals(0.0, north.bearingDegrees!!, 0.01)

        val southwest = CellMotionEstimator.Displacement(-40, 30, 0.8, 600)
            .toVelocity(46.0569, zoom, tileSize)!!
        assertEquals(233.1, southwest.bearingDegrees!!, 0.2)
        assertEquals("SW", TileMath.compassPoint(southwest.bearingDegrees!!))
    }

    @Test
    fun `stationary cells have no bearing rather than a bearing of north`() {
        val v = CellMotionEstimator.Displacement(0, 0, 0.8, 600)
            .toVelocity(46.0569, zoom, tileSize)!!
        assertEquals(0.0, v.speedMetresPerSecond, 1e-9)
        assertNull("a stationary cell is not heading north", v.bearingDegrees)
    }

    @Test
    fun `an impossibly fast cell is not treated as usable`() {
        // 200 km/h is an alignment that latched onto the wrong feature, not weather.
        val v = CellMotionEstimator.CellVelocity(
            speedMetresPerSecond = 55.0, bearingDegrees = 90.0, confidence = 0.9,
        )
        assertFalse(v.isUsable)
    }
}

class RadarFieldTest {

    private val range = TileMath.TileRange(
        zoom = 8, tileSize = 512,
        minTileX = 137, maxTileX = 138, minTileY = 90, maxTileY = 91,
    )

    @Test
    fun `no data is distinct from a weak echo`() {
        val f = RadarField.empty(16, 16, range, 0)
        assertNull("empty field reads as no data", f.dbzAt(4, 4))
        assertFalse(f.sampleAt(4, 4).hasEcho)

        f.set(4, 4, DbzPalette.Sample(5, DbzPalette.PrecipType.RAIN))
        assertEquals(5, f.dbzAt(4, 4))
        assertTrue("a weak echo is still an echo", f.sampleAt(4, 4).hasEcho)
        assertFalse("but not one worth reporting", f.sampleAt(4, 4).isMeaningful)
    }

    @Test
    fun `out of bounds reads are empty rather than throwing`() {
        val f = RadarField.empty(16, 16, range, 0)
        assertNull(f.dbzAt(-1, 0))
        assertNull(f.dbzAt(0, 16))
        assertFalse(f.sampleAt(100, 100).hasEcho)
    }

    @Test
    fun `precipitation type survives a round trip`() {
        val f = RadarField.empty(8, 8, range, 0)
        f.set(1, 1, DbzPalette.Sample(30, DbzPalette.PrecipType.RAIN))
        f.set(2, 2, DbzPalette.Sample(30, DbzPalette.PrecipType.SNOW))

        assertEquals(DbzPalette.PrecipType.RAIN, f.sampleAt(1, 1).type)
        assertEquals(DbzPalette.PrecipType.SNOW, f.sampleAt(2, 2).type)
    }

    @Test
    fun `coverage counts only meaningful precipitation`() {
        val f = RadarField.empty(10, 10, range, 0)
        assertEquals(0.0, f.meaningfulCoverage(), 1e-9)

        // Ten weak echoes, below the clutter floor.
        for (i in 0 until 10) f.set(i, 0, DbzPalette.Sample(5, DbzPalette.PrecipType.RAIN))
        assertEquals("clutter must not count as coverage", 0.0, f.meaningfulCoverage(), 1e-9)

        for (i in 0 until 10) f.set(i, 1, DbzPalette.Sample(35, DbzPalette.PrecipType.RAIN))
        assertEquals(0.10, f.meaningfulCoverage(), 1e-9)
    }

    @Test
    fun `downsampling averages and skips no data`() {
        val f = RadarField.empty(4, 4, range, 0)
        // Top-left 2x2 block: three readings of 40 and one gap. The average must be 40,
        // not 30, because the gap is not a zero.
        f.set(0, 0, DbzPalette.Sample(40, DbzPalette.PrecipType.RAIN))
        f.set(1, 0, DbzPalette.Sample(40, DbzPalette.PrecipType.RAIN))
        f.set(0, 1, DbzPalette.Sample(40, DbzPalette.PrecipType.RAIN))

        val small = f.downsample(2)
        assertEquals(2, small.width)
        assertEquals(40, small.dbzAt(0, 0))
        assertNull("a wholly empty block stays empty", small.dbzAt(1, 1))
    }

    @Test
    fun `downsampling by one returns the same field`() {
        val f = RadarField.empty(8, 8, range, 0)
        assertTrue(f === f.downsample(1))
    }

    @Test
    fun `a geographic lookup finds the right pixel`() {
        val f = RadarField.empty(1024, 1024, range, 0)
        val (lon, lat) = f.positionOf(500, 400)
        f.set(500, 400, DbzPalette.Sample(42, DbzPalette.PrecipType.RAIN))

        assertEquals(42, f.sampleAt(lon, lat).dbz)
    }

    @Test
    fun `a position outside the field reads as empty`() {
        val f = RadarField.empty(1024, 1024, range, 0)
        // Paris, comfortably outside a window over Slovenia.
        assertFalse(f.sampleAt(2.3522, 48.8566).hasEcho)
    }

    @Test
    fun `a mismatched array size is rejected at construction`() {
        try {
            RadarField(4, 4, ByteArray(8), ByteArray(16), range, 0)
            org.junit.Assert.fail("expected a size mismatch to be refused")
        } catch (expected: IllegalArgumentException) {
            // A silently wrong stride would misplace every reading on the map.
        }
    }
}

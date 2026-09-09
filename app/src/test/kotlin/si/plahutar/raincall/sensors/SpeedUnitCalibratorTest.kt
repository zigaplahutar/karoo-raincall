package si.plahutar.raincall.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import si.plahutar.raincall.radar.TileMath

/**
 * The scenario each of these guards against: a km/h stream read as m/s. A rider at
 * 20 km/h reports the number 20, which is a perfectly plausible m/s reading, so nothing
 * about the magnitude gives it away. Only comparison against GPS displacement does.
 */
class SpeedUnitCalibratorTest {

    private val startLat = 46.0569
    private val startLon = 14.5058

    /**
     * Simulate a rider travelling due east at [trueSpeedMps], feeding the calibrator
     * the position fixes plus whatever the speed stream would report in [unitFactor]
     * (1.0 for m/s, 3.6 for km/h).
     */
    private fun ride(
        calibrator: SpeedUnitCalibrator,
        trueSpeedMps: Double,
        unitFactor: Double,
        fixes: Int,
        intervalMillis: Long = 2000,
        accuracy: Double? = 5.0,
    ) {
        var lat = startLat
        var lon = startLon
        var t = 0L
        // First fix establishes the baseline and cannot produce a comparison.
        calibrator.observe(t, lat, lon, trueSpeedMps * unitFactor, accuracy)

        repeat(fixes) {
            t += intervalMillis
            val distance = trueSpeedMps * (intervalMillis / 1000.0)
            val moved = TileMath.destination(lon, lat, 90.0, distance)
            lon = moved.first
            lat = moved.second
            calibrator.observe(t, lat, lon, trueSpeedMps * unitFactor, accuracy)
        }
    }

    @Test
    fun `a metres per second stream is identified`() {
        val c = SpeedUnitCalibrator()
        ride(c, trueSpeedMps = 8.0, unitFactor = 1.0, fixes = 10)

        assertTrue(c.isCalibrated)
        assertEquals(SpeedUnitCalibrator.Unit.METRES_PER_SECOND, c.unit)
        assertEquals(8.0, c.toMetresPerSecond(8.0), 1e-9)
    }

    @Test
    fun `a kilometres per hour stream is identified and converted`() {
        val c = SpeedUnitCalibrator()
        // True 8 m/s, but the stream reports 28.8 because it is in km/h.
        ride(c, trueSpeedMps = 8.0, unitFactor = 3.6, fixes = 10)

        assertTrue(c.isCalibrated)
        assertEquals(SpeedUnitCalibrator.Unit.KILOMETRES_PER_HOUR, c.unit)
        assertEquals(8.0, c.toMetresPerSecond(28.8), 1e-6)
    }

    @Test
    fun `the ambiguous case a magnitude check would miss`() {
        // 20 km/h. As km/h the stream says 20, which read as m/s is 72 km/h — entirely
        // plausible for a descent, so no magnitude check can catch it.
        val trueSpeed = 20.0 / 3.6
        val c = SpeedUnitCalibrator()
        ride(c, trueSpeedMps = trueSpeed, unitFactor = 3.6, fixes = 12)

        assertEquals(SpeedUnitCalibrator.Unit.KILOMETRES_PER_HOUR, c.unit)
        assertEquals(trueSpeed, c.toMetresPerSecond(20.0), 0.01)
    }

    @Test
    fun `nothing is decided before enough evidence accumulates`() {
        val c = SpeedUnitCalibrator()
        ride(c, trueSpeedMps = 8.0, unitFactor = 1.0, fixes = 3)

        assertFalse(c.isCalibrated)
        assertEquals(SpeedUnitCalibrator.Unit.UNKNOWN, c.unit)
        // Undecided must still return a usable number rather than blanking the field.
        assertEquals(8.0, c.toMetresPerSecond(8.0), 1e-9)
    }

    @Test
    fun `slow riding produces no votes because GPS speed is mostly noise there`() {
        val c = SpeedUnitCalibrator()
        // 2 m/s is 7 km/h, below the useful threshold.
        ride(c, trueSpeedMps = 2.0, unitFactor = 1.0, fixes = 20)

        assertEquals(0, c.comparisonCount)
        assertFalse(c.isCalibrated)
    }

    @Test
    fun `poor fixes are excluded`() {
        val c = SpeedUnitCalibrator()
        ride(c, trueSpeedMps = 8.0, unitFactor = 1.0, fixes = 20, accuracy = 100.0)

        assertEquals(0, c.comparisonCount)
        assertFalse(c.isCalibrated)
    }

    @Test
    fun `intervals that are too long or too short are excluded`() {
        val tooShort = SpeedUnitCalibrator()
        ride(tooShort, 8.0, 1.0, fixes = 20, intervalMillis = 200)
        assertEquals(0, tooShort.comparisonCount)

        val tooLong = SpeedUnitCalibrator()
        ride(tooLong, 8.0, 1.0, fixes = 20, intervalMillis = 30_000)
        assertEquals(0, tooLong.comparisonCount)
    }

    @Test
    fun `ratios between the two candidates vote for neither`() {
        val c = SpeedUnitCalibrator()
        // Ratio of 2.0 sits in the dead band: not m/s, not km/h, just wrong.
        ride(c, trueSpeedMps = 8.0, unitFactor = 2.0, fixes = 20)

        assertTrue("samples were examined", c.comparisonCount > 0)
        assertFalse("but none of them was convincing", c.isCalibrated)
    }

    @Test
    fun `the decision latches and does not flip later`() {
        val c = SpeedUnitCalibrator()
        ride(c, trueSpeedMps = 8.0, unitFactor = 1.0, fixes = 10)
        assertEquals(SpeedUnitCalibrator.Unit.METRES_PER_SECOND, c.unit)

        // Feed it contradictory evidence; a mid-ride flip would be worse than a wrong
        // answer, because the ETA would jump by a factor of 3.6 for no visible reason.
        ride(c, trueSpeedMps = 8.0, unitFactor = 3.6, fixes = 20)
        assertEquals(SpeedUnitCalibrator.Unit.METRES_PER_SECOND, c.unit)
    }

    @Test
    fun `a stationary rider contributes nothing`() {
        val c = SpeedUnitCalibrator()
        repeat(20) { i ->
            c.observe(i * 2000L, startLat, startLon, 0.0, 5.0)
        }
        assertEquals(0, c.comparisonCount)
    }

    @Test
    fun `reset clears the decision`() {
        val c = SpeedUnitCalibrator()
        ride(c, trueSpeedMps = 8.0, unitFactor = 3.6, fixes = 10)
        assertTrue(c.isCalibrated)

        c.reset()
        assertFalse(c.isCalibrated)
        assertEquals(0, c.comparisonCount)
        assertEquals(SpeedUnitCalibrator.Unit.UNKNOWN, c.unit)
    }

    @Test
    fun `moderate GPS noise does not prevent a correct decision`() {
        // Real fixes wander. The ratio only needs to distinguish 1.0 from 3.6, so a few
        // percent of jitter must not stop it.
        val c = SpeedUnitCalibrator()
        var lat = startLat
        var lon = startLon
        var t = 0L
        val trueSpeed = 8.0
        c.observe(t, lat, lon, trueSpeed, 5.0)

        val jitter = listOf(1.08, 0.93, 1.05, 0.96, 1.11, 0.90, 1.03, 0.97, 1.06, 0.94, 1.02, 0.98)
        for (factor in jitter) {
            t += 2000
            val moved = TileMath.destination(lon, lat, 90.0, trueSpeed * 2.0 * factor)
            lon = moved.first
            lat = moved.second
            c.observe(t, lat, lon, trueSpeed, 5.0)
        }

        assertEquals(SpeedUnitCalibrator.Unit.METRES_PER_SECOND, c.unit)
    }
}

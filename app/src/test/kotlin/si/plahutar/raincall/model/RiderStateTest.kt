package si.plahutar.raincall.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.plahutar.raincall.radar.TileMath

class RiderStateTest {

    private fun state(
        speed: Double? = 8.0,          // 28.8 km/h
        heading: Double? = 90.0,
        curvature: Double = 0.0,
        accuracy: Double? = 5.0,
    ) = RiderState(
        timestampMillis = 1_000_000,
        latitude = 46.0569,
        longitude = 14.5058,
        accuracyMetres = accuracy,
        speedMetresPerSecond = speed,
        headingDegrees = heading,
        pathCurvature = curvature,
        riding = true,
    )

    @Test
    fun `a stationary rider cannot be projected forward`() {
        val stopped = state(speed = 0.2)
        assertTrue(stopped.isStationary)
        assertFalse(stopped.canProject)
        assertEquals(RiderState.Confidence.NONE, stopped.confidence)
        assertEquals(0.0, stopped.horizonMinutes, 1e-9)
    }

    @Test
    fun `the stationary threshold sits above GPS noise, not at zero`() {
        // Standing at a light, noise alone fakes a fraction of a metre per second.
        assertTrue(state(speed = 0.4).isStationary)
        assertFalse(state(speed = 0.6).isStationary)
    }

    @Test
    fun `no heading means no projection even when moving`() {
        val noHeading = state(heading = null)
        assertFalse(noHeading.canProject)
        assertEquals(RiderState.Confidence.NONE, noHeading.confidence)
        assertNull(noHeading.projectedPosition(10.0))
    }

    @Test
    fun `a straight road with a good fix is high confidence`() {
        val s = state(curvature = 0.0, accuracy = 5.0)
        assertEquals(RiderState.Confidence.HIGH, s.confidence)
        assertEquals(30.0, s.horizonMinutes, 1e-9)
        assertEquals(5.0, s.coneHalfAngleDegrees, 1e-9)
    }

    @Test
    fun `curvature drives confidence down through the bands`() {
        assertEquals(RiderState.Confidence.HIGH, state(curvature = 0.01).confidence)
        assertEquals(RiderState.Confidence.MEDIUM, state(curvature = 0.05).confidence)
        assertEquals(RiderState.Confidence.MEDIUM, state(curvature = 0.20).confidence)
        assertEquals(RiderState.Confidence.LOW, state(curvature = 0.25).confidence)
        assertEquals(RiderState.Confidence.LOW, state(curvature = 0.60).confidence)
    }

    @Test
    fun `a poor GPS fix caps confidence regardless of how straight the road is`() {
        // A 60 m fix cannot support a minute-precise ETA even on a dead-straight road.
        val s = state(curvature = 0.0, accuracy = 60.0)
        assertEquals(RiderState.Confidence.LOW, s.confidence)

        val mediocre = state(curvature = 0.0, accuracy = 30.0)
        assertEquals(RiderState.Confidence.MEDIUM, mediocre.confidence)
    }

    @Test
    fun `a missing accuracy reading does not by itself lower confidence`() {
        // Not every platform reports accuracy; absence is not evidence of a bad fix.
        assertEquals(
            RiderState.Confidence.HIGH,
            state(curvature = 0.0, accuracy = null).confidence,
        )
    }

    @Test
    fun `the horizon shortens as the path gets twisty`() {
        assertEquals(30.0, state(curvature = 0.0).horizonMinutes, 1e-9)
        assertEquals(28.5, state(curvature = 0.05).horizonMinutes, 0.01)
        // Once confidence drops to low, the horizon collapses to the floor: predicting
        // 20 minutes out is not honest when the next three are a guess.
        assertEquals(5.0, state(curvature = 0.25).horizonMinutes, 1e-9)
        assertEquals(5.0, state(curvature = 0.9).horizonMinutes, 1e-9)
    }

    @Test
    fun `the cone opens with curvature and never closes to zero`() {
        assertEquals(5.0, state(curvature = 0.0).coneHalfAngleDegrees, 1e-9)
        assertEquals(8.75, state(curvature = 0.05).coneHalfAngleDegrees, 0.01)
        assertEquals(23.75, state(curvature = 0.25).coneHalfAngleDegrees, 0.01)
        // Capped: beyond 60 degrees either side the cone covers so much ground it stops
        // saying anything useful.
        assertEquals(60.0, state(curvature = 1.0).coneHalfAngleDegrees, 1e-9)
        // Even on a perfectly straight road the rider can still turn off.
        assertTrue(state(curvature = 0.0).coneHalfAngleDegrees > 0.0)
    }

    @Test
    fun `projected position moves the right distance in the right direction`() {
        val s = state(speed = 10.0, heading = 90.0)   // 10 m/s due east
        val projected = s.projectedPosition(6.0)      // 6 minutes = 3600 m
        assertNotNull(projected)

        val (lon, lat) = projected!!
        assertEquals(
            3600.0,
            TileMath.distanceMetres(s.longitude, s.latitude, lon, lat),
            1.0,
        )
        assertEquals(
            90.0,
            TileMath.bearingDegrees(s.longitude, s.latitude, lon, lat),
            0.01,
        )
    }

    @Test
    fun `confidence labels stay short enough for a data field`() {
        for (c in RiderState.Confidence.entries) {
            assertTrue("'${c.label()}' is too long for a field", c.label().length <= 12)
        }
    }
}

class SpeedSanityTest {

    @Test
    fun `bicycle speeds are accepted`() {
        assertTrue(SpeedSanity.isPlausibleMetresPerSecond(0.0))
        assertTrue(SpeedSanity.isPlausibleMetresPerSecond(5.0))    // 18 km/h
        assertTrue(SpeedSanity.isPlausibleMetresPerSecond(11.1))   // 40 km/h
        assertTrue(SpeedSanity.isPlausibleMetresPerSecond(20.0))   // 72 km/h, a fast descent
    }

    @Test
    fun `a fast descent is still a bicycle and keeps its forecast`() {
        // The ceiling used to sit at 20 m/s, so anything quicker was discarded as
        // implausible — which turned speed to null, canProject to false and confidence
        // to NONE. The app went blind above 72 km/h: on a long alpine descent the rider
        // covers ground fastest and gets told least. Deciding the unit is
        // SpeedUnitCalibrator's job, so this only has to reject genuine nonsense.
        assertTrue("25 m/s is 90 km/h — quick, but a bicycle", SpeedSanity.isPlausibleMetresPerSecond(25.0))
        assertTrue("30 m/s is 108 km/h — the record books, but physical", SpeedSanity.isPlausibleMetresPerSecond(30.0))
    }

    @Test
    fun `impossible values are rejected`() {
        assertFalse(SpeedSanity.isPlausibleMetresPerSecond(-1.0))
        assertFalse("40 m/s is 144 km/h; not a bicycle in any unit", SpeedSanity.isPlausibleMetresPerSecond(40.0))
        assertFalse(SpeedSanity.isPlausibleMetresPerSecond(Double.NaN))
        assertFalse(SpeedSanity.isPlausibleMetresPerSecond(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `the conversion factor is right`() {
        assertEquals(10.0, 36.0 * SpeedSanity.KMH_TO_MPS, 1e-9)
        assertEquals(5.0, 18.0 * SpeedSanity.KMH_TO_MPS, 1e-9)
    }
}

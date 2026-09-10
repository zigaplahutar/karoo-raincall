package si.plahutar.raincall.forecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.radar.CellMotionEstimator
import si.plahutar.raincall.radar.DbzPalette
import si.plahutar.raincall.radar.RadarField
import si.plahutar.raincall.radar.TileMath

/**
 * Scenarios are built as real fields with rain placed at known pixels, and the expected
 * answers were worked out separately from the same geometry rather than by running this
 * code.
 *
 * The rider sits at Ljubljana, which at zoom 8 falls at mosaic pixel (673, 520) of a
 * 2x2 tile window, with 212.172 m per pixel.
 */
class RainForecasterTest {

    private val zoom = 8
    private val tileSize = 512
    private val riderLat = 46.0569
    private val riderLon = 14.5058

    private val range = TileMath.TileRange(
        zoom = zoom, tileSize = tileSize,
        minTileX = 137, maxTileX = 138,
        minTileY = 90, maxTileY = 91,
    )

    private val fieldSize = 1024
    private val riderX = 673
    private val riderY = 520
    private val metresPerPixel = 212.17196

    private fun emptyField(timeEpochSeconds: Long = 0) =
        RadarField.empty(fieldSize, fieldSize, range, timeEpochSeconds)

    /** A vertical wall of rain [offsetPixels] east of the rider, ten pixels thick. */
    private fun bandEastOfRider(
        offsetPixels: Int,
        dbz: Int = 35,
        type: DbzPalette.PrecipType = DbzPalette.PrecipType.RAIN,
        thickness: Int = 10,
        timeEpochSeconds: Long = 0,
    ): RadarField {
        val f = emptyField(timeEpochSeconds)
        val sample = DbzPalette.Sample(dbz, type)
        for (x in (riderX + offsetPixels) until (riderX + offsetPixels + thickness)) {
            for (y in 0 until fieldSize) {
                f.set(x, y, sample)
            }
        }
        return f
    }

    private fun rider(
        speed: Double? = 8.0,
        heading: Double? = 90.0,
        curvature: Double = 0.0,
        accuracy: Double? = 5.0,
    ) = RiderState(
        timestampMillis = 0,
        latitude = riderLat,
        longitude = riderLon,
        accuracyMetres = accuracy,
        speedMetresPerSecond = speed,
        headingDegrees = heading,
        pathCurvature = curvature,
        riding = true,
    )

    private fun velocity(speedMps: Double, bearing: Double?) =
        CellMotionEstimator.CellVelocity(speedMps, bearing, confidence = 0.8)

    // ---------------------------------------------------------------- nearest rain

    @Test
    fun `nearest rain reports distance and direction`() {
        val f = emptyField()
        f.set(riderX + 50, riderY, DbzPalette.Sample(35, DbzPalette.PrecipType.RAIN))

        val forecast = RainForecaster.forecast(rider(), f, null, nowEpochSeconds = 0)
        val nearest = forecast.nearest
        assertNotNull(nearest)
        assertEquals(10608.0, nearest!!.distanceMetres, 50.0)
        assertEquals(10.61, nearest.distanceKm, 0.05)
        assertEquals(90.0, nearest.bearingDegrees, 1.0)
        assertEquals("E", nearest.compass)
        assertEquals(DbzPalette.Intensity.MODERATE, nearest.intensity)
    }

    @Test
    fun `nearest rain finds the closest of several cells`() {
        val f = emptyField()
        f.set(riderX + 50, riderY, DbzPalette.Sample(35, DbzPalette.PrecipType.RAIN))
        f.set(riderX + 25, riderY + 25, DbzPalette.Sample(20, DbzPalette.PrecipType.RAIN))
        f.set(riderX, riderY - 100, DbzPalette.Sample(50, DbzPalette.PrecipType.RAIN))

        val nearest = RainForecaster.forecast(rider(), f, null, 0).nearest!!
        assertEquals(7501.0, nearest.distanceMetres, 50.0)
        assertEquals("SE", nearest.compass)
        assertEquals(DbzPalette.Intensity.LIGHT, nearest.intensity)
    }

    @Test
    fun `clutter below the floor is not reported as rain`() {
        val f = emptyField()
        // 10 dBZ: insects, dust, ground clutter. Not something to warn about.
        f.set(riderX + 20, riderY, DbzPalette.Sample(10, DbzPalette.PrecipType.RAIN))

        assertNull(RainForecaster.forecast(rider(), f, null, 0).nearest)
    }

    @Test
    fun `a clear sky produces nothing at all`() {
        val forecast = RainForecaster.forecast(rider(), emptyField(), null, 0)
        assertNull(forecast.nearest)
        assertNull(forecast.encounter)
        assertTrue(forecast.isClear)
    }

    @Test
    fun `snow is reported as snow, not rain`() {
        val f = emptyField()
        f.set(riderX + 30, riderY, DbzPalette.Sample(30, DbzPalette.PrecipType.SNOW))

        val nearest = RainForecaster.forecast(rider(), f, null, 0).nearest!!
        assertEquals(DbzPalette.PrecipType.SNOW, nearest.type)
        assertFalse("hail is a rain phenomenon", nearest.possibleHail)
    }

    @Test
    fun `very high reflectivity flags possible hail`() {
        val f = emptyField()
        f.set(riderX + 30, riderY, DbzPalette.Sample(58, DbzPalette.PrecipType.RAIN))

        val nearest = RainForecaster.forecast(rider(), f, null, 0).nearest!!
        assertTrue(nearest.possibleHail)
        assertEquals(DbzPalette.Intensity.HEAVY, nearest.intensity)
    }

    @Test
    fun `nearest rain accounts for drift since the frame was taken`() {
        // Rain 50 px east when observed. Cells move west at 12 m/s. Six minutes later
        // they have travelled 4.3 km, about 20 px, so they are now much closer.
        val f = bandEastOfRider(50, timeEpochSeconds = 0)

        val fresh = RainForecaster.forecast(
            rider(), f, velocity(12.0, 270.0), nowEpochSeconds = 0,
        ).nearest!!
        val aged = RainForecaster.forecast(
            rider(), f, velocity(12.0, 270.0), nowEpochSeconds = 360,
        ).nearest!!

        assertTrue(
            "drift must bring the band closer, was ${fresh.distanceKm} then ${aged.distanceKm}",
            aged.distanceMetres < fresh.distanceMetres - 3000,
        )
    }

    // ---------------------------------------------------------------------- the ETA

    @Test
    fun `riding into a stationary band gives the expected ETA`() {
        // 50 px east is 10.6 km; at 8 m/s that is 22 minutes.
        val f = bandEastOfRider(50)

        val encounter = RainForecaster.forecast(rider(), f, null, 0).encounter
        assertNotNull(encounter)
        assertEquals(22.0, encounter!!.etaMinutes, 1.0)
        assertEquals(DbzPalette.Intensity.MODERATE, encounter.intensity)
    }

    @Test
    fun `a band closing head-on arrives sooner than distance alone suggests`() {
        // 100 px east is 21.2 km — over 44 minutes away at 8 m/s, so beyond the horizon
        // if it stood still. Moving west at 10 m/s it arrives in about 20 minutes.
        val f = bandEastOfRider(100)

        val stationary = RainForecaster.forecast(rider(), f, null, 0).encounter
        assertNull("stationary, it is beyond the 30 minute horizon", stationary)

        val closing = RainForecaster.forecast(
            rider(), f, velocity(10.0, 270.0), 0,
        ).encounter
        assertNotNull(closing)
        assertEquals(20.0, closing!!.etaMinutes, 1.0)
    }

    @Test
    fun `frame age moves the ETA earlier`() {
        val f = bandEastOfRider(100, timeEpochSeconds = 0)
        val v = velocity(10.0, 270.0)

        val fresh = RainForecaster.forecast(rider(), f, v, nowEpochSeconds = 0).encounter!!
        val aged = RainForecaster.forecast(rider(), f, v, nowEpochSeconds = 360).encounter!!

        assertEquals(20.0, fresh.etaMinutes, 1.0)
        assertEquals(16.5, aged.etaMinutes, 1.0)
        assertEquals(360L, RainForecaster.forecast(rider(), f, v, 360).frameAgeSeconds)
        assertTrue(
            "ignoring frame age would make every forecast late",
            aged.etaMinutes < fresh.etaMinutes,
        )
    }

    @Test
    fun `rain behind the rider is never an encounter`() {
        val f = bandEastOfRider(-60)   // 60 px west, rider heading east

        val forecast = RainForecaster.forecast(rider(), f, null, 0)
        assertNotNull("it is still the nearest rain", forecast.nearest)
        assertNull("but it will never be reached", forecast.encounter)
        assertEquals("W", forecast.nearest!!.compass)
    }

    @Test
    fun `the case a single number gets wrong - close but never caught`() {
        // A cell 1.2 km east, running east at 15 m/s while the rider does 8. The
        // distance sounds urgent; the ETA is never. Reporting one figure derived from
        // the other would be wrong in both directions.
        val f = bandEastOfRider(6, thickness = 4)

        val forecast = RainForecaster.forecast(rider(), f, velocity(15.0, 90.0), 0)
        assertNotNull(forecast.nearest)
        assertTrue("it really is close", forecast.nearest!!.distanceKm < 2.0)
        assertNull("and it really will never catch the rider", forecast.encounter)
    }

    @Test
    fun `a rider already in the rain gets an ETA of zero and full coverage`() {
        val f = emptyField()
        for (x in (riderX - 20)..(riderX + 20)) {
            for (y in (riderY - 20)..(riderY + 20)) {
                f.set(x, y, DbzPalette.Sample(40, DbzPalette.PrecipType.RAIN))
            }
        }

        val encounter = RainForecaster.forecast(rider(), f, null, 0).encounter!!
        assertEquals(0.0, encounter.etaMinutes, 1e-9)
        assertEquals("being in it is not a marginal case", 1.0, encounter.coneCoverage, 1e-9)
        assertTrue(encounter.unavoidableOnCurrentPath)
    }

    @Test
    fun `a band spanning the cone reads as unavoidable`() {
        val f = bandEastOfRider(30)
        val encounter = RainForecaster.forecast(rider(), f, null, 0).encounter!!
        assertEquals(1.0, encounter.coneCoverage, 1e-9)
        assertTrue(encounter.unavoidableOnCurrentPath)
    }

    @Test
    fun `duration is measured through the band and reported`() {
        // A band 40 px thick is 8.5 km; at 8 m/s that is about 17.7 minutes inside it.
        val f = bandEastOfRider(20, thickness = 40)

        val encounter = RainForecaster.forecast(rider(), f, null, 0).encounter!!
        assertNotNull("the band ends, so the duration is knowable", encounter.durationMinutes)
        assertEquals(17.7, encounter.durationMinutes!!, 2.0)
    }

    @Test
    fun `the worst intensity met is reported, not the first`() {
        val f = emptyField()
        // Light on the leading edge, heavy behind it.
        for (x in (riderX + 20) until (riderX + 30)) {
            for (y in 0 until fieldSize) {
                f.set(x, y, DbzPalette.Sample(20, DbzPalette.PrecipType.RAIN))
            }
        }
        for (x in (riderX + 30) until (riderX + 45)) {
            for (y in 0 until fieldSize) {
                f.set(x, y, DbzPalette.Sample(50, DbzPalette.PrecipType.RAIN))
            }
        }

        val encounter = RainForecaster.forecast(rider(), f, null, 0).encounter!!
        assertEquals(
            "warning about light rain when heavy follows would be a poor warning",
            DbzPalette.Intensity.HEAVY, encounter.intensity,
        )
    }

    // ------------------------------------------------------------ degraded states

    @Test
    fun `a stationary rider gets nearest rain but no ETA`() {
        val f = bandEastOfRider(50)
        val forecast = RainForecaster.forecast(rider(speed = 0.1), f, null, 0)

        assertNotNull("distance needs no assumption about where you are going", forecast.nearest)
        assertNull(forecast.encounter)
        assertEquals(RiderState.Confidence.NONE, forecast.confidence)
    }

    @Test
    fun `no heading means no ETA but still a distance`() {
        val f = bandEastOfRider(50)
        val forecast = RainForecaster.forecast(rider(heading = null), f, null, 0)

        assertNotNull(forecast.nearest)
        assertNull(forecast.encounter)
    }

    @Test
    fun `a twisty path shortens the horizon and so may hide a distant band`() {
        val f = bandEastOfRider(50)   // 22 minutes away

        val straight = RainForecaster.forecast(rider(curvature = 0.0), f, null, 0)
        assertEquals(30.0, straight.horizonMinutes, 1e-9)
        assertNotNull(straight.encounter)

        val twisty = RainForecaster.forecast(rider(curvature = 0.4), f, null, 0)
        assertEquals(5.0, twisty.horizonMinutes, 1e-9)
        assertNull(
            "predicting 22 minutes ahead is not honest when the next 3 are a guess",
            twisty.encounter,
        )
        assertEquals(RiderState.Confidence.LOW, twisty.confidence)
    }

    @Test
    fun `an unusable cell velocity is treated as stationary rather than trusted`() {
        val f = bandEastOfRider(50)
        val noisy = CellMotionEstimator.CellVelocity(10.0, 270.0, confidence = 0.02)

        val forecast = RainForecaster.forecast(rider(), f, noisy, 0)
        // Falls back to the stationary answer, not the closing one.
        assertEquals(22.0, forecast.encounter!!.etaMinutes, 1.0)
    }

    @Test
    fun `a frame stamped in the future is refused`() {
        val f = bandEastOfRider(50, timeEpochSeconds = 1000)
        val forecast = RainForecaster.forecast(rider(), f, null, nowEpochSeconds = 0)

        assertTrue(forecast.isClear)
        assertEquals(RiderState.Confidence.NONE, forecast.confidence)
    }

    @Test
    fun `unavailable is not the same as clear`() {
        val unavailable = RainForecast.unavailable(frameAgeSeconds = 120)
        assertEquals(RiderState.Confidence.NONE, unavailable.confidence)
        assertEquals(0.0, unavailable.horizonMinutes, 1e-9)
        assertEquals(120L, unavailable.frameAgeSeconds)

        // This test used to reason that confidence was what stopped the UI rendering
        // "you are clear" over a dead radar feed. It is not, and cannot be: confidence
        // describes the *rider* — heading, fix accuracy, how twisty the road has been —
        // and never how much of the field was observed. A rider with a perfect fix on a
        // straight road scores HIGH confidence whether the radar returned a picture or
        // nothing at all. Availability is the flag that actually carries this, and the
        // message routes on it.
        assertEquals(RainForecast.Availability.NO_RADAR, unavailable.availability)
        assertEquals(
            RainForecast.Availability.OK,
            RainForecaster.forecast(rider(), bandEastOfRider(50), null, 0).availability,
        )
    }

    @Test
    fun `a rider outside the field coverage gets no forecast rather than a wrong one`() {
        val f = bandEastOfRider(50)
        val faraway = rider().copy(latitude = 48.8566, longitude = 2.3522)   // Paris

        val forecast = RainForecaster.forecast(faraway, f, null, 0)
        assertNull(forecast.nearest)
        assertNull(
            "nothing was observed anywhere near this rider, so nothing may be reported",
            forecast.encounter,
        )
    }
}

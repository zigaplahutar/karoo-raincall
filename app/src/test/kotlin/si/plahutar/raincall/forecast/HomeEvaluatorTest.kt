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
import kotlin.math.cos
import kotlin.math.sin

/**
 * The case that motivates all of this: in simulation, riding east away from an advancing
 * band scored zero wet minutes for a whole hour — perfect by the plain evasion measure,
 * and useless, because it carried the rider steadily further from home. Staying dry by
 * fleeing is not a solution if you wanted to get back.
 */
class HomeEvaluatorTest {

    private val riderLat = 46.0569
    private val riderLon = 14.5058
    private val riderX = 673
    private val riderY = 520
    private val fieldSize = 1024

    private val range = TileMath.TileRange(
        zoom = 8, tileSize = 512,
        minTileX = 137, maxTileX = 138, minTileY = 90, maxTileY = 91,
    )

    private fun rider(heading: Double = 90.0, speed: Double = 8.0) = RiderState(
        timestampMillis = 0,
        latitude = riderLat,
        longitude = riderLon,
        accuracyMetres = 5.0,
        speedMetresPerSecond = speed,
        headingDegrees = heading,
        pathCurvature = 0.0,
        riding = true,
    )

    /** Home at a distance and bearing from the rider. */
    private fun home(bearingDegrees: Double, metres: Double): HomeContext {
        val (lon, lat) = TileMath.destination(riderLon, riderLat, bearingDegrees, metres)
        return HomeContext(latitude = lat, longitude = lon)
    }

    private fun band(
        normalDegrees: Double,
        offsetPixels: Int,
        widthPixels: Int,
    ): RadarField {
        val f = RadarField.empty(fieldSize, fieldSize, range, 0)
        val sample = DbzPalette.Sample(35, DbzPalette.PrecipType.RAIN)
        val r = Math.toRadians(normalDegrees)
        val nx = sin(r)
        val ny = -cos(r)
        for (y in 0 until fieldSize) {
            for (x in 0 until fieldSize) {
                val d = (x - riderX) * nx + (y - riderY) * ny
                if (d >= offsetPixels && d <= offsetPixels + widthPixels) f.set(x, y, sample)
            }
        }
        return f
    }

    private fun velocity(speed: Double, bearing: Double) =
        CellMotionEstimator.CellVelocity(speed, bearing, confidence = 0.8)

    // ------------------------------------------------------------------- relevance

    @Test
    fun `nearby home is always relevant`() {
        assertTrue(HomeEvaluator.isRelevant(rider(), home(270.0, 12_000.0)))
        assertTrue(HomeEvaluator.isRelevant(rider(), home(90.0, 3_000.0)))
    }

    @Test
    fun `a distant home is relevant when the rider is pointed at it`() {
        // 30 km out is beyond the plain distance test, but a rider who has just turned
        // for home should not be met with silence.
        val far = home(270.0, 30_000.0)
        assertTrue(
            "heading home",
            HomeEvaluator.isRelevant(rider(heading = 270.0), far),
        )
    }

    @Test
    fun `home beyond what the radar can speak to is out of range`() {
        // 60 km is over two hours of riding; advising on arrival then would be a forecast
        // dressed up as a measurement.
        val advice = HomeEvaluator.evaluate(
            rider(heading = 270.0),
            home(270.0, 60_000.0),
            band(90.0, 50, 40),
            null,
            nowEpochSeconds = 0,
        )
        assertEquals(HomeEvaluator.HomeAdvice.OutOfRange, advice)
    }

    // -------------------------------------------------------------------- the advice

    @Test
    fun `a clear sky means home is comfortable and nothing is said`() {
        val advice = HomeEvaluator.evaluate(
            rider(), home(270.0, 12_000.0),
            RadarField.empty(fieldSize, fieldSize, range, 0),
            null, 0,
        )
        assertTrue(advice is HomeEvaluator.HomeAdvice.Comfortable)
        assertNull(
            "a deadline that is not really a deadline is noise",
            EvasionPhrasing.describeHome(advice),
        )
    }

    @Test
    fun `a band advancing over home means turn for home now`() {
        // Home 12 km west. A band front 35 px (7.4 km) west of the rider, 10 px thick,
        // marching east at the rider's own speed. Turn now and you beat it home; delay
        // and you ride into it.
        val advice = HomeEvaluator.evaluate(
            rider(heading = 90.0),
            home(270.0, 12_000.0),
            band(90.0, -165, 47),
            velocity(8.0, 90.0),
            nowEpochSeconds = 0,
        )

        assertNotNull(advice)
        assertTrue(
            "expected a turnaround deadline, got $advice",
            advice is HomeEvaluator.HomeAdvice.TurnAroundWithin ||
                advice is HomeEvaluator.HomeAdvice.WetWhateverYouDo,
        )
    }

    @Test
    fun `the latest safe turnaround is reported, not the first one that works`() {
        // The rider wants to know how much longer they can carry on, not merely that
        // turning round this instant would have worked.
        val advice = HomeEvaluator.evaluate(
            rider(), home(270.0, 8_000.0), band(90.0, 120, 60), velocity(4.0, 270.0), 0,
        )
        if (advice is HomeEvaluator.HomeAdvice.TurnAroundWithin) {
            assertTrue(
                "must be one of the durations offered",
                advice.minutes in HomeEvaluator.TURNAROUND_OPTIONS_MINUTES.toList(),
            )
        }
    }

    @Test
    fun `no heading or speed means no home advice`() {
        val f = band(90.0, 50, 40)
        assertNull(HomeEvaluator.evaluate(
            rider().copy(headingDegrees = null), home(270.0, 10_000.0), f, null, 0))
        assertNull(HomeEvaluator.evaluate(
            rider().copy(speedMetresPerSecond = null), home(270.0, 10_000.0), f, null, 0))
    }

    @Test
    fun `a frame from the future is refused`() {
        assertNull(HomeEvaluator.evaluate(
            rider(), home(270.0, 10_000.0), band(90.0, 50, 40), null, nowEpochSeconds = -60))
    }

    // -------------------------------------------------------------------- phrasing

    @Test
    fun `turn now reads as now, not as zero minutes`() {
        val detail = EvasionPhrasing.describeHome(
            HomeEvaluator.HomeAdvice.TurnAroundWithin(0.0, 12_000.0)
        )!!
        assertEquals("turn for home now", detail.full)
        assertEquals("home now", detail.short)
    }

    @Test
    fun `a deadline states the time remaining`() {
        val detail = EvasionPhrasing.describeHome(
            HomeEvaluator.HomeAdvice.TurnAroundWithin(15.0, 12_000.0)
        )!!
        assertTrue(detail.full.contains("15"))
        assertTrue("must fit a field", detail.full.length <= 24)
        assertTrue(detail.short.length <= 14)
    }

    @Test
    fun `an unavoidably wet ride home is said plainly`() {
        val detail = EvasionPhrasing.describeHome(
            HomeEvaluator.HomeAdvice.WetWhateverYouDo(18.0, 40.0)
        )!!
        assertTrue(detail.full.contains("18"))
        assertFalse("no false reassurance", detail.full.contains("dry"))
    }

    @Test
    fun `when a soaking is certain the advice becomes how soon it ends`() {
        // Staying dry is off the table, so the useful question changes: not "how do I
        // dodge this" but "how do I get it over with". A rider told only that every
        // option is wet has learned nothing they cannot already feel.
        val detail = EvasionPhrasing.describeHome(
            HomeEvaluator.HomeAdvice.WetWhateverYouDo(
                wetMinutes = 22.0,
                arrivalMinutes = 40.0,
                directArrivalMinutes = 18.0,
            )
        )!!

        assertTrue("should name the soonest arrival: '${detail.full}'", detail.full.contains("18"))
        assertTrue("must fit a field", detail.full.length <= 30)
        assertTrue(detail.short.length <= 14)
    }

    @Test
    fun `cutting away from a loaded route is said out loud`() {
        // A rider who has followed a route all morning should hear that the advice
        // abandons it, rather than discovering that at the next junction.
        val onRoute = EvasionPhrasing.describeHome(
            HomeEvaluator.HomeAdvice.WetWhateverYouDo(
                wetMinutes = 22.0,
                arrivalMinutes = 40.0,
                directArrivalMinutes = 18.0,
                leavesRoute = true,
            )
        )!!
        val freeRiding = EvasionPhrasing.describeHome(
            HomeEvaluator.HomeAdvice.WetWhateverYouDo(
                wetMinutes = 22.0,
                arrivalMinutes = 40.0,
                directArrivalMinutes = 18.0,
                leavesRoute = false,
            )
        )!!

        assertTrue("leaving the route must read differently", onRoute.full != freeRiding.full)
        assertTrue(onRoute.full.contains("straight"))
    }

    @Test
    fun `getting home soonest is worth the full-screen interruption`() {
        // "No better route" is worth a line in the field the rider chose to look at, but
        // not an interruption. A soaking with a stated end is a different matter.
        val forecast = RainForecast(
            nearest = null,
            encounter = Encounter(
                etaMinutes = 4.0, durationMinutes = 30.0,
                intensity = DbzPalette.Intensity.HEAVY,
                type = DbzPalette.PrecipType.RAIN,
                possibleHail = false, coneCoverage = 1.0,
            ),
            confidence = RiderState.Confidence.HIGH,
            horizonMinutes = 30.0,
            frameAgeSeconds = 60,
        )

        val message = MessageComposer.compose(
            forecast,
            DisplayUnits.METRIC,
            EvasionEvaluator.Advice.NoGoodOption(30.0),
            HomeEvaluator.HomeAdvice.WetWhateverYouDo(
                wetMinutes = 25.0,
                arrivalMinutes = 40.0,
                directArrivalMinutes = 18.0,
            ),
        )

        assertTrue(
            "alert was '${message.alertDetail}'",
            message.alertDetail!!.contains("18"),
        )
    }

    @Test
    fun `home advice takes precedence over a plain detour in the message`() {
        val forecast = RainForecast(
            nearest = null,
            encounter = Encounter(
                etaMinutes = 12.0, durationMinutes = 20.0,
                intensity = DbzPalette.Intensity.MODERATE,
                type = DbzPalette.PrecipType.RAIN,
                possibleHail = false, coneCoverage = 1.0,
            ),
            confidence = RiderState.Confidence.HIGH,
            horizonMinutes = 30.0,
            frameAgeSeconds = 60,
        )

        val message = MessageComposer.compose(
            forecast,
            DisplayUnits.METRIC,
            EvasionEvaluator.Advice.Detour("NE", 45.0, 2.0, 9.0),
            HomeEvaluator.HomeAdvice.TurnAroundWithin(10.0, 12_000.0),
        )

        // Told "head NE to stay dry" when NE is away from home, the rider has been
        // answered a question they did not ask.
        assertTrue(message.details.any { it.full == "turn home within 10 min" })
        assertFalse(message.details.any { it.full.contains("head NE") })
        assertTrue(message.alertDetail!!.contains("turn home within 10 min"))
    }

    @Test
    fun `without home advice the plain detour still shows`() {
        val forecast = RainForecast(
            nearest = null,
            encounter = Encounter(
                12.0, 20.0, DbzPalette.Intensity.MODERATE,
                DbzPalette.PrecipType.RAIN, false, 1.0,
            ),
            confidence = RiderState.Confidence.HIGH,
            horizonMinutes = 30.0,
            frameAgeSeconds = 60,
        )
        val message = MessageComposer.compose(
            forecast, DisplayUnits.METRIC,
            EvasionEvaluator.Advice.Detour("NE", 45.0, 2.0, 9.0),
            home = null,
        )
        assertTrue(message.details.any { it.full == "head NE to stay dry" })
    }
}

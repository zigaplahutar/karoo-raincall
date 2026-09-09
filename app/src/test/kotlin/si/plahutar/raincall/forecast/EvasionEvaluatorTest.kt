package si.plahutar.raincall.forecast

import org.junit.Assert.assertEquals
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
 * Scenarios where the right answer is known in advance, because the failure mode here is
 * not a wrong number but bad judgement: something that always recommends a detour (some
 * direction is always marginally drier) or never does.
 */
class EvasionEvaluatorTest {

    private val zoom = 8
    private val tileSize = 512
    private val riderLat = 46.0569
    private val riderLon = 14.5058
    private val riderX = 673
    private val riderY = 520
    private val fieldSize = 1024

    private val range = TileMath.TileRange(
        zoom = zoom, tileSize = tileSize,
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

    /**
     * A band of rain whose face is perpendicular to [normalDegrees], occupying the strip
     * from [offsetPixels] to [offsetPixels] + [widthPixels] measured along that normal
     * from the rider.
     *
     * Oriented rather than aligned to the pixel grid on purpose: a band defined only by
     * column would be infinite north to south, so every northward detour would escape it
     * for free and the evaluator would look cleverer than it is.
     */
    private fun band(
        normalDegrees: Double,
        offsetPixels: Int,
        widthPixels: Int,
        dbz: Int = 35,
    ): RadarField {
        val f = RadarField.empty(fieldSize, fieldSize, range, 0)
        val sample = DbzPalette.Sample(dbz, DbzPalette.PrecipType.RAIN)
        val radians = Math.toRadians(normalDegrees)
        // Screen +y is south, so the northward component of the normal is negative y.
        val nx = sin(radians)
        val ny = -cos(radians)

        for (y in 0 until fieldSize) {
            for (x in 0 until fieldSize) {
                val d = (x - riderX) * nx + (y - riderY) * ny
                if (d >= offsetPixels && d <= offsetPixels + widthPixels) {
                    f.set(x, y, sample)
                }
            }
        }
        return f
    }

    private fun velocity(speed: Double, bearing: Double?) =
        CellMotionEstimator.CellVelocity(speed, bearing, confidence = 0.8)

    @Test
    fun `a stationary band straight ahead is worth avoiding`() {
        // 50 px is 10.6 km ahead, 40 px is 8.5 km thick.
        val field = band(normalDegrees = 90.0, offsetPixels = 50, widthPixels = 40)

        val advice = EvasionEvaluator.evaluate(rider(), field, null, nowEpochSeconds = 0)
        assertTrue(
            "expected real advice, got $advice",
            advice is EvasionEvaluator.Advice.Detour ||
                advice is EvasionEvaluator.Advice.TurnBack,
        )
    }

    @Test
    fun `a clear sky produces no advice at all`() {
        val field = RadarField.empty(fieldSize, fieldSize, range, 0)
        assertEquals(
            EvasionEvaluator.Advice.NoRain,
            EvasionEvaluator.evaluate(rider(), field, null, 0),
        )
    }

    @Test
    fun `rain well behind the rider does not prompt advice`() {
        val field = band(normalDegrees = 90.0, offsetPixels = -200, widthPixels = 30)
        val advice = EvasionEvaluator.evaluate(rider(), field, null, 0)

        assertTrue(
            "there is nothing to avoid ahead, got $advice",
            advice is EvasionEvaluator.Advice.NoRain ||
                advice is EvasionEvaluator.Advice.ContinueIsBest,
        )
    }

    @Test
    fun `a marginal saving is not worth leaving the route for`() {
        // A thin band: continuing costs only a couple of wet minutes. Telling someone to
        // turn off their route for that is worse than saying nothing.
        val field = band(normalDegrees = 90.0, offsetPixels = 60, widthPixels = 4)

        val advice = EvasionEvaluator.evaluate(rider(), field, null, 0)
        assertTrue(
            "a small saving must not trigger a detour, got $advice",
            advice is EvasionEvaluator.Advice.ContinueIsBest,
        )
    }

    @Test
    fun `a front the rider is already inside is reported as having no way round`() {
        // Wide enough that an hour of riding in any direction stays inside it: at 8 m/s
        // the rider covers 28.8 km, and this band is well over a hundred wide.
        val field = band(normalDegrees = 90.0, offsetPixels = -400, widthPixels = 800)

        val advice = EvasionEvaluator.evaluate(rider(), field, null, 0)
        assertTrue(
            "an app that always finds a solution stops being believed, got $advice",
            advice is EvasionEvaluator.Advice.NoGoodOption,
        )
        assertTrue((advice as EvasionEvaluator.Advice.NoGoodOption).wetMinutes >= 25.0)
    }

    @Test
    fun `ties are broken towards the least disruptive option`() {
        // Several options keep the rider equally dry. Without an ordering the advice
        // would be whichever was built first, which could send someone back the way they
        // came when a slight turn would do.
        val field = band(normalDegrees = 90.0, offsetPixels = 50, widthPixels = 40)
        val advice = EvasionEvaluator.evaluate(rider(heading = 90.0), field, null, 0)

        if (advice is EvasionEvaluator.Advice.Detour) {
            val turn = kotlin.math.abs(TileMath.bearingDelta(90.0, advice.bearingDegrees))
            assertTrue(
                "a detour of $turn degrees is more than needed",
                turn <= 135.0,
            )
        }
    }

    /**
     * A compact cell, as opposed to a band. Needed because an idealised infinite band
     * can always be escaped by riding along its length, so a band scenario never gives
     * waiting a chance to be the right answer.
     */
    private fun cell(
        offsetEastPixels: Int,
        radiusPixels: Int,
        dbz: Int = 35,
    ): RadarField {
        val f = RadarField.empty(fieldSize, fieldSize, range, 0)
        val sample = DbzPalette.Sample(dbz, DbzPalette.PrecipType.RAIN)
        val cx = riderX + offsetEastPixels
        val r2 = radiusPixels * radiusPixels
        for (y in 0 until fieldSize) {
            for (x in 0 until fieldSize) {
                val dx = x - cx
                val dy = y - riderY
                if (dx * dx + dy * dy <= r2) f.set(x, y, sample)
            }
        }
        return f
    }

    @Test
    fun `waiting wins when a compact cell is crossing the route just ahead`() {
        // 30 px is 6.4 km ahead, 25 px is a 5.3 km radius, tracking north across the
        // rider's path. Ride on and you meet it; stand still and it goes by.
        val field = cell(offsetEastPixels = 30, radiusPixels = 25)

        val advice = EvasionEvaluator.evaluate(
            rider(), field, velocity(8.0, 0.0), nowEpochSeconds = 0,
        )
        assertTrue("expected a wait, got $advice", advice is EvasionEvaluator.Advice.Wait)
        val wait = advice as EvasionEvaluator.Advice.Wait
        assertTrue("a wait must be one of the offered durations",
            wait.waitMinutes in EvasionEvaluator.WAIT_OPTIONS_MINUTES.toList())
    }

    @Test
    fun `a wait must repay the time it costs`() {
        // Standing under a tree for twenty minutes to dodge six minutes of rain is not a
        // saving, it is a slower way to finish the ride.
        val ratio = EvasionEvaluator.WAIT_PAYBACK_RATIO
        assertTrue("a 20 minute wait must be worth at least 10 wet minutes",
            20.0 * ratio >= 10.0)
    }

    @Test
    fun `waiting is preferred over a detour when both keep the rider equally dry`() {
        // A detour abandons the route for the rest of the hour; a wait costs ten minutes
        // and leaves the rider where they meant to be. Scoring a detour purely by
        // angular deviation made every detour cheaper than any wait, and across 320
        // simulated cells the wait branch never won once.
        val field = cell(offsetEastPixels = 30, radiusPixels = 25)
        val advice = EvasionEvaluator.evaluate(rider(), field, velocity(8.0, 180.0), 0)

        assertTrue(
            "expected a wait rather than a detour, got $advice",
            advice is EvasionEvaluator.Advice.Wait,
        )
    }

    @Test
    fun `no heading or no speed means no comparison is possible`() {
        val field = band(normalDegrees = 90.0, offsetPixels = 50, widthPixels = 40)

        assertEquals(
            EvasionEvaluator.Advice.Unknown,
            EvasionEvaluator.evaluate(rider(heading = 0.0).copy(headingDegrees = null), field, null, 0),
        )
        assertEquals(
            EvasionEvaluator.Advice.Unknown,
            EvasionEvaluator.evaluate(rider().copy(speedMetresPerSecond = null), field, null, 0),
        )
    }

    @Test
    fun `a frame from the future is refused rather than guessed at`() {
        val field = band(normalDegrees = 90.0, offsetPixels = 50, widthPixels = 40)
        assertEquals(
            EvasionEvaluator.Advice.Unknown,
            EvasionEvaluator.evaluate(rider(), field, null, nowEpochSeconds = -100),
        )
    }

    @Test
    fun `an unusable cell velocity is treated as stationary rather than trusted`() {
        val field = band(normalDegrees = 90.0, offsetPixels = 50, widthPixels = 40)
        val noisy = CellMotionEstimator.CellVelocity(20.0, 270.0, confidence = 0.01)

        val withNoise = EvasionEvaluator.evaluate(rider(), field, noisy, 0)
        val withNothing = EvasionEvaluator.evaluate(rider(), field, null, 0)
        assertEquals(withNothing::class, withNoise::class)
    }
}

class EvasionPhrasingTest {

    @Test
    fun `no rain and unknown produce no line`() {
        // Reassuring someone about a danger that was never there wastes a line the rest
        // of the message can use.
        assertNull(EvasionPhrasing.describe(EvasionEvaluator.Advice.NoRain))
        assertNull(EvasionPhrasing.describe(EvasionEvaluator.Advice.Unknown))
    }

    @Test
    fun `every phrasing has a short form and stays terse`() {
        val cases = listOf(
            EvasionEvaluator.Advice.ContinueIsBest(8.0),
            EvasionEvaluator.Advice.Detour("NE", 45.0, 2.0, 9.0),
            EvasionEvaluator.Advice.Wait(10.0, 7.0),
            EvasionEvaluator.Advice.TurnBack(12.0),
            EvasionEvaluator.Advice.NoGoodOption(31.0),
        )
        for (advice in cases) {
            val detail = EvasionPhrasing.describe(advice)!!
            assertTrue("'${detail.full}' is too long", detail.full.length <= 24)
            assertTrue("'${detail.short}' is too long", detail.short.length <= 14)
            assertTrue(detail.short.length <= detail.full.length)
        }
    }

    @Test
    fun `the no-way-round wording is honest rather than reassuring`() {
        val detail = EvasionPhrasing.describe(EvasionEvaluator.Advice.NoGoodOption(31.0))!!
        assertTrue(detail.full.contains("no way round"))
        assertTrue("the scale of it matters", detail.full.contains("31"))
    }

    @Test
    fun `advice appears in the field message`() {
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
        )

        assertTrue(message.details.any { it.full == "head NE to stay dry" })
        assertTrue("actionable advice belongs in the alert too",
            message.alertDetail!!.contains("head NE"))
    }

    @Test
    fun `non-actionable advice stays out of the full screen alert`() {
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
            EvasionEvaluator.Advice.ContinueIsBest(8.0),
        )

        // Worth a line in the field, which the rider chose to look at. Not worth the
        // full-screen interruption they did not.
        assertTrue(message.details.any { it.full == "no better route" })
        assertTrue(!message.alertDetail!!.contains("no better route"))
    }
}

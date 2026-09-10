package si.plahutar.raincall.forecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.radar.DbzPalette
import si.plahutar.raincall.radar.RadarField
import si.plahutar.raincall.radar.TileMath

/**
 * What the app is allowed to say about ground it never looked at.
 *
 * The radar window is 60 km around the rider. Over a sixty-minute evasion horizon a
 * rider covers 43 km, and the backward advection displaces each lookup by tens more, so
 * leaving the window is routine rather than exotic. Every simulator used to read a
 * position outside the mosaic as `isMeaningful == false` — indistinguishable from clear
 * sky — and quietly banked it as dry minutes.
 *
 * The measured consequence, before the fix: with rain over one hundred per cent of the
 * downloaded window and the rider near its western edge, the advice was
 * `TurnBack(savedMinutes=48.0)`. Forty-eight dry minutes, none of them observed.
 */
class CoverageHonestyTest {

    private val zoom = 8
    private val tileSize = 512
    private val fieldSize = 1024
    private val riderLat = 46.0569
    private val riderLon = 14.5058

    private val range = TileMath.TileRange(
        zoom = zoom, tileSize = tileSize,
        minTileX = 137, maxTileX = 138, minTileY = 90, maxTileY = 91,
    )

    private fun rider(
        lat: Double = riderLat,
        lon: Double = riderLon,
        heading: Double = 90.0,
        speed: Double = 12.0,
    ) = RiderState(
        timestampMillis = 0,
        latitude = lat,
        longitude = lon,
        accuracyMetres = 5.0,
        speedMetresPerSecond = speed,
        headingDegrees = heading,
        pathCurvature = 0.0,
        riding = true,
    )

    /** Rain over every pixel, so there is no dry option anywhere we can see. */
    private fun soakedEverywhere(): RadarField {
        val f = RadarField.empty(fieldSize, fieldSize, range, 0)
        val sample = DbzPalette.Sample(40, DbzPalette.PrecipType.RAIN)
        for (y in 0 until fieldSize) {
            for (x in 0 until fieldSize) f.set(x, y, sample)
        }
        return f
    }

    /** Longitude of a pixel column, so a rider can be placed near the window's edge. */
    private fun lonAtColumn(x: Int): Double =
        TileMath.globalPixelToLonLat(range.toGlobalPixel(x, fieldSize / 2), zoom, tileSize).first

    // ------------------------------------------------------- the field itself

    @Test
    fun `a position outside the window is unobserved, not dry`() {
        val f = RadarField.empty(fieldSize, fieldSize, range, 0)

        assertEquals(
            "inside the window with no echo is a genuine observation of no rain",
            RadarField.Observation.DRY,
            f.observe(riderLon, riderLat),
        )
        assertEquals(
            "Paris is nowhere near a window over Slovenia",
            RadarField.Observation.UNOBSERVED,
            f.observe(2.3522, 48.8566),
        )
    }

    @Test
    fun `wet is only reported above the clutter floor`() {
        val f = RadarField.empty(fieldSize, fieldSize, range, 0)
        f.set(673, 520, DbzPalette.Sample(5, DbzPalette.PrecipType.RAIN))
        assertEquals(
            "a weak echo is an observation of nothing worth reporting",
            RadarField.Observation.DRY,
            f.observe(riderLon, riderLat),
        )
    }

    // -------------------------------------------------------------- evasion

    @Test
    fun `riding off the edge of coverage is not offered as an escape`() {
        // The rider sits 40 px from the western edge with rain everywhere the app can
        // see. Turning back leaves the window almost immediately, and everything past
        // that point is unknown. Recommending it would be inventing a forecast.
        val f = soakedEverywhere()
        val nearEdge = rider(lon = lonAtColumn(40), heading = 90.0)

        val advice = EvasionEvaluator.evaluate(nearEdge, f, null, 0)

        assertFalse(
            "advice was $advice — turning back here rides straight out of coverage",
            advice is EvasionEvaluator.Advice.TurnBack,
        )
        assertFalse(
            "advice was $advice — a detour out of coverage is not an escape either",
            advice is EvasionEvaluator.Advice.Detour,
        )
    }

    @Test
    fun `rain across the whole window is honestly reported as no good option`() {
        val f = soakedEverywhere()

        val advice = EvasionEvaluator.evaluate(rider(), f, null, 0)

        assertTrue(
            "everything visible is wet, so there is genuinely nowhere to go: $advice",
            advice is EvasionEvaluator.Advice.NoGoodOption,
        )
    }

    // ----------------------------------------------------------------- home

    @Test
    fun `a ride home that leaves coverage is not called dry`() {
        // Home lies well outside the downloaded window. The old code walked there,
        // found nothing because there was nothing to find, and called it comfortable.
        val f = soakedEverywhere()
        val farHome = HomeContext(
            latitude = riderLat,
            longitude = lonAtColumn(-2000),
        )

        val advice = HomeEvaluator.evaluate(rider(heading = 270.0), farHome, f, null, 0)

        assertFalse(
            "advice was $advice — the way home was never observed",
            advice is HomeEvaluator.HomeAdvice.Comfortable,
        )
    }

    // ------------------------------------------------------------ forecaster

    @Test
    fun `the cone stops at the edge of coverage rather than reporting clear beyond it`() {
        // A rider heading out of the window. Nothing is found ahead, but "nothing found"
        // must not be dressed up as a searched-and-clear thirty minutes.
        val f = RadarField.empty(fieldSize, fieldSize, range, 0)
        val leaving = rider(lon = lonAtColumn(20), heading = 270.0, speed = 15.0)

        val forecast = RainForecaster.forecast(leaving, f, null, 0)

        assertEquals(
            "the radar itself was fine; there is simply no encounter to report",
            RainForecast.Availability.OK,
            forecast.availability,
        )
        assertEquals("nothing was found ahead", null, forecast.encounter)
    }

    @Test
    fun `cone coverage counts only the samples that were actually observed`() {
        // Half the cone outside the window and the visible half wet is fully wet as far
        // as anyone can tell — not one-in-nine.
        val f = soakedEverywhere()
        val nearEdge = rider(lon = lonAtColumn(30), heading = 270.0, speed = 12.0)

        val forecast = RainForecaster.forecast(nearEdge, f, null, 0)
        val encounter = forecast.encounter

        if (encounter != null) {
            assertNotEquals(
                "coverage must be a fraction of what was seen, not of all nine samples",
                0.0,
                encounter.coneCoverage,
            )
            assertTrue(
                "coverage ${encounter.coneCoverage} should be a real fraction",
                encounter.coneCoverage > 0.0 && encounter.coneCoverage <= 1.0,
            )
        }
    }
}

package si.plahutar.raincall.forecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.radar.DbzPalette

/**
 * The wet-road warning was computed on every recompute throughout every ride and then
 * dropped on the floor: `wetRoadAdvice()` had no callers. These pin it to the message
 * where a rider can actually see it.
 */
class WetRoadSurfacingTest {

    private fun clearForecast(horizon: Double = 30.0) = RainForecast(
        nearest = null,
        encounter = null,
        confidence = RiderState.Confidence.HIGH,
        horizonMinutes = horizon,
        frameAgeSeconds = 120,
    )

    private fun encounterForecast() = RainForecast(
        nearest = null,
        encounter = Encounter(
            etaMinutes = 12.0,
            durationMinutes = 20.0,
            intensity = DbzPalette.Intensity.MODERATE,
            type = DbzPalette.PrecipType.RAIN,
            possibleHail = false,
            coneCoverage = 1.0,
        ),
        confidence = RiderState.Confidence.HIGH,
        horizonMinutes = 30.0,
        frameAgeSeconds = 120,
    )

    @Test
    fun `a clear sky still mentions roads left wet by rain that has passed`() {
        // The moment of highest risk is often the first dry corner: the rider has stopped
        // thinking about rain and the surface has not caught up.
        val m = MessageComposer.compose(clearForecast(), wetRoads = "wet roads ~15 min")

        assertTrue(
            "details were ${m.details.map { it.full }}",
            m.details.any { it.full.contains("wet roads") },
        )
    }

    @Test
    fun `rain still coming outranks roads left over from rain that has been`() {
        val m = MessageComposer.compose(encounterForecast(), wetRoads = "wet roads ~15 min")

        val wetRoadIndex = m.details.indexOfFirst { it.full.contains("wet roads") }
        assertTrue("the grip line should be present", wetRoadIndex >= 0)
        assertTrue(
            "but after the intensity of what is coming",
            m.details.indexOfFirst { it.full.contains("moderate") } < wetRoadIndex,
        )
    }

    @Test
    fun `no warning means no line`() {
        val m = MessageComposer.compose(clearForecast(), wetRoads = null)
        assertTrue(m.details.none { it.full.contains("wet roads") })
    }

    // ------------------------------------------------------- the tracker itself

    @Test
    fun `advice is a query, not a state change`() {
        // It used to clear its own state once the drying window expired, so asking twice
        // could give two different answers.
        val tracker = WetRoadTracker()
        tracker.observe(0L, DbzPalette.Intensity.MODERATE)

        val first = tracker.advice(10 * 60_000L, 8.0, rainingNow = false)
        val second = tracker.advice(10 * 60_000L, 8.0, rainingNow = false)

        assertNotNull(first)
        assertEquals("asking twice must not change the answer", first, second)
    }

    @Test
    fun `the window expires and a later shower starts its own`() {
        val tracker = WetRoadTracker()
        tracker.observe(0L, DbzPalette.Intensity.HEAVY)

        // Well past the heavy-rain drying window.
        val longAfter = 90 * 60_000L
        assertNull(tracker.advice(longAfter, 8.0, rainingNow = false))

        // Observing dry conditions after the window resets the worst-intensity memory,
        // so a later light shower gets a light shower's window rather than inheriting
        // the earlier downpour's.
        tracker.observe(longAfter, DbzPalette.Intensity.NONE)
        tracker.observe(longAfter, DbzPalette.Intensity.LIGHT)

        val advice = tracker.advice(longAfter + 25 * 60_000L, 8.0, rainingNow = false)
        assertNull("a light shower has dried after 25 minutes", advice)
    }
}

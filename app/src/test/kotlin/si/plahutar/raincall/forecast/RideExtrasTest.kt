package si.plahutar.raincall.forecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.plahutar.raincall.radar.DbzPalette

class WetRoadTrackerTest {

    private val minute = 60_000L

    @Test
    fun `nothing is said before it has rained`() {
        val t = WetRoadTracker()
        assertNull(t.advice(10 * minute, 8.0, rainingNow = false))
    }

    @Test
    fun `nothing is said while it is still raining`() {
        // The rider can see that for themselves; the line is better spent on the forecast.
        val t = WetRoadTracker()
        t.observe(0, DbzPalette.Intensity.MODERATE)
        assertNull(t.advice(minute, 8.0, rainingNow = true))
    }

    @Test
    fun `grip is flagged after the rain stops`() {
        val t = WetRoadTracker()
        t.observe(0, DbzPalette.Intensity.MODERATE)

        val advice = t.advice(5 * minute, 8.0, rainingNow = false)
        assertNotNull(advice)
        assertTrue("expected a countdown, got '$advice'", advice!!.contains("min"))
        assertTrue(advice.length <= 20)
    }

    @Test
    fun `heavier rain keeps the roads wet for longer`() {
        val light = WetRoadTracker().apply { observe(0, DbzPalette.Intensity.LIGHT) }
        val heavy = WetRoadTracker().apply { observe(0, DbzPalette.Intensity.HEAVY) }

        // 25 minutes after light rain the window has closed; after heavy it has not.
        assertNull(light.advice(25 * minute, 8.0, false))
        assertNotNull(heavy.advice(25 * minute, 8.0, false))
    }

    @Test
    fun `the warning expires`() {
        val t = WetRoadTracker()
        t.observe(0, DbzPalette.Intensity.LIGHT)
        assertNotNull(t.advice(10 * minute, 8.0, false))
        assertNull(t.advice(30 * minute, 8.0, false))
    }

    @Test
    fun `a stationary rider is not warned about cornering`() {
        val t = WetRoadTracker()
        t.observe(0, DbzPalette.Intensity.HEAVY)
        assertNull(t.advice(5 * minute, 0.5, false))
    }

    @Test
    fun `the worst intensity seen sets the drying window`() {
        // A downpour that eased to drizzle before stopping still leaves standing water.
        val t = WetRoadTracker()
        t.observe(0, DbzPalette.Intensity.HEAVY)
        t.observe(minute, DbzPalette.Intensity.LIGHT)
        assertNotNull(t.advice(25 * minute, 8.0, false))
    }
}

class RideSummaryTrackerTest {

    private fun wet(dbz: Int) = DbzPalette.Sample(dbz, DbzPalette.PrecipType.RAIN)
    private val dry = DbzPalette.Sample.EMPTY

    @Test
    fun `a dry ride says so`() {
        val t = RideSummaryTracker()
        repeat(60) { t.observe(dry) }

        val summary = t.summary()
        assertTrue(summary.stayedDry)
        assertEquals(listOf("Stayed dry"), summary.lines())
    }

    @Test
    fun `wet minutes and worst intensity are reported`() {
        val t = RideSummaryTracker()
        t.setIntervalMinutes(1.0)
        repeat(30) { t.observe(dry) }
        repeat(10) { t.observe(wet(25)) }     // light
        repeat(5) { t.observe(wet(50)) }      // heavy

        val summary = t.summary()
        assertEquals(15.0, summary.wetMinutes, 1e-9)
        assertEquals(DbzPalette.Intensity.HEAVY, summary.maxIntensity)
        assertTrue(summary.lines()[0].contains("15 min in rain"))
        assertTrue(summary.lines()[0].contains("heavy"))
    }

    @Test
    fun `snow is named as snow`() {
        val t = RideSummaryTracker()
        t.setIntervalMinutes(1.0)
        repeat(5) { t.observe(DbzPalette.Sample(30, DbzPalette.PrecipType.SNOW)) }

        assertTrue(t.summary().lines()[0].contains("snow"))
    }

    @Test
    fun `hail gets its own line`() {
        val t = RideSummaryTracker()
        t.setIntervalMinutes(1.0)
        repeat(3) { t.observe(wet(58)) }

        assertTrue(t.summary().possibleHail)
        assertTrue(t.summary().lines().contains("possible hail"))
    }

    @Test
    fun `clutter does not count as a wet minute`() {
        val t = RideSummaryTracker()
        t.setIntervalMinutes(1.0)
        repeat(20) { t.observe(wet(8)) }      // below the clutter floor

        assertTrue(t.summary().stayedDry)
    }

    @Test
    fun `forecast accuracy is reported plainly, including when it is poor`() {
        val t = RideSummaryTracker()
        repeat(3) { t.recordPrediction(10.0, actuallyWet = true) }
        repeat(7) { t.recordPrediction(10.0, actuallyWet = false) }

        val summary = t.summary()
        assertEquals(0.3, summary.forecastAccuracy!!, 1e-9)
        // A tool that only reports its successes is not worth believing about anything.
        assertTrue(summary.lines().any { it.contains("30%") })
    }

    @Test
    fun `no predictions means no accuracy claim`() {
        val t = RideSummaryTracker()
        repeat(10) { t.observe(dry) }
        assertNull(t.summary().forecastAccuracy)
        assertFalse(t.summary().lines().any { it.contains("%") })
    }

    @Test
    fun `reset clears everything`() {
        val t = RideSummaryTracker()
        t.setIntervalMinutes(1.0)
        repeat(10) { t.observe(wet(40)) }
        t.recordPrediction(5.0, true)

        t.reset()
        assertTrue(t.summary().stayedDry)
        assertNull(t.summary().forecastAccuracy)
    }
}

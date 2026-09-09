package si.plahutar.raincall.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.plahutar.raincall.forecast.DisplayUnits
import si.plahutar.raincall.forecast.Encounter
import si.plahutar.raincall.forecast.MessageComposer
import si.plahutar.raincall.forecast.RainForecast
import si.plahutar.raincall.forecast.Severity
import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.radar.DbzPalette
import kotlin.random.Random

/**
 * The interesting question is not whether this warns — that part is easy. It is how
 * often it interrupts, which decides whether the app stays on the bar or gets
 * uninstalled after one wet ride. So most of these tests simulate a whole ride and count.
 */
class AlertPolicyTest {

    private fun forecast(
        eta: Double?,
        intensity: DbzPalette.Intensity = DbzPalette.Intensity.MODERATE,
        hail: Boolean = false,
        frameAgeSeconds: Long = 120,
        confidence: RiderState.Confidence = RiderState.Confidence.HIGH,
    ) = RainForecast(
        nearest = null,
        encounter = eta?.let {
            Encounter(
                etaMinutes = it,
                durationMinutes = 20.0,
                intensity = intensity,
                type = DbzPalette.PrecipType.RAIN,
                possibleHail = hail,
                coneCoverage = 1.0,
            )
        },
        confidence = confidence,
        horizonMinutes = 30.0,
        frameAgeSeconds = frameAgeSeconds,
    )

    /** Feed one sample. Time is expressed in whole minutes for readability. */
    private fun AlertPolicy.step(
        minute: Int,
        eta: Double?,
        intensity: DbzPalette.Intensity = DbzPalette.Intensity.MODERATE,
        hail: Boolean = false,
        riding: Boolean = true,
        frameAgeSeconds: Long = 120,
        confidence: RiderState.Confidence = RiderState.Confidence.HIGH,
    ): AlertRequest? {
        val f = forecast(eta, intensity, hail, frameAgeSeconds, confidence)
        val m = MessageComposer.compose(f, DisplayUnits.METRIC)
        // Offset so the simulated clock never reads zero, which is a real timestamp and
        // must not be mistaken for "no alert has fired yet".
        return evaluate(f, m, riding, nowMillis = 1_000_000L + minute * 60_000L)
    }

    // ------------------------------------------------------------- the nagging tests

    @Test
    fun `a steady approach warns three times, once per threshold`() {
        val policy = AlertPolicy()
        val alerts = (0..40).mapNotNull { minute ->
            policy.step(minute, eta = maxOf(0.0, 40.0 - minute))?.let { minute to it }
        }

        assertEquals("one alert per threshold and no more", 3, alerts.size)
        assertEquals(listOf(20, 30, 35), alerts.map { it.first })
        assertTrue(alerts.all { it.second.reason == AlertRequest.Reason.APPROACHING })
    }

    @Test
    fun `an ETA oscillating across a threshold warns once, not repeatedly`() {
        // This is the behaviour that would make the app unbearable: cells move and each
        // frame shifts the ETA, so a naive "warn below ten minutes" fires every wobble.
        val policy = AlertPolicy()
        val random = Random(3)
        var count = 0
        for (minute in 0 until 30) {
            val eta = 10.0 + random.nextDouble(-2.5, 2.5)
            if (policy.step(minute, eta) != null) count++
        }

        assertEquals("half an hour of wobble must not mean half an hour of alerts", 1, count)
    }

    @Test
    fun `an ETA that jumps past several thresholds warns once, not three times`() {
        val policy = AlertPolicy()
        assertNull("22 minutes crosses nothing", policy.step(0, eta = 22.0))

        var count = 0
        for (minute in 1..10) {
            if (policy.step(minute, eta = 4.0) != null) count++
        }
        assertEquals("one event deserves one alert", 1, count)
    }

    @Test
    fun `flickering detection over two hours stays under two interruptions per hour`() {
        val policy = AlertPolicy()
        val random = Random(11)
        var count = 0
        for (minute in 0 until 120) {
            val eta = if (random.nextDouble() < 0.35) null else random.nextDouble(3.0, 25.0)
            val intensity = if (random.nextBoolean()) {
                DbzPalette.Intensity.LIGHT
            } else {
                DbzPalette.Intensity.MODERATE
            }
            if (policy.step(minute, eta, intensity) != null) count++
        }

        assertTrue("$count alerts in two hours is too many", count <= 4)
    }

    // ------------------------------------------------------------------- escalation

    @Test
    fun `worsening conditions are allowed to interrupt again`() {
        val policy = AlertPolicy()
        val reasons = mutableListOf<AlertRequest.Reason>()

        for (minute in 0..2) {
            policy.step(minute, 15.0, DbzPalette.Intensity.LIGHT)?.let { reasons.add(it.reason) }
        }
        for (minute in 3..5) {
            policy.step(minute, 9.0, DbzPalette.Intensity.LIGHT)?.let { reasons.add(it.reason) }
        }
        for (minute in 6..8) {
            policy.step(minute, 6.0, DbzPalette.Intensity.HEAVY)?.let { reasons.add(it.reason) }
        }
        for (minute in 9..13) {
            policy.step(minute, 3.0, DbzPalette.Intensity.HEAVY, hail = true)
                ?.let { reasons.add(it.reason) }
        }

        assertTrue("worsening should be reported", reasons.contains(AlertRequest.Reason.WORSENED))
        assertTrue("hail should be reported", reasons.contains(AlertRequest.Reason.HAIL))
        assertEquals("but still only four alerts across the whole episode", 4, reasons.size)
    }

    @Test
    fun `hail gets a longer dismiss time and its own severity`() {
        val policy = AlertPolicy()
        val alert = policy.step(0, eta = 8.0, intensity = DbzPalette.Intensity.HEAVY, hail = true)

        assertNotNull(alert)
        assertEquals(Severity.HAIL, alert!!.severity)
        assertEquals(AlertPolicy.AUTO_DISMISS_HAIL_MS, alert.autoDismissMs)
        assertTrue(alert.autoDismissMs > AlertPolicy.AUTO_DISMISS_MS)
    }

    @Test
    fun `the same severity does not re-alert just because it persists`() {
        val policy = AlertPolicy()
        assertNotNull(policy.step(0, eta = 8.0, intensity = DbzPalette.Intensity.HEAVY))

        var count = 0
        for (minute in 1..30) {
            if (policy.step(minute, 8.0, DbzPalette.Intensity.HEAVY) != null) count++
        }
        assertEquals(0, count)
    }

    // -------------------------------------------------------------------- suppression

    @Test
    fun `rain that was already falling when we started is not announced`() {
        // Telling someone riding through rain that it is raining is not a warning, it is
        // an observation they made several minutes ago.
        val policy = AlertPolicy()
        var count = 0
        for (minute in 0..19) {
            if (policy.step(minute, eta = 0.0) != null) count++
        }
        assertEquals(0, count)
    }

    @Test
    fun `nothing is shown when not riding`() {
        val policy = AlertPolicy()
        for (minute in 0..20) {
            assertNull(policy.step(minute, eta = 5.0, riding = false))
        }
    }

    @Test
    fun `a stale frame does not justify interrupting anyone`() {
        val policy = AlertPolicy()
        // The field can go on showing a stale figure with its age attached. A
        // full-screen alert asserts an urgency that stale data cannot support.
        assertNull(policy.step(0, eta = 5.0, frameAgeSeconds = 20 * 60))
    }

    @Test
    fun `no alert without usable confidence`() {
        val policy = AlertPolicy()
        assertNull(policy.step(0, eta = 5.0, confidence = RiderState.Confidence.NONE))
    }

    @Test
    fun `low confidence still warns, because being vague is not the same as being silent`() {
        val policy = AlertPolicy()
        assertNotNull(policy.step(0, eta = 4.0, confidence = RiderState.Confidence.LOW))
    }

    // ----------------------------------------------------------------- episode reset

    @Test
    fun `a cell that passes by earns one clear message`() {
        val policy = AlertPolicy()
        assertNotNull(policy.step(0, eta = 12.0))

        val clears = (1..19).mapNotNull { minute ->
            policy.step(minute, eta = null)?.let { minute to it }
        }
        assertEquals(1, clears.size)
        assertEquals(AlertRequest.Reason.CLEARED, clears[0].second.reason)
        assertEquals("only after a sustained clear spell", 11, clears[0].first)
    }

    @Test
    fun `a clear spell with nothing preceding it says nothing`() {
        val policy = AlertPolicy()
        for (minute in 0..30) {
            assertNull(
                "there was never anything to clear",
                policy.step(minute, eta = null),
            )
        }
    }

    @Test
    fun `a brief gap does not re-arm the thresholds`() {
        // A cell edge wobbling out of the cone for a frame or two is common. Re-arming
        // on that would let a single shower warn three times.
        val policy = AlertPolicy()
        assertNotNull(policy.step(0, eta = 8.0))

        assertNull(policy.step(1, eta = null))
        assertNull(policy.step(2, eta = null))

        var count = 0
        for (minute in 3..20) {
            if (policy.step(minute, eta = 8.0) != null) count++
        }
        assertEquals("the same shower must not warn twice", 0, count)
    }

    @Test
    fun `a genuinely separate shower warns again`() {
        val policy = AlertPolicy()
        assertNotNull(policy.step(0, eta = 8.0))
        for (minute in 1..29) policy.step(minute, eta = null)

        val second = (30..34).mapNotNull { policy.step(it, eta = 8.0) }
        assertEquals("a new system after a long clear spell deserves a warning", 1, second.size)
    }

    @Test
    fun `reset clears everything including the already-seen suppression`() {
        val policy = AlertPolicy()
        for (minute in 0..5) policy.step(minute, eta = 0.0)   // suppressed at startup

        policy.reset()
        assertNotNull(
            "after a reset this is a fresh ride and a fresh warning",
            policy.step(10, eta = 8.0),
        )
    }

    @Test
    fun `a timestamp of zero is not mistaken for never having alerted`() {
        // Zero is a valid clock reading. Using it as a sentinel for "no alert yet" makes
        // the cooldown silently inoperative, which is exactly the sort of bug that only
        // shows up in a harness starting at t=0.
        val policy = AlertPolicy()
        val first = policy.evaluate(
            forecast(eta = 15.0),
            MessageComposer.compose(forecast(eta = 15.0), DisplayUnits.METRIC),
            riding = true,
            nowMillis = 0L,
        )
        assertNotNull(first)

        val tooSoon = policy.evaluate(
            forecast(eta = 9.0),
            MessageComposer.compose(forecast(eta = 9.0), DisplayUnits.METRIC),
            riding = true,
            nowMillis = 60_000L,
        )
        assertNull("the cooldown must apply from a zero timestamp too", tooSoon)
    }
}

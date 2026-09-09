package si.plahutar.raincall.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected values were computed against an independent model of the weighting scheme,
 * not by running this class. The wrap-around cases in particular are where an
 * implementation can look right and be badly wrong.
 */
class HeadingTrackerTest {

    private fun tracker() = HeadingTracker()

    @Test
    fun `headings either side of north average to north, not south`() {
        // The failure this guards against: the ordinary mean of 350 and 10 is 180,
        // pointing exactly backwards.
        val t = tracker()
        t.add(0, 350.0)
        t.add(1000, 0.0)
        t.add(2000, 10.0)

        val result = t.smoothed(2000)
        assertNotNull(result.headingDegrees)
        assertEquals(0.926, result.headingDegrees!!, 0.01)
        assertEquals(0.990, result.resultant, 0.001)
        assertTrue("nearly straight", result.curvature < 0.02)
    }

    @Test
    fun `a constant heading comes back unchanged with zero curvature`() {
        val t = tracker()
        repeat(4) { t.add(it * 1000L, 90.0) }

        val result = t.smoothed(3000)
        assertEquals(90.0, result.headingDegrees!!, 1e-6)
        assertEquals(1.0, result.resultant, 1e-9)
        assertEquals(0.0, result.curvature, 1e-9)
        assertEquals(4, result.sampleCount)
    }

    @Test
    fun `too few samples yields no heading rather than a guess`() {
        val t = tracker()
        t.add(0, 90.0)
        t.add(1000, 90.0)

        val result = t.smoothed(1000)
        assertNull("two samples is not enough to smooth", result.headingDegrees)
        assertFalse(result.isUsable)
        // Unknown must read as maximally uncertain, so nothing downstream treats the
        // absence of data as a straight road.
        assertEquals(1.0, result.curvature, 1e-9)
    }

    @Test
    fun `an empty tracker is unusable`() {
        val result = tracker().smoothed(0)
        assertNull(result.headingDegrees)
        assertEquals(0, result.sampleCount)
        assertEquals(1.0, result.curvature, 1e-9)
    }

    @Test
    fun `input outside 0 to 360 is normalised`() {
        val t = tracker()
        t.add(0, 450.0)      // 90
        t.add(1000, -270.0)  // 90
        t.add(2000, 90.0)

        assertEquals(90.0, t.smoothed(2000).headingDegrees!!, 1e-6)
    }

    @Test
    fun `alternating opposite headings report the recent one but flag it as unreliable`() {
        // A switchback climb. Recency weighting means the newest heading wins rather
        // than the samples cancelling to nothing — but the resultant collapses, which
        // is what tells the rest of the app not to trust it.
        val t = tracker()
        t.add(0, 0.0)
        t.add(5000, 180.0)
        t.add(10000, 0.0)
        t.add(15000, 180.0)

        val result = t.smoothed(15000)
        assertEquals(180.0, result.headingDegrees!!, 0.01)
        assertEquals(0.333, result.resultant, 0.01)
        assertTrue("curvature must be high on switchbacks", result.curvature > 0.5)
    }

    @Test
    fun `a gentle bend stays usable and barely raises curvature`() {
        val t = tracker()
        listOf(80.0, 85.0, 90.0, 95.0, 100.0).forEachIndexed { i, h ->
            t.add(i * 1000L, h)
        }

        val result = t.smoothed(4000)
        assertEquals(91.38, result.headingDegrees!!, 0.05)
        assertTrue("a sweeping bend is still predictable", result.curvature < 0.02)
    }

    @Test
    fun `recency weighting catches up after a completed turn`() {
        // Rode north for five seconds, then east for five. Unweighted this would sit at
        // 45 degrees, half way through a turn that already finished.
        val t = tracker()
        repeat(5) { t.add(it * 1000L, 0.0) }
        repeat(5) { t.add(5000L + it * 1000L, 90.0) }

        val result = t.smoothed(9000)
        assertEquals(63.43, result.headingDegrees!!, 0.05)
        assertTrue("must have moved past the midpoint", result.headingDegrees!! > 45.0)
    }

    @Test
    fun `samples older than the window are evicted`() {
        val t = tracker()
        repeat(5) { t.add(it * 1000L, 0.0) }          // north, then a long gap
        repeat(5) { t.add(25_000L + it * 1000L, 90.0) } // east, 25 s later

        val result = t.smoothed(29_000)
        assertEquals(5, result.sampleCount)
        assertEquals("stale northward samples must not survive", 90.0, result.headingDegrees!!, 1e-6)
        assertEquals(1.0, result.resultant, 1e-9)
    }

    @Test
    fun `prune alone drops stale samples without a smoothing call`() {
        val t = tracker()
        repeat(5) { t.add(it * 1000L, 0.0) }
        assertEquals(5, t.sampleCount)

        t.prune(100_000)
        assertEquals(0, t.sampleCount)
    }

    @Test
    fun `clear forgets everything`() {
        val t = tracker()
        repeat(5) { t.add(it * 1000L, 45.0) }
        t.clear()

        assertEquals(0, t.sampleCount)
        assertNull(t.smoothed(5000).headingDegrees)
    }

    @Test
    fun `a timestamp before the newest sample does not produce a negative weight`() {
        // Clock adjustments happen. The weighting must degrade gracefully rather than
        // producing an exponential blow-up from a negative age.
        val t = tracker()
        repeat(4) { t.add(it * 1000L, 90.0) }

        val result = t.smoothed(0)
        assertTrue(result.resultant <= 1.0)
        assertEquals(90.0, result.headingDegrees!!, 1e-6)
    }
}

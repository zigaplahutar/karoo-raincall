package si.plahutar.raincall.forecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.radar.DbzPalette

class MessageComposerTest {

    private fun forecast(
        eta: Double? = 12.0,
        duration: Double? = 20.0,
        intensity: DbzPalette.Intensity = DbzPalette.Intensity.MODERATE,
        type: DbzPalette.PrecipType = DbzPalette.PrecipType.RAIN,
        hail: Boolean = false,
        coverage: Double = 1.0,
        nearestMetres: Double? = null,
        nearestCompass: String = "NW",
        confidence: RiderState.Confidence = RiderState.Confidence.HIGH,
        horizon: Double = 30.0,
    ) = RainForecast(
        nearest = nearestMetres?.let {
            NearestRain(
                distanceMetres = it,
                bearingDegrees = 315.0,
                compass = nearestCompass,
                intensity = intensity,
                type = type,
                possibleHail = hail,
            )
        },
        encounter = eta?.let {
            Encounter(
                etaMinutes = it,
                durationMinutes = duration,
                intensity = intensity,
                type = type,
                possibleHail = hail,
                coneCoverage = coverage,
            )
        },
        confidence = confidence,
        horizonMinutes = horizon,
        frameAgeSeconds = 120,
    )

    // ------------------------------------------------------------------- wording

    @Test
    fun `a straightforward encounter reads plainly`() {
        val m = MessageComposer.compose(forecast())
        assertEquals("Rain in 12 min", m.headline)
        assertEquals("moderate, ~20 min", m.details[0].full)
        assertEquals("intensity survives when the duration will not fit", "moderate", m.details[0].short)
        assertNull("high confidence needs no hedge", m.qualifier)
        assertEquals(Severity.MODERATE, m.severity)
    }

    @Test
    fun `hail is always possible, never stated as fact`() {
        val m = MessageComposer.compose(forecast(hail = true, intensity = DbzPalette.Intensity.HEAVY))
        assertTrue(m.details.any { it.full == "possible hail" })
        assertEquals(Severity.HAIL, m.severity)
        assertEquals("Possible hail in 12 min", m.alertTitle)
        // A 2D mosaic cannot see the freezing level, so a bare "Hail" would overclaim.
        assertFalse(m.details.any { it.full == "hail" })
        assertFalse(m.alertTitle.startsWith("Hail "))
    }

    @Test
    fun `hail outranks intensity in the alert title`() {
        val heavyOnly = MessageComposer.compose(
            forecast(intensity = DbzPalette.Intensity.HEAVY)
        )
        assertEquals("Rain in 12 min", heavyOnly.alertTitle)

        val withHail = MessageComposer.compose(
            forecast(intensity = DbzPalette.Intensity.HEAVY, hail = true)
        )
        assertEquals("Possible hail in 12 min", withHail.alertTitle)
        assertTrue(withHail.severity.ordinal > heavyOnly.severity.ordinal)
    }

    @Test
    fun `empty categories never appear`() {
        val m = MessageComposer.compose(forecast(hail = false))
        assertFalse(
            "showing 'no hail' would waste the most valuable line on the screen",
            m.details.any { it.full.contains("hail") },
        )
    }

    @Test
    fun `snow is called snow`() {
        val m = MessageComposer.compose(forecast(type = DbzPalette.PrecipType.SNOW))
        assertEquals("Snow in 12 min", m.headline)
    }

    @Test
    fun `already raining reads as now, not as zero minutes`() {
        val m = MessageComposer.compose(forecast(eta = 0.0))
        assertEquals("Rain now", m.headline)
        assertEquals("Rain now", m.alertTitle)
    }

    @Test
    fun `confidence appears in the message rather than being hidden`() {
        val certain = MessageComposer.compose(forecast(confidence = RiderState.Confidence.HIGH))
        assertEquals("Rain in 12 min", certain.headline)
        assertNull(certain.qualifier)

        val approx = MessageComposer.compose(forecast(confidence = RiderState.Confidence.MEDIUM))
        assertEquals("Rain in ~12 min", approx.headline)
        assertEquals("approx", approx.qualifier)

        val vague = MessageComposer.compose(forecast(confidence = RiderState.Confidence.LOW))
        assertEquals("Rain in ~12 min", vague.headline)
        assertEquals("uncertain", vague.qualifier)
    }

    @Test
    fun `an encounter with no end is said to be ongoing, not given a made-up length`() {
        val m = MessageComposer.compose(forecast(duration = null))
        assertEquals("moderate, ongoing", m.details[0].full)
    }

    @Test
    fun `a stationary rider under a clear sky is not told the radar is broken`() {
        // Standing still there is no heading to project along, so confidence is NONE and
        // nothing is found — which is exactly the shape of a dead radar feed. Inferring
        // "no data" from that told a rider at a café stop that the app had failed, when
        // the truth was the pleasanter one: nothing is coming.
        val m = MessageComposer.compose(
            forecast(
                eta = null,
                nearestMetres = null,
                confidence = RiderState.Confidence.NONE,
                horizon = 0.0,
            )
        )

        assertNotEquals("No radar data", m.headline)
        assertEquals(Severity.CLEAR, m.severity)
    }

    @Test
    fun `a stale frame is reported as stale, with its age, not as clear`() {
        val m = MessageComposer.compose(RainForecast.stale(frameAgeSeconds = 24 * 60))

        assertEquals(Severity.UNKNOWN, m.severity)
        assertTrue("headline was \"${m.headline}\"", m.headline.contains("24"))
        assertFalse(m.headline.contains("Clear", ignoreCase = true))
    }

    @Test
    fun `no radar data does not read as clear`() {
        val m = MessageComposer.compose(
            RainForecast.unavailable(frameAgeSeconds = 600)
        )
        assertEquals(Severity.UNKNOWN, m.severity)
        assertEquals("No radar data", m.headline)
        // The distinction that matters: only one of these means it is safe to carry on.
        assertFalse(m.headline.contains("Clear", ignoreCase = true))
    }

    @Test
    fun `a clear sky states how far ahead it looked`() {
        val m = MessageComposer.compose(
            forecast(eta = null, horizon = 30.0, confidence = RiderState.Confidence.HIGH)
        )
        assertEquals("Clear for 30 min", m.headline)
        assertEquals(Severity.CLEAR, m.severity)
    }

    @Test
    fun `rain off the path falls back to distance and direction`() {
        val m = MessageComposer.compose(
            forecast(eta = null, nearestMetres = 10608.0, nearestCompass = "NW")
        )
        assertEquals("Rain 10.6 km NW", m.headline)
        assertEquals("10.6 km NW", m.headlineShort)
        assertEquals("moderate, off path", m.details[0].full)
    }

    @Test
    fun `the nearest cell gets a line when it is not the one about to hit`() {
        val m = MessageComposer.compose(forecast(eta = 12.0, nearestMetres = 3000.0))
        assertTrue(m.details.any { it.full.startsWith("nearest") })

        // But not when rain is imminent: the ETA already says what matters.
        val imminent = MessageComposer.compose(forecast(eta = 1.0, nearestMetres = 300.0))
        assertFalse(imminent.details.any { it.full.startsWith("nearest") })
    }

    // ------------------------------------------------------------------ distances

    @Test
    fun `distances read naturally at each scale`() {
        fun m(metres: Double) = MessageComposer.formatDistance(metres, DisplayUnits.METRIC)
        // Under a kilometre, metres read better than a decimal fraction.
        assertEquals("400 m", m(410.0))
        assertEquals("950 m", m(940.0))
        // Just under a kilometre must not round up into "1000 m", which is a kilometre
        // written the long way round.
        assertEquals("1 km", m(975.0))
        assertEquals("1.2 km", m(1230.0))
        assertEquals("10.6 km", m(10608.0))
        assertEquals("60 km", m(60000.0))
    }

    @Test
    fun `imperial units are supported`() {
        fun m(metres: Double) = MessageComposer.formatDistance(metres, DisplayUnits.IMPERIAL)
        assertTrue(m(500.0).endsWith(" yd"))
        assertTrue(m(10608.0).endsWith(" mi"))
        assertEquals("6.6 mi", m(10608.0))
    }

    // -------------------------------------------------------------- fitting text

    @Test
    fun `a one line field gets the headline and nothing else`() {
        val m = MessageComposer.compose(forecast())
        val lines = m.render(maxLines = 1, maxChars = 20)
        assertEquals(1, lines.size)
        assertEquals("Rain in 12 min", lines[0])
    }

    @Test
    fun `a narrow field falls back to the short headline`() {
        val m = MessageComposer.compose(forecast())
        assertEquals("Rain 12m", m.single(maxChars = 10))
    }

    @Test
    fun `details that do not fit are dropped, never truncated mid-word`() {
        val m = MessageComposer.compose(forecast(nearestMetres = 3000.0))
        val lines = m.render(maxLines = 4, maxChars = 12)

        for (line in lines) {
            assertTrue("'$line' overflows 12 chars", line.length <= 12)
        }
        // "moderate, ~20 min" is 17 chars and must be absent rather than cut to
        // "moderate, ~2" — a half-word read at speed is worse than no word.
        assertFalse(lines.any { it == "moderate, ~2" })
    }

    @Test
    fun `the confidence marker survives on fields too narrow for the word`() {
        val m = MessageComposer.compose(
            forecast(confidence = RiderState.Confidence.LOW, duration = 5.0)
        )
        // "light, ~5 min (uncertain)" will not fit, but the marker will.
        val lines = m.render(maxLines = 3, maxChars = 20)
        assertTrue(
            "confidence must not be silently dropped: $lines",
            lines.any { it.contains("uncertain") || it.contains("?") },
        )
    }

    @Test
    fun `a very narrow field steps down rather than truncating into a misleading number`() {
        val m = MessageComposer.compose(forecast(eta = 12.0))
        // Cutting "Rain 12m" to six characters would give "Rain 1", which reads as one
        // minute. The minimal form is terse but cannot be misread.
        val line = m.single(maxChars = 6)
        assertEquals("12m", line)
        assertFalse("must never look like a different ETA", line == "Rain 1")
    }

    @Test
    fun `every headline tier fits three characters or fewer at the minimal level`() {
        val cases = listOf(
            forecast(),
            forecast(eta = null, horizon = 30.0),
            forecast(eta = null, nearestMetres = 10608.0),
        )
        for (f in cases) {
            val m = MessageComposer.compose(f)
            assertTrue(
                "'${m.headlineMinimal}' is too long to guarantee no truncation",
                m.headlineMinimal.length <= 3,
            )
        }
        assertEquals("--", MessageComposer.compose(RainForecast.unavailable()).headlineMinimal)
    }

    @Test
    fun `zero space produces nothing rather than throwing`() {
        val m = MessageComposer.compose(forecast())
        assertTrue(m.render(0, 20).isEmpty())
        assertTrue(m.render(4, 0).isEmpty())
        assertEquals("", m.single(0))
    }

    /**
     * The overflow guarantee, checked exhaustively rather than by sampling.
     *
     * Every combination of precipitation type, intensity, ETA, duration, hail, nearest
     * cell and confidence is composed and rendered at a range of field widths. Nothing
     * may ever exceed the width it was given. This is the test that makes "it will not
     * overlap on the screen" a property of the code rather than a hope.
     */
    @Test
    fun `no message can ever overflow the space it is given`() {
        var checked = 0
        for (type in listOf(DbzPalette.PrecipType.RAIN, DbzPalette.PrecipType.SNOW)) {
            for (intensity in listOf(
                DbzPalette.Intensity.LIGHT,
                DbzPalette.Intensity.MODERATE,
                DbzPalette.Intensity.HEAVY,
            )) {
                for (eta in listOf(null, 0.0, 1.0, 12.0, 29.5)) {
                    for (duration in listOf(null, 0.5, 20.0, 59.5)) {
                        for (hail in listOf(false, true)) {
                            for (nearest in listOf(null, 120.0, 950.0, 10608.0, 59_900.0)) {
                                for (confidence in RiderState.Confidence.entries) {
                                    val f = forecast(
                                        eta = eta, duration = duration,
                                        intensity = intensity, type = type, hail = hail,
                                        nearestMetres = nearest, confidence = confidence,
                                    )
                                    for (units in DisplayUnits.entries) {
                                        val m = MessageComposer.compose(f, units)
                                        for (maxChars in listOf(6, 8, 10, 14, 18, 21, 30, 48)) {
                                            for (maxLines in 1..5) {
                                                val lines = m.render(maxLines, maxChars)
                                                assertTrue(
                                                    "too many lines: ${lines.size} > $maxLines",
                                                    lines.size <= maxLines,
                                                )
                                                for (line in lines) {
                                                    assertTrue(
                                                        "'$line' (${line.length}) overflows " +
                                                            "$maxChars chars",
                                                        line.length <= maxChars,
                                                    )
                                                }
                                                checked++
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("expected a large number of combinations, got $checked", checked > 100_000)
    }

    @Test
    fun `alert text stays within a sensible width for the full screen banner`() {
        // The alert is full-screen so it has far more room than a data field, but it is
        // still read at speed and should not run to two wrapped lines.
        for (hail in listOf(false, true)) {
            for (intensity in DbzPalette.Intensity.entries) {
                for (duration in listOf(null, 20.0)) {
                    val m = MessageComposer.compose(
                        forecast(hail = hail, intensity = intensity, duration = duration)
                    )
                    assertTrue("title too long: '${m.alertTitle}'", m.alertTitle.length <= 26)
                    m.alertDetail?.let {
                        assertTrue("detail too long: '$it'", it.length <= 44)
                    }
                }
            }
        }
    }
}

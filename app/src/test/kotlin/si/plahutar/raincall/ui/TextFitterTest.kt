package si.plahutar.raincall.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import si.plahutar.raincall.forecast.DisplayUnits
import si.plahutar.raincall.forecast.Encounter
import si.plahutar.raincall.forecast.MessageComposer
import si.plahutar.raincall.forecast.NearestRain
import si.plahutar.raincall.forecast.RainForecast
import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.radar.DbzPalette

/**
 * The measurement function here is deliberately *proportional*, not a fixed width per
 * character. A monospace stand-in would let the fitter pass tests it would fail on the
 * device, because the whole reason for measuring rather than counting characters is
 * that "Rain in 12 min" and "WWWW WW WW WWW" are the same length and nowhere near the
 * same width.
 */
class TextFitterTest {

    /** Rough per-character widths relative to the font size, as a real font behaves. */
    private fun glyphWidth(c: Char): Float = when (c) {
        'i', 'l', 'j', '.', ',', '\'', '!' -> 0.28f
        'f', 't', 'r', '(', ')', ' ' -> 0.34f
        'm', 'w', 'M', 'W' -> 0.88f
        in 'A'..'Z' -> 0.66f
        in '0'..'9' -> 0.56f
        else -> 0.54f
    }

    private val measure: (String, Float) -> Float = { text, size ->
        text.sumOf { glyphWidth(it).toDouble() }.toFloat() * size
    }

    private fun fitter() = TextFitter(measure)

    private fun message(
        eta: Double? = 12.0,
        duration: Double? = 20.0,
        intensity: DbzPalette.Intensity = DbzPalette.Intensity.MODERATE,
        hail: Boolean = false,
        nearestMetres: Double? = null,
        confidence: RiderState.Confidence = RiderState.Confidence.HIGH,
    ) = MessageComposer.compose(
        RainForecast(
            nearest = nearestMetres?.let {
                NearestRain(it, 315.0, "NW", intensity, DbzPalette.PrecipType.RAIN, hail)
            },
            encounter = eta?.let {
                Encounter(it, duration, intensity, DbzPalette.PrecipType.RAIN, hail, 1.0)
            },
            confidence = confidence,
            horizonMinutes = 30.0,
            frameAgeSeconds = 60,
        ),
        DisplayUnits.METRIC,
    )

    /** Assert every line genuinely fits the usable width at the chosen size. */
    private fun assertFits(layout: TextFitter.Layout, widthPx: Int) {
        val usable = widthPx * (1f - 2f * TextFitter.HORIZONTAL_PADDING_FRACTION)
        for (line in layout.lines) {
            val measured = measure(line, layout.textSizePx)
            assertTrue(
                "'$line' measured ${measured}px at ${layout.textSizePx}px, " +
                    "usable width is $usable",
                measured <= usable,
            )
        }
    }

    @Test
    fun `a full width field shows the headline at full size plus detail`() {
        // Karoo's screen is 480 px wide; a full-width quarter-height field.
        val layout = fitter().fit(message(), widthPx = 480, heightPx = 180, baseTextSizePx = 40f)

        assertEquals("Rain in 12 min", layout.lines[0])
        assertTrue("there is room for detail", layout.lines.size >= 2)
        assertEquals(40f, layout.textSizePx, 0.01f)
        assertFits(layout, 480)
    }

    @Test
    fun `a narrow field shrinks the text rather than dropping to the short headline`() {
        val layout = fitter().fit(message(), widthPx = 240, heightPx = 120, baseTextSizePx = 40f)

        assertTrue("text must have shrunk", layout.textSizePx < 40f)
        assertEquals("and the full headline should survive", "Rain in 12 min", layout.lines[0])
        assertFits(layout, 240)
    }

    @Test
    fun `a very small field falls back down the headline ladder`() {
        val layout = fitter().fit(message(), widthPx = 100, heightPx = 60, baseTextSizePx = 32f)

        assertTrue(layout.lines.isNotEmpty())
        assertTrue(
            "expected an abbreviated headline, got '${layout.lines[0]}'",
            layout.lines[0] == "Rain 12m" || layout.lines[0] == "12m",
        )
        assertFits(layout, 100)
    }

    @Test
    fun `text never shrinks below the legibility floor`() {
        val layout = fitter().fit(message(), widthPx = 60, heightPx = 40, baseTextSizePx = 40f)
        assertTrue(
            "illegible text is no better than none: ${layout.textSizePx}",
            layout.textSizePx >= TextFitter.MIN_TEXT_SIZE_PX,
        )
    }

    @Test
    fun `line count follows the height of the field`() {
        val short = fitter().fit(message(nearestMetres = 3000.0), 480, 50, 32f)
        val tall = fitter().fit(message(nearestMetres = 3000.0), 480, 400, 32f)

        assertEquals("a one-line-tall field gets one line", 1, short.lines.size)
        assertTrue("a tall field uses more", tall.lines.size > short.lines.size)
    }

    @Test
    fun `lines never exceed the cap however tall the field`() {
        val layout = fitter().fit(message(nearestMetres = 3000.0), 480, 2000, 32f)
        assertTrue(layout.availableLines <= TextFitter.MAX_LINES)
        assertTrue(layout.lines.size <= TextFitter.MAX_LINES)
    }

    @Test
    fun `a zero sized field produces nothing rather than throwing`() {
        assertTrue(fitter().fit(message(), 0, 100, 32f).isEmpty)
        assertTrue(fitter().fit(message(), 100, 0, 32f).isEmpty)
    }

    @Test
    fun `wide glyphs are accounted for, not assumed average`() {
        // Two strings of equal character count and very different width. A fitter that
        // counted characters would treat these identically and overflow on the second.
        val narrow = "1111111111"
        val wide = "WWWWWWWWWW"
        assertTrue(measure(wide, 32f) > measure(narrow, 32f) * 1.5f)
    }

    /**
     * The property that matters on the device: whatever the message and whatever the
     * field, nothing drawn is wider than the space it was given.
     */
    @Test
    fun `no message overflows any field size`() {
        val messages = buildList {
            for (eta in listOf(null, 0.0, 1.0, 12.0, 29.0)) {
                for (intensity in DbzPalette.Intensity.entries) {
                    for (hail in listOf(false, true)) {
                        for (nearest in listOf(null, 950.0, 10608.0, 59_900.0)) {
                            for (confidence in RiderState.Confidence.entries) {
                                add(message(eta, 20.0, intensity, hail, nearest, confidence))
                            }
                        }
                    }
                }
            }
        }

        var checked = 0
        for (m in messages) {
            for (width in listOf(60, 100, 120, 160, 240, 320, 480)) {
                for (height in listOf(30, 50, 90, 120, 180, 400)) {
                    for (base in listOf(20f, 24f, 32f, 40f, 56f)) {
                        val layout = fitter().fit(m, width, height, base)
                        val usable = width * (1f - 2f * TextFitter.HORIZONTAL_PADDING_FRACTION)
                        assertTrue(
                            "too many lines for ${height}px",
                            layout.lines.size <= layout.availableLines,
                        )
                        for (line in layout.lines) {
                            assertTrue(
                                "'$line' at ${layout.textSizePx}px overflows ${width}px",
                                measure(line, layout.textSizePx) <= usable ||
                                    // The only permitted exception: a field so narrow
                                    // that even the minimal headline at the legibility
                                    // floor will not fit. Better a clipped two-character
                                    // hint than a blank field.
                                    line == m.headlineMinimal,
                            )
                        }
                        checked++
                    }
                }
            }
        }
        assertTrue("expected many combinations, got $checked", checked > 10_000)
    }

    @Test
    fun `the lines that fit are the most important ones, in order`() {
        val m = message(nearestMetres = 3000.0, hail = true)
        val layout = fitter().fit(m, widthPx = 480, heightPx = 400, baseTextSizePx = 28f)

        assertEquals("Rain in 12 min", layout.lines[0])
        // Intensity and duration matter more than where the nearest other cell is.
        assertTrue(layout.lines.size >= 3)
        assertTrue(layout.lines[1].startsWith("moderate"))
        assertTrue(layout.lines.contains("possible hail"))
        assertFalse(
            "the nearest cell is the least important line and goes last",
            layout.lines[1].startsWith("nearest"),
        )
    }
}

package si.plahutar.raincall.ui

import si.plahutar.raincall.forecast.RainMessage
import kotlin.math.floor
import kotlin.math.max

/**
 * Decides what text size to use and how many lines fit, for a data field of a given
 * measured size.
 *
 * Karoo tells the extension the real pixel dimensions of each field through
 * `ViewConfig.viewSize`, and the font size it uses for its own numeric fields through
 * `ViewConfig.textSize`. So there is no need to guess at size classes: the layout is
 * derived from what the field actually is.
 *
 * Measurement is injected rather than calling into `android.graphics` directly, which
 * keeps this testable on the JVM and makes the fitting logic something that can be
 * checked rather than eyeballed on a device.
 */
class TextFitter(
    /**
     * Width in pixels of [text] rendered at [textSizePx].
     *
     * On the device this is backed by `Paint.measureText`. Estimating a character count
     * from the font size instead is what produces text that fits in testing and
     * overlaps in the field: glyph widths are not uniform, and "Rain in 12 min" and
     * "WWWW WW WW WWW" are the same character count and nowhere near the same width.
     */
    private val measure: (text: String, textSizePx: Float) -> Float,
) {

    companion object {
        /**
         * Line height as a multiple of the font size.
         *
         * 1.25 leaves enough gap that descenders on one line do not touch the ascenders
         * of the next, which is what "overlapping" usually turns out to mean in
         * practice.
         */
        const val LINE_SPACING = 1.25f

        /**
         * Padding reserved on each side, as a fraction of the field width.
         *
         * Text flush against the edge of a field looks broken next to Karoo's own
         * fields and is harder to read against the boundary line.
         */
        const val HORIZONTAL_PADDING_FRACTION = 0.04f

        /**
         * Smallest font we will shrink to, in pixels at standard density.
         *
         * Below roughly this, text on a bar-mounted screen stops being readable at
         * speed, and showing something illegible is no better than showing nothing.
         */
        const val MIN_TEXT_SIZE_PX = 20f

        /** Scale steps tried, largest first, relative to the field's own font size. */
        private val SCALE_STEPS = floatArrayOf(1.0f, 0.85f, 0.7f, 0.6f, 0.5f, 0.4f)

        /** Never stack more than this many lines, however tall the field is. */
        const val MAX_LINES = 5
    }

    /** The chosen layout for one field. */
    data class Layout(
        val lines: List<String>,
        val textSizePx: Float,
        /** Lines the field had room for at this size, whether or not they were used. */
        val availableLines: Int,
    ) {
        val isEmpty: Boolean get() = lines.isEmpty()
    }

    /**
     * Fit [message] into a field of [widthPx] by [heightPx].
     *
     * @param baseTextSizePx the size Karoo uses for its own numeric field of this size,
     *        from `ViewConfig.textSize` converted to pixels. Starting from it keeps
     *        RainCall looking like the rest of the screen rather than like a bolted-on
     *        extension.
     *
     * Policy: keep the text as large as the *full* headline allows, then use whatever
     * vertical room is left for detail. Legibility on a moving bike comes first; extra
     * detail is worth having but not at the cost of squinting.
     */
    fun fit(
        message: RainMessage,
        widthPx: Int,
        heightPx: Int,
        baseTextSizePx: Float,
    ): Layout {
        if (widthPx <= 0 || heightPx <= 0) return Layout(emptyList(), baseTextSizePx, 0)

        val usableWidth = widthPx * (1f - 2f * HORIZONTAL_PADDING_FRACTION)

        val candidateSizes = SCALE_STEPS
            .map { baseTextSizePx * it }
            .filter { it >= MIN_TEXT_SIZE_PX }
            .ifEmpty {
                // A field so small that even the floor is too big for it. Use the floor
                // anyway: the ladder in RainMessage will fall back to the shortest form,
                // which is a few characters and stands a good chance of fitting.
                listOf(MIN_TEXT_SIZE_PX)
            }

        // Largest size at which the full headline fits, else the largest at which the
        // short one does, else the largest at which the minimal one does.
        val chosenSize = candidateSizes.firstOrNull { size ->
            measure(message.headline, size) <= usableWidth
        } ?: candidateSizes.firstOrNull { size ->
            measure(message.headlineShort, size) <= usableWidth
        } ?: candidateSizes.firstOrNull { size ->
            measure(message.headlineMinimal, size) <= usableWidth
        } ?: candidateSizes.last()

        val lineHeight = chosenSize * LINE_SPACING
        val availableLines = max(1, floor(heightPx / lineHeight).toInt())
            .coerceAtMost(MAX_LINES)

        val lines = message.render(availableLines) { text ->
            measure(text, chosenSize) <= usableWidth
        }

        return Layout(
            lines = lines,
            textSizePx = chosenSize,
            availableLines = availableLines,
        )
    }
}

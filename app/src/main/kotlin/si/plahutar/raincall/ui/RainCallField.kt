package si.plahutar.raincall.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.hammerhead.karooext.models.ViewConfig
import si.plahutar.raincall.forecast.Severity

/**
 * The data field view.
 *
 * Deliberately plain. Karoo's own fields are a large number and a small label on a flat
 * background, and an extension that looks different reads as a foreign object on the
 * screen rather than as part of the computer. The one place colour is used is severity,
 * where it carries information rather than decoration.
 */
@Composable
fun RainCallField(
    layout: TextFitter.Layout,
    severity: Severity,
    alignment: ViewConfig.Alignment,
    /**
     * [TextFitter.Layout.textSizePx] converted to sp.
     *
     * The fitter measures against real device pixels (matching `ViewConfig.viewSize`,
     * which Karoo also reports in pixels), but Compose text is always sized in sp and
     * silently re-multiplies by the display's scaled density when it renders. Passing
     * the raw pixel value straight into `.sp` would apply that density scaling twice,
     * inflating the text and overflowing the field it was just fitted to.
     */
    textSizeSp: Float,
) {
    val textAlign = when (alignment) {
        ViewConfig.Alignment.LEFT -> TextAlign.Start
        ViewConfig.Alignment.CENTER -> TextAlign.Center
        ViewConfig.Alignment.RIGHT -> TextAlign.End
    }

    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = when (alignment) {
            ViewConfig.Alignment.LEFT -> Alignment.Horizontal.Start
            ViewConfig.Alignment.CENTER -> Alignment.Horizontal.CenterHorizontally
            ViewConfig.Alignment.RIGHT -> Alignment.Horizontal.End
        },
    ) {
        layout.lines.forEachIndexed { index, line ->
            Text(
                text = line,
                style = TextStyle(
                    // The fitter already decided this size against the measured field
                    // width; textSizeSp only converts its unit, it does not rescale it.
                    fontSize = textSizeSp.sp,
                    // Only the headline is bold. Bolding everything would remove the
                    // distinction rather than emphasise it.
                    fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                    color = ColorProvider(
                        if (index == 0) headlineColour(severity) else detailColour()
                    ),
                    textAlign = textAlign,
                ),
                // maxLines = 1 per Text, because the fitter has already broken the
                // message into lines that fit. Letting Glance wrap would undo that work
                // and is exactly how text ends up overlapping.
                maxLines = 1,
            )
        }
    }
}

/**
 * Headline colour by severity.
 *
 * Only three states get a colour: hail, heavy rain, and no data. Colouring light and
 * moderate too would mean the screen is always coloured, and a warning colour that is
 * always on stops being a warning.
 */
private fun headlineColour(severity: Severity): Color = when (severity) {
    Severity.HAIL -> Color(0xFFD32F2F)      // red: the only genuinely hazardous case
    Severity.HEAVY -> Color(0xFFF57C00)     // amber
    Severity.UNKNOWN -> Color(0xFF9E9E9E)   // grey: we cannot see, which is not "clear"
    else -> Color(0xFF000000)
}

private fun detailColour(): Color = Color(0xFF616161)

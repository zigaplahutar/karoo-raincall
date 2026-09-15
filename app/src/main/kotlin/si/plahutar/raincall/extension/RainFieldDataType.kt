package si.plahutar.raincall.extension

import android.content.Context
import android.graphics.Paint
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import si.plahutar.raincall.forecast.RainMessage
import si.plahutar.raincall.ui.RainCallField
import si.plahutar.raincall.ui.TextFitter

/**
 * The RainCall data field.
 *
 * One data type serves every field size. Karoo passes the real dimensions of whichever
 * slot the rider put it in, and [TextFitter] decides the font size and how many lines
 * to use from those dimensions. Declaring separate small, medium and large data types
 * would mean three entries cluttering the rider's field picker for what is one piece of
 * information, and would still not adapt if they resized the slot.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class RainFieldDataType(
    extension: String,
    /** Latest composed message, shared by the pipeline. */
    private val messages: Flow<RainMessage>,
) : DataTypeImpl(extension, TYPE_ID) {

    companion object {
        const val TYPE_ID = "rain-call"
    }

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Karoo draws the header and boundary for us; we only supply the body. Asking
        // for the header keeps the field looking like the built-in ones.
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = true))
        }

        val fitter = TextFitter(measure = paintMeasurer(context))

        val job = scope.launch {
            messages.collect { message ->
                val scaledDensity = context.resources.displayMetrics.scaledDensity
                val layout = fitter.fit(
                    message = message,
                    widthPx = config.viewSize.first,
                    heightPx = config.viewSize.second,
                    baseTextSizePx = config.textSize * scaledDensity,
                )

                val result = glance.compose(context, DpSize.Unspecified) {
                    RainCallField(
                        layout = layout,
                        severity = message.severity,
                        alignment = config.alignment,
                        // Undo the sp-to-px conversion above: Compose's `.sp` applies
                        // scaledDensity again on render, so the value handed to it must
                        // be back in sp or the on-screen text ends up scaledDensity
                        // times too big.
                        textSizeSp = layout.textSizePx / scaledDensity,
                    )
                }
                emitter.updateView(result.remoteViews)
            }
        }

        emitter.setCancellable {
            job.cancel()
            scope.cancel()
        }
    }

    /**
     * A measurer backed by the platform text engine.
     *
     * Real measurement rather than a character-count estimate. A `Paint` is reused
     * across calls because allocating one per measurement, several times per field per
     * update, is needless churn on a device that also has a map to draw.
     */
    private fun paintMeasurer(context: Context): (String, Float) -> Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        return { text, textSizePx ->
            paint.textSize = textSizePx
            paint.measureText(text)
        }
    }
}

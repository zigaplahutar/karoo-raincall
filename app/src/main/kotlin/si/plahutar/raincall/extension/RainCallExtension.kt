package si.plahutar.raincall.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import si.plahutar.raincall.alert.AlertPresenter
import si.plahutar.raincall.forecast.DisplayUnits
import si.plahutar.raincall.radar.RadarRepository
import si.plahutar.raincall.sensors.RiderStateProvider

/**
 * The extension service Karoo binds to.
 *
 * Owns the one pipeline and hands its output to the data field. Everything below this
 * point is free of Karoo types, which is what has let the maths and the decision logic
 * be tested without a device throughout.
 */
class RainCallExtension : KarooExtension(EXTENSION_ID, VERSION) {

    companion object {
        /** Must not contain a dot; the SDK checks this at construction. */
        const val EXTENSION_ID = "raincall"
        const val VERSION = "0.1.0"
    }

    private lateinit var karooSystem: KarooSystemService
    private lateinit var scope: CoroutineScope
    private lateinit var pipeline: ForecastPipeline

    private val units = MutableStateFlow(DisplayUnits.METRIC)

    override val types: List<DataTypeImpl> by lazy {
        listOf(
            RainFieldDataType(
                extension = EXTENSION_ID,
                messages = pipeline.messages,
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        karooSystem = KarooSystemService(applicationContext)
        val presenter = AlertPresenter(karooSystem)
        val riderStates = RiderStateProvider(karooSystem)

        pipeline = ForecastPipeline(
            repository = RadarRepository(),
            riderStates = riderStates.state,
            units = { units.value },
            onAlert = presenter::show,
        )

        karooSystem.connect { connected ->
            if (connected) {
                riderStates.start(scope)
                pipeline.start(scope)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }
}

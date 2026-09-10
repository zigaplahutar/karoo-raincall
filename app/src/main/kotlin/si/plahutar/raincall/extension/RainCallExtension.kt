package si.plahutar.raincall.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import si.plahutar.raincall.alert.AlertPresenter
import si.plahutar.raincall.forecast.DisplayUnits
import si.plahutar.raincall.radar.RadarRepository
import si.plahutar.raincall.sensors.RiderStateProvider
import si.plahutar.raincall.sensors.consumerFlow

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
            routes = riderStates.route,
            units = { units.value },
            onAlert = presenter::show,
        )

        karooSystem.connect { connected ->
            if (connected) {
                riderStates.start(scope)
                pipeline.start(scope)
                scope.launch { watchRideLifecycle(riderStates) }
                scope.launch { followUnitPreference() }
            }
        }
    }

    /**
     * Follow the rider's distance-unit preference.
     *
     * The formatting for miles and yards was written and tested from the start, but
     * nothing ever set this, so it was unreachable: a rider with the Karoo in imperial
     * got kilometres from RainCall alone, which reads as a bug in the field rather than
     * as a setting. `preferredUnit.distance` is the same switch the rest of the computer
     * obeys, so following it is what makes the extension look native.
     */
    private suspend fun followUnitPreference() {
        karooSystem.consumerFlow<UserProfile>().collect { profile ->
            units.value = when (profile.preferredUnit.distance) {
                UserProfile.PreferredUnit.UnitType.IMPERIAL -> DisplayUnits.IMPERIAL
                else -> DisplayUnits.METRIC
            }
        }
    }

    /**
     * Clear per-ride state when a ride ends.
     *
     * Keyed on the transition into [RideState.Idle], never on "not recording". A pause —
     * especially an automatic one at a traffic light — is a few seconds in the middle of
     * a ride, and treating it as an ending would wipe the ride summary, forget where
     * home is, and re-arm the "already raining when we started" suppression so the next
     * warning is swallowed. Without this the state simply never cleared at all: a second
     * ride inherited the first one's home and its accumulated wet minutes.
     */
    private suspend fun watchRideLifecycle(riderStates: RiderStateProvider) {
        var wasIdle = true
        riderStates.rideState.collect { state ->
            val idle = state is RideState.Idle
            if (idle && !wasIdle) pipeline.reset()
            wasIdle = idle
        }
    }

    override fun onDestroy() {
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }
}

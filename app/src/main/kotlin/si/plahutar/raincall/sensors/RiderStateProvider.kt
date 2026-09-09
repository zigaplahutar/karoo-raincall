package si.plahutar.raincall.sensors

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.model.SpeedSanity

/**
 * Assembles a single [RiderState] from the several Karoo streams that each carry part
 * of the picture.
 *
 * Four sources, because no one of them has everything:
 *
 *  - [OnLocationChanged] — position and orientation. The documented event, always there.
 *  - [DataType.Type.LOCATION] — the same position plus **fix accuracy**, which the event
 *    above does not carry. Accuracy decides whether an ETA deserves to sound confident.
 *  - [DataType.Type.SMOOTHED_3S_AVERAGE_SPEED] — speed already smoothed by the Karoo.
 *    Using this rather than differencing GPS positions ourselves removes a whole class
 *    of noise, especially at low speed where GPS-derived speed is worst.
 *  - [RideState] — whether the ride is actually recording, so a paused rider is not
 *    projected forwards down the road.
 *
 * The heading from [OnLocationChanged] goes through [HeadingTracker] rather than being
 * used raw: a single fix jitters badly when barely moving, and the tracker also yields
 * the path-curvature figure the uncertainty cone needs.
 */
class RiderStateProvider(
    private val karooSystem: KarooSystemService,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        private const val TAG = "RiderStateProvider"

        /**
         * A speed reading older than this is dropped rather than reused.
         *
         * If the sensor drops out mid-ride, carrying the last known speed forward would
         * keep projecting a rider who may have stopped entirely.
         */
        private const val SPEED_STALE_MILLIS = 15_000L
    }

    private val headingTracker = HeadingTracker()

    /**
     * Decides whether the speed stream is m/s or km/h by comparing it against GPS
     * displacement. A magnitude check alone cannot do this: if the stream were km/h, a
     * rider at 20 km/h reports the number 20, which is a perfectly plausible m/s value.
     */
    private val speedCalibrator = SpeedUnitCalibrator()

    private val _state = MutableStateFlow<RiderState?>(null)

    /**
     * Latest rider state, or null before the first position fix.
     *
     * Null rather than a placeholder: consumers must show "waiting for GPS" rather than
     * a state built from zeroes that looks like a rider stationary at the equator.
     */
    val state: StateFlow<RiderState?> = _state.asStateFlow()

    private var latestSpeedAtMillis: Long = 0
    private var latestAccuracyMetres: Double? = null
    private var riding: Boolean = false

    /** Raw speed exactly as the stream reported it, before unit conversion. */
    private var latestRawSpeed: Double? = null

    /** Logged once so the decision is visible without spamming every fix. */
    private var loggedCalibration = false

    /**
     * Start collecting. Cancelling [scope] tears every listener down.
     *
     * Each stream is collected in its own coroutine and each has its own [catch]: one
     * sensor failing must not silently take the others with it. A ride with no speed
     * sensor should still get "nearest rain 1.2 km NW", just without an ETA.
     */
    fun start(scope: CoroutineScope) {
        scope.launch { collectLocation() }
        scope.launch { collectAccuracy() }
        scope.launch { collectSpeed() }
        scope.launch { collectRideState() }
    }

    private suspend fun collectLocation() {
        karooSystem.consumerFlow<OnLocationChanged>()
            .catch { Log.e(TAG, "location stream failed", it) }
            .collect { event ->
                val now = clock()

                // orientation is nullable in the SDK and genuinely absent sometimes,
                // typically at a standstill where there is no direction of travel to
                // report. Feeding a fabricated value in would poison the smoother.
                event.orientation?.let { headingTracker.add(now, it) }

                // Position fixes are what let the calibrator cross-check the speed
                // stream, so this has to happen on every location update.
                val wasCalibrated = speedCalibrator.isCalibrated
                speedCalibrator.observe(
                    timestampMillis = now,
                    latitude = event.lat,
                    longitude = event.lng,
                    rawSpeed = latestRawSpeed,
                    accuracyMetres = latestAccuracyMetres,
                )
                if (!wasCalibrated && speedCalibrator.isCalibrated && !loggedCalibration) {
                    loggedCalibration = true
                    Log.i(
                        TAG,
                        "speed stream calibrated as ${speedCalibrator.unit} after " +
                            "${speedCalibrator.comparisonCount} comparisons",
                    )
                }

                val smoothed = headingTracker.smoothed(now)
                val speed = currentSpeed(now)

                _state.value = RiderState(
                    timestampMillis = now,
                    latitude = event.lat,
                    longitude = event.lng,
                    accuracyMetres = latestAccuracyMetres,
                    speedMetresPerSecond = speed,
                    headingDegrees = smoothed.headingDegrees,
                    pathCurvature = smoothed.curvature,
                    riding = riding,
                )
            }
    }

    /**
     * Accuracy comes from the LOCATION data type, which carries it as an optional field.
     *
     * Position is taken from [OnLocationChanged] rather than from here so there is one
     * authority for where the rider is; this stream contributes only the accuracy.
     */
    private suspend fun collectAccuracy() {
        karooSystem.streamDataPoints(DataType.Type.LOCATION)
            .catch { Log.e(TAG, "location data type stream failed", it) }
            .collect { dataPoint ->
                latestAccuracyMetres = dataPoint.values[DataType.Field.LOC_ACCURACY]
            }
    }

    private suspend fun collectSpeed() {
        karooSystem.streamDataPoints(DataType.Type.SMOOTHED_3S_AVERAGE_SPEED)
            .catch { Log.e(TAG, "speed stream failed", it) }
            .collect { dataPoint ->
                val raw = dataPoint.singleValue ?: return@collect
                latestRawSpeed = raw
                latestSpeedAtMillis = clock()
            }
    }

    private suspend fun collectRideState() {
        karooSystem.consumerFlow<RideState>()
            .catch { Log.e(TAG, "ride state stream failed", it) }
            .collect { rideState ->
                riding = rideState is RideState.Recording
                if (!riding) {
                    // A paused or finished ride leaves a stale heading behind that would
                    // otherwise be projected forward when riding resumes elsewhere.
                    // The unit calibration is deliberately kept: it is a property of the
                    // device, not of this ride, and re-deciding costs another warm-up.
                    headingTracker.clear()
                }
            }
    }

    /**
     * Convert a raw speed reading to metres per second.
     *
     * The unit comes from [SpeedUnitCalibrator], which decides by cross-checking
     * against GPS displacement rather than by guessing from magnitude. Until it has
     * decided, m/s is assumed — the documented best guess, and being briefly wrong at
     * the very start of a ride is better than leaving the field blank exactly when the
     * rider first looks at it.
     *
     * The plausibility check still runs afterwards, but now only as a filter for
     * genuine nonsense (a GPS jump, a NaN) rather than as a unit detector.
     */
    private fun normaliseSpeed(raw: Double): Double? {
        if (!raw.isFinite() || raw < 0.0) return null
        val mps = speedCalibrator.toMetresPerSecond(raw)
        return if (SpeedSanity.isPlausibleMetresPerSecond(mps)) mps else null
    }

    /**
     * Latest speed in m/s, or null if unavailable or stale.
     *
     * Conversion happens here rather than when the reading arrives, so that a
     * calibration decision reached part way through a ride applies immediately to the
     * value in hand instead of waiting for the next reading to replace it.
     */
    private fun currentSpeed(nowMillis: Long): Double? {
        val raw = latestRawSpeed ?: return null
        if (nowMillis - latestSpeedAtMillis > SPEED_STALE_MILLIS) return null
        return normaliseSpeed(raw)
    }
}

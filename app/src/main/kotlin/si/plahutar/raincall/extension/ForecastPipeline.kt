package si.plahutar.raincall.extension

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import si.plahutar.raincall.alert.AlertPolicy
import si.plahutar.raincall.alert.AlertRequest
import si.plahutar.raincall.forecast.DisplayUnits
import si.plahutar.raincall.forecast.EvasionEvaluator
import si.plahutar.raincall.forecast.HomeContext
import si.plahutar.raincall.forecast.HomeEvaluator
import si.plahutar.raincall.forecast.MessageComposer
import si.plahutar.raincall.forecast.RainForecast
import si.plahutar.raincall.forecast.RainForecaster
import si.plahutar.raincall.forecast.RainMessage
import si.plahutar.raincall.forecast.RideSummaryTracker
import si.plahutar.raincall.forecast.WetRoadTracker
import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.radar.CellMotionEstimator
import si.plahutar.raincall.radar.RadarField
import si.plahutar.raincall.radar.RadarSource

/**
 * The loop that turns radar and rider position into something on the screen.
 *
 * Runs on the radar's own cadence rather than the rider's: RainViewer publishes a new
 * frame every ten minutes, so refreshing faster only re-downloads the same picture. The
 * forecast is recomputed more often than that, because the *rider* moves between frames
 * and their ETA changes even when the weather does not.
 */
class ForecastPipeline(
    private val repository: RadarSource,
    private val riderStates: StateFlow<RiderState?>,
    private val units: () -> DisplayUnits,
    private val onAlert: (AlertRequest) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        private const val TAG = "ForecastPipeline"

        /** How often to look for a new radar frame. */
        const val RADAR_POLL_MILLIS = 3 * 60 * 1000L

        /**
         * How often to recompute from the frame in hand.
         *
         * The rider moves between frames, so their ETA changes even when the weather
         * does not. Cheap: no network, just arithmetic over a field already decoded.
         */
        const val RECOMPUTE_MILLIS = 30 * 1000L

        /** Wait before retrying after a failed fetch. */
        const val RETRY_MILLIS = 60 * 1000L
    }

    private val _forecasts = MutableStateFlow(RainForecast.unavailable())
    val forecasts: StateFlow<RainForecast> = _forecasts.asStateFlow()

    private val _messages = MutableStateFlow(
        MessageComposer.compose(RainForecast.unavailable())
    )
    val messages: StateFlow<RainMessage> = _messages.asStateFlow()

    private val alertPolicy = AlertPolicy(clock)
    private val wetRoads = WetRoadTracker()
    private val summary = RideSummaryTracker()

    // Written by the radar loop, read by the recompute loop — different coroutines on
    // different threads. Volatile so a field swapped in by one is actually seen by the
    // other rather than sitting in a cache line until something else happens to flush it.
    @Volatile
    private var currentField: RadarField? = null

    @Volatile
    private var velocity: CellMotionEstimator.CellVelocity? = null

    /**
     * Where the ride started.
     *
     * Captured from the first fix rather than configured: it is right almost always and
     * asks nothing of the rider.
     */
    @Volatile
    private var home: HomeContext? = null

    fun start(scope: CoroutineScope) {
        scope.launch { radarLoop() }
        scope.launch { recomputeLoop() }
    }

    private suspend fun radarLoop() {
        while (currentCoroutineContext().isActive) {
            val rider = riderStates.value
            if (rider == null) {
                // No fix yet, so nothing to centre a tile window on.
                delay(RETRY_MILLIS)
                continue
            }

            val refreshed = runCatching { refreshRadar(rider) }.getOrElse {
                Log.w(TAG, "radar refresh failed", it)
                false
            }
            delay(if (refreshed) RADAR_POLL_MILLIS else RETRY_MILLIS)
        }
    }

    /**
     * Look for a newer frame and, if there is one, decode it and re-estimate motion.
     *
     * The index is fetched afresh every cycle. It must never be cached across cycles on
     * the strength of which branch was taken last time: an index held past the point
     * where a new frame was published reports the same `latest` forever, the
     * "nothing new" branch below is then taken every cycle, and the radar silently
     * freezes on whichever frame happened to be current when the ride started. It is a
     * few kB of JSON; refetching it is the cheap half of this method.
     */
    private suspend fun refreshRadar(rider: RiderState): Boolean {
        val idx = repository.fetchIndex() ?: return false

        val frames = idx.recentPast(2)
        val latest = frames.lastOrNull() ?: return false

        // Nothing new published yet; the frame in hand is still the current one.
        if (currentField?.timeEpochSeconds == latest.time) return true

        val newest = repository.fetchField(idx, latest, rider.longitude, rider.latitude)
            ?: return false

        val older = frames.getOrNull(frames.size - 2)?.let {
            repository.fetchField(idx, it, rider.longitude, rider.latitude)
        }

        currentField = newest

        velocity = if (older != null && older.range == newest.range) {
            CellMotionEstimator.estimate(older, newest)
                ?.toVelocity(rider.latitude, newest.range.zoom, newest.range.tileSize)
                ?.takeIf { it.isUsable }
                .also {
                    if (it != null) {
                        Log.i(
                            TAG,
                            "cells moving ${"%.0f".format(it.speedKmh)} km/h " +
                                "toward ${it.bearingDegrees?.toInt() ?: -1} deg",
                        )
                    }
                }
        } else {
            // Without a pair there is no motion estimate. Treating cells as stationary
            // makes the ETA conservative rather than confident, which is the right way
            // to be wrong.
            null
        }

        return true
    }

    private suspend fun recomputeLoop() {
        while (currentCoroutineContext().isActive) {
            runCatching { recompute() }.onFailure { Log.w(TAG, "recompute failed", it) }
            delay(RECOMPUTE_MILLIS)
        }
    }

    private fun recompute() {
        val rider = riderStates.value
        val field = currentField
        val now = clock()
        val nowSeconds = now / 1000

        if (rider == null || field == null) {
            publish(RainForecast.unavailable(), MessageComposer.compose(RainForecast.unavailable()))
            return
        }

        if (home == null && rider.riding) home = HomeContext(rider.latitude, rider.longitude)

        val forecast = RainForecaster.forecast(rider, field, velocity, nowSeconds)

        // Standing still, the cone is meaningless — there is not even a direction. The
        // forecaster already declines to project, so the message degrades to distance
        // and bearing on its own without anything special here.
        val evasion = if (rider.canProject) {
            EvasionEvaluator.evaluate(rider, field, velocity, nowSeconds)
        } else {
            EvasionEvaluator.Advice.Unknown
        }

        val homeAdvice = home
            ?.takeIf { rider.canProject }
            ?.let { HomeEvaluator.evaluate(rider, it, field, velocity, nowSeconds) }

        val atRider = field.sampleAt(rider.longitude, rider.latitude)
        summary.setIntervalMinutes(RECOMPUTE_MILLIS / 60_000.0)
        summary.observe(atRider)
        wetRoads.observe(now, atRider.intensity)

        val message = MessageComposer.compose(forecast, units(), evasion, homeAdvice)
        publish(forecast, message)

        alertPolicy.evaluate(forecast, message, rider.riding, now)?.let(onAlert)
    }

    private fun publish(forecast: RainForecast, message: RainMessage) {
        _forecasts.value = forecast
        _messages.value = message
    }

    /** Grip warning after rain has stopped, or null. */
    fun wetRoadAdvice(): String? {
        val rider = riderStates.value ?: return null
        val rainingNow = currentField
            ?.sampleAt(rider.longitude, rider.latitude)
            ?.isMeaningful == true
        return wetRoads.advice(clock(), rider.speedMetresPerSecond, rainingNow)
    }

    /** What actually happened, for the end of the ride. */
    fun rideSummary() = summary.summary()

    /** Called when a ride ends. */
    fun reset() {
        alertPolicy.reset()
        wetRoads.reset()
        summary.reset()
        repository.clear()
        home = null
        currentField = null
        velocity = null
    }
}

package si.plahutar.raincall.sensors

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Coroutine wrappers over the Karoo SDK's callback API.
 *
 * The SDK hands out listener ids and expects them back on teardown; [callbackFlow] with
 * [awaitClose] makes that automatic, so a cancelled collector cannot leak a listener
 * that keeps waking the device for the rest of the ride.
 *
 * These mirror the helpers in Hammerhead's own sample extension rather than inventing a
 * different shape, so anything learned from their examples transfers directly.
 */

/** Stream one Karoo data type as a flow of its raw [StreamState]. */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val listenerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
        trySendBlocking(event.state)
    }
    awaitClose { removeConsumer(listenerId) }
}

/** Stream any Karoo event type as a flow. */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> = callbackFlow {
    val listenerId = addConsumer<T> { trySend(it) }
    awaitClose { removeConsumer(listenerId) }
}

/**
 * Only the data points from a stream, dropping the lifecycle states.
 *
 * [StreamState.Searching], [StreamState.Idle] and [StreamState.NotAvailable] carry no
 * measurement. Callers that need to distinguish "sensor is looking" from "sensor is
 * absent" should collect [streamDataFlow] directly instead.
 */
fun KarooSystemService.streamDataPoints(dataTypeId: String): Flow<DataPoint> =
    streamDataFlow(dataTypeId).mapNotNull { state ->
        (state as? StreamState.Streaming)?.dataPoint
    }

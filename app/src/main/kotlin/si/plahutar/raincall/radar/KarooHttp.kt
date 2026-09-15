package si.plahutar.raincall.radar

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A GET request routed through Karoo's own network bridge instead of the device's
 * network stack.
 *
 * Karoo has no cellular modem, so a device off WiFi has no network route of its own —
 * `java.net.HttpURLConnection` would simply fail. Karoo's system service knows more than
 * that: it can relay the request over Bluetooth to the Companion app on the rider's
 * phone and let the phone's own connection carry it, which is the only way to reach the
 * network without WiFi. Routing every request through it, rather than only as a
 * fallback, means RainCall gets that path for free and never has to guess which one is
 * live — Karoo picks WiFi when it has it and falls back on its own.
 *
 * The bridge caps both the request and the response at 100 KB and settles the exchange
 * itself (a tile too large to fit is reported back as a failure, not a silent hang), so
 * there is nothing to retry here beyond turning its callback into a suspend call.
 */
suspend fun KarooSystemService.httpGet(
    url: String,
    timeoutMillis: Long = 30_000L,
): ByteArray {
    val body = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            var consumerId: String? = null
            consumerId = addConsumer(
                OnHttpResponse.MakeHttpRequest(method = "GET", url = url),
                onError = { message ->
                    consumerId?.let { removeConsumer(it) }
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IOException("Karoo HTTP request failed for $url: $message")
                        )
                    }
                },
            ) { event: OnHttpResponse ->
                val state = event.state
                if (state is HttpResponseState.Complete) {
                    consumerId?.let { removeConsumer(it) }
                    if (!continuation.isActive) return@addConsumer
                    if (state.statusCode in 200..299) {
                        continuation.resume(state.body)
                    } else {
                        continuation.resumeWithException(
                            IOException(
                                "HTTP ${state.statusCode} for $url" +
                                    (state.error?.let { ": $it" } ?: "")
                            )
                        )
                    }
                }
            }
            continuation.invokeOnCancellation {
                consumerId?.let { removeConsumer(it) }
            }
        }
    }
    return body ?: throw IOException("Karoo HTTP request timed out after ${timeoutMillis}ms: $url")
}

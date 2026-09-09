package si.plahutar.raincall.alert

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import si.plahutar.raincall.R
import si.plahutar.raincall.forecast.Severity

/**
 * Turns an [AlertRequest] into a Karoo full-screen alert and dispatches it.
 *
 * Kept apart from [AlertPolicy] so the decision of *whether* to interrupt stays free of
 * Android types and can be simulated over a whole ride in a test. This class does only
 * the presentation, which is the part that has to be checked on the device anyway.
 */
class AlertPresenter(
    private val karooSystem: KarooSystemService,
) {

    companion object {
        private const val TAG = "AlertPresenter"

        /**
         * Alert id.
         *
         * Constant on purpose: a new alert for the same ongoing situation should replace
         * the previous one rather than stack behind it. A rider who looks down after a
         * climb should see the current state, not a queue of what they missed.
         */
        private const val ALERT_ID = "raincall-alert"
    }

    fun show(request: AlertRequest) {
        Log.i(TAG, "alert: ${request.reason} — ${request.title}")

        karooSystem.dispatch(
            InRideAlert(
                id = ALERT_ID,
                icon = iconFor(request.severity),
                title = request.title,
                detail = request.detail,
                autoDismissMs = request.autoDismissMs,
                backgroundColor = backgroundFor(request.severity),
                textColor = textColourFor(request.severity),
            )
        )
    }

    private fun iconFor(severity: Severity): Int = when (severity) {
        Severity.HAIL -> R.drawable.ic_hail
        Severity.CLEAR -> R.drawable.ic_clear
        else -> R.drawable.ic_raincall
    }

    /**
     * Background colour by severity.
     *
     * Only hail gets red. Colouring heavy rain red too would leave nothing left to
     * escalate to, and a rider who sees red for ordinary heavy rain learns to discount
     * it — which is precisely the wrong lesson for the one case that is genuinely
     * dangerous.
     */
    private fun backgroundFor(severity: Severity): Int = when (severity) {
        Severity.HAIL -> R.color.alert_hail
        Severity.HEAVY -> R.color.alert_heavy
        Severity.CLEAR -> R.color.alert_clear
        else -> R.color.alert_default
    }

    private fun textColourFor(severity: Severity): Int = when (severity) {
        Severity.HAIL, Severity.HEAVY -> R.color.alert_text_on_dark
        else -> R.color.alert_text_on_light
    }
}

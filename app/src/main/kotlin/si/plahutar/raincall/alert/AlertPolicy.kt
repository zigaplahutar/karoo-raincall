package si.plahutar.raincall.alert

import si.plahutar.raincall.forecast.RainForecast
import si.plahutar.raincall.forecast.RainMessage
import si.plahutar.raincall.forecast.Severity
import si.plahutar.raincall.model.RiderState

/**
 * What to show, if anything.
 *
 * Deliberately free of Android types so the decision logic can be simulated over a whole
 * ride in a test rather than judged by riding around in the rain.
 */
data class AlertRequest(
    val title: String,
    val detail: String?,
    val severity: Severity,
    /** Why this fired. Logged, and useful when working out why it did not. */
    val reason: Reason,
    val autoDismissMs: Long,
) {
    enum class Reason {
        /** Crossed one of the ETA warning thresholds. */
        APPROACHING,
        /** Conditions got materially worse than already warned about. */
        WORSENED,
        /** Hail appeared where there was none. */
        HAIL,
        /** An episode ended without the rider getting wet. */
        CLEARED,
    }
}

/**
 * Decides when a full-screen alert is warranted.
 *
 * The hard part is not deciding that rain is coming; the field already says that. It is
 * interrupting rarely enough that the interruption still means something. An ETA
 * oscillates as cells move and each radar frame lands, so the naive rule "warn when the
 * ETA drops below ten minutes" fires again every time it wobbles across the line.
 *
 * Four mechanisms keep that in check:
 *
 *  - **Thresholds fire once per episode.** Crossing ten minutes warns once. It does not
 *    warn again when the ETA drifts back to eleven and down to nine.
 *  - **A cooldown** sets a floor on the gap between any two alerts.
 *  - **Escalation is allowed to interrupt** — rain turning heavy, or hail appearing, is
 *    new information and worth saying even soon after the last alert.
 *  - **An episode only resets after a sustained clear spell**, so a gap of one frame
 *    does not re-arm every threshold.
 *
 * Not thread-safe; the caller confines it to one coroutine.
 */
class AlertPolicy(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        /**
         * ETA thresholds, in minutes, largest first.
         *
         * Twenty is "start thinking about it", ten is "decide now", five is "it is
         * happening". Below that a warning adds nothing the rider cannot see for
         * themselves by looking up.
         */
        val ETA_THRESHOLDS_MINUTES = doubleArrayOf(20.0, 10.0, 5.0)

        /** Minimum gap between alerts, unless something escalates. */
        const val COOLDOWN_MILLIS = 4 * 60 * 1000L

        /** Even an escalation will not interrupt within this of the last alert. */
        const val ESCALATION_COOLDOWN_MILLIS = 60 * 1000L

        /**
         * How long the forecast must stay clear before thresholds re-arm.
         *
         * A single frame with no intersection is common — a cell edge wobbles, the cone
         * shifts. Re-arming on that would let one shower warn three times.
         */
        const val EPISODE_RESET_MINUTES = 10.0

        /** Long enough to read at speed, short enough not to sit over the screen. */
        const val AUTO_DISMISS_MS = 9_000L

        /** Hail is the one case worth holding on screen slightly longer. */
        const val AUTO_DISMISS_HAIL_MS = 12_000L

        /**
         * A forecast older than this is not worth interrupting anyone about.
         *
         * The field can go on showing a stale figure with its age attached; a
         * full-screen alert asserts urgency that stale data cannot support.
         */
        const val MAX_FRAME_AGE_SECONDS = 15 * 60L
    }

    /** Thresholds already fired in the current episode. */
    private val firedThresholds = mutableSetOf<Double>()

    /** Worst severity already warned about in this episode. */
    private var warnedSeverity: Severity = Severity.CLEAR

    /**
     * When the last alert fired, or null if none has.
     *
     * Nullable rather than a sentinel of zero: zero is a perfectly valid timestamp, and
     * conflating "never" with "at the epoch" makes the cooldown silently inoperative
     * whenever the clock happens to read zero — which is exactly what a test harness
     * starting at t=0 does.
     */
    private var lastAlertAtMillis: Long? = null

    /** When the forecast first went clear, or null while an episode is running. */
    private var clearSinceMillis: Long? = null

    /** True once an episode has produced at least one alert. */
    private var episodeAlerted: Boolean = false

    /**
     * Suppresses an alert for a condition that already existed when we started looking.
     *
     * Telling someone already riding through rain that it is raining is not a warning,
     * it is an observation they made some minutes ago.
     */
    private var seenFirstForecast: Boolean = false

    /**
     * A threshold crossed while a cooldown blocked the alert, waiting to see whether
     * it is still crossed once the cooldown lifts.
     *
     * If the ETA recovers above it first, the crossing is abandoned — but abandoned
     * is not the same as never having happened: the threshold is marked spent right
     * then, so a later, unrelated dip back below it does not get treated as a fresh
     * crossing. Without this, a single threshold could fire once per wobble instead
     * of once per episode, just by being blocked at the wrong moment.
     */
    private var pendingThreshold: Double? = null

    /** Evaluate the latest forecast. Returns an alert to show, or null. */
    fun evaluate(
        forecast: RainForecast,
        message: RainMessage,
        riding: Boolean,
        nowMillis: Long = clock(),
    ): AlertRequest? {
        // Never interrupt someone who is not riding. Alerts belong to the ride, and a
        // full-screen banner on a parked bike is noise.
        if (!riding) return null

        // Nothing to say without data, and nothing worth interrupting for on data this
        // old.
        if (forecast.availability != RainForecast.Availability.OK) return null
        if (forecast.confidence == RiderState.Confidence.NONE) return null
        if (forecast.frameAgeSeconds > MAX_FRAME_AGE_SECONDS) return null

        val encounter = forecast.encounter
        val firstLook = !seenFirstForecast
        seenFirstForecast = true

        if (encounter == null) {
            return handleClearForecast(nowMillis)
        }

        // An episode is running again.
        clearSinceMillis = null

        // Do not announce a condition that predates us watching.
        if (firstLook && encounter.etaMinutes <= 0.0) {
            markAsAlreadyKnown(encounter.etaMinutes, message.severity)
            return null
        }

        pendingThreshold?.let { pending ->
            if (encounter.etaMinutes > pending) {
                // Recovered before the cooldown lifted: this crossing does not get to
                // fire, but it did happen, so it does not get to fire later either.
                firedThresholds.add(pending)
                pendingThreshold = null
            }
        }

        val hailIsNew = message.severity == Severity.HAIL && warnedSeverity != Severity.HAIL
        val worsened = message.severity.ordinal > warnedSeverity.ordinal

        val crossed = ETA_THRESHOLDS_MINUTES.firstOrNull { threshold ->
            encounter.etaMinutes <= threshold && threshold !in firedThresholds
        }

        val previous = lastAlertAtMillis
        val escalating = hailIsNew || worsened

        val allowed = when {
            previous == null -> true
            escalating -> nowMillis - previous >= ESCALATION_COOLDOWN_MILLIS
            else -> nowMillis - previous >= COOLDOWN_MILLIS
        }

        val reason = when {
            hailIsNew -> AlertRequest.Reason.HAIL
            worsened && episodeAlerted -> AlertRequest.Reason.WORSENED
            crossed != null -> AlertRequest.Reason.APPROACHING
            else -> null
        } ?: return null

        if (!allowed) {
            // Still crossed, just cooled down. Remember it so a later call can either
            // fire it (ETA still down there once the cooldown lifts) or spend it
            // silently (ETA recovered first) — see [pendingThreshold].
            if (reason == AlertRequest.Reason.APPROACHING) {
                pendingThreshold = crossed
            }
            return null
        }

        pendingThreshold = null

        // Mark every threshold at or above the current ETA as spent, not just the one
        // that triggered. Otherwise an ETA that jumps from 22 to 4 minutes between
        // frames would fire three separate alerts in quick succession for one event.
        for (threshold in ETA_THRESHOLDS_MINUTES) {
            if (encounter.etaMinutes <= threshold) firedThresholds.add(threshold)
        }
        if (message.severity.ordinal > warnedSeverity.ordinal) {
            warnedSeverity = message.severity
        }
        lastAlertAtMillis = nowMillis
        episodeAlerted = true

        return AlertRequest(
            title = message.alertTitle,
            detail = message.alertDetail,
            severity = message.severity,
            reason = reason,
            autoDismissMs = if (message.severity == Severity.HAIL) {
                AUTO_DISMISS_HAIL_MS
            } else {
                AUTO_DISMISS_MS
            },
        )
    }

    /**
     * The forecast shows no encounter.
     *
     * Worth one alert if the rider was previously warned and has now escaped it —
     * telling someone they no longer need to divert is as useful as telling them they
     * do. Silence otherwise.
     */
    private fun handleClearForecast(nowMillis: Long): AlertRequest? {
        val since = clearSinceMillis ?: nowMillis.also { clearSinceMillis = it }

        val clearForMinutes = (nowMillis - since) / 60_000.0
        if (clearForMinutes < EPISODE_RESET_MINUTES) return null

        val wasWarned = episodeAlerted
        resetEpisode()

        if (!wasWarned) return null

        lastAlertAtMillis = nowMillis
        return AlertRequest(
            title = "Rain cleared",
            detail = "Nothing on your route",
            severity = Severity.CLEAR,
            reason = AlertRequest.Reason.CLEARED,
            autoDismissMs = AUTO_DISMISS_MS,
        )
    }

    /** Treat an already-present condition as one we have implicitly warned about. */
    private fun markAsAlreadyKnown(etaMinutes: Double, severity: Severity) {
        for (threshold in ETA_THRESHOLDS_MINUTES) {
            if (etaMinutes <= threshold) firedThresholds.add(threshold)
        }
        warnedSeverity = severity
        episodeAlerted = true
    }

    private fun resetEpisode() {
        firedThresholds.clear()
        warnedSeverity = Severity.CLEAR
        episodeAlerted = false
        clearSinceMillis = null
        pendingThreshold = null
    }

    /** Called when a ride ends or is reset. */
    fun reset() {
        resetEpisode()
        lastAlertAtMillis = null
        seenFirstForecast = false
    }
}

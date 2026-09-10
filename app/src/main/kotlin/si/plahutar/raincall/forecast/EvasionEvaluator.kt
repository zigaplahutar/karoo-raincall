package si.plahutar.raincall.forecast

import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.radar.CellMotionEstimator
import si.plahutar.raincall.radar.RadarField
import si.plahutar.raincall.radar.TileMath
import kotlin.math.abs

/**
 * Compares ways out of the rain and picks the best, or says honestly that there is none.
 *
 * Four families of option, all scored by the same measure — how many of the next sixty
 * minutes the rider would spend wet:
 *
 *  - **continue** on the current heading
 *  - **reverse** and go back
 *  - **wait** in place for a while, then carry on
 *  - **detour** in one of eight compass directions
 *
 * The hard part is judgement rather than arithmetic. It is easy to build something that
 * always recommends a detour, because some direction is always marginally drier, or one
 * that never does. Two rules keep it honest:
 *
 *  - An alternative must save a **meaningful** number of wet minutes before it is
 *    suggested at all. Sending someone off their route to save thirty seconds is worse
 *    than saying nothing.
 *  - When options score equally, the **least disruptive** wins. Continuing beats a small
 *    detour, which beats a long wait, which beats turning round.
 *
 * Unlike the ETA cone, each option here is scored along a single committed path rather
 * than a widening wedge: the rider who takes the advice is choosing a direction, so the
 * uncertainty the cone models no longer applies to them.
 */
object EvasionEvaluator {

    /** Time resolution of the simulation, in minutes. */
    const val STEP_MINUTES = 0.5

    /** How far ahead each option is simulated. */
    const val HORIZON_MINUTES = 60.0

    /** Wait durations considered, in minutes. */
    val WAIT_OPTIONS_MINUTES = doubleArrayOf(5.0, 10.0, 20.0)

    /**
     * Minutes of rain an alternative must save before it is worth suggesting.
     *
     * Tuned against simulated scenarios: a band sweeping across the route showed a
     * detour saving 1.5 minutes, which is not worth leaving your route for, while a
     * band squarely ahead showed 16.5, which plainly is. Five sits between them.
     */
    const val MIN_SAVING_MINUTES = 5.0

    /**
     * Above this, the situation is reported as having no good option.
     *
     * Being told to divert into 30 minutes of rain instead of 35 is not advice. An app
     * that always finds a "solution" stops being believed.
     */
    const val NO_GOOD_OPTION_MINUTES = 25.0

    /** Detour directions closer than this to straight on or straight back are skipped. */
    private const val MIN_DETOUR_SEPARATION_DEGREES = 30.0

    /**
     * Wet minutes a wait must save per minute spent standing still.
     *
     * Standing under a tree for twenty minutes to dodge six minutes of rain is not a
     * saving, it is a slower way to finish the ride. At 0.5 a twenty-minute wait has to
     * be worth at least ten minutes of rain.
     */
    const val WAIT_PAYBACK_RATIO = 0.5

    private val COMPASS_BEARINGS = doubleArrayOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)

    /** What kind of manoeuvre an option represents. */
    enum class Kind { CONTINUE, REVERSE, WAIT, DETOUR }

    /**
     * How far into an option the radar window actually reached before the path left it.
     *
     * An option that rides off the edge of the downloaded mosaic cannot be credited with
     * dry minutes out there, so options are compared only over the span every one of
     * them can speak to, and an option too short to vouch for is not recommended at all.
     */
    const val MIN_KNOWN_MINUTES = 15.0

    /**
     * The result of simulating one plan: how wet it was, and how far it could be seen.
     *
     * [wetByStep] holds cumulative wet minutes after each half-minute step, which is what
     * lets two options be compared over a shared span even when one of them ran out of
     * radar coverage sooner than the other.
     */
    data class Simulation(
        private val wetByStep: DoubleArray,
        /**
         * How far ahead this plan stayed inside radar coverage.
         *
         * Less than [HORIZON_MINUTES] means the path left the downloaded window;
         * everything past this point is unknown rather than dry.
         */
        val knownMinutes: Double,
    ) {
        /** Total wet minutes over the whole span that could be observed. */
        val wetMinutes: Double get() = wetByStep.last()

        /** Wet minutes counted only up to [minutes], for like-for-like ranking. */
        fun wetWithin(minutes: Double): Double {
            if (wetByStep.isEmpty()) return 0.0
            val index = (minutes / STEP_MINUTES).toInt().coerceIn(0, wetByStep.size - 1)
            return wetByStep[index]
        }

        // Hand-written because the array field makes the generated ones wrong.
        override fun equals(other: Any?): Boolean =
            other is Simulation &&
                knownMinutes == other.knownMinutes &&
                wetByStep.contentEquals(other.wetByStep)

        override fun hashCode(): Int = 31 * wetByStep.contentHashCode() + knownMinutes.hashCode()
    }

    /** One evaluated option. */
    data class Option(
        val kind: Kind,
        /** Direction of travel, or null for a pure wait. */
        val bearingDegrees: Double?,
        /** Minutes spent stationary before setting off, 0 for everything but a wait. */
        val waitMinutes: Double,
        val simulation: Simulation,
    ) {
        /** Minutes of the next hour spent in precipitation, as far as could be seen. */
        val wetMinutes: Double get() = simulation.wetMinutes

        /** How far ahead this option stayed inside radar coverage. */
        val knownMinutes: Double get() = simulation.knownMinutes

        /** Whether the coverage reaches far enough to recommend this at all. */
        val isVouchable: Boolean get() = knownMinutes >= MIN_KNOWN_MINUTES

        /** Compass label for the direction, or null. */
        val compass: String? get() = bearingDegrees?.let { TileMath.compassPoint(it) }
    }

    /**
     * The advice, as one of a small set of shapes rather than free text.
     *
     * Fixed shapes on purpose: consistency matters more than expressiveness on a screen
     * read at speed, and a rider who has seen the same four phrasings before can parse
     * them at a glance.
     */
    sealed class Advice {
        /** Nothing to avoid. */
        data object NoRain : Advice()

        /** Rain about, but continuing is already the best choice. */
        data class ContinueIsBest(val wetMinutes: Double) : Advice()

        data class Detour(
            val compass: String,
            val bearingDegrees: Double,
            val wetMinutes: Double,
            val savedMinutes: Double,
        ) : Advice()

        data class Wait(
            val waitMinutes: Double,
            val savedMinutes: Double,
        ) : Advice()

        data class TurnBack(val savedMinutes: Double) : Advice()

        /** Every option is bad. Saying so is more useful than inventing a solution. */
        data class NoGoodOption(val wetMinutes: Double) : Advice()

        /** Not enough information to compare anything. */
        data object Unknown : Advice()
    }

    /**
     * Evaluate every option and return the advice.
     *
     * Costs roughly 1,300 field lookups: eleven options across 121 half-minute steps.
     * That is well within what the Karoo can do between radar refreshes.
     */
    fun evaluate(
        rider: RiderState,
        field: RadarField,
        cellVelocity: CellMotionEstimator.CellVelocity?,
        nowEpochSeconds: Long,
    ): Advice {
        val heading = rider.headingDegrees ?: return Advice.Unknown
        val speed = rider.speedMetresPerSecond ?: return Advice.Unknown
        if (speed <= 0.0) return Advice.Unknown

        val frameAge = nowEpochSeconds - field.timeEpochSeconds
        if (frameAge < 0) return Advice.Unknown

        val velocity = cellVelocity?.takeIf { it.isUsable }
        val cellSpeed = velocity?.speedMetresPerSecond ?: 0.0
        val cellBearing = velocity?.bearingDegrees

        val options = buildOptions(rider, field, speed, heading, cellSpeed, cellBearing, frameAge)
        val continueOption = options.first { it.kind == Kind.CONTINUE }

        if (continueOption.wetMinutes <= 0.0) {
            // Nothing ahead. Say so rather than ranking eleven equally dry options and
            // picking one at random.
            return if (options.all { it.wetMinutes <= 0.0 }) {
                Advice.NoRain
            } else {
                Advice.ContinueIsBest(0.0)
            }
        }

        // Only options the radar can actually vouch for are eligible to be recommended.
        // One that leaves the downloaded window after four minutes may well be drier out
        // there, but we have not looked, and saying "turn back" on that basis is
        // invention rather than advice.
        val vouchable = options.filter { it.isVouchable }
        if (vouchable.none { it.kind != Kind.CONTINUE }) {
            return Advice.ContinueIsBest(continueOption.wetMinutes)
        }

        // Compare over the span every candidate reached, so an option is never rewarded
        // for having run out of coverage early.
        val commonHorizon = vouchable.minOf { it.knownMinutes }
        val continueWet = continueOption.simulation.wetWithin(commonHorizon)

        // Drop waits that do not repay the time they cost before ranking, rather than
        // after: otherwise a wait can win the comparison and then be discarded, leaving
        // the rider with the second-best answer presented as the best.
        val viable = vouchable.filter { option ->
            option.kind != Kind.WAIT ||
                (continueWet - option.simulation.wetWithin(commonHorizon)) >=
                option.waitMinutes * WAIT_PAYBACK_RATIO
        }

        val best = viable.minWithOrNull(
            compareBy<Option> { it.simulation.wetWithin(commonHorizon) }
                .thenBy { disruption(it, heading) }
        ) ?: return Advice.ContinueIsBest(continueOption.wetMinutes)

        val bestWet = best.simulation.wetWithin(commonHorizon)
        val saving = continueWet - bestWet

        // Judge "nothing works" on the saving rather than an absolute soak time. Forty
        // wet minutes reduced to twenty-six is worth saying even though twenty-six is a
        // drenching; what is not worth saying is thirty-five reduced to thirty.
        if (saving < MIN_SAVING_MINUTES && continueWet >= NO_GOOD_OPTION_MINUTES) {
            return Advice.NoGoodOption(continueOption.wetMinutes)
        }

        if (best.kind == Kind.CONTINUE || saving < MIN_SAVING_MINUTES) {
            return Advice.ContinueIsBest(continueOption.wetMinutes)
        }

        return when (best.kind) {
            Kind.DETOUR -> Advice.Detour(
                compass = best.compass!!,
                bearingDegrees = best.bearingDegrees!!,
                wetMinutes = best.wetMinutes,
                savedMinutes = saving,
            )
            Kind.WAIT -> Advice.Wait(best.waitMinutes, saving)
            Kind.REVERSE -> Advice.TurnBack(saving)
            Kind.CONTINUE -> Advice.ContinueIsBest(continueOption.wetMinutes)
        }
    }

    private fun buildOptions(
        rider: RiderState,
        field: RadarField,
        speed: Double,
        heading: Double,
        cellSpeed: Double,
        cellBearing: Double?,
        frameAgeSeconds: Long,
    ): List<Option> {
        val options = mutableListOf<Option>()

        fun score(bearing: Double?, wait: Double) = simulate(
            rider, field, speed, bearing, wait, cellSpeed, cellBearing, frameAgeSeconds,
        )

        options.add(Option(Kind.CONTINUE, heading, 0.0, score(heading, 0.0)))

        val reverse = (heading + 180.0) % 360.0
        options.add(Option(Kind.REVERSE, reverse, 0.0, score(reverse, 0.0)))

        for (wait in WAIT_OPTIONS_MINUTES) {
            options.add(Option(Kind.WAIT, heading, wait, score(heading, wait)))
        }

        for (bearing in COMPASS_BEARINGS) {
            val offAxis = abs(TileMath.bearingDelta(heading, bearing))
            // Directions nearly straight on or straight back duplicate continue and
            // reverse; including them would clutter the ranking with near-identical
            // options and make an arbitrary tie-break look like a real recommendation.
            if (offAxis < MIN_DETOUR_SEPARATION_DEGREES) continue
            if (abs(offAxis - 180.0) < MIN_DETOUR_SEPARATION_DEGREES) continue
            options.add(Option(Kind.DETOUR, bearing, 0.0, score(bearing, 0.0)))
        }

        return options
    }

    /**
     * Minutes spent in precipitation over the horizon, following one plan.
     *
     * Uses the same backward advection as the ETA search: the sample point is stepped
     * against the cell velocity by the frame's age plus the elapsed time, rather than
     * the field being moved forward.
     *
     * Stepping stops the moment the lookup leaves radar coverage. Carrying on would
     * quietly accumulate dry minutes for ground that was never observed, and over a
     * sixty-minute horizon a rider covers 43 km while the advection displaces the lookup
     * by tens more — so leaving a 60 km window is routine, not exotic.
     */
    private fun simulate(
        rider: RiderState,
        field: RadarField,
        speed: Double,
        bearing: Double?,
        waitMinutes: Double,
        cellSpeed: Double,
        cellBearing: Double?,
        frameAgeSeconds: Long,
    ): Simulation {
        val steps = (HORIZON_MINUTES / STEP_MINUTES).toInt() + 1
        val wetByStep = DoubleArray(steps)
        var wet = 0.0
        var minutes = 0.0
        var known = 0.0
        var index = 0

        while (index < steps) {
            val position = if (bearing == null || minutes <= waitMinutes) {
                rider.longitude to rider.latitude
            } else {
                TileMath.destination(
                    rider.longitude, rider.latitude,
                    bearing,
                    speed * (minutes - waitMinutes) * 60.0,
                )
            }

            val lookup = if (cellBearing != null && cellSpeed > 0.0) {
                TileMath.destination(
                    position.first, position.second,
                    (cellBearing + 180.0) % 360.0,
                    cellSpeed * (frameAgeSeconds + minutes * 60.0),
                )
            } else {
                position
            }

            when (field.observe(lookup.first, lookup.second)) {
                RadarField.Observation.WET -> wet += STEP_MINUTES
                RadarField.Observation.DRY -> Unit
                RadarField.Observation.UNOBSERVED -> {
                    // Out of coverage. Everything from here on is unknown; fill the rest
                    // of the timeline with the total so far so a shared cut-off past this
                    // point reads the last figure we could actually stand behind.
                    java.util.Arrays.fill(wetByStep, index, steps, wet)
                    return Simulation(wetByStep, known)
                }
            }

            wetByStep[index] = wet
            known = minutes
            minutes += STEP_MINUTES
            index++
        }

        return Simulation(wetByStep, HORIZON_MINUTES)
    }

    /**
     * How much an option disrupts the ride, 0 (none) to 1 (most).
     *
     * Breaks ties between options that would keep the rider equally dry — and several
     * usually do. Without an ordering the advice would be whichever option happened to
     * be built first, which could send someone back the way they came when a slight
     * turn would have done.
     *
     * The weights reflect what each choice actually costs the rider, which is not the
     * same as how far it turns them. An earlier version scored a detour purely by
     * angular deviation, which made every detour cheaper than any wait — and across 320
     * simulated cells the "wait" branch never won once. But a detour means abandoning
     * the route for the rest of the hour, while a wait costs ten minutes and leaves the
     * rider exactly where they meant to be. Weighted this way, waiting wins in the
     * situation it should: a compact cell crossing the route just ahead.
     */
    private fun disruption(option: Option, heading: Double): Double = when (option.kind) {
        Kind.CONTINUE -> 0.0
        Kind.WAIT -> 0.15 + option.waitMinutes / 100.0
        Kind.DETOUR ->
            0.40 + abs(TileMath.bearingDelta(heading, option.bearingDegrees ?: heading)) / 180.0 * 0.40
        Kind.REVERSE -> 1.0
    }
}

package si.plahutar.raincall.forecast

import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.radar.CellMotionEstimator
import si.plahutar.raincall.radar.RadarField
import si.plahutar.raincall.radar.TileMath
import kotlin.math.abs

/**
 * Where the ride started, or wherever the rider wants to end up.
 *
 * Captured from the first position fix of the ride rather than configured, because that
 * is right almost always and asks nothing of the rider.
 */
data class HomeContext(
    val latitude: Double,
    val longitude: Double,
)

/**
 * Answers the question a rider with a destination actually has.
 *
 * Plain evasion asks "which way is driest", and on its own that can produce advice which
 * is technically perfect and practically useless: in one simulated case, riding east
 * away from an advancing band scored zero wet minutes for the whole hour — while
 * carrying the rider steadily further from home. Staying dry by fleeing is not a
 * solution if you wanted to get back.
 *
 * So when home is close enough to matter, the question becomes "how much longer can I
 * keep going before I have to turn for home", which is both more useful and answerable
 * with the same machinery.
 *
 * The one scoring difference: the simulation stops on arrival. Once you are home you are
 * dry, and counting the remainder of the hour as dry for every option would flatten the
 * comparison and make a distant turnaround look as good as an immediate one.
 */
object HomeEvaluator {

    const val STEP_MINUTES = 0.5

    /**
     * How far ahead home options are simulated.
     *
     * Longer than the plain evasion horizon because getting home takes time — a 20 km
     * ride back is 42 minutes at a steady pace — but not so long that it depends on
     * radar predicting further than it can.
     */
    const val HORIZON_MINUTES = 90.0

    /** Outbound durations tried before turning for home, in minutes. */
    val TURNAROUND_OPTIONS_MINUTES = doubleArrayOf(0.0, 5.0, 10.0, 15.0, 20.0, 30.0)

    /** Close enough to count as arrived. */
    const val ARRIVAL_RADIUS_METRES = 200.0

    /**
     * Home is treated as a goal worth advising on within this distance.
     *
     * Twenty kilometres is about forty minutes of riding, which is inside what a radar
     * nowcast can speak to. Advising someone 60 km out about their arrival is a forecast
     * dressed up as a measurement.
     */
    const val RELEVANT_DISTANCE_METRES = 20_000.0

    /** Heading within this of the bearing home counts as "already heading back". */
    const val HEADING_HOME_TOLERANCE_DEGREES = 60.0

    /**
     * The advice about getting home, or null when home is not a live consideration.
     */
    sealed class HomeAdvice {
        /** Home is reachable dry whenever you like; nothing to warn about. */
        data class Comfortable(val distanceMetres: Double) : HomeAdvice()

        /**
         * You can keep going for this long and still get home dry.
         *
         * Zero means turn now.
         */
        data class TurnAroundWithin(
            val minutes: Double,
            val distanceMetres: Double,
        ) : HomeAdvice()

        /** No turnaround gets you home dry; this is the least wet. */
        data class WetWhateverYouDo(
            val wetMinutes: Double,
            val arrivalMinutes: Double?,
        ) : HomeAdvice()

        /** Home is too far to say anything useful about within the radar horizon. */
        data object OutOfRange : HomeAdvice()
    }

    /**
     * Whether home is worth advising on at all.
     *
     * Any of three reasons qualifies: it is close, the rider is already pointed at it,
     * or it is reachable inside the horizon. Requiring all three would silence the
     * advice exactly when a rider 18 km out has just turned for home.
     */
    fun isRelevant(rider: RiderState, home: HomeContext): Boolean {
        val distance = TileMath.distanceMetres(
            rider.longitude, rider.latitude, home.longitude, home.latitude,
        )
        if (distance <= RELEVANT_DISTANCE_METRES) return true

        val speed = rider.speedMetresPerSecond ?: return false
        if (speed > 0 && distance / speed / 60.0 <= HORIZON_MINUTES) return true

        val heading = rider.headingDegrees ?: return false
        val bearingHome = TileMath.bearingDegrees(
            rider.longitude, rider.latitude, home.longitude, home.latitude,
        )
        return abs(TileMath.bearingDelta(heading, bearingHome)) <= HEADING_HOME_TOLERANCE_DEGREES
    }

    fun evaluate(
        rider: RiderState,
        home: HomeContext,
        field: RadarField,
        cellVelocity: CellMotionEstimator.CellVelocity?,
        nowEpochSeconds: Long,
    ): HomeAdvice? {
        val heading = rider.headingDegrees ?: return null
        val speed = rider.speedMetresPerSecond ?: return null
        if (speed <= 0.0) return null

        val frameAge = nowEpochSeconds - field.timeEpochSeconds
        if (frameAge < 0) return null

        if (!isRelevant(rider, home)) return null

        val distance = TileMath.distanceMetres(
            rider.longitude, rider.latitude, home.longitude, home.latitude,
        )
        if (distance / speed / 60.0 > HORIZON_MINUTES) return HomeAdvice.OutOfRange

        val velocity = cellVelocity?.takeIf { it.isUsable }
        val cellSpeed = velocity?.speedMetresPerSecond ?: 0.0
        val cellBearing = velocity?.bearingDegrees

        var latestDry: Double? = null
        var bestWet = Double.MAX_VALUE
        var bestArrival: Double? = null

        for (outbound in TURNAROUND_OPTIONS_MINUTES) {
            val result = simulate(
                rider, home, field, speed, heading, outbound,
                cellSpeed, cellBearing, frameAge,
            )
            if (result.arrivalMinutes == null) continue

            if (result.wetMinutes <= 0.0) {
                // Keep the largest, not the first: the rider wants to know how long they
                // can carry on, not merely that turning now would work.
                latestDry = maxOf(latestDry ?: 0.0, outbound)
            }
            if (result.wetMinutes < bestWet) {
                bestWet = result.wetMinutes
                bestArrival = result.arrivalMinutes
            }
        }

        return when {
            // Dry even after the longest outbound leg we tried: there is nothing to
            // warn about, and inventing a deadline would be false precision.
            latestDry != null && latestDry >= TURNAROUND_OPTIONS_MINUTES.last() ->
                HomeAdvice.Comfortable(distance)

            latestDry != null -> HomeAdvice.TurnAroundWithin(latestDry, distance)

            bestWet != Double.MAX_VALUE ->
                HomeAdvice.WetWhateverYouDo(bestWet, bestArrival)

            else -> HomeAdvice.OutOfRange
        }
    }

    private data class Result(val wetMinutes: Double, val arrivalMinutes: Double?)

    /**
     * Ride the current heading for [outboundMinutes], then head for home.
     *
     * The homeward leg re-aims each step rather than committing to one bearing. Over
     * these distances a straight run would be close enough, but re-aiming costs nothing
     * and copes with an outbound leg that curved away.
     */
    private fun simulate(
        rider: RiderState,
        home: HomeContext,
        field: RadarField,
        speed: Double,
        heading: Double,
        outboundMinutes: Double,
        cellSpeed: Double,
        cellBearing: Double?,
        frameAgeSeconds: Long,
    ): Result {
        var longitude = rider.longitude
        var latitude = rider.latitude
        var wet = 0.0
        var minutes = 0.0
        var arrival: Double? = null

        while (minutes <= HORIZON_MINUTES) {
            if (minutes <= outboundMinutes) {
                val out = TileMath.destination(
                    rider.longitude, rider.latitude, heading, speed * minutes * 60.0,
                )
                longitude = out.first
                latitude = out.second
            } else {
                val remaining = TileMath.distanceMetres(
                    longitude, latitude, home.longitude, home.latitude,
                )
                if (remaining <= ARRIVAL_RADIUS_METRES) {
                    arrival = minutes
                    break
                }
                val bearingHome = TileMath.bearingDegrees(
                    longitude, latitude, home.longitude, home.latitude,
                )
                val stepMetres = minOf(speed * STEP_MINUTES * 60.0, remaining)
                val next = TileMath.destination(longitude, latitude, bearingHome, stepMetres)
                longitude = next.first
                latitude = next.second
            }

            val lookup = if (cellBearing != null && cellSpeed > 0.0) {
                TileMath.destination(
                    longitude, latitude,
                    (cellBearing + 180.0) % 360.0,
                    cellSpeed * (frameAgeSeconds + minutes * 60.0),
                )
            } else {
                longitude to latitude
            }

            if (field.sampleAt(lookup.first, lookup.second).isMeaningful) {
                wet += STEP_MINUTES
            }
            minutes += STEP_MINUTES
        }

        return Result(wet, arrival)
    }
}

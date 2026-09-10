package si.plahutar.raincall.forecast

import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.radar.CellMotionEstimator
import si.plahutar.raincall.radar.DbzPalette
import si.plahutar.raincall.radar.RadarField
import si.plahutar.raincall.radar.RainViewerApi
import si.plahutar.raincall.radar.TileMath
import kotlin.math.tan

/**
 * Turns a radar field, a cell velocity and a rider state into a forecast.
 *
 * ## Backward advection
 *
 * The obvious way to ask "will there be rain here in twelve minutes" is to move the
 * whole rain field forward twelve minutes and look. That costs a field transform per
 * time step. The equivalent and far cheaper question is to leave the field alone and
 * move the *sample point* backwards by the same displacement: if a cell travelling at
 * velocity v would be at point p at time t, then when the frame was taken it was at
 * p - v*t. One lookup replaces a field copy.
 *
 * Checked against forward advection over a fifteen-minute lead: the two agree to within
 * about 13 metres, which is a sixteenth of a pixel at zoom 8 and far below the radar's
 * own resolution.
 *
 * ## Frame age is part of the lead time
 *
 * A frame stamped six minutes ago shows where cells *were*, not where they are. At a
 * typical 12 m/s that is already 4.3 km of drift — twenty pixels. Every lookup
 * therefore advects by `frame age + lead time`, never by lead time alone. Forgetting
 * this is an easy mistake that produces a forecast which is quietly, consistently late.
 *
 * ## The cone
 *
 * Without a route we do not know where the rider will turn, so the forward projection
 * is a widening wedge rather than a line. "You will hit rain" means rain intersects the
 * wedge, which is why each time step is sampled across the wedge's width and not only
 * down its axis.
 */
object RainForecaster {

    /** Time resolution of the ETA search, in minutes. */
    const val STEP_MINUTES = 0.5

    /**
     * Lateral samples taken across the cone at each time step.
     *
     * Nine keeps the worst realistic gap near a kilometre — narrow enough that a shower
     * cannot slip between samples. The widest cone only occurs with high curvature,
     * which also collapses the horizon to five minutes, so the wide-and-distant
     * combination that would leave big gaps cannot actually arise.
     */
    const val CONE_SAMPLES = 9

    /** How far out to look for the nearest precipitation, in metres. */
    const val NEAREST_SEARCH_RADIUS_M = 60_000.0

    /**
     * Beyond this frame age the answer stops being allowed to sound certain.
     *
     * Ten minutes is one RainViewer publishing interval; at a typical 12 m/s the cells
     * have moved 7 km since the picture was taken, which the advection corrects for but
     * cannot make exact.
     */
    const val AGEING_FRAME_SECONDS = 10 * 60L

    /**
     * Once an encounter starts, how much longer to keep stepping to measure it.
     *
     * Bounded so a rider inside a large frontal band does not send the search running
     * to the end of the field; past this the duration is reported as unknown rather
     * than as a precise but fictional number.
     */
    const val MAX_DURATION_MINUTES = 60.0

    /**
     * Build a forecast.
     *
     * @param nowEpochSeconds current time, used with the field's own timestamp to work
     *        out how stale the observation is.
     * @param cellVelocity how the rain is moving, or null if it could not be
     *        established. Null is handled by treating cells as stationary, which is
     *        wrong but not silently so: it makes the ETA conservative rather than
     *        confident, and the caller can lower the reported confidence.
     */
    fun forecast(
        rider: RiderState,
        field: RadarField,
        cellVelocity: CellMotionEstimator.CellVelocity?,
        nowEpochSeconds: Long,
    ): RainForecast {
        val frameAge = nowEpochSeconds - field.timeEpochSeconds
        if (frameAge < 0) {
            // A frame stamped in the future means a clock problem somewhere. Producing
            // a forecast from it would be guesswork dressed as data.
            return RainForecast.unavailable()
        }

        // Past this the observed positions have been carried forward so far that the
        // result is extrapolation, not measurement — and because everything is advected
        // by `age + lead`, a stalled feed eventually pushes every lookup clean out of the
        // window and the app would cheerfully report a clear sky.
        if (frameAge > RainViewerApi.MAX_USABLE_FRAME_AGE_SECONDS) {
            return RainForecast.stale(frameAge)
        }

        val metresPerPixel = field.metresPerPixel(rider.latitude)

        val velocity = cellVelocity?.takeIf { it.isUsable }
        val cellSpeed = velocity?.speedMetresPerSecond ?: 0.0
        val cellBearing = velocity?.bearingDegrees

        val nearest = findNearest(rider, field, cellSpeed, cellBearing, frameAge, metresPerPixel)

        val encounter = if (rider.canProject) {
            findEncounter(rider, field, cellSpeed, cellBearing, frameAge)
        } else {
            null
        }

        return RainForecast(
            nearest = nearest,
            encounter = encounter,
            // An ageing frame caps how confident the answer may sound, however good the
            // rider's fix and however straight the road: by ten minutes the cells have
            // moved kilometres from where they were seen.
            confidence = minOf(rider.confidence, confidenceCeilingFor(frameAge)),
            horizonMinutes = rider.horizonMinutes,
            frameAgeSeconds = frameAge,
            availability = RainForecast.Availability.OK,
        )
    }

    /**
     * How confident a forecast may sound given only the age of the frame it came from.
     *
     * Not a judgement about the rider — that is [RiderState.confidence] — but about the
     * observation, and the final confidence is the lesser of the two.
     */
    private fun confidenceCeilingFor(frameAgeSeconds: Long): RiderState.Confidence = when {
        frameAgeSeconds <= AGEING_FRAME_SECONDS -> RiderState.Confidence.HIGH
        else -> RiderState.Confidence.MEDIUM
    }

    /**
     * Closest precipitation to the rider right now.
     *
     * Works in pixel space rather than doing a great-circle distance per pixel: over
     * the tens of kilometres involved the difference is far below the radar's own
     * resolution, and a haversine for each of 65,000 pixels would be wasteful on a
     * device that has to do this every few minutes.
     *
     * Rather than advecting every pixel forward to its present position, the rider is
     * shifted backwards by the same amount. Identical geometry, one subtraction instead
     * of sixty-five thousand.
     */
    private fun findNearest(
        rider: RiderState,
        field: RadarField,
        cellSpeed: Double,
        cellBearing: Double?,
        frameAgeSeconds: Long,
        metresPerPixel: Double,
    ): NearestRain? {
        val riderGlobal = TileMath.lonLatToGlobalPixel(
            rider.longitude, rider.latitude, field.range.zoom, field.range.tileSize,
        )
        val riderPixel = field.range.toMosaicPixel(riderGlobal) ?: return null

        // Displacement the cells have undergone since the frame was taken, in pixels.
        var ageDx = 0.0
        var ageDy = 0.0
        if (cellBearing != null && cellSpeed > 0.0) {
            val drift = cellSpeed * frameAgeSeconds / metresPerPixel
            val radians = Math.toRadians(cellBearing)
            ageDx = drift * kotlin.math.sin(radians)
            // Screen rows run downward, so northward movement is negative y.
            ageDy = -drift * kotlin.math.cos(radians)
        }

        // Shifting the rider back is equivalent to shifting every echo forward.
        val originX = riderPixel.first - ageDx
        val originY = riderPixel.second - ageDy

        val radiusPixels = NEAREST_SEARCH_RADIUS_M / metresPerPixel
        val minX = kotlin.math.max(0, (originX - radiusPixels).toInt())
        val maxX = kotlin.math.min(field.width - 1, (originX + radiusPixels).toInt())
        val minY = kotlin.math.max(0, (originY - radiusPixels).toInt())
        val maxY = kotlin.math.min(field.height - 1, (originY + radiusPixels).toInt())

        var bestDistanceSq = Double.MAX_VALUE
        var bestX = -1
        var bestY = -1

        for (y in minY..maxY) {
            val dy = y - originY
            val dySq = dy * dy
            // Whole row is already further than the best find; skip it.
            if (dySq >= bestDistanceSq) continue
            for (x in minX..maxX) {
                val sample = field.sampleAt(x, y)
                if (!sample.isMeaningful) continue
                val dx = x - originX
                val distSq = dx * dx + dySq
                if (distSq < bestDistanceSq) {
                    bestDistanceSq = distSq
                    bestX = x
                    bestY = y
                }
            }
        }

        if (bestX < 0) return null

        val sample = field.sampleAt(bestX, bestY)
        val distancePixels = kotlin.math.sqrt(bestDistanceSq)
        if (distancePixels * metresPerPixel > NEAREST_SEARCH_RADIUS_M) return null

        // Bearing measured in pixel space, then read as a compass direction. +y is
        // south, hence the negation.
        val bearing = if (distancePixels < 0.5) {
            0.0
        } else {
            (Math.toDegrees(
                kotlin.math.atan2(bestX - originX, -(bestY - originY))
            ) + 360.0) % 360.0
        }

        return NearestRain(
            distanceMetres = distancePixels * metresPerPixel,
            bearingDegrees = bearing,
            compass = TileMath.compassPoint(bearing),
            intensity = sample.intensity,
            type = sample.type,
            possibleHail = sample.possibleHail,
        )
    }

    /**
     * Step forward in time looking for the first intersection between the rider's cone
     * and precipitation.
     */
    private fun findEncounter(
        rider: RiderState,
        field: RadarField,
        cellSpeed: Double,
        cellBearing: Double?,
        frameAgeSeconds: Long,
    ): Encounter? {
        val heading = rider.headingDegrees ?: return null
        val riderSpeed = rider.speedMetresPerSecond ?: return null
        if (riderSpeed <= 0.0) return null

        val horizon = rider.horizonMinutes
        if (horizon <= 0.0) return null

        val coneHalfAngle = Math.toRadians(rider.coneHalfAngleDegrees)

        var firstHitMinutes: Double? = null
        var firstHitCoverage = 0.0
        var worstIntensity = DbzPalette.Intensity.NONE
        var encounterType = DbzPalette.PrecipType.NONE
        var hail = false
        var lastWetMinutes = 0.0
        var endedWhileStillWet = true

        val limit = horizon + MAX_DURATION_MINUTES
        var minutes = 0.0
        while (minutes <= limit) {
            // Stop looking for a *first* contact past the horizon, but keep stepping
            // afterwards to measure how long an encounter already under way lasts.
            if (firstHitMinutes == null && minutes > horizon) break

            val alongMetres = riderSpeed * minutes * 60.0
            val axis = TileMath.destination(
                rider.longitude, rider.latitude, heading, alongMetres,
            )
            val halfWidth = alongMetres * tan(coneHalfAngle)

            var wetSamples = 0
            var observedSamples = 0
            var stepIntensity = DbzPalette.Intensity.NONE
            var stepType = DbzPalette.PrecipType.NONE
            var stepHail = false

            for (i in 0 until CONE_SAMPLES) {
                val lateralOffset = if (CONE_SAMPLES == 1) {
                    0.0
                } else {
                    // -1 at one edge, +1 at the other.
                    val fraction = (2.0 * i / (CONE_SAMPLES - 1)) - 1.0
                    fraction * halfWidth
                }

                val samplePoint = if (lateralOffset == 0.0) {
                    axis
                } else {
                    TileMath.destination(
                        axis.first, axis.second,
                        heading + if (lateralOffset > 0) 90.0 else -90.0,
                        kotlin.math.abs(lateralOffset),
                    )
                }

                // Backward advection: step the sample point against the cell velocity by
                // the full elapsed time, which is the frame's own age plus the lead.
                val lookupPoint = if (cellBearing != null && cellSpeed > 0.0) {
                    val totalSeconds = frameAgeSeconds + minutes * 60.0
                    TileMath.destination(
                        samplePoint.first, samplePoint.second,
                        (cellBearing + 180.0) % 360.0,
                        cellSpeed * totalSeconds,
                    )
                } else {
                    samplePoint
                }

                if (field.observe(lookupPoint.first, lookupPoint.second) ==
                    RadarField.Observation.UNOBSERVED
                ) {
                    // Outside the downloaded window. Not dry — unknown.
                    continue
                }
                observedSamples++

                val sample = field.sampleAt(lookupPoint.first, lookupPoint.second)
                if (!sample.isMeaningful) continue

                wetSamples++
                if (sample.intensity.ordinal > stepIntensity.ordinal) {
                    stepIntensity = sample.intensity
                    stepType = sample.type
                }
                if (sample.possibleHail) stepHail = true
            }

            // The cone has walked off the edge of the mosaic. Stopping here reports
            // "nothing found within N minutes", which is true; carrying on would report
            // "no rain ahead" about ground that was never looked at.
            if (observedSamples == 0) break

            if (wetSamples > 0) {
                if (firstHitMinutes == null) {
                    firstHitMinutes = minutes
                    // Out of the samples we could actually see, not out of all nine:
                    // a cone half outside the window is not half dry.
                    firstHitCoverage = wetSamples.toDouble() / observedSamples
                }
                lastWetMinutes = minutes
                if (stepIntensity.ordinal > worstIntensity.ordinal) {
                    worstIntensity = stepIntensity
                    encounterType = stepType
                }
                if (stepHail) hail = true
            } else if (firstHitMinutes != null) {
                // Came out the other side; the encounter has a measurable end.
                endedWhileStillWet = false
                break
            }

            minutes += STEP_MINUTES
        }

        val eta = firstHitMinutes ?: return null

        // At t = 0 the cone has zero width, so a rider already in the rain registers as
        // a single sample. Report full coverage rather than 1/9, because "you are in it"
        // is not a marginal case.
        val coverage = if (eta == 0.0) 1.0 else firstHitCoverage

        return Encounter(
            etaMinutes = eta,
            durationMinutes = if (endedWhileStillWet) null else (lastWetMinutes - eta + STEP_MINUTES),
            intensity = worstIntensity,
            type = encounterType,
            possibleHail = hail,
            coneCoverage = coverage,
        )
    }
}

package si.plahutar.raincall.forecast

import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.radar.DbzPalette
import kotlin.math.roundToInt

/** Distance units for the rendered text. */
enum class DisplayUnits { METRIC, IMPERIAL }

/**
 * How serious the situation is. Drives alert colour and whether to interrupt at all.
 *
 * Ordered least to most serious, so comparisons can use the ordinal.
 */
enum class Severity {
    /** No radar data. Deliberately distinct from [CLEAR]. */
    UNKNOWN,
    CLEAR,
    LIGHT,
    MODERATE,
    HEAVY,
    HAIL,
}

/**
 * A composed message, held as facts rather than as finished strings.
 *
 * Karoo tells us the real size of a data field at runtime via `ViewConfig.viewSize`, so
 * rather than guessing at three fixed size classes and hoping the text fits, the pieces
 * are kept separate and [render] assembles the longest version that fits the space
 * actually available. Overflow is then prevented by construction rather than by luck.
 */
data class RainMessage(
    val severity: Severity,

    /** Full headline, e.g. "Rain in 12 min". */
    val headline: String,

    /** Abbreviated headline for small fields, e.g. "Rain 12m". */
    val headlineShort: String,

    /**
     * Shortest usable headline, e.g. "12m".
     *
     * Exists so that truncation essentially never happens. Cutting "Rain 12m" to fit
     * six characters would give "Rain 1", which does not merely look bad — it reads as
     * one minute. A misleading number is worse than a terse one.
     */
    val headlineMinimal: String,

    /**
     * Supporting facts, most important first.
     *
     * Empty categories are never included: if there is no hail and no snow, those words
     * simply do not appear. Showing "hail: no" would waste the most valuable line on
     * the screen.
     */
    val details: List<Detail>,

    /** Confidence qualifier, e.g. "uncertain", or null when confidence is high. */
    val qualifier: String?,

    /** Single-character confidence marker for fields too narrow for a word. */
    val qualifierMark: String?,

    /** Title for a full-screen alert. */
    val alertTitle: String,

    /** Detail line for a full-screen alert, or null. */
    val alertDetail: String?,
) {

    /**
     * One supporting fact, with a shorter wording to fall back on.
     *
     * Without this a narrow field silently loses the *more* important line and keeps a
     * less important one, purely because the important one happened to be wider —
     * intensity vanishing while "possible hail" survives. Having a short form means the
     * fact degrades instead of disappearing.
     */
    data class Detail(val full: String, val short: String = full) {
        /** Longest form that satisfies [fits], or null if neither does. */
        fun bestFit(fits: (String) -> Boolean): String? = when {
            fits(full) -> full
            fits(short) -> short
            else -> null
        }
    }

    /**
     * Assemble the longest rendering that fits.
     *
     * @param maxLines how many lines the field can show.
     * @param maxChars how many characters fit on a line at the field's font size.
     *
     * Lines are never truncated mid-word and never silently cut: if a detail does not
     * fit, it is dropped entirely rather than shown as a fragment. A half-word on a
     * screen being read at 30 km/h is worse than no word.
     */
    fun render(maxLines: Int, maxChars: Int): List<String> {
        if (maxChars <= 0) return emptyList()
        // The final take() is the hard guarantee: whatever the ladder produced, nothing
        // leaves this function wider than it was allowed to be.
        return render(maxLines) { it.length <= maxChars }
            .map { if (it.length <= maxChars) it else it.take(maxChars) }
    }

    /**
     * Assemble the longest rendering whose every line satisfies [fits].
     *
     * The predicate form exists so the renderer can *measure* text against the real
     * field width with the real font, rather than estimating a character count from the
     * font size. Estimating is what produces text that fits in testing and overlaps on
     * the device, because glyph widths are not uniform: "Rain in 12 min" and
     * "WWWW WW WW WWW" are the same number of characters and nowhere near the same
     * number of pixels.
     */
    fun render(maxLines: Int, fits: (String) -> Boolean): List<String> {
        if (maxLines <= 0) return emptyList()

        // Step down the headline ladder until something fits. Truncation is the last
        // resort and in practice unreachable, because headlineMinimal is a few
        // characters at most.
        val head = listOf(headline, headlineShort, headlineMinimal).firstOrNull(fits)
            ?: return listOf(headlineMinimal)

        val lines = mutableListOf(head)
        if (maxLines == 1) return lines

        val remaining = details.toMutableList()
        if (qualifier != null) {
            if (remaining.isEmpty()) {
                remaining.add(Detail(qualifier))
            } else {
                val base = remaining[0]
                val combined = "${base.full} ($qualifier)"
                val marked = qualifierMark?.let { "${base.full} $it" }
                val shortMarked = qualifierMark?.let { "${base.short} $it" }
                when {
                    fits(combined) -> remaining[0] = Detail(combined, base.short)
                    marked != null && fits(marked) ->
                        remaining[0] = Detail(marked, shortMarked ?: base.short)
                    shortMarked != null && fits(shortMarked) ->
                        remaining[0] = Detail(shortMarked, shortMarked)
                    fits(qualifier) -> remaining.add(minOf(1, remaining.size), Detail(qualifier))
                }
            }
        }

        for (detail in remaining) {
            if (lines.size >= maxLines) break
            // Try the full wording, then the short one. A fact that fits neither is
            // skipped rather than truncated: a half-word read at speed is worse than no
            // word at all.
            detail.bestFit(fits)?.let { lines.add(it) }
        }

        return lines
    }

    /** Convenience for a one-line field. */
    fun single(maxChars: Int): String = render(1, maxChars).firstOrNull() ?: ""
}

/**
 * Turns a [RainForecast] into rider-facing English.
 *
 * Wording rules, all of them deliberate:
 *
 *  - **Hail is always "possible".** A 2D reflectivity mosaic cannot see the freezing
 *    level, so a very heavy warm-season downpour trips the same threshold. Stating it
 *    as fact would be overclaiming.
 *  - **Confidence is part of the message**, not a hidden field. A wrong ETA delivered
 *    confidently costs more trust than a vague one delivered honestly.
 *  - **Empty categories are omitted.** No hail, no hail word.
 *  - **"No data" never renders as "clear".** Not seeing rain and seeing no rain are
 *    different, and only one of them means it is safe to carry on.
 */
object MessageComposer {

    fun compose(
        forecast: RainForecast,
        units: DisplayUnits = DisplayUnits.METRIC,
        evasion: EvasionEvaluator.Advice = EvasionEvaluator.Advice.Unknown,
        home: HomeEvaluator.HomeAdvice? = null,
    ): RainMessage {
        val encounter = forecast.encounter
        val nearest = forecast.nearest

        return when {
            // Routed on what the radar could tell us, never inferred from the rider's
            // confidence. A stationary rider under a genuinely clear sky has no heading
            // to project along and nothing nearby to report, which used to be
            // indistinguishable from a dead radar feed and was announced as "No radar
            // data" — a false alarm about the app rather than about the weather.
            forecast.availability == RainForecast.Availability.NO_RADAR -> noData()

            forecast.availability == RainForecast.Availability.STALE ->
                staleData(forecast.frameAgeSeconds)

            encounter != null -> fromEncounter(forecast, encounter, units, evasion, home)

            nearest != null -> fromNearestOnly(forecast, nearest, units)

            else -> clear(forecast)
        }
    }

    private fun noData() = RainMessage(
        severity = Severity.UNKNOWN,
        headline = "No radar data",
        headlineShort = "No data",
        headlineMinimal = "--",
        details = emptyList(),
        qualifier = null,
        qualifierMark = null,
        alertTitle = "No radar data",
        alertDetail = null,
    )

    /**
     * The frame in hand is too old to reason from.
     *
     * Given its own wording rather than folded into [noData] because the age is the
     * useful part: a rider told "radar 24 min old" knows the feed stalled and roughly
     * when, and can weigh that against what they can see out of their own eyes.
     */
    private fun staleData(frameAgeSeconds: Long): RainMessage {
        val minutes = (frameAgeSeconds / 60.0).roundToInt()
        return RainMessage(
            severity = Severity.UNKNOWN,
            headline = "Radar $minutes min old",
            headlineShort = "Stale ${minutes}m",
            headlineMinimal = "--",
            details = listOf(RainMessage.Detail("too old to forecast", "stale")),
            qualifier = null,
            qualifierMark = null,
            alertTitle = "Radar data stale",
            alertDetail = null,
        )
    }

    private fun clear(forecast: RainForecast): RainMessage {
        val horizon = forecast.horizonMinutes.roundToInt()
        return RainMessage(
            severity = Severity.CLEAR,
            // Bounded rather than absolute: "clear" alone would imply we looked further
            // than we did.
            headline = if (horizon > 0) "Clear for ${horizon} min" else "No rain nearby",
            headlineShort = "Clear",
            headlineMinimal = "OK",
            details = emptyList(),
            qualifier = null,
            qualifierMark = null,
            alertTitle = "Clear",
            alertDetail = null,
        )
    }

    private fun fromEncounter(
        forecast: RainForecast,
        encounter: Encounter,
        units: DisplayUnits,
        evasion: EvasionEvaluator.Advice,
        home: HomeEvaluator.HomeAdvice?,
    ): RainMessage {
        val noun = precipNoun(encounter.type)
        val eta = encounter.etaMinutes.roundToInt()
        val approximate = forecast.confidence == RiderState.Confidence.LOW ||
            forecast.confidence == RiderState.Confidence.MEDIUM

        val headline = when {
            eta <= 0 -> "$noun now"
            approximate -> "$noun in ~$eta min"
            else -> "$noun in $eta min"
        }
        val headlineShort = if (eta <= 0) "$noun now" else "$noun ${eta}m"
        val headlineMinimal = if (eta <= 0) "now" else "${eta}m"

        val details = mutableListOf<RainMessage.Detail>()

        // Intensity and duration on one line: they are read together. The short form
        // keeps the intensity when the duration will not fit, because how hard it will
        // rain matters more than for how long.
        val intensity = encounter.intensity.label()
        val duration = encounter.durationMinutes?.roundToInt()
        details.add(
            when {
                duration != null && duration > 0 ->
                    RainMessage.Detail("$intensity, ~$duration min", intensity)
                duration != null -> RainMessage.Detail(intensity)
                // Null duration means it ran past the horizon, which is a real fact and
                // more useful than an invented number.
                else -> RainMessage.Detail("$intensity, ongoing", intensity)
            }
        )

        if (encounter.possibleHail) {
            details.add(RainMessage.Detail("possible hail", "hail?"))
        }

        // Getting home outranks a plain detour. Told "head NE to stay dry" when NE is
        // away from home, the rider has been answered a question they did not ask.
        val homeLine = EvasionPhrasing.describeHome(home)
        if (homeLine != null) {
            details.add(homeLine)
        } else {
            EvasionPhrasing.describe(evasion)?.let { details.add(it) }
        }

        // The nearest cell is worth a line when it is not the one about to hit, because
        // it tells the rider something the ETA does not.
        forecast.nearest?.let { near ->
            if (eta > 2) {
                val distance = formatDistance(near.distanceMetres, units)
                details.add(
                    RainMessage.Detail("nearest $distance ${near.compass}", "$distance ${near.compass}")
                )
            }
        }

        val severity = severityOf(encounter.intensity, encounter.possibleHail)

        val alertTitle = when {
            encounter.possibleHail && eta <= 0 -> "Possible hail now"
            encounter.possibleHail -> "Possible hail in $eta min"
            eta <= 0 -> "$noun now"
            else -> "$noun in $eta min"
        }
        val alertDetail = buildString {
            append(intensity)
            if (duration != null && duration > 0) append(", lasts ~$duration min")
            if (encounter.possibleHail) append(" · possible hail")
            // Only actionable advice goes in the alert. "No better route" is worth a
            // line in the field, where the rider chose to look, but not worth the
            // full-screen interruption they did not.
            when {
                homeLine != null && home is HomeEvaluator.HomeAdvice.TurnAroundWithin ->
                    append(
                        if (home.minutes <= 0.0) " · turn for home now"
                        else " · turn home within ${home.minutes.roundToInt()} min"
                    )
                else -> when (evasion) {
                    is EvasionEvaluator.Advice.Detour -> append(" · head ${evasion.compass}")
                    is EvasionEvaluator.Advice.Wait ->
                        append(" · wait ${evasion.waitMinutes.roundToInt()} min")
                    is EvasionEvaluator.Advice.TurnBack -> append(" · turn back")
                    is EvasionEvaluator.Advice.NoGoodOption -> append(" · no way round")
                    else -> Unit
                }
            }
        }

        return RainMessage(
            severity = severity,
            headline = headline,
            headlineShort = headlineShort,
            headlineMinimal = headlineMinimal,
            details = details,
            qualifier = qualifierFor(forecast.confidence),
            qualifierMark = qualifierMarkFor(forecast.confidence),
            alertTitle = alertTitle,
            alertDetail = alertDetail,
        )
    }

    /**
     * There is rain about, but the rider's path does not meet it inside the horizon —
     * or there is no usable heading to project along.
     *
     * The distance still holds either way, which is why it is the fallback: it needs no
     * assumption about where the rider is going.
     */
    private fun fromNearestOnly(
        forecast: RainForecast,
        nearest: NearestRain,
        units: DisplayUnits,
    ): RainMessage {
        val noun = precipNoun(nearest.type)
        val distance = formatDistance(nearest.distanceMetres, units)

        val details = mutableListOf<RainMessage.Detail>()
        details.add(
            RainMessage.Detail("${nearest.intensity.label()}, off path", "off path")
        )
        if (nearest.possibleHail) {
            details.add(RainMessage.Detail("possible hail", "hail?"))
        }

        return RainMessage(
            severity = Severity.CLEAR,
            headline = "$noun $distance ${nearest.compass}",
            headlineShort = "$distance ${nearest.compass}",
            headlineMinimal = nearest.compass,
            details = details,
            qualifier = qualifierFor(forecast.confidence),
            qualifierMark = qualifierMarkFor(forecast.confidence),
            alertTitle = "$noun nearby",
            alertDetail = "$distance ${nearest.compass}, not on your path",
        )
    }

    private fun precipNoun(type: DbzPalette.PrecipType): String = when (type) {
        DbzPalette.PrecipType.SNOW -> "Snow"
        else -> "Rain"
    }

    private fun severityOf(
        intensity: DbzPalette.Intensity,
        possibleHail: Boolean,
    ): Severity = when {
        possibleHail -> Severity.HAIL
        intensity == DbzPalette.Intensity.HEAVY -> Severity.HEAVY
        intensity == DbzPalette.Intensity.MODERATE -> Severity.MODERATE
        intensity == DbzPalette.Intensity.LIGHT -> Severity.LIGHT
        else -> Severity.CLEAR
    }

    /**
     * Confidence wording.
     *
     * High confidence gets no qualifier at all: adding "likely" to every message would
     * make the word meaningless exactly when it needs to carry weight.
     */
    private fun qualifierFor(confidence: RiderState.Confidence): String? = when (confidence) {
        RiderState.Confidence.HIGH -> null
        RiderState.Confidence.MEDIUM -> "approx"
        RiderState.Confidence.LOW -> "uncertain"
        RiderState.Confidence.NONE -> null
    }

    /**
     * One-character confidence marker for fields too narrow for a word.
     *
     * "~" for an approximate figure and "?" for an uncertain one read at a glance and
     * cost a single character, which on a quarter-width field is the difference between
     * showing the confidence and dropping it entirely.
     */
    private fun qualifierMarkFor(confidence: RiderState.Confidence): String? =
        when (confidence) {
            RiderState.Confidence.MEDIUM -> "~"
            RiderState.Confidence.LOW -> "?"
            else -> null
        }

    /**
     * Distance in the rider's units.
     *
     * Sub-kilometre distances get metres because "0.4 km" reads worse than "400 m" at a
     * glance, and at that range the exact figure starts to matter.
     */
    internal fun formatDistance(metres: Double, units: DisplayUnits): String = when (units) {
        DisplayUnits.METRIC ->
            if (metres < 1000) "${(metres / 50).roundToInt() * 50} m"
            else "${round1(metres / 1000.0)} km"

        DisplayUnits.IMPERIAL -> {
            val miles = metres / 1609.344
            if (miles < 1.0) "${(metres / 1609.344 * 1760 / 50).roundToInt() * 50} yd"
            else "${round1(miles)} mi"
        }
    }

    private fun round1(value: Double): String {
        val rounded = (value * 10).roundToInt() / 10.0
        return if (rounded == rounded.toInt().toDouble()) "${rounded.toInt()}"
        else "$rounded"
    }
}

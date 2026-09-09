package si.plahutar.raincall.forecast

import kotlin.math.roundToInt

/**
 * Turns [EvasionEvaluator.Advice] into one of a small set of fixed phrasings.
 *
 * Fixed shapes rather than generated prose. On a screen read at speed, a rider who has
 * seen the same handful of phrasings before can parse them at a glance; novelty costs
 * comprehension and buys nothing.
 *
 * Every phrasing has a short form, because these share a data field with the ETA and
 * are the first thing to be squeezed out when space is tight.
 */
object EvasionPhrasing {

    /**
     * Advice about getting home, which takes precedence over a plain detour.
     *
     * A rider has a destination, not merely a wish to stay dry. Told "head NE to stay
     * dry" when NE is away from home, they have been given an answer to a question they
     * did not ask. So when home is a live consideration, this line wins the space.
     */
    fun describeHome(advice: HomeEvaluator.HomeAdvice?): RainMessage.Detail? = when (advice) {
        null,
        is HomeEvaluator.HomeAdvice.OutOfRange,
        // Nothing to say when home is comfortably reachable: a deadline that is not
        // really a deadline is noise, and it would occupy the line a real one needs.
        is HomeEvaluator.HomeAdvice.Comfortable -> null

        is HomeEvaluator.HomeAdvice.TurnAroundWithin ->
            if (advice.minutes <= 0.0) {
                RainMessage.Detail("turn for home now", "home now")
            } else {
                val minutes = advice.minutes.roundToInt()
                RainMessage.Detail("turn home within $minutes min", "home in $minutes m")
            }

        is HomeEvaluator.HomeAdvice.WetWhateverYouDo -> {
            val wet = advice.wetMinutes.roundToInt()
            RainMessage.Detail("wet home whenever, $wet min", "wet home")
        }
    }

    /**
     * The line to show, or null when there is nothing useful to say.
     *
     * Null for "no rain" on purpose: printing "clear if you continue" when there is no
     * rain anywhere would be reassurance about a danger that was never there, and it
     * would occupy a line that the rest of the message can use.
     */
    fun describe(advice: EvasionEvaluator.Advice): RainMessage.Detail? = when (advice) {
        is EvasionEvaluator.Advice.NoRain -> null
        is EvasionEvaluator.Advice.Unknown -> null

        is EvasionEvaluator.Advice.ContinueIsBest ->
            RainMessage.Detail("no better route", "no detour")

        is EvasionEvaluator.Advice.Detour -> RainMessage.Detail(
            "head ${advice.compass} to stay dry",
            "go ${advice.compass}",
        )

        is EvasionEvaluator.Advice.Wait -> {
            val minutes = advice.waitMinutes.roundToInt()
            RainMessage.Detail("wait $minutes min, it passes", "wait $minutes min")
        }

        is EvasionEvaluator.Advice.TurnBack ->
            RainMessage.Detail("turn back to stay dry", "turn back")

        // Being honest that there is no way out matters more than sounding useful. An
        // app that always finds a solution stops being believed the first time the
        // solution is wrong.
        is EvasionEvaluator.Advice.NoGoodOption -> {
            val minutes = advice.wetMinutes.roundToInt()
            RainMessage.Detail("no way round, $minutes+ min", "no way round")
        }
    }
}

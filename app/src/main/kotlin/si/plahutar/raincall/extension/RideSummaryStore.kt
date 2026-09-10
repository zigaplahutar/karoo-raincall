package si.plahutar.raincall.extension

import android.content.Context
import si.plahutar.raincall.forecast.RideSummaryTracker

/**
 * Keeps the last ride's summary so it can be read after the ride is over.
 *
 * The tracker lives in the extension service and its numbers are wiped when a ride ends,
 * which is exactly the moment the summary becomes interesting. Persisting the finished
 * lines is the whole of what is needed — the summary is a handful of strings, not a
 * dataset — and it means the about screen can show it without reaching into a running
 * service.
 *
 * The value is deliberately over time rather than in the moment: a rider who can see the
 * forecast was right the last five times will believe the sixth.
 */
class RideSummaryStore(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    companion object {
        private const val NAME = "raincall-ride-summary"
        private const val KEY_LINES = "lines"
        private const val KEY_FINISHED_AT = "finishedAt"

        /** Separator that cannot occur in a composed summary line. */
        private const val SEPARATOR = "\n"
    }

    /** Store a finished ride. */
    fun save(summary: RideSummaryTracker.Summary, finishedAtMillis: Long) {
        val lines = summary.lines()
        if (lines.isEmpty()) return
        prefs.edit()
            .putString(KEY_LINES, lines.joinToString(SEPARATOR))
            .putLong(KEY_FINISHED_AT, finishedAtMillis)
            .apply()
    }

    /** The last ride's summary lines, or empty if there has not been one. */
    fun lastSummaryLines(): List<String> =
        prefs.getString(KEY_LINES, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /** When that ride finished, or 0. */
    fun lastFinishedAtMillis(): Long = prefs.getLong(KEY_FINISHED_AT, 0L)
}

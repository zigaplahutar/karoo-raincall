package si.plahutar.raincall.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import si.plahutar.raincall.R
import si.plahutar.raincall.extension.RideSummaryStore
import si.plahutar.raincall.radar.RainViewerApi

/**
 * The about screen: attribution, and how the last ride went.
 *
 * This screen exists for two reasons, and the first is not optional. RainViewer's free
 * terms of use require the attribution to be visible in the app, and RainCall is built
 * entirely on their data. Until this screen existed the attribution string sat in
 * `strings.xml` referenced by nothing at all — the requirement documented, and quietly
 * unmet.
 *
 * The second reason is the post-ride summary, which had the same problem from the other
 * end: it was computed every thirty seconds throughout every ride and had nowhere to go.
 *
 * Built from plain views rather than Compose. The project pulls in Glance for the data
 * field, but not `activity-compose`, and one static screen is not worth a dependency.
 */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(heading(getString(R.string.app_name)))
        root.addView(body(getString(R.string.about_blurb)))

        addRideSummary(root)

        // Mandatory, and last so it is the thing left on screen.
        root.addView(spacer())
        root.addView(heading(getString(R.string.about_data_source)))
        root.addView(
            body(RainViewerApi.ATTRIBUTION_TEXT).apply {
                setTextColor(Color.parseColor("#1565C0"))
                setOnClickListener { openRainViewer() }
            }
        )
        root.addView(body(RainViewerApi.ATTRIBUTION_URL))

        setContentView(
            ScrollView(this).apply {
                addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        )
    }

    private fun addRideSummary(root: LinearLayout) {
        val store = RideSummaryStore(this)
        val lines = store.lastSummaryLines()
        if (lines.isEmpty()) return

        root.addView(spacer())
        root.addView(heading(getString(R.string.about_last_ride)))
        lines.forEach { root.addView(body(it)) }
    }

    private fun openRainViewer() {
        // A Karoo may have no browser at all, so a missing one must not crash the screen
        // whose entire job is to keep the attribution visible.
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RainViewerApi.ATTRIBUTION_URL)))
        }.onFailure { if (it !is ActivityNotFoundException) throw it }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(Color.BLACK)
        gravity = Gravity.START
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.parseColor("#424242"))
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun spacer() = TextView(this).apply { setPadding(0, dp(8), 0, 0) }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
        ).toInt()
}

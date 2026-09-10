package si.plahutar.raincall.extension

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.plahutar.raincall.forecast.DisplayUnits
import si.plahutar.raincall.forecast.RainForecast
import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.radar.DbzPalette
import si.plahutar.raincall.radar.RadarField
import si.plahutar.raincall.radar.RadarSource
import si.plahutar.raincall.radar.RainViewerApi
import si.plahutar.raincall.radar.TileMath

/**
 * The polling loop, which is where the app's worst bug lived and where nothing was
 * tested at all.
 *
 * These run against a fake RainViewer on virtual time, so a simulated hour of riding
 * costs milliseconds. That matters: the defect below only appears after the second poll,
 * which is six minutes into a ride, and no amount of reading the method made it obvious.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForecastPipelineTest {

    private val range = TileMath.TileRange(
        zoom = 8, tileSize = 512,
        minTileX = 137, maxTileX = 138, minTileY = 90, maxTileY = 91,
    )

    /**
     * A RainViewer that publishes a new frame every ten minutes, as the real one does.
     *
     * Counts calls, because the bug is not a wrong answer — every individual answer was
     * correct — but a call that stops being made.
     */
    private class FakeRadar(
        private val nowMillis: () -> Long,
        private val range: TileMath.TileRange,
    ) : RadarSource {
        var indexFetches = 0
        var fieldFetches = 0
        var cleared = 0

        /** Frame times handed out so far, newest last. */
        val servedFrames = mutableListOf<Long>()

        private fun newestFrameSeconds(): Long {
            val minutes = nowMillis() / 60_000
            return (minutes / 10) * 10 * 60
        }

        override suspend fun fetchIndex(): RainViewerApi.Index {
            indexFetches++
            val newest = newestFrameSeconds()
            return RainViewerApi.Index(
                host = "https://tilecache.example",
                radar = RainViewerApi.Radar(
                    past = listOf(
                        RainViewerApi.Frame(newest - 600, "/p0"),
                        RainViewerApi.Frame(newest, "/p1"),
                    ),
                ),
            )
        }

        override suspend fun fetchField(
            index: RainViewerApi.Index,
            frame: RainViewerApi.Frame,
            longitude: Double,
            latitude: Double,
        ): RadarField {
            fieldFetches++
            servedFrames.add(frame.time)
            // A field with a little rain in it, so the forecaster has something to chew.
            val f = RadarField.empty(1024, 1024, range, frame.time)
            for (y in 0 until 40) {
                for (x in 0 until 40) {
                    f.set(x, y, DbzPalette.Sample(35, DbzPalette.PrecipType.RAIN))
                }
            }
            return f
        }

        override fun clear() {
            cleared++
        }
    }

    private fun rider() = RiderState(
        timestampMillis = 0,
        latitude = 46.0569,
        longitude = 14.5058,
        accuracyMetres = 5.0,
        speedMetresPerSecond = 8.0,
        headingDegrees = 90.0,
        pathCurvature = 0.0,
        riding = true,
    )

    @Test
    fun `the frame index is refetched every cycle, so new frames are noticed`() = runTest {
        // The bug: the index was cached and only invalidated on the branch that fetched a
        // new frame. The "nothing new published yet" branch returned early with it still
        // cached, so from the second poll onwards the same index was reused forever, its
        // newest frame never changed, and the radar froze on whichever frame the ride
        // started with. Over an hour that is one download and a frame ageing to 60
        // minutes, at which point the advection carries every lookup out of the window
        // and the app reports a clear sky in the rain.
        val radar = FakeRadar({ currentTime }, range)
        val pipeline = ForecastPipeline(
            repository = radar,
            riderStates = MutableStateFlow(rider()),
            units = { DisplayUnits.METRIC },
            onAlert = {},
            clock = { currentTime },
        )

        pipeline.start(backgroundScope)
        advanceTimeBy(60 * 60 * 1000L)

        val expectedPolls = 60 / 3
        assertTrue(
            "expected roughly one index fetch per 3-minute poll, got ${radar.indexFetches}",
            radar.indexFetches >= expectedPolls - 1,
        )
        assertTrue(
            "an hour of riding should pull more than one radar frame, got ${radar.fieldFetches}",
            radar.fieldFetches >= 6,
        )
    }

    @Test
    fun `a frame published mid-ride is picked up`() = runTest {
        val radar = FakeRadar({ currentTime }, range)
        val pipeline = ForecastPipeline(
            repository = radar,
            riderStates = MutableStateFlow(rider()),
            units = { DisplayUnits.METRIC },
            onAlert = {},
            clock = { currentTime },
        )

        pipeline.start(backgroundScope)
        advanceTimeBy(35 * 60 * 1000L)

        val distinct = radar.servedFrames.distinct()
        assertTrue(
            "35 minutes spans three publishing intervals; saw frames $distinct",
            distinct.size >= 3,
        )
    }

    @Test
    fun `the forecast keeps up rather than ageing away`() = runTest {
        val radar = FakeRadar({ currentTime }, range)
        val pipeline = ForecastPipeline(
            repository = radar,
            riderStates = MutableStateFlow(rider()),
            units = { DisplayUnits.METRIC },
            onAlert = {},
            clock = { currentTime },
        )

        pipeline.start(backgroundScope)
        advanceTimeBy(60 * 60 * 1000L)

        // With the index frozen this climbed past an hour and the forecast quietly
        // became fiction.
        val age = pipeline.forecasts.value.frameAgeSeconds
        assertTrue(
            "frame age ${age}s should stay inside the usable window after an hour",
            age <= RainViewerApi.MAX_USABLE_FRAME_AGE_SECONDS,
        )
    }

    @Test
    fun `reset clears the cached frame and the tile cache`() = runTest {
        val radar = FakeRadar({ currentTime }, range)
        val pipeline = ForecastPipeline(
            repository = radar,
            riderStates = MutableStateFlow(rider()),
            units = { DisplayUnits.METRIC },
            onAlert = {},
            clock = { currentTime },
        )

        pipeline.start(backgroundScope)
        advanceTimeBy(5 * 60 * 1000L)
        assertNotNull(pipeline.forecasts.value)

        pipeline.reset()
        assertEquals("the repository cache must be dropped too", 1, radar.cleared)

        // Nothing in hand any more, so the next recompute must say so rather than
        // reusing the previous ride's frame.
        advanceTimeBy(ForecastPipeline.RECOMPUTE_MILLIS + 1)
        assertEquals(
            "a fresh ride must not inherit the last one's radar",
            RainForecast.Availability.NO_RADAR,
            pipeline.forecasts.value.availability,
        )
    }
}

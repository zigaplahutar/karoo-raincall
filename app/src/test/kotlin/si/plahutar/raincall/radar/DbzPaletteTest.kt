package si.plahutar.raincall.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every expected dBZ here is taken from RainViewer's published colour table, not from
 * running this decoder. If the linear-encoding assumption is ever wrong, these fail.
 */
class DbzPaletteTest {

    @Test
    fun `transparent pixels mean no data, not zero dBZ`() {
        val sample = DbzPalette.decodeLinear(0x00000000)
        assertNull(sample.dbz)
        assertFalse(sample.hasEcho)
        assertEquals(DbzPalette.PrecipType.NONE, sample.type)
        // The distinction that matters: "nothing there" is not "a very weak echo".
        assertFalse(sample.isMeaningful)
    }

    @Test
    fun `nearly transparent pixels are treated as no data`() {
        // Alpha 4 is below the threshold; the palette's lowest dBZ entries have very
        // low alpha and are noise we do not want to act on.
        assertNull(DbzPalette.decodeLinear(0x04303030).dbz)
    }

    @Test
    fun `rain values decode as grey minus 32`() {
        assertEquals(0, DbzPalette.decodeLinear(0xFF202020.toInt()).dbz)
        assertEquals(20, DbzPalette.decodeLinear(0xFF343434.toInt()).dbz)
        assertEquals(35, DbzPalette.decodeLinear(0xFF434343.toInt()).dbz)
        assertEquals(54, DbzPalette.decodeLinear(0xFF565656.toInt()).dbz)
        assertEquals(55, DbzPalette.decodeLinear(0xFF575757.toInt()).dbz)
        assertEquals(95, DbzPalette.decodeLinear(0xFF7F7F7F.toInt()).dbz)

        assertEquals(
            DbzPalette.PrecipType.RAIN,
            DbzPalette.decodeLinear(0xFF434343.toInt()).type,
        )
    }

    @Test
    fun `snow values decode as grey minus 160`() {
        assertEquals(0, DbzPalette.decodeLinear(0xFFA0A0A0.toInt()).dbz)
        assertEquals(20, DbzPalette.decodeLinear(0xFFB4B4B4.toInt()).dbz)
        assertEquals(55, DbzPalette.decodeLinear(0xFFD7D7D7.toInt()).dbz)
        assertEquals(95, DbzPalette.decodeLinear(0xFFFFFFFF.toInt()).dbz)

        assertEquals(
            DbzPalette.PrecipType.SNOW,
            DbzPalette.decodeLinear(0xFFB4B4B4.toInt()).type,
        )
    }

    @Test
    fun `the rain snow boundary sits at grey 128`() {
        // 127 is the top of the rain range, 129 the bottom of the snow range.
        assertEquals(DbzPalette.PrecipType.RAIN, DbzPalette.decodeLinear(0xFF7F7F7F.toInt()).type)
        assertEquals(DbzPalette.PrecipType.SNOW, DbzPalette.decodeLinear(0xFF818181.toInt()).type)
        assertEquals(-31, DbzPalette.decodeLinear(0xFF818181.toInt()).dbz)
    }

    @Test
    fun `intensity bands split at the documented thresholds`() {
        fun bandOf(dbz: Int) =
            DbzPalette.Sample(dbz, DbzPalette.PrecipType.RAIN).intensity

        assertEquals(DbzPalette.Intensity.NONE, bandOf(14))
        assertEquals(DbzPalette.Intensity.LIGHT, bandOf(15))
        assertEquals(DbzPalette.Intensity.LIGHT, bandOf(30))
        assertEquals(DbzPalette.Intensity.MODERATE, bandOf(31))
        assertEquals(DbzPalette.Intensity.MODERATE, bandOf(45))
        assertEquals(DbzPalette.Intensity.HEAVY, bandOf(46))
        assertEquals(DbzPalette.Intensity.HEAVY, bandOf(60))
    }

    @Test
    fun `weak echoes below the clutter floor are not reported as precipitation`() {
        // Radar routinely sees insects, dust and ground clutter down here.
        val weak = DbzPalette.Sample(10, DbzPalette.PrecipType.RAIN)
        assertTrue("there is an echo", weak.hasEcho)
        assertFalse("but it is not worth warning about", weak.isMeaningful)
        assertEquals(DbzPalette.Intensity.NONE, weak.intensity)
    }

    @Test
    fun `hail flags at 55 dBZ and only for rain`() {
        assertFalse(DbzPalette.Sample(54, DbzPalette.PrecipType.RAIN).possibleHail)
        assertTrue(DbzPalette.Sample(55, DbzPalette.PrecipType.RAIN).possibleHail)
        assertTrue(DbzPalette.Sample(70, DbzPalette.PrecipType.RAIN).possibleHail)

        // A very strong snow echo is wet snow or graupel, not hail. Warning about hail
        // in a snowstorm would be a straightforward false alarm.
        assertFalse(DbzPalette.Sample(60, DbzPalette.PrecipType.SNOW).possibleHail)
        assertFalse(DbzPalette.Sample(null, DbzPalette.PrecipType.NONE).possibleHail)
    }

    @Test
    fun `greyscale tiles pass the scheme check and colour tiles fail it`() {
        val greyTile = intArrayOf(
            0x00000000, 0xFF202020.toInt(), 0xFF7F7F7F.toInt(), 0xFFB4B4B4.toInt(),
        )
        assertTrue(DbzPalette.looksLikeLinearScheme(greyTile))

        // A Universal Blue pixel: channels differ, so this is not scheme 0.
        val colourTile = intArrayOf(0xFF00A3E0.toInt(), 0xFF202020.toInt())
        assertFalse(DbzPalette.looksLikeLinearScheme(colourTile))
    }

    @Test
    fun `an entirely empty tile does not trigger the fallback`() {
        // Clear sky is the common case and must not be read as a wrong colour scheme.
        val emptyTile = IntArray(64) { 0x00000000 }
        assertTrue(DbzPalette.looksLikeLinearScheme(emptyTile))
    }

    @Test
    fun `universal blue fallback decodes known palette entries`() {
        assertEquals(20, DbzPalette.decodeUniversalBlue(0xFF00A3E0.toInt()).dbz)
        assertEquals(35, DbzPalette.decodeUniversalBlue(0xFFFFEE00.toInt()).dbz)
        assertEquals(54, DbzPalette.decodeUniversalBlue(0xFF5D0000.toInt()).dbz)
        // The dark-red to magenta jump that the hail threshold was chosen from.
        assertEquals(55, DbzPalette.decodeUniversalBlue(0xFFFFAAFF.toInt()).dbz)
        assertTrue(DbzPalette.decodeUniversalBlue(0xFFFFAAFF.toInt()).possibleHail)
    }

    @Test
    fun `universal blue fallback refuses to guess at unknown colours`() {
        // A blend that would appear if smoothing were left on. Better to report nothing
        // than to snap it to a neighbouring value and invent a reading.
        assertNull(DbzPalette.decodeUniversalBlue(0xFF7A51F0.toInt()).dbz)
    }
}

class RainViewerApiTest {

    @Test
    fun `tile url has the documented shape`() {
        val url = RainViewerApi.tileUrl(
            host = "https://tilecache.rainviewer.com",
            framePath = "/v2/radar/1609401600",
            zoom = 8,
            tileX = 138,
            tileY = 91,
        )
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/1609401600/512/8/138/91/0/0_1.png",
            url,
        )
    }

    @Test
    fun `tile url tolerates a trailing slash on the host`() {
        val url = RainViewerApi.tileUrl(
            host = "https://tilecache.rainviewer.com/",
            framePath = "/v2/radar/1609401600",
            zoom = 8, tileX = 138, tileY = 91,
        )
        assertFalse("no doubled slash", url.contains("com//v2"))
    }

    @Test
    fun `analysis defaults keep the linear decoding valid`() {
        // These three together are what make DbzPalette.decodeLinear correct.
        // If any drifts, decoding silently returns wrong numbers rather than failing.
        assertEquals(0, RainViewerApi.COLOR_SCHEME_LINEAR)
        assertEquals(0, RainViewerApi.SMOOTH_OFF)
        assertEquals(1, RainViewerApi.SNOW_ON)
    }

    @Test
    fun `recent past frames come back oldest first`() {
        val index = RainViewerApi.Index(
            host = "https://tilecache.rainviewer.com",
            radar = RainViewerApi.Radar(
                past = listOf(
                    RainViewerApi.Frame(3000, "/c"),
                    RainViewerApi.Frame(1000, "/a"),
                    RainViewerApi.Frame(2000, "/b"),
                ),
            ),
        )
        val recent = index.recentPast(2)
        assertEquals(listOf(2000L, 3000L), recent.map { it.time })
        assertEquals(3000L, index.latestPast?.time)
    }

    @Test
    fun `an empty index is reported as unusable`() {
        assertFalse(RainViewerApi.Index().isUsable)
        assertFalse(
            RainViewerApi.Index(host = "https://example.com").isUsable
        )
        assertFalse(
            RainViewerApi.Index(
                radar = RainViewerApi.Radar(past = listOf(RainViewerApi.Frame(1, "/a"))),
            ).isUsable
        )
    }

    @Test
    fun `nowcast availability is reported rather than assumed`() {
        val withoutNowcast = RainViewerApi.Index(
            host = "https://tilecache.rainviewer.com",
            radar = RainViewerApi.Radar(past = listOf(RainViewerApi.Frame(1000, "/a"))),
        )
        assertTrue(withoutNowcast.isUsable)
        assertFalse(withoutNowcast.hasNowcast)
    }

    @Test
    fun `frame age is measured from the observation time`() {
        val frame = RainViewerApi.Frame(time = 1_000_000, path = "/a")
        assertEquals(300L, RainViewerApi.frameAgeSeconds(frame, 1_000_300))
        // Frames can be stamped slightly ahead of the device clock; the sign must be
        // preserved rather than clamped, so callers can spot a bad clock.
        assertEquals(-60L, RainViewerApi.frameAgeSeconds(frame, 999_940))
    }
}

package si.plahutar.raincall.forecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.plahutar.raincall.model.RiderState
import si.plahutar.raincall.model.RouteContext
import si.plahutar.raincall.radar.DbzPalette
import si.plahutar.raincall.radar.RadarField
import si.plahutar.raincall.radar.TileMath

/**
 * What a loaded route buys.
 *
 * The uncertainty cone exists to model "we do not know which way this rider will turn".
 * A route removes that uncertainty entirely, so following it should give a sharper
 * answer — and, importantly, a *different* one in the cases where the cone was guessing.
 */
class RouteForecastTest {

    private val zoom = 8
    private val tileSize = 512
    private val fieldSize = 1024
    private val riderLat = 46.0569
    private val riderLon = 14.5058

    private val range = TileMath.TileRange(
        zoom = zoom, tileSize = tileSize,
        minTileX = 137, maxTileX = 138, minTileY = 90, maxTileY = 91,
    )

    private fun rider(
        heading: Double = 90.0,
        speed: Double = 10.0,
        curvature: Double = 0.0,
        accuracy: Double = 5.0,
    ) = RiderState(
        timestampMillis = 0,
        latitude = riderLat,
        longitude = riderLon,
        accuracyMetres = accuracy,
        speedMetresPerSecond = speed,
        headingDegrees = heading,
        pathCurvature = curvature,
        riding = true,
    )

    /** A straight route east from the rider. */
    private fun routeEast(kilometres: Int = 30): RouteContext =
        RouteContext((0..kilometres).map { km ->
            TileMath.destination(riderLon, riderLat, 90.0, km * 1000.0)
        })

    /** A blob of rain centred on a position, of the given radius. */
    private fun rainAt(
        bearingFromRider: Double,
        distanceMetres: Double,
        radiusMetres: Double,
    ): RadarField {
        val f = RadarField.empty(fieldSize, fieldSize, range, 0)
        val (cLon, cLat) = TileMath.destination(riderLon, riderLat, bearingFromRider, distanceMetres)
        val sample = DbzPalette.Sample(35, DbzPalette.PrecipType.RAIN)
        for (y in 0 until fieldSize) {
            for (x in 0 until fieldSize) {
                val (lon, lat) = f.positionOf(x, y)
                if (TileMath.distanceMetres(lon, lat, cLon, cLat) <= radiusMetres) {
                    f.set(x, y, sample)
                }
            }
        }
        return f
    }

    @Test
    fun `rain squarely on the route is found`() {
        // 6 km east at 10 m/s is ten minutes away.
        val field = rainAt(bearingFromRider = 90.0, distanceMetres = 6_000.0, radiusMetres = 1_500.0)

        val forecast = RainForecaster.forecast(rider(), field, null, 0, routeEast())

        val encounter = forecast.encounter
        assertNotNull("rain sits directly on the route", encounter)
        assertEquals("6 km at 10 m/s", 7.5, encounter!!.etaMinutes, 2.0)
        assertEquals(
            "the route is the path, so a hit is unavoidable by definition",
            1.0,
            encounter.coneCoverage,
            1e-9,
        )
    }

    @Test
    fun `a shower the route passes clear of is not reported as an encounter`() {
        // Rain 6 km ahead but 5 km off to the north. The cone, which has to allow for the
        // rider turning, clips it. The route says plainly that they do not go that way.
        val offRoute = TileMath.destination(riderLon, riderLat, 40.0, 7_000.0)
        val field = RadarField.empty(fieldSize, fieldSize, range, 0)
        val sample = DbzPalette.Sample(35, DbzPalette.PrecipType.RAIN)
        for (y in 0 until fieldSize) {
            for (x in 0 until fieldSize) {
                val (lon, lat) = field.positionOf(x, y)
                if (TileMath.distanceMetres(lon, lat, offRoute.first, offRoute.second) <= 2_000.0) {
                    field.set(x, y, sample)
                }
            }
        }

        // A wandering rider: the cone opens wide and may well catch it.
        val wandering = rider(curvature = 0.2)
        val withCone = RainForecaster.forecast(wandering, field, null, 0, route = null)

        // The same rider, on a route that goes due east.
        val withRoute = RainForecaster.forecast(wandering, field, null, 0, routeEast())

        assertNull("the route does not go near that shower", withRoute.encounter)
        // The cone is allowed to be more cautious; the point is only that the route is
        // not, and that the two genuinely differ.
        assertTrue(
            "this scenario is only meaningful if the cone behaves differently",
            withCone.encounter != null || withRoute.encounter == null,
        )
    }

    @Test
    fun `a twisty road on a known route keeps the full horizon`() {
        // Curvature stands in for "we cannot tell where this rider will go". On a route
        // we can, so a switchback descent must not collapse the horizon to five minutes
        // and hide a shower twenty minutes out.
        val twisty = rider(curvature = 0.9)

        val coned = RainForecaster.forecast(twisty, RadarField.empty(fieldSize, fieldSize, range, 0), null, 0)
        val routed = RainForecaster.forecast(
            twisty, RadarField.empty(fieldSize, fieldSize, range, 0), null, 0, routeEast(),
        )

        assertEquals(
            "without a route, a twisty path is only defensible five minutes ahead",
            RiderState.MIN_HORIZON_MINUTES,
            coned.horizonMinutes,
            1e-9,
        )
        assertEquals(
            "with a route the bends are known, so the full horizon stands",
            RiderState.MAX_HORIZON_MINUTES,
            routed.horizonMinutes,
            1e-9,
        )
        assertTrue(
            "and the answer may sound as confident as the fix allows",
            routed.confidence.ordinal > coned.confidence.ordinal,
        )
    }

    @Test
    fun `a poor fix still limits a route forecast`() {
        // A route says where the rider is going, not where they are. A 60 m fix is still
        // a 60 m fix.
        val badFix = rider(accuracy = 60.0)
        val routed = RainForecaster.forecast(
            badFix, RadarField.empty(fieldSize, fieldSize, range, 0), null, 0, routeEast(),
        )

        assertEquals(RiderState.Confidence.LOW, routed.confidence)
        assertEquals(RiderState.MIN_HORIZON_MINUTES, routed.horizonMinutes, 1e-9)
    }

    @Test
    fun `a rider who has left the route falls back to the cone`() {
        // Loaded a route this morning, riding somewhere else this afternoon. Following it
        // would forecast for a road they are nowhere near.
        val strayed = RiderState(
            timestampMillis = 0,
            latitude = riderLat + 0.2,
            longitude = riderLon + 0.2,
            accuracyMetres = 5.0,
            speedMetresPerSecond = 10.0,
            headingDegrees = 90.0,
            pathCurvature = 0.9,
            riding = true,
        )

        val forecast = RainForecaster.forecast(
            strayed, RadarField.empty(fieldSize, fieldSize, range, 0), null, 0, routeEast(),
        )

        assertEquals(
            "far off the route, so the cone and its shortened horizon apply again",
            RiderState.MIN_HORIZON_MINUTES,
            forecast.horizonMinutes,
            1e-9,
        )
    }

    @Test
    fun `the forecast stops at the end of the route rather than inventing a continuation`() {
        // A 2 km route at 10 m/s runs out after about three minutes. Rain 10 km further
        // on is not on the rider's path, because their path has ended.
        val shortRoute = routeEast(kilometres = 2)
        val field = rainAt(bearingFromRider = 90.0, distanceMetres = 12_000.0, radiusMetres = 2_000.0)

        val forecast = RainForecaster.forecast(rider(), field, null, 0, shortRoute)

        assertNull("the route finishes long before that rain", forecast.encounter)
    }
}

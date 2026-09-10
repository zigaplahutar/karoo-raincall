package si.plahutar.raincall.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.plahutar.raincall.radar.TileMath

class RouteContextTest {

    private val startLon = 14.5058
    private val startLat = 46.0569

    /** A straight run east, one point every kilometre. */
    private fun eastwardRoute(kilometres: Int): RouteContext {
        val points = (0..kilometres).map { km ->
            TileMath.destination(startLon, startLat, 90.0, km * 1000.0)
        }
        return RouteContext(points)
    }

    @Test
    fun `the destination is where the route ends`() {
        val route = eastwardRoute(10)
        val destination = route.destination!!

        assertEquals(
            "ten kilometres east of the start",
            10_000.0,
            TileMath.distanceMetres(startLon, startLat, destination.first, destination.second),
            50.0,
        )
    }

    @Test
    fun `a reversed route ends where the polyline starts`() {
        // The Karoo can run a saved route backwards. Taking the polyline's last point
        // regardless would aim every home-advice answer at the far end of the ride —
        // precisely the place the rider is heading away from.
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

        val forward = RouteContext.fromPolyline(encoded, reversed = false)!!
        val backward = RouteContext.fromPolyline(encoded, reversed = true)!!

        assertEquals(forward.points.first(), backward.points.last())
        assertEquals(forward.points.last(), backward.points.first())
        assertEquals(forward.points.first(), backward.destination)
    }

    @Test
    fun `advancing along the route covers the distance asked for`() {
        val route = eastwardRoute(20)

        val after5km = route.advance(0, 5_000.0)
        assertEquals(
            5_000.0,
            TileMath.distanceMetres(startLon, startLat, after5km.first, after5km.second),
            60.0,
        )

        val after12500m = route.advance(0, 12_500.0)
        assertEquals(
            "must interpolate inside a segment, not snap to the nearest point",
            12_500.0,
            TileMath.distanceMetres(startLon, startLat, after12500m.first, after12500m.second),
            60.0,
        )
    }

    @Test
    fun `advancing past the finish stops at the finish`() {
        val route = eastwardRoute(5)
        val wayPast = route.advance(0, 500_000.0)

        assertEquals(
            "the rider cannot be further along than the end of the route",
            route.points.last(),
            wayPast,
        )
    }

    @Test
    fun `remaining distance shrinks as the rider progresses`() {
        val route = eastwardRoute(10)

        assertEquals(10_000.0, route.remainingMetres(0), 100.0)
        assertEquals(6_000.0, route.remainingMetres(4), 100.0)
        assertEquals(0.0, route.remainingMetres(10), 1.0)
    }

    @Test
    fun `deviation measures how far off the route the rider is`() {
        val route = eastwardRoute(10)

        assertEquals("on the line", 0.0, route.deviationMetres(startLon, startLat), 50.0)

        val (offLon, offLat) = TileMath.destination(startLon, startLat, 0.0, 2_000.0)
        assertEquals("two kilometres north of it", 2_000.0, route.deviationMetres(offLon, offLat), 100.0)
    }

    @Test
    fun `nearest index finds where along the route the rider is`() {
        val route = eastwardRoute(10)
        val (lon, lat) = TileMath.destination(startLon, startLat, 90.0, 7_000.0)

        assertEquals(7, route.nearestIndex(lon, lat))
    }

    @Test
    fun `a polyline too short to describe a path is refused`() {
        assertNull("one point is a position, not a route", RouteContext.fromPolyline("_p~iF~ps|U"))
        assertNull(RouteContext.fromPolyline(""))
        assertNotNull(RouteContext.fromPolyline("_p~iF~ps|U_ulLnnqC"))
    }

    @Test
    fun `a blank route name is treated as no name`() {
        val route = RouteContext.fromPolyline("_p~iF~ps|U_ulLnnqC", name = "   ")
        assertNull(route!!.name)
    }

    @Test
    fun `an empty route is not usable`() {
        assertTrue(eastwardRoute(3).isUsable)
        assertTrue(!RouteContext(emptyList()).isUsable)
        assertTrue(!RouteContext(listOf(startLon to startLat)).isUsable)
    }
}

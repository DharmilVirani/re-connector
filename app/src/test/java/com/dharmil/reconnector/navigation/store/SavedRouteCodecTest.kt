package com.dharmil.reconnector.navigation.store

import com.dharmil.reconnector.navigation.GeoPoint
import com.dharmil.reconnector.navigation.Maneuver
import com.dharmil.reconnector.navigation.RoutePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SavedRouteCodecTest {
    @Test
    fun roundTripsRouteGeometryAndInstructions() {
        val route = RoutePlan(
            id = "route-1",
            destinationName = "Pune",
            points = listOf(GeoPoint(18.52, 73.85), GeoPoint(18.53, 73.86)),
            maneuvers = listOf(Maneuver(1, "Turn right", 250)),
        )

        val restored = SavedRouteCodec.decode(SavedRouteCodec.encode(route))

        assertEquals(route.id, restored.id)
        assertEquals(route.destinationName, restored.destinationName)
        assertEquals(route.points, restored.points)
        assertEquals(route.maneuvers, restored.maneuvers)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedCoordinatePairs() {
        SavedRouteCodec.decode(
            """{"version":1,"id":"r","destination":"Pune","points":[18.5,73.8,18.6],"maneuvers":[]}""",
        )
    }

    @Test
    fun rejectsNonIntegralFormatVersionInsteadOfCoercingIt() {
        assertThrows(IllegalArgumentException::class.java) {
            SavedRouteCodec.decode(
                """{"version":1.9,"id":"r","destination":"Pune","points":[18.5,73.8,18.6,73.9],"maneuvers":[]}""",
            )
        }
    }

    @Test
    fun skipsOneMalformedRouteWithoutLosingValidRoutes() {
        val valid = RoutePlan(
            id = "good-route",
            destinationName = "Pune",
            points = listOf(GeoPoint(18.52, 73.85), GeoPoint(18.53, 73.86)),
            maneuvers = emptyList(),
        )

        val listing = decodeSavedRouteEntries(
            listOf(
                "broken-route" to "{bad json",
                "good-route" to SavedRouteCodec.encode(valid),
            ),
        )

        assertEquals(listOf(valid.id), listing.routes.map(RoutePlan::id))
        assertEquals(1, listing.skippedInvalidCount)
    }
}

package com.dharmil.reconnector.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlanTest {
    private val route = RoutePlan(
        id = "fixture",
        destinationName = "Test",
        points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001), GeoPoint(0.001, 0.001)),
        maneuvers = listOf(Maneuver(2, "Turn left", 100)),
    )

    @Test
    fun includesRemainderOfCurrentLegAndFollowingLegs() {
        val progress = update(route, GeoPoint(0.0, 0.0008))

        assertEquals(0, progress?.segmentIndex)
        assertEquals(133.6, progress?.remainingMeters ?: 0.0, 1.0)
        assertEquals(0, progress?.nextManeuverIndex)
    }

    @Test
    fun finalLegKeepsItsRemainingDistance() {
        val progress = update(route, GeoPoint(0.0008, 0.001))

        assertEquals(1, progress?.segmentIndex)
        assertTrue((progress?.remainingMeters ?: 0.0) in 20.0..25.0)
        assertEquals(0, progress?.nextManeuverIndex)
    }

    @Test
    fun staleFixIsRejected() {
        val progress = RouteProgressCalculator.update(
            route,
            null,
            LocationFix(GeoPoint(0.0, 0.0008), 1_000),
            nowMillis = 7_000,
        )

        assertNull(progress)
    }

    @Test
    fun offRouteFixDoesNotAdvanceProgress() {
        val previous = update(route, GeoPoint(0.0, 0.0008)) ?: error("Expected progress")
        val progress = RouteProgressCalculator.update(
            route,
            previous,
            LocationFix(GeoPoint(0.01, 0.0008), 10_200, horizontalAccuracyMeters = 5.0),
            nowMillis = 10_200,
        )

        assertNotNull(progress)
        assertTrue(progress!!.isOffRoute)
        assertEquals(previous.distanceAlongRouteMeters, progress.distanceAlongRouteMeters, 0.0)
    }

    @Test
    fun progressDoesNotMoveBackwardWithinCurrentSegment() {
        val previous = update(route, GeoPoint(0.0, 0.0008)) ?: error("Expected progress")
        val progress = RouteProgressCalculator.update(
            route,
            previous,
            LocationFix(GeoPoint(0.0, 0.0005), 10_200),
            nowMillis = 10_200,
        )

        assertEquals(previous.distanceAlongRouteMeters, requireNotNull(progress).distanceAlongRouteMeters, 0.0)
    }

    @Test
    fun backwardFixOnRouteIsNotMarkedOffRoute() {
        val previous = update(route, GeoPoint(0.0, 0.0008)) ?: error("Expected progress")
        val progress = RouteProgressCalculator.update(
            route,
            previous,
            LocationFix(GeoPoint(0.0, 0.0001), 10_200),
            nowMillis = 10_200,
        ) ?: error("Expected progress")

        assertFalse(progress.isOffRoute)
        assertEquals(previous.distanceAlongRouteMeters, progress.distanceAlongRouteMeters, 0.0)
    }

    @Test
    fun offRouteDistanceSearchIncludesEarlierRouteSegments() {
        val loopRoute = RoutePlan(
            "loop",
            "Loop",
            listOf(
                GeoPoint(0.0, 0.0),
                GeoPoint(0.0, 0.001),
                GeoPoint(0.001, 0.001),
                GeoPoint(0.001, 0.0),
            ),
            emptyList(),
        )
        val previous = update(loopRoute, GeoPoint(0.001, 0.0001)) ?: error("Expected progress")
        val progress = RouteProgressCalculator.update(
            loopRoute,
            previous,
            LocationFix(GeoPoint(0.0, 0.001), 10_200),
            nowMillis = 10_200,
        ) ?: error("Expected progress")

        assertFalse(progress.isOffRoute)
        assertEquals(0.0, progress.distanceFromRouteMeters ?: Double.NaN, 0.5)
    }

    @Test
    fun highLatitudeCrossTrackSearchFallsBackSafely() {
        val polarRoute = RoutePlan(
            "polar",
            "Polar",
            listOf(GeoPoint(89.999, 0.0), GeoPoint(89.999, 0.001)),
            emptyList(),
        )
        val progress = update(polarRoute, GeoPoint(89.999, 0.0005)) ?: error("Expected progress")

        assertFalse(progress.isOffRoute)
        assertEquals(0.0, progress.distanceFromRouteMeters ?: Double.NaN, 0.5)
    }

    @Test
    fun routeCopiesMutableInputGeometryAndManeuvers() {
        val points = mutableListOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))
        val maneuvers = mutableListOf(Maneuver(1, "Arrive", 0))
        val savedRoute = RoutePlan("copy", "Copy", points, maneuvers)
        points.add(GeoPoint(0.001, 0.001))
        maneuvers.clear()

        assertEquals(2, savedRoute.points.size)
        assertEquals(1, savedRoute.maneuvers.size)
    }

    @Test
    fun crossingCannotJumpFarAheadOnRoute() {
        val crossingRoute = RoutePlan(
            id = "crossing",
            destinationName = "Crossing",
            points = listOf(
                GeoPoint(0.0, 0.0),
                GeoPoint(0.0, 0.001),
                GeoPoint(0.001, 0.001),
                GeoPoint(0.001, 0.0),
                GeoPoint(-0.001, 0.001),
            ),
            maneuvers = listOf(Maneuver(2, "Turn", 100)),
        )
        val previous = update(crossingRoute, GeoPoint(0.0, 0.00045)) ?: error("Expected progress")
        val progress = RouteProgressCalculator.update(
            crossingRoute,
            previous,
            LocationFix(GeoPoint(0.0, 0.00055), 10_200, horizontalAccuracyMeters = 5.0),
            nowMillis = 10_200,
        )

        assertTrue((progress?.segmentIndex ?: Int.MAX_VALUE) <= 1)
        assertFalse(progress?.isOffRoute ?: true)
    }

    @Test
    fun repeatedStationaryFixesCannotRatchetAcrossCompactLoops() {
        val points = buildList {
            for (level in 20 downTo 0) {
                val latitude = level * 0.000001
                val firstLongitude = if (level % 2 == 0) 0.0 else 0.0001
                add(GeoPoint(latitude, firstLongitude))
                add(GeoPoint(latitude, if (firstLongitude == 0.0) 0.0001 else 0.0))
            }
        }
        val compactRoute = RoutePlan("compact", "Compact", points, emptyList())
        var progress = update(compactRoute, GeoPoint(0.00002, 0.00005)) ?: error("Expected progress")

        repeat(10) { index ->
            progress = RouteProgressCalculator.update(
                compactRoute,
                progress,
                LocationFix(GeoPoint(0.000005, 0.00005), 10_200L + index * 1_000L),
                nowMillis = 10_200L + index * 1_000L,
            ) ?: error("Expected progress")
        }

        assertEquals(0, progress.segmentIndex)
        assertTrue(progress.distanceAlongRouteMeters < 10.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteCoordinates() {
        GeoPoint(Double.NaN, 0.0)
    }

    private fun update(route: RoutePlan, point: GeoPoint): RouteProgress? =
        RouteProgressCalculator.update(
            route = route,
            previous = null,
            fix = LocationFix(point, 10_000),
            nowMillis = 10_100,
        )
}

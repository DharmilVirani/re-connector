package com.dharmil.reconnector.navigation.store

import com.dharmil.reconnector.navigation.GeoPoint
import com.dharmil.reconnector.navigation.RoutePlan
import org.maplibre.geojson.Geometry
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Polygon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCorridorGeometryTest {
    @Test
    fun createsClosedCorridorAroundStraightRoute() {
        val polygon = RouteCorridorGeometry.create(
            route(
                GeoPoint(18.5, 73.8),
                GeoPoint(18.5, 73.9),
            ),
        )
        val ring = polygons(polygon).first().outer().coordinates()

        assertEquals(ring.first(), ring.last())
        assertTrue(ring.maxOf { it.latitude() } > 18.504)
        assertTrue(ring.minOf { it.latitude() } < 18.496)
    }

    @Test
    fun samplesBendsAlongLongRouteInsteadOfTakingItsBoundingBox() {
        val polygon = RouteCorridorGeometry.create(
            route(
                GeoPoint(18.5, 73.8),
                GeoPoint(18.51, 73.81),
                GeoPoint(18.52, 73.81),
                GeoPoint(18.52, 73.9),
            ),
        )
        val ring = polygons(polygon).first().outer().coordinates()

        assertEquals(ring.first(), ring.last())
        assertTrue(ring.size >= 9)
        assertTrue(ring.all { it.latitude() in 18.49..18.53 && it.longitude() in 73.79..73.91 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRouteWithNoUsableLength() {
        RouteCorridorGeometry.create(route(GeoPoint(18.5, 73.8), GeoPoint(18.5, 73.8)))
    }

    @Test
    fun buffersTightSwitchbacksIntoValidPolygonGeometry() {
        val corridor = RouteCorridorGeometry.create(
            route(
                GeoPoint(18.5, 73.8),
                GeoPoint(18.509, 73.8),
                GeoPoint(18.509, 73.80284),
                GeoPoint(18.5, 73.80284),
            ),
        )

        assertTrue(polygons(corridor).isNotEmpty())
        assertTrue(polygons(corridor).all { polygon ->
            polygon.outer().coordinates().let { it.size >= 5 && it.first() == it.last() }
        })
    }

    private fun route(vararg points: GeoPoint) = RoutePlan("offline-test", "Destination", points.toList(), emptyList())

    private fun polygons(geometry: Geometry): List<Polygon> = when (geometry) {
        is Polygon -> listOf(geometry)
        is MultiPolygon -> geometry.polygons()
        else -> error("Unsupported corridor geometry")
    }
}

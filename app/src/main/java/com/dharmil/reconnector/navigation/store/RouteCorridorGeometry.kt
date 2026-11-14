package com.dharmil.reconnector.navigation.store

import com.dharmil.reconnector.navigation.GeoPoint
import com.dharmil.reconnector.navigation.RoutePlan
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon as JtsMultiPolygon
import org.locationtech.jts.geom.Polygon as JtsPolygon
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.maplibre.geojson.Geometry
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

internal object RouteCorridorGeometry {
    private val geometryFactory = GeometryFactory()

    fun create(route: RoutePlan, halfWidthMeters: Double = CORRIDOR_HALF_WIDTH_METERS): Geometry {
        require(halfWidthMeters.isFinite() && halfWidthMeters > 0.0)
        require(route.points.all { it.latitude in -MAX_MERCATOR_LATITUDE..MAX_MERCATOR_LATITUDE }) {
            "Route is outside the supported map latitude range"
        }
        val meanLatitude = route.points.sumOf(GeoPoint::latitude) / route.points.size
        val longitudeScale = METERS_PER_DEGREE * cos(Math.toRadians(meanLatitude))
        require(abs(longitudeScale) > MIN_LONGITUDE_SCALE)
        val path = sample(route.points, longitudeScale)
        val line = geometryFactory.createLineString(path.map { Coordinate(it.x, it.y) }.toTypedArray())
        require(line.length > MIN_VECTOR_LENGTH) { "Route geometry has no usable length" }
        val buffered = BufferOp.bufferOp(
            line,
            halfWidthMeters,
            BUFFER_QUADRANT_SEGMENTS,
            BufferParameters.CAP_ROUND,
        )
        require(!buffered.isEmpty && buffered.isValid) { "Could not build a valid offline route corridor" }
        return when (buffered) {
            is JtsPolygon -> buffered.toMapPolygon(longitudeScale)
            is JtsMultiPolygon -> MultiPolygon.fromPolygons(
                (0 until buffered.numGeometries).map { (buffered.getGeometryN(it) as JtsPolygon).toMapPolygon(longitudeScale) },
            )
            else -> error("Offline route corridor produced unsupported geometry")
        }
    }

    private fun sample(points: List<GeoPoint>, longitudeScale: Double): List<XY> {
        val sampled = ArrayList<XY>(min(points.size, MAX_CORRIDOR_POINTS))
        sampled += points.first().toXY(longitudeScale)
        var traveledMeters = 0.0
        for (index in 1 until points.lastIndex) {
            traveledMeters += distanceMeters(points[index - 1], points[index], longitudeScale)
            val turnDegrees = turnAngleDegrees(points[index - 1], points[index], points[index + 1], longitudeScale)
            if (traveledMeters >= SAMPLE_INTERVAL_METERS || turnDegrees >= MIN_TURN_ANGLE_DEGREES) {
                require(sampled.size < MAX_CORRIDOR_POINTS) {
                    "Route is too complex for one offline map download"
                }
                sampled += points[index].toXY(longitudeScale)
                traveledMeters = 0.0
            }
        }
        require(sampled.size < MAX_CORRIDOR_POINTS) { "Route is too complex for one offline map download" }
        sampled += points.last().toXY(longitudeScale)
        return sampled
    }

    private fun turnAngleDegrees(previous: GeoPoint, current: GeoPoint, next: GeoPoint, scale: Double): Double {
        val before = current.toXY(scale) - previous.toXY(scale)
        val after = next.toXY(scale) - current.toXY(scale)
        if (before.length() == 0.0 || after.length() == 0.0) return 0.0
        return Math.toDegrees(abs(atan2(before.x * after.y - before.y * after.x, before.dot(after))))
    }

    private fun distanceMeters(start: GeoPoint, end: GeoPoint, longitudeScale: Double): Double =
        (end.toXY(longitudeScale) - start.toXY(longitudeScale)).length()

    private fun JtsPolygon.toMapPolygon(longitudeScale: Double): Polygon = Polygon.fromLngLats(
        buildList {
            add(exteriorRing.coordinates.toMapPoints(longitudeScale))
            for (index in 0 until numInteriorRing) {
                add(getInteriorRingN(index).coordinates.toMapPoints(longitudeScale))
            }
        },
    )

    private fun Array<Coordinate>.toMapPoints(longitudeScale: Double) = map { coordinate ->
        Point.fromLngLat(
            coordinate.x / longitudeScale,
            coordinate.y / METERS_PER_DEGREE,
        )
    }

    private fun GeoPoint.toXY(longitudeScale: Double) = XY(
        longitude * longitudeScale,
        latitude * METERS_PER_DEGREE,
    )

    private data class XY(val x: Double, val y: Double) {
        operator fun minus(other: XY) = XY(x - other.x, y - other.y)
        fun dot(other: XY) = x * other.x + y * other.y
        fun length() = hypot(x, y)
    }

    private const val CORRIDOR_HALF_WIDTH_METERS = 500.0
    private const val SAMPLE_INTERVAL_METERS = 250.0
    private const val MIN_TURN_ANGLE_DEGREES = 18.0
    private const val MAX_CORRIDOR_POINTS = 4_000
    private const val METERS_PER_DEGREE = 111_320.0
    private const val MAX_MERCATOR_LATITUDE = 85.0
    private const val MIN_LONGITUDE_SCALE = 1.0
    private const val MIN_VECTOR_LENGTH = 0.01
    private const val BUFFER_QUADRANT_SEGMENTS = 4
}

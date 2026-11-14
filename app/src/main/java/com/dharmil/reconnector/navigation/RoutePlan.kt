package com.dharmil.reconnector.navigation

import java.util.Collections
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0
internal const val MAX_ROUTE_POINT_COUNT = 50_000
internal const val MAX_ROUTE_MANEUVER_COUNT = 20_000

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

data class Maneuver(
    val pointIndex: Int,
    val instruction: String,
    val distanceMeters: Int,
) {
    init {
        require(instruction.isNotBlank())
        require(distanceMeters >= 0)
    }
}

class RoutePlan(
    val id: String,
    val destinationName: String,
    points: List<GeoPoint>,
    maneuvers: List<Maneuver>,
) {
    val points: List<GeoPoint> = Collections.unmodifiableList(points.toList())
    val maneuvers: List<Maneuver> = Collections.unmodifiableList(maneuvers.toList())
    private val cumulativeMeters: DoubleArray
    private val spatialCells: Map<Long, IntArray>
    private val broadSegments: IntArray
    private val visitedSegments: IntArray
    private var spatialSearchGeneration = 0

    init {
        require(id.isNotBlank())
        require(destinationName.isNotBlank())
        require(points.size in 2..MAX_ROUTE_POINT_COUNT) { "Route geometry exceeds supported size" }
        require(maneuvers.size <= MAX_ROUTE_MANEUVER_COUNT) { "Route has too many turn instructions" }
        require(maneuvers.all { it.pointIndex in points.indices })
        require(maneuvers.zipWithNext().all { (a, b) -> a.pointIndex <= b.pointIndex })

        cumulativeMeters = DoubleArray(points.size)
        for (index in 1 until points.size) {
            cumulativeMeters[index] = cumulativeMeters[index - 1] + greatCircleDistanceMeters(
                points[index - 1],
                points[index],
            )
        }

        val cells = HashMap<Long, MutableList<Int>>()
        val broad = ArrayList<Int>()
        for (index in 0 until this.points.lastIndex) {
            val start = this.points[index]
            val end = this.points[index + 1]
            val firstLatitudeCell = floor(min(start.latitude, end.latitude) / SPATIAL_CELL_DEGREES).toInt()
            val lastLatitudeCell = floor(maxOf(start.latitude, end.latitude) / SPATIAL_CELL_DEGREES).toInt()
            val firstLongitudeCell = floor(min(start.longitude, end.longitude) / SPATIAL_CELL_DEGREES).toInt()
            val lastLongitudeCell = floor(maxOf(start.longitude, end.longitude) / SPATIAL_CELL_DEGREES).toInt()
            val cellCount = (lastLatitudeCell - firstLatitudeCell + 1L) *
                (lastLongitudeCell - firstLongitudeCell + 1L)
            if (cellCount > MAX_INDEXED_CELLS_PER_SEGMENT) {
                broad.add(index)
                continue
            }
            for (latitudeCell in firstLatitudeCell..lastLatitudeCell) {
                for (longitudeCell in firstLongitudeCell..lastLongitudeCell) {
                    cells.getOrPut(cellKey(latitudeCell, longitudeCell)) { ArrayList() }.add(index)
                }
            }
        }
        spatialCells = HashMap<Long, IntArray>(cells.size).apply {
            for ((key, segments) in cells) put(key, segments.toIntArray())
        }
        broadSegments = broad.toIntArray()
        visitedSegments = IntArray(this.points.lastIndex)
    }

    internal val totalDistanceMeters: Double
        get() = cumulativeMeters.last()

    internal fun distanceAlong(segmentIndex: Int, fraction: Double): Double =
        cumulativeMeters[segmentIndex] +
            (cumulativeMeters[segmentIndex + 1] - cumulativeMeters[segmentIndex]) * fraction

    internal fun distanceAt(pointIndex: Int): Double = cumulativeMeters[pointIndex]

    internal fun segmentLength(segmentIndex: Int): Double =
        cumulativeMeters[segmentIndex + 1] - cumulativeMeters[segmentIndex]

    @Synchronized
    internal fun distanceFromRouteMeters(point: GeoPoint, searchRadiusMeters: Double): Double? {
        val latitudeCell = floor(point.latitude / SPATIAL_CELL_DEGREES).toInt()
        val longitudeCell = floor(point.longitude / SPATIAL_CELL_DEGREES).toInt()
        val latitudeRadius = kotlin.math.ceil(searchRadiusMeters / METERS_PER_DEGREE).toInt() + 1
        val longitudeScale = METERS_PER_DEGREE * cos(Math.toRadians(point.latitude))
        if (abs(longitudeScale) < 1.0) return nearestSegmentDistance(point, searchRadiusMeters)
        val longitudeRadius = kotlin.math.ceil(searchRadiusMeters / (longitudeScale * SPATIAL_CELL_DEGREES)).toInt() + 1
        val latitudeProbeCount = latitudeRadius.toLong() * 2L + 1L
        val longitudeProbeCount = longitudeRadius.toLong() * 2L + 1L
        if (latitudeProbeCount > MAX_SPATIAL_CELL_PROBES ||
            longitudeProbeCount > MAX_SPATIAL_CELL_PROBES ||
            latitudeProbeCount > MAX_SPATIAL_CELL_PROBES / longitudeProbeCount
        ) {
            return nearestSegmentDistance(point, searchRadiusMeters)
        }
        val generation = if (spatialSearchGeneration == Int.MAX_VALUE) {
            visitedSegments.fill(0)
            1
        } else {
            spatialSearchGeneration + 1
        }
        spatialSearchGeneration = generation

        var nearestDistanceSquared = searchRadiusMeters * searchRadiusMeters
        var foundWithinRadius = false
        for (index in broadSegments) {
            visitedSegments[index] = generation
            val distanceSquared = segmentDistanceSquaredMeters(point, points[index], points[index + 1])
            if (distanceSquared <= nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared
                foundWithinRadius = true
            }
        }
        for (latitude in latitudeCell - latitudeRadius..latitudeCell + latitudeRadius) {
            for (longitude in longitudeCell - longitudeRadius..longitudeCell + longitudeRadius) {
                val segments = spatialCells[cellKey(latitude, longitude)] ?: continue
                for (index in segments) {
                    if (visitedSegments[index] == generation) continue
                    visitedSegments[index] = generation
                    val distanceSquared = segmentDistanceSquaredMeters(point, points[index], points[index + 1])
                    if (distanceSquared <= nearestDistanceSquared) {
                        nearestDistanceSquared = distanceSquared
                        foundWithinRadius = true
                    }
                }
            }
        }
        return if (foundWithinRadius) {
            sqrt(nearestDistanceSquared)
        } else {
            null
        }
    }

    private fun nearestSegmentDistance(point: GeoPoint, searchRadiusMeters: Double): Double? {
        var nearestDistanceSquared = searchRadiusMeters * searchRadiusMeters
        var foundWithinRadius = false
        for (index in 0 until points.lastIndex) {
            val distanceSquared = segmentDistanceSquaredMeters(point, points[index], points[index + 1])
            if (distanceSquared <= nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared
                foundWithinRadius = true
            }
        }
        return if (foundWithinRadius) {
            sqrt(nearestDistanceSquared)
        } else {
            null
        }
    }

    private fun cellKey(latitudeCell: Int, longitudeCell: Int): Long =
        (latitudeCell.toLong() shl Int.SIZE_BITS) xor (longitudeCell.toLong() and 0xffff_ffffL)

    private fun segmentDistanceSquaredMeters(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val longitudeScale = METERS_PER_DEGREE * cos(Math.toRadians(point.latitude))
        val px = (point.longitude - start.longitude) * longitudeScale
        val py = (point.latitude - start.latitude) * METERS_PER_DEGREE
        val ex = (end.longitude - start.longitude) * longitudeScale
        val ey = (end.latitude - start.latitude) * METERS_PER_DEGREE
        val lengthSquared = ex * ex + ey * ey
        val fraction = if (lengthSquared == 0.0) 0.0 else ((px * ex + py * ey) / lengthSquared).coerceIn(0.0, 1.0)
        val dx = px - fraction * ex
        val dy = py - fraction * ey
        return dx * dx + dy * dy
    }

    private companion object {
        const val SPATIAL_CELL_DEGREES = 0.001
        const val METERS_PER_DEGREE = 111_320.0
        const val MAX_INDEXED_CELLS_PER_SEGMENT = 64L
        const val MAX_SPATIAL_CELL_PROBES = 10_000L
    }
}

data class LocationFix(
    val point: GeoPoint,
    val timestampMillis: Long,
    val horizontalAccuracyMeters: Double? = null,
) {
    init {
        require(timestampMillis >= 0L)
        require(horizontalAccuracyMeters == null ||
            (horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters >= 0.0))
    }
}

data class RouteProgress(
    val routeId: String,
    val segmentIndex: Int,
    val segmentFraction: Double,
    val distanceAlongRouteMeters: Double,
    val nextManeuverIndex: Int,
    val remainingMeters: Double,
    val distanceFromRouteMeters: Double?,
    val isOffRoute: Boolean,
    val lastFix: GeoPoint,
    val lastFixTimestampMillis: Long,
    val lastFixAccuracyMeters: Double?,
)

object RouteProgressCalculator {
    private const val MAX_ACCURACY_TOLERANCE_METERS = 150.0
    private const val MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND = 60.0
    // Allow some arc-versus-chord difference between consecutive location fixes.
    private const val MOVEMENT_FACTOR = 1.5

    fun update(
        route: RoutePlan,
        previous: RouteProgress?,
        fix: LocationFix,
        nowMillis: Long,
        staleAfterMillis: Long = 5_000,
        offRouteThresholdMeters: Double = 60.0,
    ): RouteProgress? {
        require(staleAfterMillis >= 0)
        require(offRouteThresholdMeters.isFinite() && offRouteThresholdMeters > 0.0)
        if (fix.timestampMillis > nowMillis || nowMillis - fix.timestampMillis > staleAfterMillis) return null

        val previousOnRoute = previous?.takeIf { it.routeId == route.id }
        val startSegment = previousOnRoute?.segmentIndex?.coerceIn(0, route.points.lastIndex - 1) ?: 0
        val previousAccuracy = previousOnRoute?.lastFixAccuracyMeters ?: 0.0
        val accuracyTolerance = min(
            maxOf(previousAccuracy, fix.horizontalAccuracyMeters ?: 0.0) * 2.0,
            MAX_ACCURACY_TOLERANCE_METERS,
        )
        val maxDistanceAlong = previousOnRoute?.let {
            val movementAllowance = maxOf(
                0.0,
                distanceMeters(it.lastFix, fix.point) * MOVEMENT_FACTOR -
                    maxOf(it.lastFixAccuracyMeters ?: 0.0, fix.horizontalAccuracyMeters ?: 0.0),
            )
            val elapsedMillis = (fix.timestampMillis - it.lastFixTimestampMillis).coerceAtLeast(0L)
            val timeAllowance = elapsedMillis / 1_000.0 * MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND
            min(route.totalDistanceMeters, it.distanceAlongRouteMeters + min(movementAllowance, timeAllowance))
        } ?: route.totalDistanceMeters
        val endSegment = lastSegmentStartingBefore(route, startSegment, maxDistanceAlong)

        val latitudeScale = 111_320.0
        val longitudeScale = latitudeScale * cos(Math.toRadians(fix.point.latitude))
        var nearestSegment = startSegment
        var nearestFraction = 0.0
        var nearestDistanceSquared = Double.POSITIVE_INFINITY

        for (index in startSegment..endSegment) {
            val start = route.points[index]
            val end = route.points[index + 1]
            val px = (fix.point.longitude - start.longitude) * longitudeScale
            val py = (fix.point.latitude - start.latitude) * latitudeScale
            val ex = (end.longitude - start.longitude) * longitudeScale
            val ey = (end.latitude - start.latitude) * latitudeScale
            val lengthSquared = ex * ex + ey * ey
            val projected = if (lengthSquared == 0.0) 0.0 else (px * ex + py * ey) / lengthSquared
            val routeLength = route.segmentLength(index)
            val maxFraction = if (routeLength == 0.0) {
                1.0
            } else {
                ((maxDistanceAlong - route.distanceAt(index)) / routeLength).coerceIn(0.0, 1.0)
            }
            val minFraction = if (index == previousOnRoute?.segmentIndex) {
                previousOnRoute.segmentFraction.coerceIn(0.0, maxFraction)
            } else {
                0.0
            }
            val fraction = projected.coerceIn(minFraction, maxFraction)
            val dx = px - fraction * ex
            val dy = py - fraction * ey
            val distanceSquared = dx * dx + dy * dy

            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared
                nearestSegment = index
                nearestFraction = fraction
            }
        }

        val offRouteThreshold = maxOf(offRouteThresholdMeters, accuracyTolerance)
        val distanceFromRoute = route.distanceFromRouteMeters(fix.point, offRouteThreshold)
        val isOffRoute = distanceFromRoute == null
        if (isOffRoute && previousOnRoute != null) {
            return previousOnRoute.copy(
                distanceFromRouteMeters = distanceFromRoute,
                isOffRoute = true,
                lastFix = fix.point,
                lastFixTimestampMillis = fix.timestampMillis,
                lastFixAccuracyMeters = fix.horizontalAccuracyMeters,
            )
        }

        val distanceAlongRoute = route.distanceAlong(nearestSegment, nearestFraction)
        var nextManeuverIndex = route.maneuvers.size
        for (index in route.maneuvers.indices) {
            if (route.distanceAt(route.maneuvers[index].pointIndex) >= distanceAlongRoute) {
                nextManeuverIndex = index
                break
            }
        }

        return RouteProgress(
            routeId = route.id,
            segmentIndex = nearestSegment,
            segmentFraction = nearestFraction,
            distanceAlongRouteMeters = distanceAlongRoute,
            nextManeuverIndex = nextManeuverIndex,
            remainingMeters = route.totalDistanceMeters - distanceAlongRoute,
            distanceFromRouteMeters = distanceFromRoute,
            isOffRoute = isOffRoute,
            lastFix = fix.point,
            lastFixTimestampMillis = fix.timestampMillis,
            lastFixAccuracyMeters = fix.horizontalAccuracyMeters,
        )
    }

    private fun lastSegmentStartingBefore(
        route: RoutePlan,
        firstSegment: Int,
        maxDistanceAlong: Double,
    ): Int {
        var low = firstSegment
        var high = route.points.lastIndex - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (route.distanceAt(middle) <= maxDistanceAlong) low = middle else high = middle - 1
        }
        return low
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        return greatCircleDistanceMeters(a, b)
    }
}

private fun greatCircleDistanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val latitudeDelta = Math.toRadians(b.latitude - a.latitude)
    val longitudeDelta = Math.toRadians(b.longitude - a.longitude)
    val latitudeA = Math.toRadians(a.latitude)
    val latitudeB = Math.toRadians(b.latitude)
    val halfLatitude = sin(latitudeDelta / 2.0)
    val halfLongitude = sin(longitudeDelta / 2.0)
    val haversine = (halfLatitude * halfLatitude +
        cos(latitudeA) * cos(latitudeB) * halfLongitude * halfLongitude
        ).coerceIn(0.0, 1.0)
    return 2.0 * EARTH_RADIUS_METERS * atan2(sqrt(haversine), sqrt(1.0 - haversine))
}

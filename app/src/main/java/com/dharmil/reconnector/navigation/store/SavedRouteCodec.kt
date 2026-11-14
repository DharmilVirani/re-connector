package com.dharmil.reconnector.navigation.store

import com.dharmil.reconnector.navigation.GeoPoint
import com.dharmil.reconnector.navigation.Maneuver
import com.dharmil.reconnector.navigation.RoutePlan
import com.dharmil.reconnector.navigation.MAX_ROUTE_MANEUVER_COUNT
import com.dharmil.reconnector.navigation.MAX_ROUTE_POINT_COUNT
import org.json.JSONArray
import org.json.JSONObject

internal object SavedRouteCodec {
    fun encode(route: RoutePlan): String {
        val points = JSONArray()
        route.points.forEach { points.put(it.latitude).put(it.longitude) }
        val maneuvers = JSONArray()
        route.maneuvers.forEach { maneuver ->
            maneuvers.put(
                JSONArray()
                    .put(maneuver.pointIndex)
                    .put(maneuver.instruction)
                    .put(maneuver.distanceMeters),
            )
        }
        return JSONObject()
            .put("version", FORMAT_VERSION)
            .put("id", route.id)
            .put("destination", route.destinationName)
            .put("points", points)
            .put("maneuvers", maneuvers)
            .toString()
            .also { require(it.length <= MAX_SERIALIZED_CHARS) { "Saved route exceeds size limit" } }
    }

    fun decode(json: String): RoutePlan {
        require(json.length <= MAX_SERIALIZED_CHARS) { "Saved route exceeds size limit" }
        val root = JSONObject(json)
        val version = root.opt("version") as? Number ?: error("Saved route has no format version")
        val numericVersion = version.toDouble()
        require(numericVersion.isFinite() && numericVersion == FORMAT_VERSION.toDouble()) {
            "Unsupported saved route format"
        }
        val id = root.opt("id") as? String ?: error("Saved route has no ID")
        val destination = root.opt("destination") as? String ?: error("Saved route has no destination")
        val encodedPoints = root.optJSONArray("points") ?: error("Saved route has no geometry")
        require(encodedPoints.length() % COORDINATE_VALUES_PER_POINT == 0) { "Saved route geometry is malformed" }
        val pointCount = encodedPoints.length() / COORDINATE_VALUES_PER_POINT
        require(pointCount in 2..MAX_ROUTE_POINT_COUNT) { "Saved route geometry exceeds supported size" }
        val points = List(pointCount) { index ->
            GeoPoint(
                encodedPoints.getNumber(index * COORDINATE_VALUES_PER_POINT).toDouble(),
                encodedPoints.getNumber(index * COORDINATE_VALUES_PER_POINT + 1).toDouble(),
            )
        }
        val encodedManeuvers = root.optJSONArray("maneuvers") ?: error("Saved route has no turn instructions")
        require(encodedManeuvers.length() <= MAX_ROUTE_MANEUVER_COUNT) { "Saved route has too many turn instructions" }
        val maneuvers = List(encodedManeuvers.length()) { index ->
            val item = encodedManeuvers.getJSONArray(index)
            require(item.length() == MANEUVER_FIELD_COUNT) { "Saved turn instruction is malformed" }
            val pointIndex = item.getNumber(MANEUVER_POINT_INDEX).toIntExact()
            val instruction = item.opt(MANEUVER_INSTRUCTION) as? String
                ?: error("Saved turn instruction is malformed")
            val distance = item.getNumber(MANEUVER_DISTANCE).toIntExact()
            Maneuver(pointIndex, instruction, distance)
        }
        return RoutePlan(id, destination, points, maneuvers)
    }

    private fun JSONArray.getNumber(index: Int): Number =
        opt(index) as? Number ?: error("Saved route contains a non-numeric value")

    private fun Number.toIntExact(): Int {
        val value = toDouble()
        require(value.isFinite() && value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            "Saved route contains an invalid integer"
        }
        return value.toInt()
    }

    private const val FORMAT_VERSION = 1
    private const val MAX_SERIALIZED_CHARS = 10_000_000
    private const val COORDINATE_VALUES_PER_POINT = 2
    private const val MANEUVER_FIELD_COUNT = 3
    private const val MANEUVER_POINT_INDEX = 0
    private const val MANEUVER_INSTRUCTION = 1
    private const val MANEUVER_DISTANCE = 2
}

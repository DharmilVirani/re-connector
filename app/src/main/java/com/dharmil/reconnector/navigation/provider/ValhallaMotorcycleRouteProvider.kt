package com.dharmil.reconnector.navigation.provider

import android.os.SystemClock
import androidx.annotation.WorkerThread
import com.dharmil.reconnector.network.AppHttpClient
import com.dharmil.reconnector.network.readUtf8Limited
import com.dharmil.reconnector.navigation.GeoPoint
import com.dharmil.reconnector.navigation.Maneuver
import com.dharmil.reconnector.navigation.MAX_ROUTE_MANEUVER_COUNT
import com.dharmil.reconnector.navigation.MAX_ROUTE_POINT_COUNT
import com.dharmil.reconnector.navigation.RoutePlan
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class ValhallaMotorcycleRouteProvider(
    private val baseUrl: () -> String = { DEFAULT_BASE_URL },
    client: OkHttpClient = AppHttpClient.instance,
) : RouteProvider {
    private val client = client.newBuilder().callTimeout(ROUTE_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    @WorkerThread
    override fun calculate(origin: GeoPoint, destination: Destination): RoutePlan {
        throttleRequest()
        val requestJson = JSONObject()
            .put("locations", JSONArray()
                .put(JSONObject().put("lat", origin.latitude).put("lon", origin.longitude).put("type", "break"))
                .put(JSONObject().put("lat", destination.point.latitude).put("lon", destination.point.longitude).put("type", "break")))
            .put("costing", MOTORCYCLE_COSTING)
            .put("units", "kilometers")
            .put("language", "en-US")
            .toString()
        val routeUrl = baseUrl().toHttpUrl()
        require(routeUrl.isHttps) { "Valhalla endpoint must use HTTPS" }
        val request = Request.Builder()
            .url(routeUrl.newBuilder().addPathSegment("route").build())
            .header("X-Client-Id", CLIENT_ID)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Routing failed with HTTP ${response.code}")
            val body = response.body ?: throw IOException("Routing service returned an empty response")
            if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Routing response exceeds size limit")
            return parseResponse(body.readUtf8Limited(MAX_RESPONSE_BYTES), destination.name)
        }
    }

    internal fun parseResponse(json: String, destinationName: String): RoutePlan {
        require(json.length <= MAX_RESPONSE_BYTES) { "Routing response exceeds size limit" }
        val trip = JSONObject(json).optJSONObject("trip") ?: error("Routing response has no trip")
        val statusValue = trip.opt("status")
        require(statusValue is Number) { "Routing response has an invalid status" }
        val numericStatus = statusValue.toDouble()
        require(numericStatus.isFinite() && numericStatus % 1.0 == 0.0) {
            "Routing response has an invalid status"
        }
        val status = numericStatus.toInt()
        require(status == SUCCESS_STATUS) { trip.optString("status_message", "No motorcycle route found") }

        val points = ArrayList<GeoPoint>()
        val maneuvers = ArrayList<Maneuver>()
        val legs = trip.optJSONArray("legs") ?: error("Routing response has no route legs")
        for (legIndex in 0 until legs.length()) {
            val leg = legs.getJSONObject(legIndex)
            val legPoints = Polyline6Decoder.decode(leg.getString("shape"))
            val mergedPointCount = points.size + legPoints.size - (if (points.isEmpty()) 0 else 1)
            require(mergedPointCount <= MAX_ROUTE_POINT_COUNT) {
                "Route geometry exceeds supported point count"
            }
            val pointOffset = if (points.isEmpty()) 0 else points.lastIndex
            if (points.isEmpty()) points.addAll(legPoints) else points.addAll(legPoints.drop(1))
            val instructions = leg.optJSONArray("maneuvers") ?: JSONArray()
            require(instructions.length() > 0) { "Routing response has no turn instructions" }
            for (index in 0 until instructions.length()) {
                require(maneuvers.size < MAX_ROUTE_MANEUVER_COUNT) {
                    "Route has too many turn instructions"
                }
                val item = instructions.getJSONObject(index)
                val instructionValue = item.opt("instruction")
                require(instructionValue is String && instructionValue.isNotBlank()) {
                    "Routing response contains an invalid instruction"
                }
                val instruction = instructionValue.trim()
                val indexValue = item.opt("begin_shape_index")
                require(indexValue is Number) { "Routing response contains an invalid maneuver index" }
                val numericIndex = indexValue.toDouble()
                require(numericIndex.isFinite() && numericIndex % 1.0 == 0.0) {
                    "Routing response contains an invalid maneuver index"
                }
                val localIndex = numericIndex.toInt()
                require(localIndex in legPoints.indices) { "Routing response contains an invalid maneuver index" }
                val lengthValue = item.opt("length")
                require(lengthValue is Number) { "Routing response contains an invalid maneuver distance" }
                val lengthKm = lengthValue.toDouble()
                require(lengthKm.isFinite() && lengthKm >= 0.0) {
                    "Routing response contains an invalid maneuver distance"
                }
                maneuvers += Maneuver(
                    pointIndex = pointOffset + localIndex,
                    instruction = instruction,
                    distanceMeters = (lengthKm * METERS_PER_KILOMETER)
                        .coerceAtMost(Int.MAX_VALUE.toDouble()).roundToInt(),
                )
            }
        }
        require(points.size >= 2) { "Routing response has insufficient route geometry" }
        return RoutePlan(UUID.randomUUID().toString(), destinationName, points, maneuvers)
    }

    private fun throttleRequest() {
        synchronized(REQUEST_LOCK) {
            val now = SystemClock.elapsedRealtime()
            val delay = (MIN_REQUEST_INTERVAL_MS - (now - lastRequestAtMillis)).coerceAtLeast(0L)
            if (delay > 0L) Thread.sleep(delay)
            lastRequestAtMillis = SystemClock.elapsedRealtime()
        }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://valhalla1.openstreetmap.de"
        const val MOTORCYCLE_COSTING = "motorcycle"
        const val CLIENT_ID = "RE-Connector/0.1.0 (personal Android app)"
        const val SUCCESS_STATUS = 0
        const val MAX_RESPONSE_BYTES = 5_000_000L
        const val ROUTE_TIMEOUT_SECONDS = 20L
        const val METERS_PER_KILOMETER = 1_000.0
        const val INVALID_SHAPE_INDEX = -1
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        val REQUEST_LOCK = Any()
        var lastRequestAtMillis = 0L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

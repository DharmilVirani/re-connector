package com.dharmil.reconnector.navigation.provider

import com.dharmil.reconnector.navigation.GeoPoint
import com.dharmil.reconnector.navigation.MAX_ROUTE_POINT_COUNT

internal object Polyline6Decoder {
    fun decode(encoded: String): List<GeoPoint> {
        require(encoded.isNotEmpty()) { "Route geometry is empty" }
        require(encoded.length <= MAX_ENCODED_GEOMETRY_LENGTH) { "Route geometry exceeds size limit" }
        val points = ArrayList<GeoPoint>(minOf(encoded.length / 4, MAX_ROUTE_POINT_COUNT))
        var index = 0
        var latitude = 0L
        var longitude = 0L
        while (index < encoded.length) {
            val latitudeDelta = decodeValue(encoded, index).also { index = it.nextIndex }
            val longitudeDelta = decodeValue(encoded, index).also { index = it.nextIndex }
            latitude += latitudeDelta.value
            longitude += longitudeDelta.value
            require(points.size < MAX_ROUTE_POINT_COUNT) { "Route geometry exceeds supported point count" }
            points += GeoPoint(latitude * COORDINATE_SCALE, longitude * COORDINATE_SCALE)
        }
        require(points.size >= 2) { "Route geometry needs at least two points" }
        return points
    }

    private fun decodeValue(encoded: String, start: Int): DecodedValue {
        var result = 0L
        var shift = 0
        var index = start
        while (true) {
            require(index < encoded.length) { "Truncated encoded route geometry" }
            val value = encoded[index++].code - ENCODING_OFFSET
            require(value in 0..0x3F) { "Invalid encoded route geometry" }
            result = result or ((value and VALUE_MASK).toLong() shl shift)
            shift += VALUE_BITS
            require(shift <= MAX_SHIFT_BITS) { "Encoded route coordinate is too large" }
            if (value < CONTINUATION_BIT) break
        }
        val signed = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return DecodedValue(signed, index)
    }

    private data class DecodedValue(val value: Long, val nextIndex: Int)

    private const val ENCODING_OFFSET = 63
    private const val VALUE_MASK = 0x1F
    private const val VALUE_BITS = 5
    private const val CONTINUATION_BIT = 0x20
    private const val MAX_SHIFT_BITS = 60
    private const val COORDINATE_SCALE = 1e-6
    private const val MAX_ENCODED_GEOMETRY_LENGTH = 1_000_000
}

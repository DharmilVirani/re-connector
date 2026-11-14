package com.dharmil.reconnector.navigation.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class Polyline6DecoderTest {
    @Test
    fun decodesSixDecimalPlaceCoordinates() {
        val points = Polyline6Decoder.decode("A?A?A?")

        assertEquals(3, points.size)
        assertEquals(0.000001, points[0].latitude, COORDINATE_TOLERANCE)
        assertEquals(0.0, points[0].longitude, COORDINATE_TOLERANCE)
        assertEquals(0.000003, points.last().latitude, COORDINATE_TOLERANCE)
    }

    @Test
    fun decodesNegativeAndPositiveDeltas() {
        val points = Polyline6Decoder.decode("@?A?")

        assertEquals(-0.000001, points[0].latitude, COORDINATE_TOLERANCE)
        assertEquals(0.0, points.last().latitude, COORDINATE_TOLERANCE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedCoordinate() {
        Polyline6Decoder.decode("A")
    }

    private companion object {
        const val COORDINATE_TOLERANCE = 1e-12
    }
}

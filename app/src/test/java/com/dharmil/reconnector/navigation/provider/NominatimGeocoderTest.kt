package com.dharmil.reconnector.navigation.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class NominatimGeocoderTest {
    @Test
    fun allowsAnOwnerSelectedHttpsEndpoint() {
        NominatimGeocoder(baseUrl = { "https://geocoder.example/search" })
            .createSearchRequest("Pune")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpsEndpoint() {
        NominatimGeocoder(baseUrl = { "http://geocoder.example/search" }).createSearchRequest("Pune")
    }

    @Test
    fun usesTheCurrentEndpointProviderValueForEachRequest() {
        var endpoint = "https://one.example/search"
        val geocoder = NominatimGeocoder(baseUrl = { endpoint })
        val first = geocoder.createSearchRequest("Pune city")
        endpoint = "https://two.example/find"
        val second = geocoder.createSearchRequest("Pune city")

        assertEquals("one.example", first.url.host)
        assertEquals("two.example", second.url.host)
        assertEquals("Pune city", second.url.queryParameter("q"))
    }

    @Test
    fun parsesSearchResults() {
        val geocoder = NominatimGeocoder()
        val destinations = geocoder.parseResults(
            """[{"display_name":"Pune, Maharashtra, India","lat":"18.5204","lon":"73.8567"}]""",
        )

        assertEquals(1, destinations.size)
        assertEquals("Pune, Maharashtra, India", destinations.single().name)
        assertEquals(18.5204, destinations.single().point.latitude, COORDINATE_TOLERANCE)
        assertEquals(73.8567, destinations.single().point.longitude, COORDINATE_TOLERANCE)
    }

    private companion object {
        const val COORDINATE_TOLERANCE = 1e-6
    }
}

package com.dharmil.reconnector.navigation.provider

import android.os.SystemClock
import androidx.annotation.WorkerThread
import com.dharmil.reconnector.network.AppHttpClient
import com.dharmil.reconnector.network.readUtf8Limited
import com.dharmil.reconnector.navigation.GeoPoint
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class NominatimGeocoder(
    private val baseUrl: () -> String = { DEFAULT_BASE_URL },
    client: OkHttpClient = AppHttpClient.instance,
) : DestinationSearchProvider {
    private val client = client.newBuilder().callTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val cache = LinkedHashMap<String, List<Destination>>(CACHE_SIZE, CACHE_LOAD_FACTOR, true)

    @WorkerThread
    override fun search(query: String): List<Destination> {
        val normalizedQuery = query.trim()
        require(normalizedQuery.length in 2..MAX_QUERY_LENGTH) { "Enter a destination to search" }
        val endpoint = baseUrl()
        val cacheKey = "$endpoint|${normalizedQuery.lowercase(Locale.ROOT)}"
        synchronized(cache) { cache[cacheKey]?.let { return it } }
        throttleRequest()

        val request = createSearchRequest(normalizedQuery, endpoint)
        val results = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Destination search failed with HTTP ${response.code}")
            val body = response.body ?: throw IOException("Destination search returned an empty response")
            if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Destination response exceeds size limit")
            parseResults(body.readUtf8Limited(MAX_RESPONSE_BYTES))
        }
        synchronized(cache) {
            cache[cacheKey] = results
            while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
        }
        return results
    }

    internal fun createSearchRequest(query: String, endpoint: String = baseUrl()): Request {
        val searchUrl = endpoint.toHttpUrl()
        require(searchUrl.isHttps) { "Geocoding endpoint must use HTTPS" }
        val url = searchUrl.newBuilder()
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", RESULT_LIMIT.toString())
            .addQueryParameter("countrycodes", "in")
            .addQueryParameter("q", query.trim())
            .build()
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
    }

    internal fun parseResults(json: String): List<Destination> {
        require(json.length <= MAX_RESPONSE_BYTES) { "Destination response exceeds size limit" }
        val results = JSONArray(json)
        return List(minOf(results.length(), RESULT_LIMIT)) { index ->
            val item = results.getJSONObject(index)
            val name = item.optString("display_name").trim()
            require(name.isNotEmpty()) { "Destination result has no name" }
            Destination(
                name,
                GeoPoint(item.getString("lat").toDouble(), item.getString("lon").toDouble()),
            )
        }
    }

    private fun throttleRequest() {
        synchronized(rateLock) {
            val now = SystemClock.elapsedRealtime()
            val delay = (MIN_REQUEST_INTERVAL_MS - (now - lastRequestAtMillis)).coerceAtLeast(0L)
            if (delay > 0L) Thread.sleep(delay)
            lastRequestAtMillis = SystemClock.elapsedRealtime()
        }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://nominatim.openstreetmap.org/search"
        const val RESULT_LIMIT = 8
        const val MAX_QUERY_LENGTH = 200
        const val MAX_RESPONSE_BYTES = 1_000_000L
        const val SEARCH_TIMEOUT_SECONDS = 15L
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        const val CACHE_SIZE = 64
        const val CACHE_LOAD_FACTOR = 0.75f
        val rateLock = Any()
        var lastRequestAtMillis = 0L
    }
}

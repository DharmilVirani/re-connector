package com.dharmil.reconnector.network

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.IOException

object AppHttpClient {
    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = MAX_CONCURRENT_REQUESTS
                maxRequestsPerHost = MAX_CONCURRENT_REQUESTS
            })
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
                val isOpenFreeMapResource = request.url.host == OFFLINE_MAP_HOST
                val response = chain.proceed(request)
                if (!isOpenFreeMapResource) return@addInterceptor response
                val body = response.body ?: return@addInterceptor response
                try {
                    response.newBuilder()
                        .body(body.limitedTo(MAX_MAP_RESOURCE_BYTES))
                        .build()
                } catch (failure: IOException) {
                    response.close()
                    throw failure
                }
            }
            .build()
    }

    private const val MAX_CONCURRENT_REQUESTS = 4
    private const val MAX_MAP_RESOURCE_BYTES = 4L * 1_000_000L
    private const val OFFLINE_MAP_HOST = "tiles.openfreemap.org"
    private const val USER_AGENT = "RE-Connector/0.1.0 (https://github.com/DharmilVirani/re-connector)"
}

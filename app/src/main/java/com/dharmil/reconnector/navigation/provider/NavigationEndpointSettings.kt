package com.dharmil.reconnector.navigation.provider

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl

class NavigationEndpointSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var geocoderSearchUrl: String
        get() = preferences.getString(GEOCODER_URL_KEY, DEFAULT_GEOCODER_URL) ?: DEFAULT_GEOCODER_URL
        set(value) = preferences.edit().putString(GEOCODER_URL_KEY, requireHttps(value)).apply()

    var routeServiceBaseUrl: String
        get() = preferences.getString(ROUTER_URL_KEY, DEFAULT_ROUTER_URL) ?: DEFAULT_ROUTER_URL
        set(value) = preferences.edit().putString(ROUTER_URL_KEY, requireHttps(value).trimEnd('/')).apply()

    private fun requireHttps(value: String): String = value.trim().also { url ->
        require(url.toHttpUrl().isHttps) { "Navigation endpoints must use HTTPS" }
    }

    private companion object {
        const val PREFERENCES_NAME = "navigation-endpoints"
        const val GEOCODER_URL_KEY = "geocoder-search-url"
        const val ROUTER_URL_KEY = "route-service-base-url"
        const val DEFAULT_GEOCODER_URL = "https://nominatim.openstreetmap.org/search"
        const val DEFAULT_ROUTER_URL = "https://valhalla1.openstreetmap.de"
    }
}

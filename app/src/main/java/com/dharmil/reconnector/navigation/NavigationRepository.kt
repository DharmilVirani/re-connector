package com.dharmil.reconnector.navigation

import android.content.Context
import androidx.annotation.WorkerThread
import com.dharmil.reconnector.navigation.provider.Destination
import com.dharmil.reconnector.navigation.provider.NavigationEndpointSettings
import com.dharmil.reconnector.navigation.provider.NominatimGeocoder
import com.dharmil.reconnector.navigation.provider.ValhallaMotorcycleRouteProvider
import com.dharmil.reconnector.navigation.store.SavedRouteListing
import com.dharmil.reconnector.navigation.store.SavedRouteStore

class NavigationRepository(context: Context) {
    private val endpoints = NavigationEndpointSettings(context.applicationContext)
    private val geocoder = NominatimGeocoder(baseUrl = { endpoints.geocoderSearchUrl })
    private val router = ValhallaMotorcycleRouteProvider(baseUrl = { endpoints.routeServiceBaseUrl })
    private val routes = SavedRouteStore(context.applicationContext)

    @WorkerThread
    fun search(query: String): List<Destination> = geocoder.search(query)

    @WorkerThread
    fun calculate(origin: GeoPoint, destination: Destination): RoutePlan = router.calculate(origin, destination)

    @WorkerThread
    fun save(route: RoutePlan) = routes.save(route)

    @WorkerThread
    fun savedRoutes(): SavedRouteListing = routes.loadAll()
}

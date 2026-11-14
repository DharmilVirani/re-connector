package com.dharmil.reconnector.navigation.provider

import androidx.annotation.WorkerThread
import com.dharmil.reconnector.navigation.GeoPoint
import com.dharmil.reconnector.navigation.RoutePlan

data class Destination(val name: String, val point: GeoPoint) {
    init {
        require(name.isNotBlank() && name.length <= MAX_DESTINATION_NAME_LENGTH)
    }

    private companion object {
        const val MAX_DESTINATION_NAME_LENGTH = 512
    }
}

fun interface DestinationSearchProvider {
    @WorkerThread
    fun search(query: String): List<Destination>
}

fun interface RouteProvider {
    @WorkerThread
    fun calculate(origin: GeoPoint, destination: Destination): RoutePlan
}

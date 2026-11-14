package com.dharmil.reconnector.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.dharmil.reconnector.navigation.GeoPoint
import com.dharmil.reconnector.navigation.LocationFix
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AndroidCurrentLocationProvider(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val timeoutHandler = Handler(Looper.getMainLooper())

    suspend fun currentFix(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): LocationFix {
        require(timeoutMillis > 0L)
        return suspendCancellableCoroutine { continuation ->
            val fine = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val coarse = appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            require(fine || coarse) { "Location permission is required" }

            val provider = selectLocationProvider(
                fine,
                gpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
                    .getOrDefault(false),
                networkEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
                    .getOrDefault(false),
            ) ?: throw IllegalStateException("Enable location services to plan a route")
            val completed = AtomicBoolean(false)
            val cancellation = CancellationSignal()
            val listener = AtomicReference<LocationListener?>()
            lateinit var timeout: Runnable

            fun finish(location: Location?, cancelRequest: Boolean = false) {
                if (!completed.compareAndSet(false, true)) return
                timeoutHandler.removeCallbacks(timeout)
                listener.get()?.let { runCatching { locationManager.removeUpdates(it) } }
                if (cancelRequest) cancellation.cancel()
                val result = runCatching {
                    location?.toFix() ?: throw IllegalStateException("No current location received")
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }

            timeout = Runnable { finish(null, cancelRequest = true) }
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    cancellation.cancel()
                    listener.get()?.let { runCatching { locationManager.removeUpdates(it) } }
                    timeoutHandler.removeCallbacks(timeout)
                }
            }
            timeoutHandler.postDelayed(timeout, timeoutMillis)
            try {
                registerLocationRequest(
                    completed = completed,
                    register = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            requestCurrentLocation(provider, cancellation) { location -> finish(location) }
                        } else {
                            val activeListener = LocationListener { location -> finish(location) }
                            listener.set(activeListener)
                            @Suppress("DEPRECATION")
                            locationManager.requestSingleUpdate(provider, activeListener, Looper.getMainLooper())
                        }
                    },
                    cleanup = {
                        cancellation.cancel()
                        listener.get()?.let { runCatching { locationManager.removeUpdates(it) } }
                    },
                )
            } catch (failure: Exception) {
                if (completed.compareAndSet(false, true)) {
                    timeoutHandler.removeCallbacks(timeout)
                    cancellation.cancel()
                    listener.get()?.let { runCatching { locationManager.removeUpdates(it) } }
                    if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestCurrentLocation(
        provider: String,
        cancellation: CancellationSignal,
        callback: (Location?) -> Unit,
    ) {
        locationManager.getCurrentLocation(provider, cancellation, appContext.mainExecutor, callback)
    }

    private fun Location.toFix() = locationFixFromFields(
        latitude = latitude,
        longitude = longitude,
        timestampMillis = time,
        horizontalAccuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
    )

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L
    }
}

internal fun locationFixFromFields(
    latitude: Double,
    longitude: Double,
    timestampMillis: Long,
    horizontalAccuracyMeters: Double?,
) = LocationFix(
    point = GeoPoint(latitude, longitude),
    timestampMillis = timestampMillis,
    horizontalAccuracyMeters = horizontalAccuracyMeters,
)

internal fun selectLocationProvider(fineLocationAllowed: Boolean, gpsEnabled: Boolean, networkEnabled: Boolean): String? =
    when {
        fineLocationAllowed && gpsEnabled -> LocationManager.GPS_PROVIDER
        networkEnabled -> LocationManager.NETWORK_PROVIDER
        gpsEnabled -> LocationManager.GPS_PROVIDER
        else -> null
    }

internal fun registerLocationRequest(
    completed: AtomicBoolean,
    register: () -> Unit,
    cleanup: () -> Unit,
) {
    if (completed.get()) return
    register()
    if (completed.get()) cleanup()
}

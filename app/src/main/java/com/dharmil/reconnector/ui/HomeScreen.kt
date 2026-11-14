package com.dharmil.reconnector.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dharmil.reconnector.location.AndroidCurrentLocationProvider
import com.dharmil.reconnector.dash.NavigationProjectionService
import com.dharmil.reconnector.ReConnectorApplication
import com.dharmil.reconnector.navigation.NavigationRepository
import com.dharmil.reconnector.navigation.RoutePlan
import com.dharmil.reconnector.navigation.provider.Destination
import com.dharmil.reconnector.navigation.store.OfflineMapStatus
import com.dharmil.reconnector.navigation.store.RouteCorridorGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val repository = remember(context) { NavigationRepository(context) }
    val offlineMaps = (context.applicationContext as ReConnectorApplication).offlineMapRepository
    val projectionPreferences = remember(context) {
        context.getSharedPreferences(NavigationProjectionService.PROJECTION_STATE_PREFERENCES, Context.MODE_PRIVATE)
    }
    val restoredProjectionState = remember(context) {
        NavigationProjectionService.restoreProjectionUiState(context)
    }
    val locationProvider = remember(context) { AndroidCurrentLocationProvider(context) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var destinations by remember { mutableStateOf(emptyList<Destination>()) }
    var savedRoutes by remember { mutableStateOf(emptyList<RoutePlan>()) }
    var skippedSavedRouteCount by remember { mutableStateOf(0) }
    var selectedRoute by remember { mutableStateOf<RoutePlan?>(null) }
    var offlineStatus by remember { mutableStateOf(OfflineMapStatus()) }
    var pendingDestination by remember { mutableStateOf<Destination?>(null) }
    var pendingProjectionRoute by remember { mutableStateOf<RoutePlan?>(null) }
    var ssid by remember { mutableStateOf("") }
    var bikeIp by rememberSaveable { mutableStateOf(DEFAULT_BIKE_IP) }
    var busy by remember { mutableStateOf(false) }
    var offlinePreparing by remember { mutableStateOf(false) }
    var projectionRequested by remember(projectionPreferences) {
        mutableStateOf(restoredProjectionState.requested)
    }
    var projectionStopping by remember(projectionPreferences) {
        mutableStateOf(restoredProjectionState.stopping)
    }
    var activeRouteId by remember(projectionPreferences) {
        mutableStateOf(restoredProjectionState.routeId)
    }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        val stoppedReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action == NavigationProjectionService.ACTION_STOPPED) {
                    error = intent.getStringExtra(NavigationProjectionService.EXTRA_STOP_REASON)
                    projectionRequested = false
                    projectionStopping = false
                    activeRouteId = null
                    projectionPreferences.edit()
                        .putBoolean(NavigationProjectionService.PROJECTION_REQUESTED, false)
                        .putBoolean(NavigationProjectionService.PROJECTION_STOPPING, false)
                        .remove(NavigationProjectionService.PROJECTION_ROUTE_ID)
                        .apply()
                } else if (intent?.action == NavigationProjectionService.ACTION_STATE_CHANGED) {
                    projectionRequested = intent.getBooleanExtra(
                        NavigationProjectionService.EXTRA_PROJECTION_REQUESTED,
                        false,
                    )
                    projectionStopping = intent.getBooleanExtra(
                        NavigationProjectionService.EXTRA_PROJECTION_STOPPING,
                        false,
                    )
                    activeRouteId = intent.getStringExtra(NavigationProjectionService.EXTRA_ROUTE_ID)
                    projectionPreferences.edit()
                        .putBoolean(NavigationProjectionService.PROJECTION_REQUESTED, projectionRequested)
                        .putBoolean(NavigationProjectionService.PROJECTION_STOPPING, projectionStopping)
                        .putString(NavigationProjectionService.PROJECTION_ROUTE_ID, activeRouteId)
                        .apply()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(NavigationProjectionService.ACTION_STATE_CHANGED)
            addAction(NavigationProjectionService.ACTION_STOPPED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(stoppedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(stoppedReceiver, filter)
        }
        onDispose { context.unregisterReceiver(stoppedReceiver) }
    }

    DisposableEffect(offlineMaps, selectedRoute?.id) {
        val routeId = selectedRoute?.id
        if (routeId == null) {
            offlineStatus = OfflineMapStatus()
            onDispose { }
        } else {
            val stopObserving = offlineMaps.observe(routeId) {
                offlineStatus = it
                if (offlinePreparing && (it.downloading || it.complete || it.issue != null)) {
                    offlinePreparing = false
                }
            }
            onDispose(stopObserving)
        }
    }

    fun calculateRoute(destination: Destination) {
        busy = true
        error = null
        selectedRoute = null
        scope.launch {
            try {
                selectedRoute = withContext(Dispatchers.IO) {
                    val origin = locationProvider.currentFix().point
                    repository.calculate(origin, destination)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not calculate route"
            } finally {
                busy = false
            }
        }
    }

    fun startProjection(route: RoutePlan) {
        if (ssid.trim().isEmpty()) {
            error = "Enter the Tripper Wi-Fi name shown by the bike"
            return
        }
        busy = true
        error = null
        val prepareIntent = Intent(context, NavigationProjectionService::class.java)
            .setAction(NavigationProjectionService.ACTION_PREPARE)
        try {
            context.startForegroundService(prepareIntent)
        } catch (failure: Exception) {
            busy = false
            error = failure.message ?: "Could not start navigation service"
            return
        }
        scope.launch {
            try {
                val listing = withContext(Dispatchers.IO) {
                    repository.save(route)
                    repository.savedRoutes()
                }
                savedRoutes = listing.routes
                skippedSavedRouteCount = listing.skippedInvalidCount
                val intent = Intent(context, NavigationProjectionService::class.java)
                    .setAction(NavigationProjectionService.ACTION_START)
                    .putExtra(NavigationProjectionService.EXTRA_ROUTE_ID, route.id)
                    .putExtra(NavigationProjectionService.EXTRA_SSID, ssid.trim())
                    .putExtra(NavigationProjectionService.EXTRA_BIKE_IP, bikeIp.trim())
                activeRouteId = route.id
                projectionRequested = true
                projectionStopping = false
                projectionPreferences.edit()
                    .putBoolean(NavigationProjectionService.PROJECTION_REQUESTED, true)
                    .putBoolean(NavigationProjectionService.PROJECTION_STOPPING, false)
                    .putString(NavigationProjectionService.PROJECTION_ROUTE_ID, route.id)
                    .apply()
                context.startService(intent)
            } catch (cancelled: CancellationException) {
                context.stopService(Intent(context, NavigationProjectionService::class.java))
                throw cancelled
            } catch (failure: Exception) {
                projectionRequested = false
                projectionStopping = false
                activeRouteId = null
                projectionPreferences.edit()
                    .putBoolean(NavigationProjectionService.PROJECTION_REQUESTED, false)
                    .putBoolean(NavigationProjectionService.PROJECTION_STOPPING, false)
                    .remove(NavigationProjectionService.PROJECTION_ROUTE_ID)
                    .apply()
                context.stopService(Intent(context, NavigationProjectionService::class.java))
                error = failure.message ?: "Could not start TFT projection"
            } finally {
                busy = false
            }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val destination = pendingDestination
        pendingDestination = null
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            destination?.let(::calculateRoute)
        } else {
            error = "Allow location access to plan a route from your current position"
        }
    }

    val projectionPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val route = pendingProjectionRoute
        pendingProjectionRoute = null
        val locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val nearbyWifiAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (route != null && locationAllowed && nearbyWifiAllowed && notificationsAllowed) {
            startProjection(route)
        } else if (route != null) {
            error = "Grant location, nearby-device, and notification access to project navigation"
        }
    }

    fun requestProjection(route: RoutePlan) {
        val permissions = buildList {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) !=
                    PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isEmpty()) startProjection(route) else {
            pendingProjectionRoute = route
            projectionPermissions.launch(permissions.toTypedArray())
        }
    }

    LaunchedEffect(repository) {
        try {
            val listing = withContext(Dispatchers.IO) { repository.savedRoutes() }
            savedRoutes = listing.routes
            skippedSavedRouteCount = listing.skippedInvalidCount
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not load saved routes"
        }
    }

    LaunchedEffect(projectionRequested, activeRouteId, savedRoutes) {
        if (projectionRequested && activeRouteId != null) {
            selectedRoute = savedRoutes.firstOrNull { it.id == activeRouteId }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("RE Connector", style = MaterialTheme.typography.headlineMedium)
            Text("Himalayan 450 · Tripper navigation", style = MaterialTheme.typography.titleMedium)
            Text("Search uses OpenStreetMap. Routes use Valhalla motorcycle routing.")
            if (projectionRequested || projectionStopping) {
                Text(
                    if (projectionStopping) "Stopping TFT projection…"
                    else "Projection requested · ${selectedRoute?.destinationName ?: "route $activeRouteId"}",
                )
                OutlinedButton(
                    onClick = {
                        projectionStopping = true
                        projectionPreferences.edit()
                            .putBoolean(NavigationProjectionService.PROJECTION_STOPPING, true)
                            .apply()
                        context.startService(
                            Intent(context, NavigationProjectionService::class.java)
                                .setAction(NavigationProjectionService.ACTION_STOP),
                        )
                    },
                    enabled = !projectionStopping,
                ) { Text("Stop TFT projection") }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(MAX_QUERY_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Destination") },
                singleLine = true,
                readOnly = projectionRequested || offlinePreparing,
            )
            Button(
                onClick = {
                    busy = true
                    error = null
                    destinations = emptyList()
                    val submittedQuery = query.trim()
                    scope.launch {
                        try {
                            destinations = withContext(Dispatchers.IO) { repository.search(submittedQuery) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Destination search failed"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = query.trim().length >= MIN_QUERY_LENGTH && !busy && !offlinePreparing && !projectionRequested,
            ) { Text("Search destinations") }

            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            destinations.forEach { destination ->
                OutlinedButton(
                    onClick = {
                        selectedRoute = null
                        error = null
                        val hasLocation = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED ||
                            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED
                        if (hasLocation) calculateRoute(destination) else {
                            pendingDestination = destination
                            locationPermission.launch(LOCATION_PERMISSIONS)
                        }
                    },
                    enabled = !busy && !offlinePreparing && !projectionRequested,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Plan from current location\n${destination.name}") }
            }

            selectedRoute?.let { route ->
                Text(route.destinationName, style = MaterialTheme.typography.titleLarge)
                Text("Route ready · ${route.points.size} map points · ${route.maneuvers.size} turns")
                route.maneuvers.take(MAX_VISIBLE_TURNS).forEach { Text("• ${it.instruction}") }
                Text(if (offlinePreparing) "Preparing offline route and map…" else offlineStatus.description())
                if (offlineStatus.downloading) {
                    OutlinedButton(
                        onClick = { offlineMaps.pause(route.id) },
                        enabled = !offlinePreparing && !projectionRequested && !projectionStopping,
                    ) { Text("Pause offline map download") }
                } else if (!offlineStatus.complete) {
                    Button(
                        onClick = {
                            offlinePreparing = true
                            error = null
                            scope.launch {
                                var waitingForStatus = false
                                try {
                                    val listing = withContext(Dispatchers.IO) {
                                        repository.save(route)
                                        repository.savedRoutes()
                                    }
                                    savedRoutes = listing.routes
                                    skippedSavedRouteCount = listing.skippedInvalidCount
                                    val corridor = withContext(Dispatchers.Default) {
                                        RouteCorridorGeometry.create(route)
                                    }
                                    waitingForStatus = true
                                    offlineMaps.download(route, corridor)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Could not prepare offline map"
                                } finally {
                                    if (!waitingForStatus) offlinePreparing = false
                                }
                            }
                        },
                        enabled = !busy && !offlinePreparing && !projectionRequested && !projectionStopping,
                    ) { Text(if (offlineStatus.completedResources > 0) "Resume offline map" else "Save route & download offline map") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val listing = withContext(Dispatchers.IO) {
                                        repository.save(route)
                                        repository.savedRoutes()
                                    }
                                    savedRoutes = listing.routes
                                    skippedSavedRouteCount = listing.skippedInvalidCount
                                    error = null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Could not save route"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && !offlinePreparing && !projectionRequested && !projectionStopping,
                    ) { Text("Save route") }
                }
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it.take(MAX_SSID_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tripper Wi-Fi name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = bikeIp,
                    onValueChange = { bikeIp = it.take(MAX_IP_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("TFT address") },
                    singleLine = true,
                )
                Button(
                    onClick = { requestProjection(route) },
                    enabled = !busy && !offlinePreparing && !projectionRequested && !projectionStopping,
                ) { Text("Connect & project to TFT") }
            }

            if (savedRoutes.isNotEmpty()) {
                Text("Saved routes", style = MaterialTheme.typography.titleLarge)
                savedRoutes.take(MAX_VISIBLE_SAVED_ROUTES).forEach { route ->
                    OutlinedButton(
                        onClick = { selectedRoute = route },
                        enabled = !offlinePreparing && !projectionRequested && !projectionStopping,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${route.destinationName} · ${route.maneuvers.size} turns") }
                }
            }

            if (skippedSavedRouteCount > 0) {
                Text("$skippedSavedRouteCount unreadable saved route(s) were skipped.")
            }

            Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val MIN_QUERY_LENGTH = 2
private const val MAX_QUERY_LENGTH = 200
private const val MAX_VISIBLE_TURNS = 5
private const val MAX_VISIBLE_SAVED_ROUTES = 20
private const val MAX_SSID_LENGTH = 32
private const val MAX_IP_LENGTH = 15
private const val DEFAULT_BIKE_IP = "192.168.1.1"
private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun OfflineMapStatus.description(): String {
    val resourceCount = requiredResources?.let { "$completedResources/$it resources" } ?: "$completedResources resources"
    val summary = "$completedTiles tiles · $resourceCount · ${completedBytes / 1_000_000} MB"
    return when {
        issue != null -> "$issue · $summary"
        complete -> "Offline map ready · $summary"
        downloading -> "Downloading offline map · $summary"
        completedResources > 0 -> "Offline map paused · $summary"
        else -> "No offline map saved. Download while online; the route stays saved offline either way."
    }
}

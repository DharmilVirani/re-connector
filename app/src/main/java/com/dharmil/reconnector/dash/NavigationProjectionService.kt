package com.dharmil.reconnector.dash

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.net.Network
import androidx.annotation.MainThread
import com.dharmil.reconnector.MainActivity
import com.dharmil.reconnector.dash.render.TripperMapRenderer
import com.dharmil.reconnector.dash.video.EncodedFrame
import com.dharmil.reconnector.dash.video.H264Encoder
import com.dharmil.reconnector.location.selectLocationProvider
import com.dharmil.reconnector.navigation.LocationFix
import com.dharmil.reconnector.navigation.GeoPoint
import com.dharmil.reconnector.navigation.RoutePlan
import com.dharmil.reconnector.navigation.RouteProgress
import com.dharmil.reconnector.navigation.RouteProgressCalculator
import com.dharmil.reconnector.navigation.store.SavedRouteStore
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NavigationProjectionService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newFixedThreadPool(2)
    private val progressWorker: ExecutorService = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val stopStarted = AtomicBoolean(false)
    private val startAccepted = AtomicBoolean(false)
    private val connectionStarted = AtomicBoolean(false)
    private var networkConnector: TripperNetworkConnector? = null
    @Volatile private var activeTransport: TripperTransport? = null
    @Volatile private var session: TripperDashSession? = null
    @Volatile private var requestedRouteId: String? = null
    @Volatile private var activeRoute: RoutePlan? = null
    @Volatile private var progress: RouteProgress? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var locationStaleTask: Runnable? = null
    private var encoder: H264Encoder? = null
    private var renderer: TripperMapRenderer? = null
    @Volatile private var stopReason: String? = null

    override fun onCreate() {
        super.onCreate()
        liveInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopActiveSession()
            ACTION_PREPARE -> prepareForegroundSession()
            ACTION_START -> startActiveSession(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopActiveSession()
        if (liveInstance === this) liveInstance = null
        super.onDestroy()
    }

    @MainThread
    private fun prepareForegroundSession() {
        if (stopStarted.get()) return
        try {
            startForegroundCompat(buildNotification("Preparing navigation"))
        } catch (failure: Exception) {
            stopWithError(failure.message ?: "Could not start navigation service")
        }
    }

    @MainThread
    private fun startActiveSession(intent: Intent) {
        if (!startAccepted.compareAndSet(false, true)) return
        val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)?.takeIf { it.length <= MAX_ROUTE_ID_LENGTH }
        val ssid = intent.getStringExtra(EXTRA_SSID)?.trim()?.takeIf(String::isNotEmpty)
        val bikeIp = intent.getStringExtra(EXTRA_BIKE_IP) ?: DEFAULT_BIKE_IP
        if (routeId == null || ssid == null) {
            stopWithError("Choose a saved route and enter the Tripper Wi-Fi name")
            return
        }
        requestedRouteId = routeId
        persistProjectionState(requested = true, stopping = false, routeId = routeId)
        val locationAllowed = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val nearbyWifiAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        if (!locationAllowed || !nearbyWifiAllowed) {
            stopWithError("Grant location and nearby-device permissions before starting navigation")
            return
        }
        try {
            startForegroundCompat(buildNotification("Requesting Tripper Wi-Fi"))
            networkConnector = TripperNetworkConnector(this).also { connector ->
                connector.request(ssid) { state ->
                    mainHandler.post {
                        if (stopStarted.get()) return@post
                        when (state) {
                            TripperNetworkState.Requesting -> updateNotification("Waiting for Tripper Wi-Fi")
                            is TripperNetworkState.Connected -> beginConnection(state.network, routeId, ssid, bikeIp)
                            TripperNetworkState.Unavailable -> stopWithError("Could not connect to Tripper Wi-Fi")
                            TripperNetworkState.Lost -> stopWithError("Tripper Wi-Fi disconnected")
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            stopWithError(failure.message ?: "Could not request Tripper Wi-Fi")
        }
    }

    private fun beginConnection(network: Network, routeId: String, ssid: String, bikeIp: String) {
        if (stopStarted.get() || !connectionStarted.compareAndSet(false, true)) return
        worker.execute {
            try {
                val route = SavedRouteStore(this).getAll().firstOrNull { it.id == routeId }
                    ?: error("Saved route is no longer available")
                val address = parseIpv4(bikeIp)
                val transport = TripperTransport.open(network, address)
                if (stopStarted.get()) {
                    transport.close()
                    return@execute
                }
                activeTransport = transport
                if (stopStarted.get()) {
                    transport.close()
                    activeTransport = null
                    return@execute
                }
                val dashSession = TripperDashSession(ssid, transport)
                dashSession.authenticate()
                mainHandler.post {
                    if (stopStarted.get()) {
                        transport.close()
                        return@post
                    }
                    activeRoute = route
                    session = dashSession
                    startProjectionPipeline(route, dashSession, transport)
                }
            } catch (failure: Exception) {
                mainHandler.post { stopWithError(failure.message ?: "Tripper connection failed") }
            }
        }
    }

    @MainThread
    private fun startProjectionPipeline(
        route: RoutePlan,
        dashSession: TripperDashSession,
        transport: TripperTransport,
    ) {
        try {
            val activeEncoder = H264Encoder(object : H264Encoder.Listener {
                override fun onEncodedFrame(frame: EncodedFrame) {
                    try {
                        dashSession.sendEncodedFrame(frame)
                    } catch (failure: Exception) {
                        mainHandler.post { stopWithError(failure.message ?: "TFT video stream stopped") }
                    }
                }

                override fun onEncoderError(error: Throwable) {
                    mainHandler.post { stopWithError(error.message ?: "H.264 encoder failed") }
                }
            })
            encoder = activeEncoder
            val surface = activeEncoder.start()
            val activeRenderer = TripperMapRenderer(this)
            renderer = activeRenderer
            activeRenderer.start(surface)
            activeRenderer.update(route, null)
            activeRoute = route
            startLocationTracking(route)
            startControlReceiver(dashSession, transport)
            worker.execute {
                try {
                    dashSession.beginNavigation(route, progress)
                    scheduler.scheduleAtFixedRate(
                        {
                            if (!stopStarted.get()) {
                                runCatching { dashSession.keepRouteCardAlive(route) }
                                val currentProgress = freshProgress()
                                runCatching { dashSession.sendNavigationInfo(route, currentProgress) }
                            }
                        },
                        KEEPALIVE_PERIOD_SECONDS,
                        KEEPALIVE_PERIOD_SECONDS,
                        TimeUnit.SECONDS,
                    )
                    mainHandler.post { updateNotification("Navigation streaming to Tripper") }
                } catch (failure: Exception) {
                    mainHandler.post { stopWithError(failure.message ?: "Could not start Tripper navigation") }
                }
            }
        } catch (failure: Exception) {
            stopWithError(failure.message ?: "Could not start map projection")
        }
    }

    private fun startControlReceiver(dashSession: TripperDashSession, transport: TripperTransport) {
        Thread({
            while (!stopStarted.get()) {
                try {
                    dashSession.handleIncoming(transport.receiveControl())
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: java.net.SocketException) {
                    if (!stopStarted.get()) mainHandler.post { stopWithError("Tripper control connection closed") }
                    return@Thread
                } catch (failure: Exception) {
                    if (!stopStarted.get()) mainHandler.post { stopWithError(failure.message ?: "Tripper control failed") }
                    return@Thread
                }
            }
        }, CONTROL_THREAD_NAME).apply { isDaemon = true; start() }
    }

    @MainThread
    private fun startLocationTracking(route: RoutePlan) {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        check(fine || coarse) { "Location permission is required for navigation" }
        val manager = getSystemService(LocationManager::class.java)
        val provider = selectLocationProvider(
            fine,
            runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false),
            runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false),
        ) ?: error("Enable location services to navigate")
        val listener = LocationListener { location -> onLocation(route, location) }
        manager.requestLocationUpdates(provider, LOCATION_INTERVAL_MILLIS, LOCATION_MIN_DISTANCE_METERS, listener, Looper.getMainLooper())
        locationManager = manager
        locationListener = listener
        scheduleStaleLocationRefresh(route)
    }

    private fun onLocation(route: RoutePlan, location: Location) {
        if (stopStarted.get()) return
        val fix = runCatching {
            LocationFix(
                point = GeoPoint(location.latitude, location.longitude),
                timestampMillis = location.time,
                horizontalAccuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            )
        }.getOrNull() ?: return
        try {
            progressWorker.execute {
                val updated = RouteProgressCalculator.update(route, progress, fix, System.currentTimeMillis())
                    ?: return@execute
                progress = updated
                mainHandler.post {
                    if (!stopStarted.get()) renderer?.update(route, updated)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Stop can shut the worker down after a provider callback is already queued.
        }
    }

    @MainThread
    private fun scheduleStaleLocationRefresh(route: RoutePlan) {
        val refresh = object : Runnable {
            override fun run() {
                if (stopStarted.get()) return
                val current = progress
                if (current == null || System.currentTimeMillis() - current.lastFixTimestampMillis > GPS_FRESHNESS_MILLIS) {
                    renderer?.update(route, null)
                }
                mainHandler.postDelayed(this, GPS_REFRESH_MILLIS)
            }
        }
        locationStaleTask = refresh
        mainHandler.postDelayed(refresh, GPS_REFRESH_MILLIS)
    }

    private fun freshProgress(): RouteProgress? = progress?.takeIf {
        System.currentTimeMillis() - it.lastFixTimestampMillis <= GPS_FRESHNESS_MILLIS
    }

    @MainThread
    private fun stopWithError(message: String) {
        stopReason = message.take(MAX_NOTIFICATION_TEXT_LENGTH)
        stopActiveSession()
    }

    @MainThread
    private fun stopActiveSession() {
        if (!stopStarted.compareAndSet(false, true)) return
        persistProjectionState(requested = requestedRouteId != null, stopping = true, routeId = requestedRouteId)
        networkConnector?.close()
        networkConnector = null
        locationStaleTask?.let(mainHandler::removeCallbacks)
        locationStaleTask = null
        locationListener?.let { listener ->
            locationManager?.let { manager -> runCatching { manager.removeUpdates(listener) } }
        }
        locationListener = null
        scheduler.shutdownNow()
        progressWorker.shutdownNow()
        val activeSession = session
        val activeTransport = activeTransport
        if (activeSession == null || activeTransport == null) {
            activeTransport?.close()
            closeProjectionResources()
            finishForegroundSession()
            worker.shutdownNow()
            return
        }
        worker.execute {
            runCatching { activeSession.stopProjection() }
            activeTransport.close()
            worker.shutdown()
            closeProjectionResourcesOnMain()
        }
    }

    private fun closeProjectionResourcesOnMain() {
        mainHandler.post {
            closeProjectionResources()
            finishForegroundSession()
        }
    }

    @MainThread
    private fun finishForegroundSession() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopReason?.let { reason ->
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification("Navigation stopped: $reason", ongoing = false),
            )
        }
        persistProjectionState(requested = false, stopping = false, routeId = null)
        broadcastStopped(stopReason)
        stopSelf()
    }

    private fun persistProjectionState(requested: Boolean, stopping: Boolean, routeId: String?) {
        getSharedPreferences(PROJECTION_STATE_PREFERENCES, MODE_PRIVATE).edit()
            .putBoolean(PROJECTION_REQUESTED, requested)
            .putBoolean(PROJECTION_STOPPING, stopping)
            .putString(PROJECTION_ROUTE_ID, routeId)
            .apply()
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_PROJECTION_REQUESTED, requested)
                .putExtra(EXTRA_PROJECTION_STOPPING, stopping)
                .putExtra(EXTRA_ROUTE_ID, routeId),
        )
    }

    private fun broadcastStopped(reason: String?) {
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName).putExtra(EXTRA_STOP_REASON, reason))
    }

    @MainThread
    private fun closeProjectionResources() {
        runCatching { renderer?.close() }
        renderer = null
        runCatching { encoder?.close() }
        encoder = null
        session = null
        activeTransport = null
        activeRoute = null
        progress = null
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String, ongoing: Boolean = true): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("RE Connector")
            .setContentText(text.take(MAX_NOTIFICATION_TEXT_LENGTH))
            .setContentIntent(openApp)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (ongoing) {
            val stop = PendingIntent.getService(
                this,
                STOP_REQUEST_CODE,
                Intent(this, NavigationProjectionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "Stop",
                    stop,
                ).build(),
            )
        }
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun parseIpv4(value: String): InetAddress {
        val octets = value.split('.')
        require(octets.size == IPV4_OCTET_COUNT) { "Enter a numeric IPv4 address" }
        val address = ByteArray(IPV4_OCTET_COUNT) { index ->
            val octet = octets[index]
            require(octet.isNotEmpty() && octet.length <= MAX_IPV4_DIGITS && octet.all(Char::isDigit))
            octet.toInt().also { require(it in 0..0xFF) }.toByte()
        }
        return InetAddress.getByAddress(address)
    }

    companion object {
        const val ACTION_START = "com.dharmil.reconnector.action.START"
        const val ACTION_STOP = "com.dharmil.reconnector.action.STOP"
        const val ACTION_PREPARE = "com.dharmil.reconnector.action.PREPARE"
        const val ACTION_STATE_CHANGED = "com.dharmil.reconnector.action.STATE_CHANGED"
        const val ACTION_STOPPED = "com.dharmil.reconnector.action.STOPPED"
        const val PROJECTION_STATE_PREFERENCES = "projection-state"
        const val PROJECTION_REQUESTED = "requested"
        const val PROJECTION_STOPPING = "stopping"
        const val PROJECTION_ROUTE_ID = "route_id"
        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_BIKE_IP = "bike_ip"
        const val EXTRA_STOP_REASON = "stop_reason"
        const val EXTRA_PROJECTION_REQUESTED = "projection_requested"
        const val EXTRA_PROJECTION_STOPPING = "projection_stopping"
        private const val DEFAULT_BIKE_IP = "192.168.1.1"
        private const val MAX_ROUTE_ID_LENGTH = 80
        private const val MAX_NOTIFICATION_TEXT_LENGTH = 100
        private const val NOTIFICATION_ID = 450
        private const val NOTIFICATION_CHANNEL_ID = "navigation-projection"
        private const val OPEN_APP_REQUEST_CODE = 451
        private const val STOP_REQUEST_CODE = 452
        private const val KEEPALIVE_PERIOD_SECONDS = 1L
        private const val LOCATION_INTERVAL_MILLIS = 1_000L
        private const val LOCATION_MIN_DISTANCE_METERS = 3f
        private const val GPS_FRESHNESS_MILLIS = 5_000L
        private const val GPS_REFRESH_MILLIS = 1_000L
        private const val CONTROL_THREAD_NAME = "re-connector-tripper-rx"
        private const val IPV4_OCTET_COUNT = 4
        private const val MAX_IPV4_DIGITS = 3
        @Volatile private var liveInstance: NavigationProjectionService? = null

        fun restoreProjectionUiState(context: Context): ProjectionUiState {
            val preferences = context.getSharedPreferences(PROJECTION_STATE_PREFERENCES, Context.MODE_PRIVATE)
            if (liveInstance == null) {
                preferences.edit()
                    .putBoolean(PROJECTION_REQUESTED, false)
                    .putBoolean(PROJECTION_STOPPING, false)
                    .remove(PROJECTION_ROUTE_ID)
                    .apply()
                return ProjectionUiState()
            }
            return ProjectionUiState(
                requested = preferences.getBoolean(PROJECTION_REQUESTED, false),
                stopping = preferences.getBoolean(PROJECTION_STOPPING, false),
                routeId = preferences.getString(PROJECTION_ROUTE_ID, null),
            )
        }
    }
}

data class ProjectionUiState(
    val requested: Boolean = false,
    val stopping: Boolean = false,
    val routeId: String? = null,
)

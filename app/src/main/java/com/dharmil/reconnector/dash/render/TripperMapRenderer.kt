package com.dharmil.reconnector.dash.render

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.MainThread
import com.dharmil.reconnector.map.MapStyleConfig
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import com.dharmil.reconnector.navigation.GeoPoint
import com.dharmil.reconnector.navigation.RoutePlan
import com.dharmil.reconnector.navigation.RouteProgress
import com.dharmil.reconnector.dash.video.TripperVideoSpec

/** Renders the navigation view to the encoder's input surface without mirroring the phone display. */
@MainThread
class TripperMapRenderer(private val context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: MapPresentation? = null

    fun start(encoderSurface: Surface) {
        checkMainThread()
        check(virtualDisplay == null) { "Map renderer is already running" }
        check(encoderSurface.isValid) { "Encoder surface is not valid" }

        val display = checkNotNull(
            displayManager.createVirtualDisplay(
                DISPLAY_NAME,
                TripperVideoSpec.WIDTH_PX,
                TripperVideoSpec.HEIGHT_PX,
                DISPLAY_DENSITY_DPI,
                encoderSurface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            ),
        ) { "Android could not create the Tripper virtual display" }

        var mapPresentation: MapPresentation? = null
        try {
            mapPresentation = MapPresentation(context, display.display)
            mapPresentation.show()
            virtualDisplay = display
            presentation = mapPresentation
        } catch (error: Throwable) {
            try {
                mapPresentation?.dismiss()
            } finally {
                try {
                    mapPresentation?.destroyMapView()
                } finally {
                    display.release()
                }
            }
            throw error
        }
    }

    fun update(route: RoutePlan?, progress: RouteProgress?) {
        checkMainThread()
        presentation?.update(route, progress)
    }

    override fun close() {
        checkMainThread()
        val activePresentation = presentation
        val activeDisplay = virtualDisplay
        presentation = null
        virtualDisplay = null
        try {
            activePresentation?.dismiss()
        } finally {
            try {
                activePresentation?.destroyMapView()
            } finally {
                activeDisplay?.release()
            }
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Map renderer must run on the main thread" }
    }

    private class MapPresentation(context: Context, display: Display) : Presentation(context, display) {
        private lateinit var mapView: MapView
        private lateinit var turnTitle: TextView
        private lateinit var turnDistance: TextView
        private lateinit var mapStatus: TextView
        private var map: MapLibreMap? = null
        private var routeSource: GeoJsonSource? = null
        private var riderSource: GeoJsonSource? = null
        private var currentRoute: RoutePlan? = null
        private var currentProgress: RouteProgress? = null
        private var mapViewCreated = false
        private var mapViewCreationAttempted = false

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setCancelable(false)
            val uiContext = getContext()
            mapView = MapView(uiContext)
            turnTitle = TextView(uiContext)
            turnDistance = TextView(uiContext)
            mapStatus = TextView(uiContext).apply {
                setTextColor(Color.WHITE)
                textSize = MAP_STATUS_SP
                maxLines = 2
                setPadding(STATUS_PADDING_DP, STATUS_PADDING_DP, STATUS_PADDING_DP, STATUS_PADDING_DP)
                background = roundedBackground(CARD_COLOR, ATTRIBUTION_RADIUS_DP)
                text = "Loading map"
            }

            val root = FrameLayout(uiContext)
            root.setBackgroundColor(Color.rgb(16, 22, 30))
            root.addView(
                mapView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            root.addView(createTurnCard(), turnCardLayout())
            root.addView(createAttribution(), attributionLayout())
            root.addView(mapStatus, mapStatusLayout())
            setContentView(root)
            mapViewCreationAttempted = true
            mapView.onCreate(savedInstanceState)
            mapViewCreated = true
            mapView.setMaximumFps(MAX_MAP_FPS)
            mapView.addOnDidFailLoadingMapListener {
                if (!isMapActive()) return@addOnDidFailLoadingMapListener
                mapStatus.text = "Map unavailable · check connection or offline coverage"
                mapStatus.visibility = View.VISIBLE
            }
            mapView.getMapAsync { readyMap ->
                if (!isMapActive()) return@getMapAsync
                map = readyMap
                readyMap.uiSettings.isCompassEnabled = false
                readyMap.setStyle(MapStyleConfig.URL) { style ->
                    if (!isMapActive()) return@setStyle
                    mapStatus.visibility = View.GONE
                    installNavigationLayers(style)
                }
            }
            update(null, null)
        }

        override fun onStart() {
            super.onStart()
            if (mapViewCreated) {
                mapView.onStart()
                mapView.onResume()
            }
        }

        override fun onStop() {
            if (mapViewCreated && !mapView.isDestroyed) {
                mapView.onPause()
                mapView.onStop()
            }
            super.onStop()
        }

        fun update(route: RoutePlan?, progress: RouteProgress?) {
            require(progress == null || route?.id == progress.routeId) { "Route progress belongs to another route" }
            val routeChanged = currentRoute?.id != route?.id
            currentRoute = route
            currentProgress = progress
            when {
                route == null -> {
                    turnTitle.text = "Navigation ready"
                    turnDistance.text = "Load a route to begin"
                }
                progress?.isOffRoute == true -> {
                    turnTitle.text = "Off route"
                    turnDistance.text = "Recalculation unavailable"
                }
                progress == null -> {
                    turnTitle.text = route.destinationName
                    turnDistance.text = "GPS unavailable"
                }
                else -> {
                    val maneuver = route.maneuvers.getOrNull(progress.nextManeuverIndex)
                    turnTitle.text = maneuver?.instruction ?: "Continue to ${route.destinationName}"
                    turnDistance.text = maneuver?.let {
                        (route.distanceAt(it.pointIndex) - progress.distanceAlongRouteMeters)
                            .coerceAtLeast(0.0).toDistanceLabel() + " to turn"
                    } ?: "${progress.remainingMeters.toDistanceLabel()} remaining"
                }
            }

            val style = map?.style ?: return
            if (routeChanged) updateRouteSource(style, route)
            updateRiderSource(style, progress?.lastFix)
            if (progress != null) {
                map?.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(progress.lastFix.toLatLng())
                            .zoom(FOLLOW_ZOOM)
                            .build(),
                    ),
                )
            } else if (routeChanged && route != null) {
                moveToRouteStart(route)
            }
        }

        fun destroyMapView() {
            if (mapViewCreationAttempted && !mapView.isDestroyed) {
                mapView.onDestroy()
            }
            mapViewCreated = false
            mapViewCreationAttempted = false
            map = null
            routeSource = null
            riderSource = null
        }

        private fun isMapActive() = mapViewCreated && !mapView.isDestroyed

        private fun installNavigationLayers(style: Style) {
            routeSource = GeoJsonSource(ROUTE_SOURCE_ID, emptyRouteGeometry())
            riderSource = GeoJsonSource(RIDER_SOURCE_ID, Point.fromLngLat(0.0, 0.0))
            style.addSource(checkNotNull(routeSource))
            style.addSource(checkNotNull(riderSource))
            style.addLayer(
                LineLayer(ROUTE_CASING_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                    lineColor(Color.WHITE),
                    lineWidth(ROUTE_CASING_WIDTH),
                    lineCap("round"),
                    lineJoin("round"),
                ),
            )
            style.addLayer(
                LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                    lineColor(ROUTE_COLOR),
                    lineWidth(ROUTE_WIDTH),
                    lineCap("round"),
                    lineJoin("round"),
                ),
            )
            style.addLayer(
                CircleLayer(RIDER_LAYER_ID, RIDER_SOURCE_ID).withProperties(
                    circleColor(ROUTE_COLOR),
                    circleRadius(RIDER_RADIUS),
                    circleStrokeColor(Color.WHITE),
                    circleStrokeWidth(RIDER_STROKE_WIDTH),
                ),
            )
            updateRouteSource(style, currentRoute)
            updateRiderSource(style, currentProgress?.lastFix)
            currentProgress?.let { progress ->
                map?.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(progress.lastFix.toLatLng()).zoom(FOLLOW_ZOOM).build(),
                    ),
                )
            }
        }

        private fun updateRouteSource(style: Style, route: RoutePlan?) {
            val source = routeSource ?: style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) ?: return
            routeSource = source
            source.setGeoJson(route?.let(::routeGeometry) ?: emptyRouteGeometry())
            if (route != null) moveToRouteStart(route)
        }

        private fun updateRiderSource(style: Style, point: GeoPoint?) {
            val source = riderSource ?: style.getSourceAs<GeoJsonSource>(RIDER_SOURCE_ID) ?: return
            riderSource = source
            if (point == null) {
                source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            } else {
                source.setGeoJson(Point.fromLngLat(point.longitude, point.latitude))
            }
        }

        private fun moveToRouteStart(route: RoutePlan) {
            map?.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(route.points.first().toLatLng())
                        .zoom(ROUTE_OVERVIEW_ZOOM)
                        .build(),
                ),
            )
        }

        private fun createTurnCard(): FrameLayout {
            val card = FrameLayout(getContext()).apply {
                background = roundedBackground(CARD_COLOR, CARD_RADIUS_DP)
                setPadding(CARD_HORIZONTAL_PADDING_DP, CARD_VERTICAL_PADDING_DP, CARD_HORIZONTAL_PADDING_DP, CARD_VERTICAL_PADDING_DP)
            }
            val text = android.widget.LinearLayout(getContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            turnTitle.apply {
                setTextColor(Color.WHITE)
                textSize = TURN_TITLE_SP
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            turnDistance.apply {
                setTextColor(Color.LTGRAY)
                textSize = TURN_DISTANCE_SP
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            text.addView(turnTitle)
            text.addView(turnDistance)
            card.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return card
        }

        private fun createAttribution(): TextView = TextView(getContext()).apply {
            text = MapStyleConfig.ATTRIBUTION
            setTextColor(Color.WHITE)
            textSize = ATTRIBUTION_SP
            maxLines = 2
            gravity = Gravity.CENTER
            setPadding(ATTRIBUTION_PADDING_DP, ATTRIBUTION_PADDING_DP / 2, ATTRIBUTION_PADDING_DP, ATTRIBUTION_PADDING_DP / 2)
            background = roundedBackground(CARD_COLOR, ATTRIBUTION_RADIUS_DP)
        }

        private fun turnCardLayout() = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ).apply {
            setMargins(OUTER_MARGIN_DP, OUTER_MARGIN_DP, OUTER_MARGIN_DP, 0)
        }

        private fun attributionLayout() = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            setMargins(OUTER_MARGIN_DP, 0, OUTER_MARGIN_DP, OUTER_MARGIN_DP)
        }

        private fun mapStatusLayout() = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply {
            setMargins(OUTER_MARGIN_DP, MAP_STATUS_TOP_MARGIN_DP, OUTER_MARGIN_DP, 0)
        }

        private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp
        }

        private fun emptyRouteGeometry() = LineString.fromLngLats(
            listOf(Point.fromLngLat(0.0, 0.0), Point.fromLngLat(0.000001, 0.000001)),
        )

        private fun routeGeometry(route: RoutePlan) = LineString.fromLngLats(
            route.points.map { Point.fromLngLat(it.longitude, it.latitude) },
        )

        private fun GeoPoint.toLatLng() = LatLng(latitude, longitude)

        private fun Double.toDistanceLabel(): String =
            if (this >= 1_000.0) "%.1f km".format(this / 1_000.0) else "${toInt()} m"

        private companion object {
            const val ROUTE_SOURCE_ID = "re-route"
            const val ROUTE_CASING_LAYER_ID = "re-route-casing"
            const val ROUTE_LAYER_ID = "re-route-line"
            const val RIDER_SOURCE_ID = "re-rider"
            const val RIDER_LAYER_ID = "re-rider-point"
            const val ROUTE_COLOR = Color.BLUE
            const val CARD_COLOR = 0xDD101820.toInt()
            const val ROUTE_OVERVIEW_ZOOM = 13.0
            const val FOLLOW_ZOOM = 16.0
            const val MAX_MAP_FPS = TripperVideoSpec.FRAME_RATE
            const val ROUTE_CASING_WIDTH = 9f
            const val ROUTE_WIDTH = 5f
            const val RIDER_RADIUS = 8f
            const val RIDER_STROKE_WIDTH = 2f
            const val CARD_RADIUS_DP = 12f
            const val ATTRIBUTION_RADIUS_DP = 6f
            const val ATTRIBUTION_SP = 8f
            const val MAP_STATUS_SP = 10f
            const val TURN_TITLE_SP = 18f
            const val TURN_DISTANCE_SP = 13f
            const val CARD_HORIZONTAL_PADDING_DP = 12
            const val CARD_VERTICAL_PADDING_DP = 8
            const val ATTRIBUTION_PADDING_DP = 4
            const val STATUS_PADDING_DP = 6
            const val MAP_STATUS_TOP_MARGIN_DP = 92
            const val OUTER_MARGIN_DP = 8
        }
    }

    private companion object {
        const val DISPLAY_NAME = "RE Connector navigation"
        const val DISPLAY_DENSITY_DPI = 160
    }
}

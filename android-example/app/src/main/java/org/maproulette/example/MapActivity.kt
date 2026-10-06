package org.maproulette.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maproulette.sdk.Bounds
import org.maproulette.example.auth.AppSession
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.TaskFilter
import org.maproulette.sdk.TaskId

/** Online map demonstration; location is requested only when the user asks. */
class MapActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: AppSession
    private lateinit var sessionClient: AppSession.SessionClient
    private val client get() = sessionClient.client
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var searchButton: Button
    private var map: MapLibreMap? = null
    private var source: GeoJsonSource? = null
    private var request: Job? = null
    /** Task ids of the shown markers, in result order, for "Next task". */
    private var shownIds: List<TaskId> = emptyList()
    private var locationTimeout: Job? = null
    private var locationListener: LocationListener? = null
    private val locations by lazy { getSystemService(LocationManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = AppSession.get(this)
        sessionClient = session.newClient()
        MapLibre.getInstance(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        val controls = LinearLayout(this)
        searchButton = Button(this).apply {
            text = "Search this area"
            isEnabled = false
            setOnClickListener { searchArea() }
        }
        controls.addView(searchButton, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply {
            text = "My location"
            setOnClickListener { locate() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        status = TextView(this).apply {
            text = "Loading map…"
            setPadding(16, 8, 16, 8)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        mapView = MapView(this)
        root.addView(mapView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener {
            status.text = "Map could not load. Check your connection and reopen the map."
        }
        mapView.getMapAsync { readyMap ->
            map = readyMap
            if (savedInstanceState == null) {
                readyMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(40.7608, -111.8910)).zoom(14.0).build()
            }
            readyMap.addOnCameraMoveStartedListener {
                request?.cancel()
                status.text = "Tap Search this area to load tasks here."
            }
            readyMap.addOnMapClickListener { coordinate -> selectTask(coordinate) }
            readyMap.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")) { style ->
                source = GeoJsonSource("tasks", FeatureCollection.fromFeatures(emptyArray()))
                style.addSource(requireNotNull(source))
                style.addLayer(CircleLayer("task-points", "tasks").withProperties(
                    circleRadius(8f), circleColor("#176B52"),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                ))
                // Keep MapLibre's attribution control, including the basemap sources.
                searchButton.isEnabled = true
                status.text = "Pan or zoom, then tap Search this area."
            }
        }
        var observedGeneration = session.view.value.generation
        scope.launch {
            session.view.collect { view ->
                if (view.generation != observedGeneration) {
                    request?.cancel()
                    sessionClient.close()
                    sessionClient = session.newClient()
                    source?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                    shownIds = emptyList()
                    status.text = "Session changed. Tap Search this area."
                    observedGeneration = view.generation
                }
            }
        }
    }

    private fun searchArea() {
        val readyMap = map ?: return
        if (readyMap.cameraPosition.zoom < 12.0) {
            status.text = "Zoom in closer to search nearby tasks."
            return
        }
        val visible = readyMap.projection.visibleRegion.latLngBounds
        val bounds = try {
            Bounds(visible.longitudeWest, visible.latitudeSouth, visible.longitudeEast, visible.latitudeNorth)
        } catch (_: IllegalArgumentException) {
            status.text = "Zoom in or move away from the date line, then search again."
            return
        }
        request?.cancel()
        source?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        shownIds = emptyList()
        status.text = "Loading tasks…"
        request = scope.launch {
            try {
                // Choice tasks only, without ones found stale (cct=3&excludeStale=true). Markers carry no
                // payload, so the task screen re-checks and says "Not available on mobile" otherwise.
                val tasks = client.findTaskMarkers(TaskFilter(emptyList(), bounds, choiceOnly = true), limit = 100)
                val features = tasks.mapNotNull { task ->
                    val point = task.point?.let(::taskPoint) ?: return@mapNotNull null
                    Feature.fromGeometry(point).apply { addStringProperty("taskId", task.id.value.toString()) }
                }
                source?.setGeoJson(FeatureCollection.fromFeatures(features))
                shownIds = tasks.filter { it.point?.let(::taskPoint) != null }.map { it.id }
                val extra = if (tasks.size == 100) " Showing up to 100 tasks; more may exist. Zoom in." else ""
                val missing = if (features.size < tasks.size) " Some tasks have no usable point." else ""
                status.text = if (tasks.isEmpty()) "No multiple-choice tasks here. Try another area."
                    else "${features.size} tasks shown. Tap a dot.$extra$missing"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = errorMessage(error)
            }
        }
    }

    private fun selectTask(coordinate: LatLng): Boolean {
        val readyMap = map ?: return false
        val pixel = readyMap.projection.toScreenLocation(coordinate)
        val hitRadius = 18f * resources.displayMetrics.density
        val hit = readyMap.queryRenderedFeatures(
            RectF(pixel.x - hitRadius, pixel.y - hitRadius, pixel.x + hitRadius, pixel.y + hitRadius),
            "task-points",
        ).firstOrNull() ?: return false
        val id = hit.getStringProperty("taskId")?.toLongOrNull()?.let(::TaskId) ?: return false
        @Suppress("DEPRECATION") // Plain Activity result API, as for AppAuth.
        startActivityForResult(TaskActivity.intent(this, id, shownIds), TaskActivity.REQUEST)
        return true
    }

    @Deprecated("Plain Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // A resolved or skipped task changes the markers: search the same area again.
        if (requestCode == TaskActivity.REQUEST && data?.getBooleanExtra(TaskActivity.EXTRA_CHANGED, false) == true) {
            searchArea()
        }
    }

    private fun locate() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        } else {
            requestLocation()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) requestLocation()
            else status.text = "Location permission declined. Pan and zoom to choose an area."
        }
    }

    @SuppressLint("MissingPermission") // Invoked only after coarse or fine runtime permission is granted.
    private fun requestLocation() {
        stopLocation()
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { locations.isProviderEnabled(it) }
        if (provider == null) {
            status.text = "Location is unavailable. Pan and zoom to choose an area."
            return
        }
        status.text = "Finding your location…"
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (locationListener !== this) return
                stopLocation()
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 14.0))
                status.text = "Location found. Tap Search this area."
            }
            override fun onProviderDisabled(provider: String) {
                if (locationListener !== this) return
                stopLocation()
                status.text = "Location is unavailable. You can still pan and zoom."
            }
            override fun onProviderEnabled(provider: String) = Unit
            @Deprecated("Required on older Android versions")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        locationListener = listener
        try {
            locations.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            locationTimeout = scope.launch {
                delay(20_000)
                stopLocation()
                status.text = "No location received. Try again or pan to your area."
            }
        } catch (_: SecurityException) {
            stopLocation()
            status.text = "Location permission is unavailable. Pan to your area instead."
        } catch (_: IllegalArgumentException) {
            stopLocation()
            status.text = "Location is unavailable. Pan to your area instead."
        }
    }

    private fun stopLocation() {
        locationTimeout?.cancel()
        locationTimeout = null
        locationListener?.let { locations.removeUpdates(it) }
        locationListener = null
    }

    private fun errorMessage(error: Exception): String = if (error is MapRouletteException) {
        "Could not load tasks (${error.kind.name.lowercase()}). Tap Search this area to retry."
    } else {
        "Could not load tasks. Tap Search this area to retry."
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() {
        if (request?.isActive == true) status.text = "Tap Search this area to load tasks here."
        request?.cancel()
        stopLocation()
        mapView.onStop()
        super.onStop()
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        stopLocation()
        scope.cancel()
        sessionClient.close()
        mapView.onDestroy()
        super.onDestroy()
    }
}

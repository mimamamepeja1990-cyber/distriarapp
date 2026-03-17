package com.distriar.driver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.distriar.driver.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var binding: ActivityMainBinding
    private lateinit var tokenStore: TokenStore
    private lateinit var repo: DriverRepository
    private lateinit var adapter: OrderAdapter

    private var googleMap: GoogleMap? = null
    private var driverMarker: Marker? = null
    private var destMarker: Marker? = null
    private var mapLoaded = false
    private var routeLine: Polyline? = null
    private var lastRouteKey: String? = null
    private var lastRouteFetchAt = 0L
    private var routeJob: Job? = null
    private var lastLocation: Location? = null
    private var currentNextOrder: Order? = null

    private lateinit var locationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var lastLocationSentAt = 0L

    private var autoRefreshJob: Job? = null
    private var loading = false
    private var currentMode: Mode = Mode.ROUTE

    private var currentOrders: List<Order> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) {
            startLocationUpdates()
            try { googleMap?.isMyLocationEnabled = true } catch (_: Exception) { }
        } else {
            Toast.makeText(this, "Permiso de ubicación requerido", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tokenStore = TokenStore(this)
        val api = ApiClient.create { tokenStore.getToken() }
        repo = DriverRepository(api)

        adapter = OrderAdapter(emptyList()) { order ->
            markDelivered(order)
        }
        binding.ordersList.layoutManager = LinearLayoutManager(this)
        binding.ordersList.adapter = adapter

        binding.btnRoute.setOnClickListener {
            switchMode(Mode.ROUTE)
        }
        binding.btnHistory.setOnClickListener {
            switchMode(Mode.HISTORY)
        }
        binding.btnRefresh.setOnClickListener {
            loadOrders(force = true)
        }
        binding.btnRoute.isEnabled = false

        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        locationClient = LocationServices.getFusedLocationProviderClient(this)

        ensureLoggedIn()
        checkMapsAvailability()
    }

    override fun onStart() {
        super.onStart()
        startAutoRefresh()
        ensureLocationPermission()
    }

    override fun onStop() {
        super.onStop()
        stopLocationUpdates()
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    private fun ensureLoggedIn() {
        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) {
            goToLogin()
            return
        }
        lifecycleScope.launch {
            try {
                val me = withContext(Dispatchers.IO) { repo.me(token) }
                binding.driverHeader.text = buildString {
                    append("Repartidor: ")
                    append(me.fullName ?: me.username)
                    if (!me.zone.isNullOrBlank()) {
                        append(" · Zona: ")
                        append(me.zone)
                    }
                }
                if (me.role.lowercase() != "repartidor") {
                    tokenStore.clear()
                    goToLogin()
                } else {
                    loadOrders(force = true)
                }
            } catch (e: Exception) {
                tokenStore.clear()
                goToLogin()
            }
        }
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun switchMode(mode: Mode) {
        if (currentMode == mode) return
        currentMode = mode
        binding.btnRoute.isEnabled = currentMode != Mode.ROUTE
        binding.btnHistory.isEnabled = currentMode != Mode.HISTORY
        loadOrders(force = true)
    }

    private fun startAutoRefresh() {
        if (autoRefreshJob != null) return
        autoRefreshJob = lifecycleScope.launch {
            while (true) {
                delay(20000)
                if (currentMode == Mode.ROUTE) {
                    loadOrders(force = false)
                }
            }
        }
    }

    private fun loadOrders(force: Boolean) {
        if (loading && !force) return
        val token = tokenStore.getToken() ?: return
        loading = true
        lifecycleScope.launch {
            try {
                val orders = withContext(Dispatchers.IO) {
                    when (currentMode) {
                        Mode.ROUTE -> repo.listRouteOrders(token)
                        Mode.HISTORY -> repo.listDeliveredOrders(token)
                    }
                }
                currentOrders = orders
                val nextOrder = orders.firstOrNull { !orderIsDelivered(it) }
                val nextId = nextOrder?.id
                currentNextOrder = nextOrder
                adapter.updateOrders(orders, nextId)
                binding.emptyView.visibility = if (orders.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                binding.nextStop.text = buildString {
                    append(getString(R.string.next_stop))
                    if (nextId != null) append(": #$nextId")
                }
                if (nextOrder != null) {
                    binding.nextStopAddress.text = formatAddress(nextOrder)
                    binding.nextStopAddress.visibility = android.view.View.VISIBLE
                } else {
                    binding.nextStopAddress.text = ""
                    binding.nextStopAddress.visibility = android.view.View.GONE
                }
                updateMap(orders, nextOrder)
                updateRoutePolyline(nextOrder)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "No se pudieron cargar pedidos", Toast.LENGTH_SHORT).show()
            } finally {
                loading = false
            }
        }
    }

    private fun markDelivered(order: Order) {
        val token = tokenStore.getToken() ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.markDelivered(token, order.id) }
                loadOrders(force = true)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "No se pudo marcar entregado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMapToolbarEnabled = false
        try {
            MapsInitializer.initialize(applicationContext, MapsInitializer.Renderer.LATEST) {}
        } catch (_: Exception) {
        }
        try {
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            if (fine == PackageManager.PERMISSION_GRANTED) {
                map.isMyLocationEnabled = true
            }
        } catch (_: Exception) {
        }
        map.setOnMapLoadedCallback {
            mapLoaded = true
            binding.mapStatus.visibility = android.view.View.GONE
        }
        val depot = LatLng(AppConfig.DEPOT_LAT, AppConfig.DEPOT_LON)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(depot, 12f))
        val nextOrder = currentOrders.firstOrNull { !orderIsDelivered(it) }
        updateMap(currentOrders, nextOrder)
        showMapTimeoutFallback()
    }

    private fun updateMap(orders: List<Order>, nextOrder: Order?) {
        val map = googleMap ?: return
        map.clear()
        driverMarker = null
        destMarker = null
        routeLine?.remove()
        routeLine = null

        val depot = LatLng(AppConfig.DEPOT_LAT, AppConfig.DEPOT_LON)
        map.addMarker(
            MarkerOptions()
                .position(depot)
                .title("Depósito")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
        )

        val points = mutableListOf<LatLng>()
        points.add(depot)

        orders.forEach { order ->
            val lat = order.deliveryLat
            val lon = order.deliveryLon
            if (lat != null && lon != null) {
                val pos = LatLng(lat, lon)
                points.add(pos)
                val hue = when {
                    nextOrder != null && order.id == nextOrder.id -> BitmapDescriptorFactory.HUE_AZURE
                    orderIsDelivered(order) -> BitmapDescriptorFactory.HUE_GREEN
                    else -> BitmapDescriptorFactory.HUE_RED
                }
                map.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .title("Pedido #${order.id}")
                        .snippet(formatAddress(order))
                        .icon(BitmapDescriptorFactory.defaultMarker(hue))
                )
            }
        }

        if (points.size > 1) {
            val polyline = PolylineOptions()
                .addAll(points)
                .color(0xFF2563EB.toInt())
                .width(6f)
            map.addPolyline(polyline)
        }

        val focus = nextOrder?.let { ord ->
            val lat = ord.deliveryLat
            val lon = ord.deliveryLon
            if (lat != null && lon != null) LatLng(lat, lon) else null
        } ?: points.firstOrNull()
        if (focus != null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(focus, 13f))
        }
    }

    private fun ensureLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
            try { googleMap?.isMyLocationEnabled = true } catch (_: Exception) { }
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
        }
    }

    private fun checkMapsAvailability() {
        val key = getString(R.string.google_maps_key)
        if (key.isBlank() || key == "REEMPLAZA_CON_TU_KEY") {
            showMapStatus("Falta la API key de Google Maps.")
            return
        }
        val availability = GoogleApiAvailability.getInstance()
        val status = availability.isGooglePlayServicesAvailable(this)
        if (status != ConnectionResult.SUCCESS) {
            showMapStatus("Google Play Services no disponible. Actualizá o usá un emulador con Google Play.")
            try {
                if (availability.isUserResolvableError(status)) {
                    availability.getErrorDialog(this, status, 9001)?.show()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun showMapStatus(message: String) {
        if (message.isBlank()) {
            binding.mapStatus.text = ""
            binding.mapStatus.visibility = android.view.View.GONE
            return
        }
        binding.mapStatus.text = message
        binding.mapStatus.visibility = android.view.View.VISIBLE
    }

    private fun showMapTimeoutFallback() {
        lifecycleScope.launch {
            delay(5000)
            if (!mapLoaded) {
                showMapStatus("Mapa sin cargar. Revisá la API key, SHA‑1 y billing en Google Cloud.")
            }
        }
    }

    private fun startLocationUpdates() {
        if (locationCallback != null) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, AppConfig.LOCATION_PUSH_INTERVAL_MS)
            .setMinUpdateIntervalMillis(4000)
            .build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                handleLocation(location)
            }
        }
        locationClient.requestLocationUpdates(request, locationCallback as LocationCallback, mainLooper)
    }

    private fun stopLocationUpdates() {
        val callback = locationCallback ?: return
        locationClient.removeLocationUpdates(callback)
        locationCallback = null
    }

    private fun handleLocation(location: Location) {
        lastLocation = location
        updateDriverMarker(location)
        val now = System.currentTimeMillis()
        if (now - lastLocationSentAt < AppConfig.LOCATION_PUSH_INTERVAL_MS) return
        lastLocationSentAt = now

        val token = tokenStore.getToken() ?: return
        val payload = DriverLocationIn(
            lat = location.latitude,
            lon = location.longitude,
            accuracy = location.accuracy,
            speed = location.speed,
            heading = location.bearing,
            battery = null,
            timestamp = location.time,
        )
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.sendLocation(token, payload) }
            } catch (_: Exception) {
            }
        }
        if (currentMode == Mode.ROUTE) {
            updateRoutePolyline(currentNextOrder)
        }
    }

    private fun updateDriverMarker(location: Location) {
        val map = googleMap ?: return
        val pos = LatLng(location.latitude, location.longitude)
        if (driverMarker == null) {
            driverMarker = map.addMarker(
                MarkerOptions()
                    .position(pos)
                    .title("Tu ubicación")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
            )
        } else {
            driverMarker?.position = pos
        }
    }

    private fun updateRoutePolyline(nextOrder: Order?) {
        val map = googleMap ?: return
        if (nextOrder == null) {
            routeLine?.remove()
            routeLine = null
            return
        }
        val destLat = nextOrder.deliveryLat
        val destLon = nextOrder.deliveryLon
        val addressFallback = if (destLat == null || destLon == null) {
            formatAddressForDirections(nextOrder)
        } else {
            null
        }
        val origin = lastLocation?.let { LatLng(it.latitude, it.longitude) }
            ?: LatLng(AppConfig.DEPOT_LAT, AppConfig.DEPOT_LON)
        val dest = if (destLat != null && destLon != null) LatLng(destLat, destLon) else null

        val key = "${origin.latitude},${origin.longitude}|${destLat ?: "addr"}|${destLon ?: "addr"}"
        val now = System.currentTimeMillis()
        if (key == lastRouteKey && now - lastRouteFetchAt < 20000) return
        lastRouteKey = key
        lastRouteFetchAt = now

        routeJob?.cancel()
        routeJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                DirectionsClient.fetchRoute(this@MainActivity, origin, dest, addressFallback)
            }
            if (result.points.isNotEmpty()) {
                showMapStatus("")
                routeLine?.remove()
                routeLine = map.addPolyline(
                    PolylineOptions()
                        .addAll(result.points)
                        .color(0xFF2563EB.toInt())
                        .width(8f)
                )
                if (dest == null && result.resolvedDestination != null) {
                    destMarker?.remove()
                    destMarker = map.addMarker(
                        MarkerOptions()
                            .position(result.resolvedDestination)
                            .title("Destino")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    )
                }
            } else {
                val msg = result.status ?: "Direcciones no disponibles"
                showMapStatus("Ruta no disponible: $msg")
            }
        }
    }

    enum class Mode {
        ROUTE,
        HISTORY,
    }
}

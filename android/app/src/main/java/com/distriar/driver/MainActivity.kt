package com.distriar.driver

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.distriar.driver.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
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
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    companion object {
        private const val NEXT_ZONE_CHANNEL_ID = "next_zone_notice"
        private const val NEXT_ZONE_NOTIFICATION_ID = 4101
        private const val KEY_LAST_ZONE_NOTICE = "last_zone_notice"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var tokenStore: TokenStore
    private lateinit var repo: DriverRepository
    private lateinit var adapter: OrderAdapter

    private var googleMap: GoogleMap? = null
    private var driverMarker: Marker? = null
    private var destMarker: Marker? = null
    private var depotMarker: Marker? = null
    private val orderMarkers = mutableMapOf<Int, Marker>()
    private var mapLoaded = false
    private var routeLine: Polyline? = null
    private var lastRouteFetchAt = 0L
    private var lastRouteOrigin: Location? = null
    private var lastRouteDestKey: String? = null
    private var routeJob: Job? = null
    private var lastLocation: Location? = null
    private var currentNextOrder: Order? = null
    private var lastCameraTarget: LatLng? = null
    private var lastCameraUpdateAt = 0L
    private var offlineSent = false
    private var pendingClosedOrder: Order? = null

    private lateinit var locationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var lastLocationSentAt = 0L

    private var autoRefreshJob: Job? = null
    private var loading = false
    private var currentMode: Mode = Mode.ROUTE

    private var currentOrders: List<Order> = emptyList()
    private val appPrefs by lazy { getSharedPreferences("driver_prefs", MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) {
            ensureLocationSettings()
        } else {
            Toast.makeText(this, "Permiso de ubicación requerido", Toast.LENGTH_LONG).show()
        }
    }

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startLocationUpdates()
            try { googleMap?.isMyLocationEnabled = true } catch (_: Exception) { }
        } else {
            Toast.makeText(this, "Activá la ubicación para ver la ruta", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val closedPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        val order = pendingClosedOrder
        pendingClosedOrder = null
        if (order == null) return@registerForActivityResult
        if (bitmap == null) {
            Toast.makeText(this, "No se tomó la foto del negocio cerrado", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        uploadClosedPhoto(order, bitmap)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tokenStore = TokenStore(this)
        val api = ApiClient.create { tokenStore.getToken() }
        repo = DriverRepository(api)

        adapter = OrderAdapter(
            emptyList(),
            onDelivered = { order -> markDelivered(order) },
            onIssue = { order -> openIssueDialog(order) },
        )
        binding.ordersList.layoutManager = LinearLayoutManager(this)
        binding.ordersList.adapter = adapter
        binding.ordersList.setHasFixedSize(true)
        binding.ordersList.itemAnimator = null
        binding.ordersList.setItemViewCacheSize(8)

        binding.btnRoute.setOnClickListener {
            switchMode(Mode.ROUTE)
        }
        binding.btnHistory.setOnClickListener {
            switchMode(Mode.HISTORY)
        }
        binding.btnRefresh.setOnClickListener {
            loadOrders(force = true)
        }
        binding.btnLogout.setOnClickListener {
            logout()
        }
        binding.btnRoute.isEnabled = false

        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        locationClient = LocationServices.getFusedLocationProviderClient(this)
        ensureNotificationChannel()

        ensureLoggedIn()
        checkMapsAvailability()
    }

    override fun onStart() {
        super.onStart()
        startAutoRefresh()
        ensureLocationPermission()
        ensureNotificationPermission()
    }

    override fun onStop() {
        super.onStop()
        stopLocationUpdates()
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    override fun onDestroy() {
        if (isFinishing) {
            sendOfflineSignal()
        }
        super.onDestroy()
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

    private fun logout() {
        sendOfflineSignal()
        tokenStore.clear()
        stopLocationUpdates()
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        goToLogin()
    }

    private fun sendOfflineSignal() {
        if (offlineSent) return
        val token = tokenStore.getToken() ?: return
        offlineSent = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.sendOffline(token) }
            } catch (_: Exception) {
            }
        }
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
                val payload = withContext(Dispatchers.IO) {
                    val notice = runCatching { repo.getNextZoneNotice(token) }.getOrNull()
                    val orders = when (currentMode) {
                        Mode.ROUTE -> repo.listRouteOrders(token)
                        Mode.HISTORY -> repo.listDeliveredOrders(token)
                    }
                    Pair(orders, notice)
                }
                val orders = payload.first
                applyNextZoneNotice(payload.second)
                val displayOrders = if (currentMode == Mode.ROUTE) orders.filter { !orderIsDelivered(it) } else orders
                currentOrders = displayOrders
                val nextOrder = displayOrders.firstOrNull()
                val nextId = nextOrder?.id
                currentNextOrder = nextOrder
                adapter.updateOrders(displayOrders, nextId)
                if (displayOrders.isEmpty()) {
                    binding.emptyView.text = if (currentMode == Mode.ROUTE) {
                        getString(R.string.all_deliveries_done)
                    } else {
                        getString(R.string.no_orders)
                    }
                    binding.emptyView.visibility = android.view.View.VISIBLE
                    binding.ordersList.visibility = android.view.View.GONE
                } else {
                    binding.emptyView.visibility = android.view.View.GONE
                    binding.ordersList.visibility = android.view.View.VISIBLE
                }
                binding.nextStop.text = buildString {
                    if (nextId != null) {
                        append(getString(R.string.next_stop))
                        append(": #$nextId")
                    } else if (currentMode == Mode.ROUTE) {
                        append(getString(R.string.all_deliveries_done))
                    } else {
                        append(getString(R.string.next_stop))
                    }
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

    private fun openIssueDialog(order: Order) {
        val options = if ((order.closedAttempts ?: 0) > 0) {
            arrayOf("Dejar problema", "Marcar cerrado nuevamente")
        } else {
            arrayOf("Dejar problema", "Marcar negocio cerrado")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Incidencia en pedido #${order.id}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptProblemDetail(order)
                    1 -> captureClosedOrder(order)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptProblemDetail(order: Order) {
        val input = EditText(this).apply {
            hint = "Ej: Calle cortada por arreglos"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 5
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Describí el problema")
            .setView(input)
            .setPositiveButton("Enviar") { _, _ ->
                val note = input.text?.toString()?.trim().orEmpty()
                if (note.isBlank()) {
                    Toast.makeText(this, "Escribí el problema antes de enviar", Toast.LENGTH_SHORT).show()
                } else {
                    submitProblem(order, note)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun submitProblem(order: Order, note: String) {
        val token = tokenStore.getToken() ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.reportProblem(token, order.id, note) }
                Toast.makeText(this@MainActivity, "Problema reportado al admin", Toast.LENGTH_SHORT).show()
                loadOrders(force = true)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "No se pudo reportar el problema", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun captureClosedOrder(order: Order) {
        pendingClosedOrder = order
        closedPhotoLauncher.launch(null)
    }

    private fun uploadClosedPhoto(order: Order, bitmap: Bitmap) {
        val token = tokenStore.getToken() ?: return
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    val bytes = stream.toByteArray()
                    val filename = "pedido-${order.id}-cerrado-${System.currentTimeMillis()}.jpg"
                    val uploaded = repo.uploadEvidence(bytes, filename)
                    repo.markBusinessClosed(token, order.id, uploaded.imageUrl)
                }
                val action = result.action?.trim().orEmpty()
                val message = when (action) {
                    "moved_to_end" -> "Marcado como cerrado. Lo movimos al final de la ruta."
                    "cancelled" -> "Pedido cancelado. Se avisó al cliente por email."
                    else -> "Incidencia de cerrado registrada."
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                loadOrders(force = true)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "No se pudo registrar el negocio cerrado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyNextZoneNotice(notice: DriverNextZoneNotice?) {
        val zone = notice?.zone?.trim().orEmpty()
        val deliveryDate = notice?.deliveryDate?.trim().orEmpty()
        if (zone.isBlank() || deliveryDate.isBlank()) {
            binding.nextDayZoneNotice.visibility = View.GONE
            binding.nextDayZoneNotice.text = ""
            return
        }
        val formattedDate = formatZoneDate(deliveryDate)
        binding.nextDayZoneNotice.text = "Zona asignada para $formattedDate: $zone"
        binding.nextDayZoneNotice.visibility = View.VISIBLE

        if (!isTomorrow(deliveryDate)) return
        val noticeKey = "$deliveryDate|$zone"
        val lastShown = appPrefs.getString(KEY_LAST_ZONE_NOTICE, null)
        if (noticeKey == lastShown) return
        val shown = showNextZoneNotification(zone, deliveryDate, notice?.message?.takeIf { it.isNotBlank() })
        if (shown) {
            appPrefs.edit().putString(KEY_LAST_ZONE_NOTICE, noticeKey).apply()
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NEXT_ZONE_CHANNEL_ID,
            "Zona siguiente",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Avisos sobre la zona asignada para el día siguiente"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun showNextZoneNotification(zone: String, deliveryDate: String, body: String?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        val text = body ?: "Mañana te toca la zona $zone."
        val notification = NotificationCompat.Builder(this, NEXT_ZONE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Zona del día siguiente")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text Fecha: ${formatZoneDate(deliveryDate)}"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(NEXT_ZONE_NOTIFICATION_ID, notification)
        return true
    }

    private fun formatZoneDate(value: String): String {
        return try {
            val date = LocalDate.parse(value)
            date.format(DateTimeFormatter.ofPattern("dd/MM"))
        } catch (_: Exception) {
            value
        }
    }

    private fun isTomorrow(value: String): Boolean {
        return try {
            LocalDate.parse(value) == LocalDate.now().plusDays(1)
        } catch (_: Exception) {
            false
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isIndoorLevelPickerEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false
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
        updateRoutePolyline(nextOrder)
        showMapTimeoutFallback()
    }

    private fun updateMap(orders: List<Order>, nextOrder: Order?) {
        val map = googleMap ?: return
        val depot = LatLng(AppConfig.DEPOT_LAT, AppConfig.DEPOT_LON)
        if (depotMarker == null) {
            depotMarker = map.addMarker(
                MarkerOptions()
                    .position(depot)
                    .title("Depósito")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            )
        } else {
            depotMarker?.position = depot
        }

        val points = mutableListOf<LatLng>()
        points.add(depot)

        val keepIds = mutableSetOf<Int>()
        orders.forEach { order ->
            val pos = resolveOrderLatLng(order)
            if (pos == null) return@forEach
            points.add(pos)
            keepIds.add(order.id)
            val hue = when {
                nextOrder != null && order.id == nextOrder.id -> BitmapDescriptorFactory.HUE_AZURE
                orderIsDelivered(order) -> BitmapDescriptorFactory.HUE_GREEN
                else -> BitmapDescriptorFactory.HUE_RED
            }
            val marker = orderMarkers[order.id]
            if (marker == null) {
                orderMarkers[order.id] = map.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .title("Pedido #${order.id}")
                        .snippet(formatAddress(order))
                        .icon(BitmapDescriptorFactory.defaultMarker(hue))
                )!!
            } else {
                marker.position = pos
                marker.title = "Pedido #${order.id}"
                marker.snippet = formatAddress(order)
                marker.setIcon(BitmapDescriptorFactory.defaultMarker(hue))
            }
        }
        // remove markers for orders no longer in list
        val toRemove = orderMarkers.keys.filter { it !in keepIds }
        toRemove.forEach { id ->
            orderMarkers[id]?.remove()
            orderMarkers.remove(id)
        }

        val focus = nextOrder?.let { resolveOrderLatLng(it) }
        ?: lastLocation?.let { LatLng(it.latitude, it.longitude) }
        ?: points.firstOrNull()
        if (focus != null && shouldUpdateCamera(focus)) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(focus, 13f))
        }
    }

    private fun shouldUpdateCamera(target: LatLng): Boolean {
        val prev = lastCameraTarget
        if (prev == null) {
            lastCameraTarget = target
            lastCameraUpdateAt = System.currentTimeMillis()
            return true
        }
        val results = FloatArray(1)
        Location.distanceBetween(prev.latitude, prev.longitude, target.latitude, target.longitude, results)
        val moved = results[0]
        val elapsed = System.currentTimeMillis() - lastCameraUpdateAt
        val shouldMove = moved > 80f || elapsed > 30000
        if (shouldMove) {
            lastCameraTarget = target
            lastCameraUpdateAt = System.currentTimeMillis()
        }
        return shouldMove
    }

    private fun resolveOrderLatLng(order: Order): LatLng? {
        val lat = order.deliveryLat
        val lon = order.deliveryLon
        if (lat != null && lon != null) {
            if (isMendozaPoint(lat, lon)) return LatLng(lat, lon)
            if (isMendozaPoint(lon, lat)) return LatLng(lon, lat)
        }
        return null
    }

    private fun isMendozaPoint(lat: Double, lon: Double): Boolean {
        return lat in AppConfig.MENDOZA_MIN_LAT..AppConfig.MENDOZA_MAX_LAT &&
            lon in AppConfig.MENDOZA_MIN_LON..AppConfig.MENDOZA_MAX_LON
    }

    private fun ensureLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) {
            ensureLocationSettings()
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
        }
    }

    private fun ensureLocationSettings() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, AppConfig.LOCATION_PUSH_INTERVAL_MS)
            .setMinUpdateIntervalMillis(4000)
            .build()
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(request)
            .setAlwaysShow(true)
        val client = LocationServices.getSettingsClient(this)
        client.checkLocationSettings(builder.build())
            .addOnSuccessListener {
                startLocationUpdates()
                try { googleMap?.isMyLocationEnabled = true } catch (_: Exception) { }
            }
            .addOnFailureListener { ex ->
                if (ex is ResolvableApiException) {
                    val intentSender = IntentSenderRequest.Builder(ex.resolution).build()
                    locationSettingsLauncher.launch(intentSender)
                } else {
                    Toast.makeText(this, "Activá la ubicación para continuar", Toast.LENGTH_LONG).show()
                }
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
        // Seed with last known location to avoid routing from depot when GPS is available.
        try {
            locationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    lastLocation = loc
                    updateDriverMarker(loc)
                    if (currentMode == Mode.ROUTE) {
                        updateRoutePolyline(currentNextOrder)
                    }
                }
            }
        } catch (_: Exception) {
        }
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
        val originLoc = lastLocation
        if (originLoc == null) {
            showMapStatus("Esperando ubicación del repartidor para calcular la ruta.")
            return
        }
        val dest = resolveOrderLatLng(nextOrder)
        val addressFallback = if (dest == null) {
            formatAddressForDirections(nextOrder)
        } else {
            null
        }
        val origin = LatLng(originLoc.latitude, originLoc.longitude)
        val destKey = if (dest != null) {
            "${dest.latitude},${dest.longitude}"
        } else {
            "addr:${addressFallback ?: ""}"
        }
        val now = System.currentTimeMillis()
        val movedMeters = lastRouteOrigin?.distanceTo(originLoc) ?: Float.MAX_VALUE
        val destChanged = destKey != lastRouteDestKey
        val elapsed = now - lastRouteFetchAt
        val shouldSkip = !destChanged && movedMeters < 30f && elapsed < 15000 && routeLine != null
        if (shouldSkip) return
        lastRouteOrigin = originLoc
        lastRouteDestKey = destKey
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

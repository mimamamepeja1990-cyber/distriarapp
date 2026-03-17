package com.distriar.driver

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object DirectionsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    suspend fun fetchRoute(context: Context, origin: LatLng, destination: LatLng?, destinationAddress: String? = null): RouteResult {
        val key = context.getString(R.string.google_maps_key)
        if (key.isBlank() || key == "REEMPLAZA_CON_TU_KEY") return RouteResult(emptyList(), null, "missing_key")

        val destParam = when {
            destination != null -> "${destination.latitude},${destination.longitude}"
            !destinationAddress.isNullOrBlank() -> destinationAddress
            else -> null
        }
        if (destParam == null) return RouteResult(emptyList(), null, "missing_destination")

        val url = "https://maps.googleapis.com/maps/api/directions/json" +
            "?origin=${origin.latitude},${origin.longitude}" +
            "&destination=${encode(destParam)}" +
            "&mode=driving" +
            "&key=${key}"

        val reqBuilder = Request.Builder().url(url)
        val sha1 = getSigningSha1(context)
        if (!sha1.isNullOrBlank()) {
            reqBuilder.header("X-Android-Package", context.packageName)
            reqBuilder.header("X-Android-Cert", sha1)
        }
        val request = reqBuilder.build()

        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return RouteResult(emptyList(), null, "http_${resp.code}")
                val body = resp.body?.string() ?: return RouteResult(emptyList(), null, "empty_body")
                val parsed = gson.fromJson(body, DirectionsResponse::class.java)
                val status = parsed.status ?: "unknown"
                if (status != "OK") return RouteResult(emptyList(), null, status)
                val route = parsed.routes?.firstOrNull()
                val poly = route?.overview_polyline?.points ?: return RouteResult(emptyList(), null, "no_polyline")
                val end = route.legs?.firstOrNull()?.end_location?.let { loc ->
                    if (loc.lat != null && loc.lng != null) LatLng(loc.lat, loc.lng) else null
                }
                RouteResult(decodePolyline(poly), end, status)
            }
        } catch (e: Exception) {
            Log.w("DirectionsClient", "Directions fetch failed", e)
            RouteResult(emptyList(), null, "exception")
        }
    }

    private fun getSigningSha1(context: Context): String? {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signatures = info.signingInfo.apkContentsSigners
            if (signatures.isEmpty()) return null
            val md = MessageDigest.getInstance("SHA1")
            val digest = md.digest(signatures[0].toByteArray())
            digest.joinToString(":") { b -> "%02X".format(b) }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return poly
    }

    data class DirectionsResponse(
        val status: String?,
        val routes: List<DirectionsRoute>?
    )

    data class DirectionsRoute(
        val overview_polyline: OverviewPolyline?,
        val legs: List<DirectionsLeg>?
    )

    data class OverviewPolyline(
        val points: String?
    )

    data class DirectionsLeg(
        val end_location: LatLngLiteral?
    )

    data class LatLngLiteral(
        val lat: Double?,
        val lng: Double?
    )

    data class RouteResult(
        val points: List<LatLng>,
        val resolvedDestination: LatLng?,
        val status: String?
    )

    private fun encode(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8")
    }
}

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

    suspend fun fetchRoute(context: Context, origin: LatLng, destination: LatLng): List<LatLng> {
        val key = context.getString(R.string.google_maps_key)
        if (key.isBlank() || key == "REEMPLAZA_CON_TU_KEY") return emptyList()

        val url = "https://maps.googleapis.com/maps/api/directions/json" +
            "?origin=${origin.latitude},${origin.longitude}" +
            "&destination=${destination.latitude},${destination.longitude}" +
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
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val parsed = gson.fromJson(body, DirectionsResponse::class.java)
                val poly = parsed.routes?.firstOrNull()?.overview_polyline?.points ?: return emptyList()
                decodePolyline(poly)
            }
        } catch (e: Exception) {
            Log.w("DirectionsClient", "Directions fetch failed", e)
            emptyList()
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
        val routes: List<DirectionsRoute>?
    )

    data class DirectionsRoute(
        val overview_polyline: OverviewPolyline?
    )

    data class OverviewPolyline(
        val points: String?
    )
}

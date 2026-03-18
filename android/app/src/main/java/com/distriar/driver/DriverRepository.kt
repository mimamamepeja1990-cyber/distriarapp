package com.distriar.driver

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class DriverRepository(private val api: ApiService) {
    suspend fun login(username: String, password: String): TokenResponse {
        return api.login(username, password)
    }

    suspend fun me(token: String): AdminUser {
        return api.me("Bearer $token")
    }

    suspend fun listRouteOrders(token: String): List<Order> {
        return api.listOrders("Bearer $token", status = null, auto = 1, limit = 200)
    }

    suspend fun listDeliveredOrders(token: String): List<Order> {
        return api.listOrders("Bearer $token", status = "entregado", auto = 0, limit = 200)
    }

    suspend fun markDelivered(token: String, orderId: Int): Order {
        return api.updateOrderStatus("Bearer $token", orderId, StatusUpdate("entregado"))
    }

    suspend fun reportProblem(token: String, orderId: Int, note: String): DeliveryIssueResult {
        return api.createDeliveryIssue("Bearer $token", orderId, DeliveryIssueRequest(type = "problema", note = note))
    }

    suspend fun markBusinessClosed(token: String, orderId: Int, photoUrl: String): DeliveryIssueResult {
        return api.createDeliveryIssue("Bearer $token", orderId, DeliveryIssueRequest(type = "negocio_cerrado", photoUrl = photoUrl))
    }

    suspend fun uploadEvidence(imageBytes: ByteArray, filename: String): ImageUploadResponse {
        val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaType())
        val part = MultipartBody.Part.createFormData("file", filename, requestBody)
        return api.uploadImage(part)
    }

    suspend fun getNextZoneNotice(token: String): DriverNextZoneNotice {
        return api.getDriverNextZone("Bearer $token")
    }

    suspend fun sendLocation(token: String, loc: DriverLocationIn): DriverLocationOut {
        return api.postDriverLocation("Bearer $token", loc)
    }

    suspend fun sendOffline(token: String) {
        api.postDriverOffline("Bearer $token")
    }
}

package com.distriar.driver

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

    suspend fun sendLocation(token: String, loc: DriverLocationIn): DriverLocationOut {
        return api.postDriverLocation("Bearer $token", loc)
    }
}

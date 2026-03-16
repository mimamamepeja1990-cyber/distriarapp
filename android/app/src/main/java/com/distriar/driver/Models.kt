package com.distriar.driver

import com.google.gson.annotations.SerializedName


data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
)

data class AdminUser(
    val id: Int,
    val username: String,
    @SerializedName("full_name") val fullName: String?,
    val role: String,
    val zone: String?,
    @SerializedName("is_active") val isActive: Boolean,
)

data class OrderItem(
    val id: String?,
    val name: String?,
    val quantity: Int?,
    @SerializedName("unit_price") val unitPrice: Double?,
)

data class Order(
    val id: Int,
    val items: List<OrderItem>?,
    val total: Double?,
    val status: String?,
    @SerializedName("user_full_name") val userFullName: String?,
    @SerializedName("user_barrio") val userBarrio: String?,
    @SerializedName("user_calle") val userCalle: String?,
    @SerializedName("user_numeracion") val userNumeracion: String?,
    @SerializedName("user_postal_code") val userPostalCode: String?,
    @SerializedName("user_department") val userDepartment: String?,
    @SerializedName("maps_url") val mapsUrl: String?,
    @SerializedName("delivery_lat") val deliveryLat: Double?,
    @SerializedName("delivery_lon") val deliveryLon: Double?,
    @SerializedName("route_order") val routeOrder: Int?,
    @SerializedName("assigned_driver_name") val assignedDriverName: String?,
    @SerializedName("assigned_driver_username") val assignedDriverUsername: String?,
)

data class StatusUpdate(
    val status: String,
)

data class DriverLocationIn(
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val speed: Float?,
    val heading: Float?,
    val battery: Float?,
    val timestamp: Long?,
)

data class DriverLocationOut(
    @SerializedName("driver_id") val driverId: Int?,
    @SerializedName("driver_username") val driverUsername: String?,
    @SerializedName("driver_name") val driverName: String?,
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val speed: Float?,
    val heading: Float?,
    val battery: Float?,
    @SerializedName("recorded_at") val recordedAt: String?,
    @SerializedName("age_sec") val ageSec: Double?,
)

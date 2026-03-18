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

data class DeliveryIssue(
    val type: String?,
    val note: String?,
    @SerializedName("photo_url") val photoUrl: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("reported_by_id") val reportedById: Int?,
    @SerializedName("reported_by_username") val reportedByUsername: String?,
    @SerializedName("closed_attempt") val closedAttempt: Int?,
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
    @SerializedName("delivery_issues") val deliveryIssues: List<DeliveryIssue>?,
    @SerializedName("closed_attempts") val closedAttempts: Int?,
    @SerializedName("last_delivery_issue_type") val lastDeliveryIssueType: String?,
    @SerializedName("last_delivery_issue_note") val lastDeliveryIssueNote: String?,
    @SerializedName("last_delivery_issue_photo_url") val lastDeliveryIssuePhotoUrl: String?,
    @SerializedName("last_delivery_issue_at") val lastDeliveryIssueAt: String?,
    @SerializedName("cancel_reason") val cancelReason: String?,
)

data class StatusUpdate(
    val status: String,
)

data class DeliveryIssueRequest(
    val type: String,
    val note: String? = null,
    @SerializedName("photo_url") val photoUrl: String? = null,
)

data class DeliveryIssueResult(
    val action: String?,
    val order: Order?,
    val issue: DeliveryIssue?,
)

data class ImageUploadResponse(
    @SerializedName("image_url") val imageUrl: String,
)

data class DriverNextZoneNotice(
    @SerializedName("driver_id") val driverId: Int?,
    @SerializedName("driver_username") val driverUsername: String?,
    val zone: String?,
    @SerializedName("delivery_date") val deliveryDate: String?,
    val message: String?,
    @SerializedName("updated_at") val updatedAt: String?,
    @SerializedName("updated_by") val updatedBy: String?,
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

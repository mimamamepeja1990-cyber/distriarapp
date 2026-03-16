package com.distriar.driver

import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @FormUrlEncoded
    @POST("/admin/auth/token")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
    ): TokenResponse

    @GET("/admin/auth/me")
    suspend fun me(@Header("Authorization") auth: String): AdminUser

    @GET("/admin/orders")
    suspend fun listOrders(
        @Header("Authorization") auth: String,
        @Query("status") status: String? = null,
        @Query("auto") auto: Int? = 1,
        @Query("limit") limit: Int? = 200,
    ): List<Order>

    @PATCH("/orders/{id}/status")
    suspend fun updateOrderStatus(
        @Header("Authorization") auth: String,
        @Path("id") id: Int,
        @Body body: StatusUpdate,
    ): Order

    @POST("/admin/driver-location")
    suspend fun postDriverLocation(
        @Header("Authorization") auth: String,
        @Body body: DriverLocationIn,
    ): DriverLocationOut
}

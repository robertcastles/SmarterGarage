package com.lusion.sgauto

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface SmartThingsApi {
    @GET("/v1/devices/{deviceId}/status")
    suspend fun getGarageStatus(
        @Path("deviceId") deviceId: String
    ): Response<GarageStatusResponse>

    @POST("/v1/devices/{deviceId}/commands")
    suspend fun sendGarageCommand(
        @Path("deviceId") deviceId: String,
        @Body command: GarageCommandRequest
    ): Response<GarageCommandResponse>

    @GET("/v1/devices")
    suspend fun getDevices(): Response<DevicesResponse>

    @POST("/ping")
    suspend fun sendPing(@Body request: PingRequest): Response<PingResponse>
}

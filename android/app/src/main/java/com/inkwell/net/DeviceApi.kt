package com.inkwell.net

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the Stage 1 routes of contract `device-api` v1. Base URL
 * ends in `/`; every path here is relative and carries the `v1/` prefix. The
 * contract header and bearer token are added by interceptors ([ContractInterceptor],
 * [AuthInterceptor]).
 */
interface DeviceApi {

    @GET("v1/health")
    suspend fun health(): HealthResponse

    @GET("v1/spaces")
    suspend fun spaces(): List<Space>

    @POST("v1/jobs")
    suspend fun createJob(@Body body: JobCreateRequest): Job

    @GET("v1/jobs/{id}")
    suspend fun getJob(@Path("id") id: String): Job

    @POST("v1/jobs/{id}/cancel")
    suspend fun cancelJob(@Path("id") id: String): Job

    /** `cursor` is passed back verbatim (opaque, contract device-api §Sync cursor). */
    @GET("v1/sync")
    suspend fun sync(@Query("cursor") cursor: String? = null): SyncResponse

    /** Stage 10: change a card's state (open|done|dismissed) → the updated card. */
    @PATCH("v1/cards/{id}")
    suspend fun patchCard(@Path("id") id: String, @Body body: CardStateRequest): CardResponse

    /** Stage 10: invoke a card action by its id → the updated card. */
    @POST("v1/cards/{id}/actions/{action_id}")
    suspend fun runCardAction(
        @Path("id") id: String,
        @Path("action_id") actionId: String,
    ): CardResponse
}

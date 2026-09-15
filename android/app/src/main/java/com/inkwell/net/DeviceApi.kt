package com.inkwell.net

import retrofit2.http.Body
import retrofit2.http.GET
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
}

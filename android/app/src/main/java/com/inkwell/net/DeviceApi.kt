package com.inkwell.net

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

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

    /** Stage 15: create a space (a new agent) → the created wire [Space]. */
    @POST("v1/spaces")
    suspend fun createSpace(@Body body: SpaceCreateRequest): Space

    /** Stage 15: partial-update a space (name/colour/model/prompt/position) → the updated [Space]. */
    @PATCH("v1/spaces/{id}")
    suspend fun patchSpace(@Path("id") id: String, @Body body: SpacePatchRequest): Space

    @POST("v1/jobs")
    suspend fun createJob(@Body body: JobCreateRequest): Job

    @GET("v1/jobs/{id}")
    suspend fun getJob(@Path("id") id: String): Job

    @POST("v1/jobs/{id}/cancel")
    suspend fun cancelJob(@Path("id") id: String): Job

    /** `cursor` is passed back verbatim (opaque, contract device-api §Sync cursor). */
    @GET("v1/sync")
    suspend fun sync(@Query("cursor") cursor: String? = null): SyncResponse

    /**
     * Stage 22 (contract device-api `GET /canvases/{id}`): a canvas with its layers and
     * rasters (never strokes). Each raster carries a fresh signed `url` (24 h) so a device
     * that missed the sync window — or hit a 403 on a stale link — can re-fetch the blob.
     */
    @GET("v1/canvases/{id}")
    suspend fun getCanvas(@Path("id") id: String): CanvasDetail

    /**
     * Stage 22: download a blob by its full signed URL (`GET /blobs/{key}?sig=&exp=`). The
     * URL already carries the signature; the bearer header is added by [AuthInterceptor].
     * `@Streaming` avoids buffering the whole (up to 20 MB) blob in memory before we copy it
     * to the cache file. `@Url` takes an ABSOLUTE link; the server ships a relative signed
     * link (`/v1/blobs/{key}?sig=&exp=`), so [DeviceRepository.downloadBlob] resolves it
     * against the paired base URL before calling here (Stage 29).
     */
    @Streaming
    @GET
    suspend fun downloadBlob(@Url url: String): ResponseBody

    /** Stage 10: change a card's state (open|done|dismissed) → the updated card. */
    @PATCH("v1/cards/{id}")
    suspend fun patchCard(@Path("id") id: String, @Body body: CardStateRequest): CardResponse

    /** Stage 10: invoke a card action by its id → the updated card. */
    @POST("v1/cards/{id}/actions/{action_id}")
    suspend fun runCardAction(
        @Path("id") id: String,
        @Path("action_id") actionId: String,
    ): CardResponse

    // --- Stage 27: brain (contract device-api §Stage 25 additions; server-truth, ADR-0013 §6) ---

    /**
     * `GET /brain/{slug}?q=&limit=` → `BrainEntry[]`. With `q`, full-text matches ranked by
     * relevance then recency; without `q`, the newest entries first. `limit` defaults to 50
     * server-side (capped at 200). Never mirrored — the Brain view calls this live each time.
     */
    @GET("v1/brain/{slug}")
    suspend fun getBrain(
        @Path("slug") slug: String,
        @Query("q") q: String? = null,
        @Query("limit") limit: Int? = null,
    ): List<BrainEntry>

    /** `POST /brain/{slug}` (idempotent on normalised text) → the created/existing entry. */
    @POST("v1/brain/{slug}")
    suspend fun postBrain(@Path("slug") slug: String, @Body body: BrainCreate): BrainEntry

    /**
     * `DELETE /brain/{slug}/{id}` → `204` (soft delete). Declared `Response<Unit>` so the empty
     * 204 body is not run through the JSON converter (Retrofit returns a null body for 204/205);
     * [DeviceRepository.deleteBrain] maps a non-2xx to [ApiException].
     */
    @DELETE("v1/brain/{slug}/{id}")
    suspend fun deleteBrain(@Path("slug") slug: String, @Path("id") id: String): Response<Unit>
}

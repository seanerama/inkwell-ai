package com.inkwell.net

import com.inkwell.data.CanvasEntity
import com.inkwell.data.LayerEntity
import com.inkwell.data.PushedCanvasStore
import com.inkwell.data.RasterEntity
import kotlinx.serialization.json.Json

/**
 * The device half of "push origin" (Stage 22, ADR-0012): discovers host-pushed `to_user`
 * jobs through the persisted `/sync` cursor and materialises them into agent-origin
 * canvases with raster layers (PDF pages / images) beneath ink.
 *
 * Android-free so the discovery/materialise logic is a JVM unit test: Room writes go
 * through [PushedCanvasStore] and blob downloads through [BlobDownloader], both fakeable.
 * The caller ([SyncWorker] in the background, the Library poll in the foreground) supplies
 * a paired [DeviceRepository] and the [SyncCursorStore]; all of it is gated by
 * `BuildConfig.PUSH_INBOX` at the call sites so OFF means no polling and no writes.
 */
class PushInbox(
    private val store: PushedCanvasStore,
    private val downloader: BlobDownloader,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val json: Json = defaultJson,
) {

    /**
     * One discovery pass. Page from the persisted cursor, which is null on first run (fresh
     * install, reinstall, upgrade, new pairing) — in that case start at `sync(null)` (the most
     * recent 100 jobs, ascending) and BACKFILL, so a `to_user` push that landed before the
     * app's first successful sync is materialised rather than silently skipped (Stage 24).
     * Materialise every `to_user`/`done` job whose canvases are not already local (dedupe via
     * [PushedCanvasStore.canvasExists]), then advance the cursor to the page's cursor —
     * advancing ONLY after the whole page materialised (a throw leaves the cursor where it
     * was, so the page is retried and dedupe skips what already landed). A full page (100)
     * means loop again.
     *
     * If the persisted cursor is unknown to the server (a `422` — e.g. the server's job data
     * was reset), RE-SEED by restarting the loop from `null` (backfill) rather than failing.
     * Any other error (network/offline) propagates so the caller keeps state and retries.
     *
     * Returns the number of jobs materialised this pass.
     */
    suspend fun poll(repo: DeviceRepository, cursorStore: SyncCursorStore): Int {
        var cursor: String? = cursorStore.get() // null on first run → backfill from the start
        var reseeded = false
        var materialised = 0
        while (true) {
            val resp = try {
                repo.sync(cursor)
            } catch (e: ApiException) {
                // Unknown/stale persisted cursor (server data reset): re-seed once by
                // restarting the loop from null (backfill). Everything else propagates.
                if (e.statusCode == UNKNOWN_CURSOR_STATUS && cursor != null && !reseeded) {
                    reseeded = true
                    cursor = null
                    continue
                }
                throw e
            }
            for (job in resp.jobs) {
                if (job.direction == "to_user" && job.status == "done") {
                    if (materialise(job)) materialised++
                }
            }
            // Advance ONLY after the whole page materialised without throwing.
            cursor = resp.cursor
            cursorStore.set(resp.cursor)
            if (resp.jobs.size < PAGE_SIZE) break
        }
        return materialised
    }

    /**
     * Materialise one `to_user` job's `result` into Room (idempotently). Creates one
     * agent-origin [CanvasEntity] per page (server id, `origin=agent`, `seen_at=null`), a
     * `raster` [LayerEntity] (`z=-1`, linked to the job) per raster layer, and a
     * [RasterEntity] whose `blob_uri` is the LOCAL cache path after download. Multi-page
     * documents land in a new folder named after the document (created once per job). No
     * user/ink layer is created — the first stroke creates it (stage 12 rule).
     *
     * Idempotent: if the (first) canvas already exists the job is treated as already
     * materialised and skipped; a partially-materialised job (a mid-page download failure)
     * re-runs and [PushedCanvasStore.canvasExists] skips the pages that already landed.
     *
     * Returns true when there was something to materialise (false for an empty/notify result).
     */
    suspend fun materialise(job: Job): Boolean {
        val result = job.result ?: return false
        val parsed = try {
            json.decodeFromString(PushResult.serializer(), result.toString())
        } catch (_: Exception) {
            return false
        }
        val canvases = parsed.canvases ?: listOfNotNull(parsed.canvas)
        if (canvases.isEmpty()) return false

        // Dedupe by canvas id: the first page existing means the job already materialised.
        if (store.canvasExists(canvases.first().id)) return false

        val multiPage = canvases.size > 1
        val folderId = if (multiPage) {
            store.createFolder(canvases.first().spaceId, folderNameFor(canvases.first().title))
        } else {
            null
        }

        val now = clock()
        for (canvas in canvases) {
            if (store.canvasExists(canvas.id)) continue
            store.insertCanvas(
                CanvasEntity(
                    id = canvas.id,
                    spaceId = canvas.spaceId,
                    title = canvas.title,
                    widthCu = canvas.widthCu,
                    heightCu = canvas.heightCu,
                    createdAt = now,
                    updatedAt = now,
                    origin = "agent",
                    folderId = folderId,
                    deletedAt = null,
                    seenAt = null,
                ),
            )
            // This canvas's raster layers, and each layer's rasters (matched by id).
            val layers = parsed.layers.filter { it.canvasId == canvas.id && it.type == "raster" }
            for (layer in layers) {
                store.insertLayer(
                    LayerEntity(
                        id = layer.id,
                        canvasId = canvas.id,
                        z = layer.z,
                        owner = "agent",
                        type = "raster",
                        visible = true,
                        opacity = 1.0f,
                        jobId = layer.jobId ?: job.id,
                        createdAt = now,
                    ),
                )
                val rasters = parsed.rasters.filter { it.layerId == layer.id }
                for (raster in rasters) {
                    val url = raster.url
                        ?: throw IllegalStateException("raster ${raster.id} has no url to download")
                    val cachePath = downloader.download(
                        url = url,
                        canvasId = canvas.id,
                        rasterId = raster.id,
                        page = raster.page,
                        mime = raster.mime,
                    )
                    store.insertRaster(
                        RasterEntity(
                            id = raster.id,
                            layerId = layer.id,
                            blobUri = cachePath,
                            mime = raster.mime,
                            page = raster.page,
                            xCu = raster.xCu,
                            yCu = raster.yCu,
                            wCu = raster.wCu,
                            hCu = raster.hCu,
                        ),
                    )
                }
            }
        }
        return true
    }

    companion object {
        /** Sync page size (contract device-api §Sync cursor: at most 100 per page). */
        const val PAGE_SIZE = 100

        /**
         * HTTP status the server returns for an unknown/invalid `/sync` cursor (contract
         * device-api: `422` validation). Treated as "re-seed and backfill" in [poll].
         */
        const val UNKNOWN_CURSOR_STATUS = 422

        /** Ignore-unknown-keys reader for the `to_user` result envelope. */
        val defaultJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * The document (folder) name for a multi-page push: a page canvas is titled
         * `"<title> — p<N>"` (contract device-api), so strip the trailing page marker to
         * recover the document title. A single-page title is returned unchanged.
         */
        private val PAGE_SUFFIX = Regex("\\s*—\\s*p\\d+\\s*$")

        fun folderNameFor(pageTitle: String): String =
            PAGE_SUFFIX.replace(pageTitle, "").trim().ifEmpty { pageTitle.trim() }
    }
}

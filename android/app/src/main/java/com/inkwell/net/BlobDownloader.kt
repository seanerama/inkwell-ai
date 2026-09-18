package com.inkwell.net

import java.io.File

/**
 * Downloads a pushed raster's bytes to the device cache and returns the local file path
 * that becomes the [com.inkwell.data.RasterEntity] `blob_uri` (Stage 22). A pure interface
 * so [PushInbox] is JVM-unit-testable with a fake that returns a canned path; the app wires
 * [CachingBlobDownloader], which talks to the server through [DeviceRepository].
 */
interface BlobDownloader {
    /**
     * Fetch the blob at [url] (a signed `GET /blobs/{key}` link) and cache it locally,
     * returning the absolute cache-file path. On a `403` (expired/invalid signature) it
     * fetches a FRESH signed url from `GET /canvases/{canvasId}` — matching [rasterId] — and
     * retries once (ADR-0012). [page]/[mime] name the cache file. Throws on a hard failure so
     * the caller does not advance the sync cursor past an unmaterialised job.
     */
    suspend fun download(
        url: String,
        canvasId: String,
        rasterId: String,
        page: Int?,
        mime: String,
    ): String
}

/**
 * [BlobDownloader] that downloads through [DeviceRepository] and caches under
 * `<cacheDir>/push/`. The bytes are content — a disposable cache, never the source of
 * truth (the server holds the original) — so a cleared cache is re-fetched via the raster's
 * fresh `url`. Files are named by raster id (+ page) so a re-materialise reuses the cache.
 */
class CachingBlobDownloader(
    /** Resolves the paired repository per call (null when unpaired), so a re-pair takes effect. */
    private val repoProvider: () -> DeviceRepository?,
    private val cacheDir: File,
) : BlobDownloader {

    override suspend fun download(
        url: String,
        canvasId: String,
        rasterId: String,
        page: Int?,
        mime: String,
    ): String {
        val ext = extensionFor(mime)
        val suffix = page?.let { "_p$it" } ?: ""
        val out = File(cacheDir, "$DIR/$rasterId$suffix.$ext")
        if (out.exists() && out.length() > 0) return out.absolutePath

        val repo = repoProvider() ?: throw IllegalStateException("not paired: cannot download blob")
        val bytes = try {
            repo.downloadBlob(url)
        } catch (e: ApiException) {
            if (e.statusCode == 403) {
                // The signed link expired between sync and download: re-fetch a fresh one.
                val fresh = repo.getCanvas(canvasId).rasters.firstOrNull { it.id == rasterId }?.url
                    ?: throw e
                repo.downloadBlob(fresh)
            } else {
                throw e
            }
        }
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        return out.absolutePath
    }

    companion object {
        const val DIR = "push"

        /** File extension for the blob's declared mime (contract: pdf/png/jpeg only). */
        fun extensionFor(mime: String): String = when (mime.substringBefore(';').trim()) {
            "application/pdf" -> "pdf"
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> "bin"
        }
    }
}

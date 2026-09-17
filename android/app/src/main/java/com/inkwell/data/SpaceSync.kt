package com.inkwell.data

import androidx.room.withTransaction
import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.FolderDao
import com.inkwell.data.dao.SpaceDao
import com.inkwell.net.DeviceRepository
import com.inkwell.net.Space
import java.time.Instant

/**
 * Stage 14 (ADR-0010): mirror the server's spaces into Room and reconcile the device's
 * locally invented placeholder space onto its server twin. The device stops inventing
 * space ids — after a successful [refresh] every canvas and folder points at a server id.
 *
 * [refresh] is idempotent and transactional:
 *  1. upsert every server [Space] into the `spaces` table **keyed by its server id** (so
 *     the FK-less canvas/folder rows can be repointed onto an existing space row);
 *  2. in ONE transaction, for each LOCAL space whose id is not in the server list but whose
 *     `slug` matches a server space, rewrite `canvases.space_id`/`folders.space_id` from the
 *     local id to the server id and delete the local space row. A local space with no slug
 *     match is left alone (logged) — there should be none.
 *
 * The transaction is run through the injected [runInTransaction] so the class stays JVM
 * unit-testable with fake DAOs; the app wires it to `db.withTransaction { }` via [create].
 * There is **no Room schema change** — reconciliation is a data rewrite, `ink-storage` v3.
 */
class SpaceSync(
    private val spaceDao: SpaceDao,
    private val canvasDao: CanvasDao,
    private val folderDao: FolderDao,
    /** The paired device repository, or null when unpaired (then [refresh] throws — non-fatal). */
    private val deviceRepositoryProvider: () -> DeviceRepository?,
    /** Runs a block atomically; the app supplies `db.withTransaction { }`, tests supply `{ it() }`. */
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
) {

    /** Signals the server could not be reached (unpaired). Callers treat it as non-fatal. */
    class NotPairedException : Exception("spaces: device is not paired")

    /**
     * Fetch `GET /spaces`, mirror + reconcile, and return the set of server space ids. On
     * an unpaired device this throws [NotPairedException]; the caller uses its cached mirror.
     * A network/HTTP failure propagates (also non-fatal at the call sites).
     */
    suspend fun refresh(): Set<String> {
        val dev = deviceRepositoryProvider() ?: throw NotPairedException()
        val serverSpaces = dev.spaces()

        // 1. Upsert every server space by its server id BEFORE any rewrite, so a repointed
        //    canvas/folder always references an existing space row.
        for (s in serverSpaces) {
            spaceDao.upsert(s.toEntity())
        }
        val serverIds = serverSpaces.map { it.id }.toSet()
        val serverBySlug = serverSpaces.associateBy { it.slug }

        // 2. Reconcile local placeholders onto their server twin by slug, in one transaction.
        val toReconcile = spaceDao.all().filter { it.id !in serverIds }
        if (toReconcile.isNotEmpty()) {
            runInTransaction {
                for (local in toReconcile) {
                    val server = serverBySlug[local.slug]
                    if (server == null) {
                        log("SpaceSync: local space '${local.slug}' (${local.id}) has no server match; leaving it")
                        continue
                    }
                    canvasDao.reassignSpace(local.id, server.id)
                    folderDao.reassignSpace(local.id, server.id)
                    spaceDao.delete(local.id)
                }
            }
        }
        return serverIds
    }

    private fun Space.toEntity(): SpaceEntity = SpaceEntity(
        id = id,
        name = name,
        slug = slug,
        systemPrompt = systemPrompt,
        tools = tools,
        model = model,
        color = color,
        position = position,
        createdAt = parseEpochMs(createdAt),
    )

    companion object {
        /**
         * Parse an RFC3339 timestamp to epoch ms; falls back to 0 when unparseable. The
         * device never surfaces a space's created_at, so the exact value is not load-bearing.
         */
        fun parseEpochMs(rfc3339: String): Long = try {
            Instant.parse(rfc3339).toEpochMilli()
        } catch (_: Exception) {
            0L
        }

        /** App wiring: reconcile through Room's [withTransaction] so ink can never be stranded. */
        fun create(
            db: InkDatabase,
            deviceRepositoryProvider: () -> DeviceRepository?,
            log: (String) -> Unit = {},
        ): SpaceSync = SpaceSync(
            spaceDao = db.spaceDao(),
            canvasDao = db.canvasDao(),
            folderDao = db.folderDao(),
            deviceRepositoryProvider = deviceRepositoryProvider,
            runInTransaction = { block -> db.withTransaction { block() } },
            log = log,
        )
    }
}

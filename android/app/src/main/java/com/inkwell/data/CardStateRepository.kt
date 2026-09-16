package com.inkwell.data

import com.inkwell.data.dao.CardStateDao

/**
 * Per-card device state persistence (Stage 10). Kept behind a small interface so the
 * ViewModel depends on the seam, not on Room — JVM unit tests use an in-memory fake and
 * the app uses [RoomCardStateRepository] over the `card_states` table.
 */
interface CardStatePersistence {
    /** Upsert a card's state (open|done|dismissed) for its job. */
    suspend fun save(cardId: String, jobId: String, state: String, updatedAt: Long)

    /** The persisted states for a job as `cardId -> state` (empty when none). */
    suspend fun forJob(jobId: String): Map<String, String>
}

/** [CardStatePersistence] over Room's [CardStateDao]. */
class RoomCardStateRepository(
    private val dao: CardStateDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : CardStatePersistence {

    override suspend fun save(cardId: String, jobId: String, state: String, updatedAt: Long) {
        dao.upsert(
            CardStateEntity(
                id = cardId,
                jobId = jobId,
                state = state,
                updatedAt = if (updatedAt > 0L) updatedAt else clock(),
            ),
        )
    }

    override suspend fun forJob(jobId: String): Map<String, String> =
        dao.forJob(jobId).associate { it.id to it.state }
}

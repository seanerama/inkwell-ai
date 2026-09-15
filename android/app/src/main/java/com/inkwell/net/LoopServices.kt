package com.inkwell.net

/**
 * Process-wide collaborators for the canvas.annotate loop that must be shared between
 * the foreground send path (the ViewModel) and the background [SyncWorker]:
 *
 *  - [offlineQueue]: jobs created while offline live here until a reconnect flushes
 *    them in order (SPEC §9.5). Both the ViewModel (on reconnect in the foreground) and
 *    the worker (on a CONNECTED background run) drain this same instance.
 *
 * Deliberately tiny — not a DI container. A DeviceRepository is built on demand from
 * the persisted pairing credentials via [repositoryFrom].
 */
object LoopServices {

    val offlineQueue = OfflineJobQueue()

    /**
     * Build a [DeviceRepository] from the persisted base URL + token, or null when the
     * device is not paired yet (no base URL). The token is read fresh per request by
     * [AuthInterceptor], so a repository built here always uses the current token.
     */
    fun repositoryFrom(tokenStore: TokenStore): DeviceRepository? {
        val base = tokenStore.getBaseUrl() ?: return null
        return DeviceRepository(ApiClient.create(base, tokenStore))
    }
}

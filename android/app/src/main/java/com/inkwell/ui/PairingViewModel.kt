package com.inkwell.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwell.net.ApiClient
import com.inkwell.net.ApiException
import com.inkwell.net.DeviceRepository
import com.inkwell.net.TokenStore
import kotlinx.coroutines.launch

/**
 * State + actions for the pairing screen. Holds no Android context — it takes a
 * [TokenStore] and a repository factory, so the flow is testable and the encrypted
 * store stays an implementation detail supplied by the Activity.
 */
class PairingViewModel(
    private val tokenStore: TokenStore,
    private val repositoryFactory: (baseUrl: String, tokenStore: TokenStore) -> DeviceRepository =
        { baseUrl, store -> DeviceRepository(ApiClient.create(baseUrl, store)) },
) : ViewModel() {

    var serverUrl by mutableStateOf(tokenStore.getBaseUrl() ?: "")
        private set
    var token by mutableStateOf(tokenStore.getToken() ?: "")
        private set
    var status by mutableStateOf("Not paired.")
        private set
    var busy by mutableStateOf(false)
        private set

    fun onServerUrlChange(value: String) { serverUrl = value }
    fun onTokenChange(value: String) { token = value }

    private fun persistAndBuild(): DeviceRepository? {
        if (serverUrl.isBlank()) {
            status = "Enter the server URL."
            return null
        }
        tokenStore.setBaseUrl(serverUrl.trim())
        tokenStore.setToken(token.trim())
        return repositoryFactory(serverUrl.trim(), tokenStore)
    }

    /** Check: call `/health` (unauthenticated) and show the server version. */
    fun onCheck() {
        val repo = persistAndBuild() ?: return
        busy = true
        status = "Checking..."
        viewModelScope.launch {
            try {
                val health = repo.health()
                status = "Server ${health.version} (${health.contract})"
            } catch (e: ApiException) {
                status = "Check failed: ${e.body?.message ?: "HTTP ${e.statusCode}"}"
            } catch (e: Exception) {
                status = "Check failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    /**
     * Ping: submit a `system.ping` job, then poll `/sync` on a 5 s cadence until it is
     * `done`/`failed` (SPEC §9.4). Gated by BuildConfig.PING_ENABLED at the call site.
     */
    fun onPing() {
        val repo = persistAndBuild() ?: return
        if (token.isBlank()) {
            status = "Enter a device token to ping."
            return
        }
        busy = true
        status = "Submitting ping..."
        viewModelScope.launch {
            try {
                val startCursor = repo.sync(null).cursor
                val job = repo.submitPing()
                status = "Ping queued (${job.id.take(8)}), polling /sync..."
                val terminal = repo.pollUntilTerminal(job.id, startCursor) {
                    // per-poll cursor observation; UI stays on the polling message
                }
                status = when (terminal.status) {
                    "done" -> "Ping done — round-trip OK (${terminal.result})"
                    else -> "Ping ${terminal.status}: ${terminal.error ?: "no detail"}"
                }
            } catch (e: ApiException) {
                status = "Ping failed: ${e.body?.message ?: "HTTP ${e.statusCode}"}"
            } catch (e: Exception) {
                status = "Ping failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }
}

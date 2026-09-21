package com.inkwell.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.inkwell.BuildConfig

/**
 * The app's only screen (walking skeleton): pair with the server, Check `/health`,
 * and Ping. The Ping button is gated by [BuildConfig.PING_ENABLED] (the kill-switch —
 * ON in debug, OFF in release until Stage 6); [pingEnabled] is injected so tests can
 * assert both states.
 */
@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    modifier: Modifier = Modifier,
    pingEnabled: Boolean = BuildConfig.PING_ENABLED,
    // Stage 29: the push-inbox status + Resync control. Null when the push inbox is not wired
    // (flag OFF / pairing-only build), in which case the Inbox section is not shown.
    inboxStatus: InboxStatus? = null,
    onResyncInbox: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Inkwell AI — Pairing")

        // Stage 28: show the installed app version so upgrade confusion is visible; the
        // paired server's /health version is surfaced in the status line by Check below.
        Text(
            text = "App v${BuildConfig.VERSION_NAME}",
            modifier = Modifier.testTag(PairingTags.APP_VERSION),
        )

        OutlinedTextField(
            value = viewModel.serverUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = { Text("Server URL") },
            placeholder = { Text("https://mini-hp01.taile0ffc4.ts.net:8444") },
            singleLine = true,
            enabled = !viewModel.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag(PairingTags.SERVER_URL),
        )

        OutlinedTextField(
            value = viewModel.token,
            onValueChange = viewModel::onTokenChange,
            label = { Text("Device token") },
            singleLine = true,
            enabled = !viewModel.busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag(PairingTags.TOKEN),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = viewModel::onCheck,
                enabled = !viewModel.busy,
                modifier = Modifier.testTag(PairingTags.CHECK),
            ) {
                Text("Check")
            }
            if (pingEnabled) {
                Button(
                    onClick = viewModel::onPing,
                    enabled = !viewModel.busy,
                    modifier = Modifier.testTag(PairingTags.PING),
                ) {
                    Text("Ping")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(text = viewModel.status, modifier = Modifier.testTag(PairingTags.STATUS))

        // Stage 29: push-inbox status + Resync so a swallowed poll failure is visible and the
        // owner can force a backfill (upgrade / missed / poisoned pushes).
        if (inboxStatus != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = inboxStatusLine(inboxStatus),
                modifier = Modifier.fillMaxWidth().testTag(PairingTags.INBOX_STATUS),
            )
            OutlinedButton(
                onClick = { onResyncInbox?.invoke() },
                enabled = onResyncInbox != null,
                modifier = Modifier.testTag(PairingTags.INBOX_RESYNC),
            ) {
                Text("Resync inbox")
            }
        }
    }
}

/** Human line for the Inbox status, e.g. "Inbox: last poll 13:04, 0 new, error: …". */
private fun inboxStatusLine(status: InboxStatus): String {
    val time = status.lastPollAt?.let {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(it))
    } ?: "never"
    val base = "Inbox: last poll $time, ${status.lastMaterialised} new"
    val err = status.lastPollError?.let { ", error: $it" } ?: ""
    val skipped = if (status.skipped.isEmpty()) "" else ", skipped ${status.skipped.size} (Resync to retry)"
    return base + err + skipped
}

/** Stable tags for the instrumented/Compose tests. */
object PairingTags {
    const val APP_VERSION = "pairing_app_version"
    const val SERVER_URL = "pairing_server_url"
    const val TOKEN = "pairing_token"
    const val CHECK = "pairing_check"
    const val PING = "pairing_ping"
    const val STATUS = "pairing_status"
    const val INBOX_STATUS = "pairing_inbox_status"
    const val INBOX_RESYNC = "pairing_inbox_resync"
}

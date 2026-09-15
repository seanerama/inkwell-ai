package com.inkwell.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.inkwell.BuildConfig
import com.inkwell.data.CanvasRepository
import com.inkwell.data.InkDatabase
import com.inkwell.net.EncryptedTokenStore

/**
 * Single-Activity host. Launch screen depends on the ink kill-switch
 * ([BuildConfig.INK_ENABLED], default ON): ON → [CanvasScreen] (the ink canvas);
 * OFF → the settings/pairing screen. Pairing is always reachable from the canvas's
 * Settings entry (SPEC §9.3 / stage: pairing moves to a settings entry).
 */
class MainActivity : ComponentActivity() {

    private val pairingViewModel: PairingViewModel by viewModels {
        val tokenStore = EncryptedTokenStore(applicationContext)
        viewModelFactory { initializer { PairingViewModel(tokenStore) } }
    }

    private val canvasViewModel: CanvasViewModel by viewModels {
        val db = InkDatabase.create(applicationContext)
        val repo = CanvasRepository(
            spaceDao = db.spaceDao(),
            canvasDao = db.canvasDao(),
            layerDao = db.layerDao(),
            strokeDao = db.strokeDao(),
        )
        viewModelFactory { initializer { CanvasViewModel(repo) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Screen routing without a nav library (two screens this stage).
                    var showSettings by remember { mutableStateOf(!BuildConfig.INK_ENABLED) }

                    if (BuildConfig.INK_ENABLED && !showSettings) {
                        CanvasScreen(
                            viewModel = canvasViewModel,
                            onOpenSettings = { showSettings = true },
                        )
                    } else {
                        SettingsScaffold(
                            canReturnToCanvas = BuildConfig.INK_ENABLED,
                            onBack = { showSettings = false },
                        ) {
                            PairingScreen(viewModel = pairingViewModel)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Wraps the pairing screen with a back affordance when it is reached as "settings"
 * from the canvas. When the ink kill-switch is OFF the pairing screen is the whole
 * app, so no back is offered.
 */
@androidx.compose.runtime.Composable
private fun SettingsScaffold(
    canReturnToCanvas: Boolean,
    onBack: () -> Unit,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (canReturnToCanvas) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.padding(8.dp),
            ) { Text("< Back to canvas") }
        }
        content()
    }
}

package com.inkwell.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.inkwell.net.EncryptedTokenStore

/** Single-Activity host for the walking-skeleton pairing screen. */
class MainActivity : ComponentActivity() {

    private val viewModel: PairingViewModel by viewModels {
        val tokenStore = EncryptedTokenStore(applicationContext)
        viewModelFactory {
            initializer { PairingViewModel(tokenStore) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PairingScreen(viewModel = viewModel)
                }
            }
        }
    }
}

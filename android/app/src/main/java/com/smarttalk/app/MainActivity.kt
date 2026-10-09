package com.smarttalk.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: ChatViewModel = viewModel()
            SmartTalkTheme(darkTheme = vm.settings.darkTheme) {
                Surface(
                    // One place owns the system-bar, cutout and keyboard insets, so no
                    // screen has to re-derive them and none can double-pad.
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SmartTalkApp(vm)
                }
            }
        }
    }
}

/**
 * Two screens, one back stack level. Kept as plain state rather than a
 * navigation library: there are only two destinations, and this avoids a
 * dependency whose API surface would need verifying.
 */
@Composable
private fun SmartTalkApp(vm: ChatViewModel) {
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(vm = vm, onDone = { showSettings = false })
    } else {
        ChatScreen(vm = vm, onOpenSettings = { showSettings = true })
    }
}

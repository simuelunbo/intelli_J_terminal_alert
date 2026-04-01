package com.terminalwatcher.settings.compose

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.awt.ComposePanel
import com.terminalwatcher.settings.SettingsViewModel
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import javax.swing.JComponent

object ComposeSettingsPanel {

    fun create(viewModel: SettingsViewModel): JComponent {
        return ComposePanel().apply {
            setContent {
                SwingBridgeTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    SettingsScreen(
                        uiState = uiState,
                        onAction = viewModel::onAction,
                    )
                }
            }
        }
    }
}

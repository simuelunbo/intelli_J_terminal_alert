package com.terminalwatcher.settings.compose

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.awt.ComposePanel
import com.terminalwatcher.settings.SettingsAction
import com.terminalwatcher.settings.SettingsUiState
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import javax.swing.JComponent

object ComposeSettingsPanel {

    fun create(
        uiStateFlow: StateFlow<SettingsUiState>,
        onAction: (SettingsAction) -> Unit,
    ): JComponent {
        return ComposePanel().apply {
            setContent {
                SwingBridgeTheme {
                    val uiState by uiStateFlow.collectAsState()
                    SettingsScreen(
                        uiState = uiState,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

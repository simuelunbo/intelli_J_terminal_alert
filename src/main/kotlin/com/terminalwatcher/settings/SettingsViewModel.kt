package com.terminalwatcher.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SettingsViewModel {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var savedSnapshot = SettingsUiState()

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.LoadSettings -> loadSettings()
            is SettingsAction.SaveSettings -> saveSettings()
            is SettingsAction.ResetSettings -> loadSettings()
            is SettingsAction.ToggleBadgeCount ->
                _uiState.update { it.copy(enableBadgeCount = action.enabled) }
            is SettingsAction.ToggleSystemNotification ->
                _uiState.update { it.copy(enableSystemNotification = action.enabled) }
            is SettingsAction.ToggleIdeBalloon ->
                _uiState.update { it.copy(enableIdeBalloon = action.enabled) }
            is SettingsAction.ToggleSound ->
                _uiState.update { it.copy(enableSound = action.enabled) }
            is SettingsAction.SelectSound ->
                _uiState.update { it.copy(soundName = action.name) }
            is SettingsAction.SelectCustomSoundPath ->
                _uiState.update { it.copy(customSoundPath = action.path) }
            is SettingsAction.ToggleClaudeCode ->
                _uiState.update { it.copy(enableClaudeCode = action.enabled) }
            is SettingsAction.ToggleCodex ->
                _uiState.update { it.copy(enableCodex = action.enabled) }
            is SettingsAction.ToggleGeminiCli ->
                _uiState.update { it.copy(enableGeminiCli = action.enabled) }
        }
    }

    // Framework integration (read-only 조회, 상태 변경 없음)
    fun isModified(): Boolean = _uiState.value != savedSnapshot

    private fun loadSettings() {
        val data = SettingsState.getInstance().state
        val state = SettingsUiState(
            enableBadgeCount = data.enableBadgeCount,
            enableSystemNotification = data.enableSystemNotification,
            enableIdeBalloon = data.enableIdeBalloon,
            enableSound = data.enableSound,
            soundName = data.soundName.orEmpty().ifEmpty { "Glass" },
            customSoundPath = data.customSoundPath.orEmpty(),
            enableClaudeCode = data.enableClaudeCode,
            enableCodex = data.enableCodex,
            enableGeminiCli = data.enableGeminiCli,
        )
        _uiState.value = state
        savedSnapshot = state
    }

    private fun saveSettings() {
        val data = SettingsState.getInstance().state
        val ui = _uiState.value
        data.enableBadgeCount = ui.enableBadgeCount
        data.enableSystemNotification = ui.enableSystemNotification
        data.enableIdeBalloon = ui.enableIdeBalloon
        data.enableSound = ui.enableSound
        data.soundName = ui.soundName
        data.customSoundPath = ui.customSoundPath
        data.enableClaudeCode = ui.enableClaudeCode
        data.enableCodex = ui.enableCodex
        data.enableGeminiCli = ui.enableGeminiCli
        savedSnapshot = ui
    }
}

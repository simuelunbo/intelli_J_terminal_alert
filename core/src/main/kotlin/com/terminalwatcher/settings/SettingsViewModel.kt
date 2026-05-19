package com.terminalwatcher.settings

import com.intellij.openapi.util.SystemInfo
import com.terminalwatcher.notify.NotifierProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SettingsViewModel {

    private val osLabels: OsLabels = when {
        SystemInfo.isMac -> OsLabels.MAC
        SystemInfo.isWindows -> OsLabels.WINDOWS
        else -> OsLabels.LINUX
    }

    private val _uiState = MutableStateFlow(SettingsUiState(osLabels = osLabels))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var savedSnapshot = _uiState.value

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
            is SettingsAction.PreviewSound -> previewSound(action.path)
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

    private fun previewSound(path: String) {
        if (path.isBlank()) return
        try {
            NotifierProvider.get().previewSound(path)
        } catch (_: Exception) {
            // 미리듣기는 실패해도 설정 흐름을 중단하지 않는다
        }
    }

    private fun loadSettings() {
        val data = SettingsState.getInstance().state
        val savedSoundName = data.soundName.orEmpty().ifEmpty {
            osLabels.soundList.firstOrNull().orEmpty()
        }
        // 저장된 이름이 현재 OS 사운드 리스트에 없으면 첫 항목으로 폴백
        // (예: Mac에서 "Glass"를 설정 → Windows로 이동 시 "chimes"로 자동 보정)
        val resolvedSoundName = when {
            osLabels.soundList.isEmpty() -> savedSoundName
            osLabels.soundList.contains(savedSoundName) -> savedSoundName
            else -> osLabels.soundList.first()
        }

        val state = SettingsUiState(
            enableBadgeCount = data.enableBadgeCount,
            enableSystemNotification = data.enableSystemNotification,
            enableIdeBalloon = data.enableIdeBalloon,
            enableSound = data.enableSound,
            soundName = resolvedSoundName,
            customSoundPath = data.customSoundPath.orEmpty(),
            enableClaudeCode = data.enableClaudeCode,
            enableCodex = data.enableCodex,
            enableGeminiCli = data.enableGeminiCli,
            osLabels = osLabels,
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

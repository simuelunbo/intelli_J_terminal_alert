package com.terminalwatcher.settings

/**
 * 설정 화면 UI 상태.
 *
 * OS별 라벨·사운드 목록·확장자 필터는 [OsLabels]에 위임된다. [osLabels] 필드는
 * `core/.../settings/SettingsViewModel.kt` 에서 OS를 감지해 주입한다.
 * UI 레이어는 OS를 직접 감지하지 않고 이 상태에서 라벨과 리스트만 읽는다.
 */
data class SettingsUiState(
    val enableBadgeCount: Boolean = true,
    val enableSystemNotification: Boolean = true,
    val enableIdeBalloon: Boolean = true,
    val enableSound: Boolean = true,
    val soundName: String = "Glass",
    val customSoundPath: String = "",
    val enableClaudeCode: Boolean = true,
    val enableCodex: Boolean = true,
    val enableGeminiCli: Boolean = true,
    val osLabels: OsLabels = OsLabels.MAC,
) {
    val soundList: List<String> get() = osLabels.soundList

    fun defaultSoundPath(name: String): String = osLabels.defaultSoundPath(name)
}

sealed interface SettingsAction {
    // 생명주기 Action (Configurable 프레임워크 연동)
    data object LoadSettings : SettingsAction
    data object SaveSettings : SettingsAction
    data object ResetSettings : SettingsAction

    // 알림 채널
    data class ToggleBadgeCount(val enabled: Boolean) : SettingsAction
    data class ToggleSystemNotification(val enabled: Boolean) : SettingsAction
    data class ToggleIdeBalloon(val enabled: Boolean) : SettingsAction

    // 사운드
    data class ToggleSound(val enabled: Boolean) : SettingsAction
    data class SelectSound(val name: String) : SettingsAction
    data class SelectCustomSoundPath(val path: String) : SettingsAction
    data class PreviewSound(val path: String) : SettingsAction

    // CLI 도구
    data class ToggleClaudeCode(val enabled: Boolean) : SettingsAction
    data class ToggleCodex(val enabled: Boolean) : SettingsAction
    data class ToggleGeminiCli(val enabled: Boolean) : SettingsAction
}

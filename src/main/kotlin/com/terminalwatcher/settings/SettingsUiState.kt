package com.terminalwatcher.settings

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
) {
    companion object {
        val SYSTEM_SOUNDS = listOf(
            "Glass", "Pop", "Ping", "Purr", "Blow", "Bottle",
            "Frog", "Hero", "Submarine", "Morse", "Tink",
        )
    }
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

    // CLI 도구
    data class ToggleClaudeCode(val enabled: Boolean) : SettingsAction
    data class ToggleCodex(val enabled: Boolean) : SettingsAction
    data class ToggleGeminiCli(val enabled: Boolean) : SettingsAction
}

package com.terminalwatcher.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "TerminalWatcherSettings",
    storages = [Storage("terminalWatcherSettings.xml")],
)
class SettingsState : SimplePersistentStateComponent<SettingsState.SettingsData>(SettingsData()) {

    class SettingsData : BaseState() {
        var enableNotifications by string(null) // 1.0.x 마이그레이션용
        var enableBadgeCount by property(true)
        var enableSystemNotification by property(true)
        var enableIdeBalloon by property(true)
        var enableSound by property(true)
        var soundName by string("Glass")
        var customSoundPath by string("")
        var enableClaudeCode by property(true)
        var enableCodex by property(true)
        var enableGeminiCli by property(true)
    }

    override fun loadState(state: SettingsData) {
        super.loadState(state)
        migrateFromV1(state)
    }

    private fun migrateFromV1(data: SettingsData) {
        val oldValue = data.enableNotifications ?: return
        if (oldValue == "false") {
            data.enableBadgeCount = false
            data.enableSystemNotification = false
            data.enableIdeBalloon = false
        }
        data.enableNotifications = null
    }

    fun isToolEnabled(toolName: String): Boolean = when (toolName) {
        "Claude Code" -> state.enableClaudeCode
        "Codex" -> state.enableCodex
        "Gemini CLI" -> state.enableGeminiCli
        else -> true
    }

    companion object {
        fun getInstance(): SettingsState =
            ApplicationManager.getApplication().getService(SettingsState::class.java)
    }
}

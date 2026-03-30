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
        var enableNotifications by property(true)
        var enableSound by property(true)
        var enableClaudeCode by property(true)
        var enableCodex by property(true)
        var enableGeminiCli by property(true)
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

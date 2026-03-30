package com.terminalwatcher.settings

import com.intellij.openapi.options.Configurable
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator

class SettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var enableNotifications: JCheckBox? = null
    private var enableSound: JCheckBox? = null
    private var enableClaudeCode: JCheckBox? = null
    private var enableCodex: JCheckBox? = null
    private var enableGeminiCli: JCheckBox? = null

    override fun getDisplayName(): String = "Terminal AI Watcher"

    override fun createComponent(): JComponent {
        val mainPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 8, 4, 8)
            gridx = 0
            weightx = 1.0
        }

        var row = 0

        val generalPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder("General")
        }
        val generalGbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(2, 4, 2, 4)
            gridx = 0
            weightx = 1.0
        }

        enableNotifications = JCheckBox("Enable notifications")
        generalGbc.gridy = 0
        generalPanel.add(enableNotifications, generalGbc)

        enableSound = JCheckBox("Enable sound alerts")
        generalGbc.gridy = 1
        generalPanel.add(enableSound, generalGbc)

        gbc.gridy = row++
        mainPanel.add(generalPanel, gbc)

        gbc.gridy = row++
        mainPanel.add(JSeparator(), gbc)

        val toolsPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder("CLI Tools")
        }
        val toolsGbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(2, 4, 2, 4)
            gridx = 0
            weightx = 1.0
        }

        enableClaudeCode = JCheckBox("Claude Code")
        toolsGbc.gridy = 0
        toolsPanel.add(enableClaudeCode, toolsGbc)

        enableCodex = JCheckBox("OpenAI Codex")
        toolsGbc.gridy = 1
        toolsPanel.add(enableCodex, toolsGbc)

        enableGeminiCli = JCheckBox("Gemini CLI")
        toolsGbc.gridy = 2
        toolsPanel.add(enableGeminiCli, toolsGbc)

        gbc.gridy = row++
        mainPanel.add(toolsPanel, gbc)

        gbc.gridy = row
        gbc.weighty = 1.0
        mainPanel.add(JPanel(), gbc)

        panel = mainPanel
        reset()
        return mainPanel
    }

    override fun isModified(): Boolean {
        val data = SettingsState.getInstance().state
        return enableNotifications?.isSelected != data.enableNotifications ||
            enableSound?.isSelected != data.enableSound ||
            enableClaudeCode?.isSelected != data.enableClaudeCode ||
            enableCodex?.isSelected != data.enableCodex ||
            enableGeminiCli?.isSelected != data.enableGeminiCli
    }

    override fun apply() {
        val data = SettingsState.getInstance().state
        data.enableNotifications = enableNotifications?.isSelected ?: true
        data.enableSound = enableSound?.isSelected ?: true
        data.enableClaudeCode = enableClaudeCode?.isSelected ?: true
        data.enableCodex = enableCodex?.isSelected ?: true
        data.enableGeminiCli = enableGeminiCli?.isSelected ?: true
    }

    override fun reset() {
        val data = SettingsState.getInstance().state
        enableNotifications?.isSelected = data.enableNotifications
        enableSound?.isSelected = data.enableSound
        enableClaudeCode?.isSelected = data.enableClaudeCode
        enableCodex?.isSelected = data.enableCodex
        enableGeminiCli?.isSelected = data.enableGeminiCli
    }

    override fun disposeUIResources() {
        panel = null
        enableNotifications = null
        enableSound = null
        enableClaudeCode = null
        enableCodex = null
        enableGeminiCli = null
    }
}

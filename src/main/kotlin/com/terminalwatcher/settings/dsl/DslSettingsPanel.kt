package com.terminalwatcher.settings.dsl

import com.intellij.ui.dsl.builder.panel
import com.terminalwatcher.settings.SettingsAction
import com.terminalwatcher.settings.SettingsUiState
import com.terminalwatcher.settings.SettingsViewModel
import javax.swing.JCheckBox
import javax.swing.JComponent

object DslSettingsPanel {

    fun create(viewModel: SettingsViewModel): JComponent {
        val state = viewModel.uiState.value

        return panel {
            group("Notification Channels") {
                row {
                    checkBox("Dock badge count").apply {
                        component.isSelected = state.enableBadgeCount
                        component.addActionListener {
                            viewModel.onAction(SettingsAction.ToggleBadgeCount((it.source as JCheckBox).isSelected))
                        }
                    }
                }
                row {
                    checkBox("macOS system notification").apply {
                        component.isSelected = state.enableSystemNotification
                        component.addActionListener {
                            viewModel.onAction(SettingsAction.ToggleSystemNotification((it.source as JCheckBox).isSelected))
                        }
                    }
                }
                row {
                    checkBox("IDE balloon (Timeline)").apply {
                        component.isSelected = state.enableIdeBalloon
                        component.addActionListener {
                            viewModel.onAction(SettingsAction.ToggleIdeBalloon((it.source as JCheckBox).isSelected))
                        }
                    }
                }
                row {
                    checkBox("Sound alert").apply {
                        component.isSelected = state.enableSound
                        component.addActionListener {
                            viewModel.onAction(SettingsAction.ToggleSound((it.source as JCheckBox).isSelected))
                        }
                    }
                }
            }

            group("Sound") {
                row("Default sound:") {
                    comboBox(SettingsUiState.SYSTEM_SOUNDS).apply {
                        component.selectedItem = state.soundName
                        component.addActionListener {
                            viewModel.onAction(SettingsAction.SelectSound(component.selectedItem as? String ?: "Glass"))
                        }
                    }
                }
                row("Custom file:") {
                    textField().apply {
                        component.text = state.customSoundPath
                        component.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = sync()
                            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = sync()
                            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = sync()
                            private fun sync() {
                                viewModel.onAction(SettingsAction.SelectCustomSoundPath(component.text))
                            }
                        })
                    }
                }
            }

            group("CLI Tools") {
                row {
                    checkBox("Claude Code").apply {
                        component.isSelected = state.enableClaudeCode
                        component.addActionListener {
                            viewModel.onAction(SettingsAction.ToggleClaudeCode((it.source as JCheckBox).isSelected))
                        }
                    }
                }
                row {
                    checkBox("OpenAI Codex").apply {
                        component.isSelected = state.enableCodex
                        component.addActionListener {
                            viewModel.onAction(SettingsAction.ToggleCodex((it.source as JCheckBox).isSelected))
                        }
                    }
                }
                row {
                    checkBox("Gemini CLI").apply {
                        component.isSelected = state.enableGeminiCli
                        component.addActionListener {
                            viewModel.onAction(SettingsAction.ToggleGeminiCli((it.source as JCheckBox).isSelected))
                        }
                    }
                }
            }
        }
    }
}

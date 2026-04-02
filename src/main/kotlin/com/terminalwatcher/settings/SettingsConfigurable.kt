package com.terminalwatcher.settings

import com.intellij.openapi.options.Configurable
import com.terminalwatcher.settings.dsl.DslSettingsPanel
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class SettingsConfigurable : Configurable {

    private val viewModel by lazy { SettingsViewModel() }
    private var wrapper: JPanel? = null

    override fun getDisplayName(): String = "Terminal AI Watcher"

    override fun createComponent(): JComponent {
        viewModel.onAction(SettingsAction.LoadSettings)
        val panel = JPanel(BorderLayout())
        wrapper = panel
        rebuildContent()
        return panel
    }

    override fun isModified(): Boolean = viewModel.isModified()

    override fun apply() {
        viewModel.onAction(SettingsAction.SaveSettings)
    }

    override fun reset() {
        viewModel.onAction(SettingsAction.ResetSettings)
        rebuildContent()
    }

    override fun disposeUIResources() {
        wrapper = null
    }

    private fun rebuildContent() {
        val panel = wrapper ?: return
        panel.removeAll()
        panel.add(DslSettingsPanel.create(viewModel), BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }
}

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
        val content = createComposePanel() ?: DslSettingsPanel.create(viewModel)
        panel.add(content, BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }

    private fun createComposePanel(): JComponent? = try {
        val clazz = Class.forName("com.terminalwatcher.settings.compose.ComposeSettingsPanel")
        val instance = clazz.getField("INSTANCE").get(null)
        val method = clazz.getMethod(
            "create",
            kotlinx.coroutines.flow.StateFlow::class.java,
            kotlin.jvm.functions.Function1::class.java,
        )
        val onAction: (SettingsAction) -> Unit = viewModel::onAction
        method.invoke(instance, viewModel.uiState, onAction) as? JComponent
    } catch (_: Exception) {
        null
    }
}

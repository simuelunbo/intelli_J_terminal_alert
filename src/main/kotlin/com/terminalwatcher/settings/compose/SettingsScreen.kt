package com.terminalwatcher.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.terminalwatcher.settings.SettingsAction
import com.terminalwatcher.settings.SettingsUiState
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Notification Channels
        SectionHeader("Notification Channels")
        CheckboxRow(
            checked = uiState.enableBadgeCount,
            onCheckedChange = { onAction(SettingsAction.ToggleBadgeCount(it)) },
        ) { Text("Dock badge count") }

        CheckboxRow(
            checked = uiState.enableSystemNotification,
            onCheckedChange = { onAction(SettingsAction.ToggleSystemNotification(it)) },
        ) { Text("macOS system notification") }

        CheckboxRow(
            checked = uiState.enableIdeBalloon,
            onCheckedChange = { onAction(SettingsAction.ToggleIdeBalloon(it)) },
        ) { Text("IDE balloon (Timeline)") }

        CheckboxRow(
            checked = uiState.enableSound,
            onCheckedChange = { onAction(SettingsAction.ToggleSound(it)) },
        ) { Text("Sound alert") }

        Spacer(Modifier.height(12.dp))

        // Sound
        SectionHeader("Sound")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Default sound:", modifier = Modifier.width(120.dp))
            Dropdown(
                menuContent = {
                    SettingsUiState.SYSTEM_SOUNDS.forEach { sound ->
                        selectableItem(
                            selected = uiState.soundName == sound,
                            onClick = { onAction(SettingsAction.SelectSound(sound)) },
                        ) { Text(sound) }
                    }
                },
            ) {
                Text(uiState.soundName)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Custom file:", modifier = Modifier.width(120.dp))
            Text(
                text = uiState.customSoundPath.ifEmpty { "No file selected" },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                    .withFileFilter { it.extension in SOUND_EXTENSIONS }
                FileChooser.chooseFile(descriptor, null, null) { file ->
                    onAction(SettingsAction.SelectCustomSoundPath(file.path))
                }
            }) { Text("Browse\u2026") }
            if (uiState.customSoundPath.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = {
                    onAction(SettingsAction.SelectCustomSoundPath(""))
                }) { Text("Clear") }
            }
        }

        Spacer(Modifier.height(12.dp))

        // CLI Tools
        SectionHeader("CLI Tools")
        CheckboxRow(
            checked = uiState.enableClaudeCode,
            onCheckedChange = { onAction(SettingsAction.ToggleClaudeCode(it)) },
        ) { Text("Claude Code") }

        CheckboxRow(
            checked = uiState.enableCodex,
            onCheckedChange = { onAction(SettingsAction.ToggleCodex(it)) },
        ) { Text("OpenAI Codex") }

        CheckboxRow(
            checked = uiState.enableGeminiCli,
            onCheckedChange = { onAction(SettingsAction.ToggleGeminiCli(it)) },
        ) { Text("Gemini CLI") }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp, top = 8.dp),
    )
}

private val SOUND_EXTENSIONS = setOf("aiff", "aif", "wav", "mp3", "m4a", "caf")

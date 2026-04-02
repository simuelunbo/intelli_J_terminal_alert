package com.terminalwatcher.dispatch

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.terminalwatcher.hook.HookEvent
import com.terminalwatcher.hook.HookEventType
import com.terminalwatcher.mac.MacNotifier
import com.terminalwatcher.settings.SettingsState

object NotificationDispatcher {

    private val log = Logger.getInstance(NotificationDispatcher::class.java)

    fun dispatchHookEvent(event: HookEvent) {
        val settings = SettingsState.getInstance()
        val state = settings.state

        if (!state.enableBadgeCount && !state.enableSystemNotification &&
            !state.enableIdeBalloon && !state.enableSound
        ) return

        val toolName = resolveToolName(event.tool)
        if (!settings.isToolEnabled(toolName)) return

        val subtitle = event.eventType.toSubtitle()
        val notificationType = event.eventType.toNotificationType()

        val location = buildLocationTag(event.cwd, event.tabName)
        val message = "$location${event.message?.take(200) ?: toolName}"

        log.info("[TWatcher] Notification: $toolName — $subtitle — $message")

        val notifier = ApplicationManager.getApplication().getService(MacNotifier::class.java)
        notifier.sendNotification(
            toolName = toolName,
            subtitle = subtitle,
            message = message,
            notificationType = notificationType,
        )

        notifier.playSound()
    }

    private fun resolveToolName(tool: String): String = when (tool) {
        "claude" -> "Claude Code"
        "codex" -> "Codex"
        "gemini" -> "Gemini CLI"
        else -> tool
    }

    private fun buildLocationTag(cwd: String?, tabName: String?): String {
        val project = cwd?.substringAfterLast("/")?.takeIf { it.isNotBlank() }.orEmpty()
        val tab = tabName.orEmpty()
        return when {
            project.isNotBlank() && tab.isNotBlank() -> "[$project/$tab] "
            project.isNotBlank() -> "[$project] "
            else -> ""
        }
    }

    private fun HookEventType.toSubtitle(): String = when (this) {
        HookEventType.PERMISSION -> "Permission Required"
        HookEventType.COMPLETE -> "Completed"
        HookEventType.ERROR -> "Error"
    }

    private fun HookEventType.toNotificationType(): NotificationType = when (this) {
        HookEventType.PERMISSION -> NotificationType.WARNING
        HookEventType.COMPLETE -> NotificationType.INFORMATION
        HookEventType.ERROR -> NotificationType.ERROR
    }
}

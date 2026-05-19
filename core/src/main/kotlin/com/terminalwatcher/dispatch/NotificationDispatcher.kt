package com.terminalwatcher.dispatch

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.terminalwatcher.hook.HookEvent
import com.terminalwatcher.hook.HookEventType
import com.terminalwatcher.notify.NotificationContext
import com.terminalwatcher.notify.NotifierProvider
import com.terminalwatcher.settings.SettingsState
import com.terminalwatcher.terminal.TabRegistry
import com.terminalwatcher.terminal.TerminalTabTracker

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

        val context = resolveContext(event)
        val rawMessage = event.message?.take(200) ?: toolName

        log.info("[TWatcher] Notification: $toolName — $subtitle — ctx=$context — $rawMessage")

        val notifier = NotifierProvider.get()
        notifier.sendNotification(
            toolName = toolName,
            subtitle = subtitle,
            message = rawMessage,
            notificationType = notificationType,
            context = context,
        )

        notifier.playSound()
    }

    private fun resolveContext(event: HookEvent): NotificationContext {
        // 1) tabId → TabRegistry exact match (env-injected path)
        val tabRegistry = ApplicationManager.getApplication()
            .getService(TabRegistry::class.java)
        val tabEntry = event.tabId?.let { tabRegistry?.lookup(it) }
        val tabNameFromRegistry = tabEntry?.contentRef?.get()?.displayName
        val projectFromRegistry = tabEntry?.projectId?.let { tabRegistry?.findProjectByProjectId(it) }

        // 2) Fallback chain: registry-resolved → event.tabName (heuristics) → cwd-based project
        val tabName = tabNameFromRegistry ?: event.tabName
        val projectName = projectFromRegistry?.name
            ?: event.cwd?.let { TerminalTabTracker.findProjectByCwd(it)?.name }
            ?: event.cwd?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

        return NotificationContext(
            projectName = projectName,
            tabName = tabName,
            projectId = event.projectId,
            tabId = event.tabId,
        )
    }

    private fun resolveToolName(tool: String): String = when (tool) {
        "claude" -> "Claude Code"
        "codex" -> "Codex"
        "gemini" -> "Gemini CLI"
        else -> tool
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

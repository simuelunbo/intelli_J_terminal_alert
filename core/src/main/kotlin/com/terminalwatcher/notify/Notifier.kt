package com.terminalwatcher.notify

import com.intellij.notification.NotificationType

/**
 * OS-agnostic abstraction over notification channels (IDE balloon, system
 * notification, taskbar/dock attention, sound playback).
 *
 * Implementations live under OS-specific packages and must NOT be imported
 * directly from cross-platform code — use [NotifierProvider] so that only
 * the current OS's implementation class is loaded at runtime.
 */
interface Notifier {
    /**
     * Legacy entry point — kept so the public surface remains source-compatible
     * for anything that hasn't migrated to the context-aware overload.
     * [NotificationDispatcher][com.terminalwatcher.dispatch.NotificationDispatcher]
     * always calls the context-aware overload below.
     */
    fun sendNotification(
        toolName: String,
        subtitle: String,
        message: String,
        notificationType: NotificationType = NotificationType.INFORMATION,
    )

    /**
     * Context-aware overload. [context] carries the resolved project/tab
     * identifiers (env-injected when available, heuristic fallback otherwise)
     * so OS implementations can format `[project/tab]` into native fields
     * (e.g. macOS subtitle line) instead of prefixing the message body.
     *
     * Default implementation prepends the location tag to [message] and
     * delegates to the legacy [sendNotification] — preserves behavior for
     * notifiers that haven't been updated yet.
     */
    fun sendNotification(
        toolName: String,
        subtitle: String,
        message: String,
        notificationType: NotificationType,
        context: NotificationContext?,
    ) {
        val location = buildLocationTag(context)
        sendNotification(toolName, subtitle, "$location$message", notificationType)
    }

    fun playSound()

    fun previewSound(path: String)

    fun resetBadge()

    companion object {
        /** Standardized `[project/tab] ` prefix used by the default overload + by Notifiers that want consistent formatting. */
        fun buildLocationTag(context: NotificationContext?): String {
            val project = context?.projectName.orEmpty()
            val tab = context?.tabName.orEmpty()
            return when {
                project.isNotBlank() && tab.isNotBlank() -> "[$project/$tab] "
                project.isNotBlank() -> "[$project] "
                tab.isNotBlank() -> "[$tab] "
                else -> ""
            }
        }
    }
}

package com.terminalwatcher.mac

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.SystemNotifications
import com.intellij.openapi.application.ApplicationManager
import com.terminalwatcher.settings.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class MacNotifier(private val scope: CoroutineScope) {

    private val log = Logger.getInstance(MacNotifier::class.java)

    @Volatile
    private var badgeCount = 0

    fun sendNotification(
        toolName: String,
        subtitle: String,
        message: String,
        notificationType: NotificationType = NotificationType.INFORMATION,
    ) {
        if (isGloballyThrottled()) return
        if (isThrottled(message)) return

        val state = SettingsState.getInstance().state

        if (state.enableIdeBalloon) {
            try {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification("$toolName — $subtitle", message, notificationType)
                    .notify(null)
            } catch (e: Exception) {
                log.warn("[TWatcher] Failed to send IDE notification", e)
            }
        }

        if (state.enableSystemNotification) {
            try {
                SystemNotifications.getInstance().notify(
                    SYSTEM_NOTIFICATION_NAME, "$toolName — $subtitle", message,
                )
            } catch (e: Exception) {
                log.warn("[TWatcher] Failed to send system notification", e)
            }
        }

        if (state.enableBadgeCount) {
            incrementBadge()
        }
    }

    fun playSound() {
        val state = SettingsState.getInstance().state
        if (!state.enableSound) return

        scope.launch(Dispatchers.IO) {
            try {
                val customPath = state.customSoundPath.orEmpty().trim()
                val soundPath = customPath.ifBlank {
                    "/System/Library/Sounds/${state.soundName}.aiff"
                }

                if (!File(soundPath).exists()) {
                    log.warn("[TWatcher] Sound file not found: $soundPath")
                    return@launch
                }

                ProcessBuilder("afplay", soundPath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            } catch (e: Exception) {
                log.warn("[TWatcher] Failed to play sound", e)
            }
        }
    }

    fun resetBadge() {
        synchronized(this) {
            badgeCount = 0
        }
        updateDockBadge(null)
        log.info("[TWatcher] Badge counter reset")
    }

    private fun incrementBadge() {
        val count = synchronized(this) { ++badgeCount }
        updateDockBadge(count.toString())

        if (ApplicationManager.getApplication().isActive) {
            scope.launch {
                delay(BADGE_FLASH_MS)
                resetBadge()
            }
        }
    }

    private fun updateDockBadge(label: String?) {
        try {
            if (java.awt.Taskbar.isTaskbarSupported()) {
                java.awt.Taskbar.getTaskbar().setIconBadge(label)
            }
        } catch (e: Exception) {
            log.debug("[TWatcher] Taskbar badge not supported: ${e.message}")
        }
    }

    private fun isGloballyThrottled(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastGlobalNotificationTime < GLOBAL_THROTTLE_MS) return true
        lastGlobalNotificationTime = now
        return false
    }

    private fun isThrottled(key: String): Boolean {
        val now = System.currentTimeMillis()
        val lastTime = lastNotificationTimes.put(key, now)
        return lastTime != null && (now - lastTime) < THROTTLE_WINDOW_MS
    }

    companion object {
        private const val THROTTLE_WINDOW_MS = 2000L
        private const val GLOBAL_THROTTLE_MS = 5000L
        private const val BADGE_FLASH_MS = 500L
        private const val NOTIFICATION_GROUP_ID = "Terminal AI Watcher"
        private const val SYSTEM_NOTIFICATION_NAME = "terminal-ai-watcher"
        private val lastNotificationTimes = ConcurrentHashMap<String, Long>()

        @Volatile
        private var lastGlobalNotificationTime = 0L
    }
}

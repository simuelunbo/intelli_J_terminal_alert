package com.terminalwatcher.mac

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.SystemNotifications
import com.terminalwatcher.hook.SoundType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

        // 1. IDE 내부 BALLOON 알림 (Timeline)
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification("$toolName — $subtitle", message, notificationType)
                .notify(null)
        } catch (e: Exception) {
            log.warn("[TWatcher] Failed to send IDE notification", e)
        }

        // 2. macOS 네이티브 알림 (SystemNotifications — IDE 아이콘 + 클릭 시 IDE 활성화)
        try {
            SystemNotifications.getInstance().notify(
                SYSTEM_NOTIFICATION_NAME,
                "$toolName — $subtitle",
                message,
            )
        } catch (e: Exception) {
            log.warn("[TWatcher] Failed to send system notification", e)
        }

        // 3. Dock 뱃지 증가
        incrementBadge()
    }

    fun playSound(soundType: SoundType) {
        scope.launch(Dispatchers.IO) {
            try {
                ProcessBuilder("afplay", "/System/Library/Sounds/${soundType.fileName}.aiff")
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
        updateDockBadge("")
        log.info("[TWatcher] Badge counter reset")
    }

    private fun incrementBadge() {
        val count = synchronized(this) { ++badgeCount }
        updateDockBadge(count.toString())
    }

    private fun updateDockBadge(label: String) {
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
        private const val NOTIFICATION_GROUP_ID = "Terminal AI Watcher"
        private const val SYSTEM_NOTIFICATION_NAME = "terminal-ai-watcher"
        private val lastNotificationTimes = ConcurrentHashMap<String, Long>()

        @Volatile
        private var lastGlobalNotificationTime = 0L
    }
}

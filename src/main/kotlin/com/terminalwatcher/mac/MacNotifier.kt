package com.terminalwatcher.mac

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
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

        // IDE BALLOON
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification("$toolName — $subtitle", message, notificationType)
                .notify(null)
        } catch (e: Exception) {
            log.warn("[TWatcher] Failed to send IDE notification", e)
        }

        // macOS 알림 센터 (Android Studio 아이콘)
        scope.launch(Dispatchers.IO) {
            sendMacSystemNotification(toolName, subtitle, message)
        }

        // Dock 뱃지 증가
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

    private fun sendMacSystemNotification(toolName: String, subtitle: String, message: String) {
        try {
            val escapedTitle = toolName.replace("\"", "\\\"")
            val escapedSubtitle = subtitle.replace("\"", "\\\"")
            val escapedMessage = message.replace("\"", "\\\"")

            val script = """
                tell application "Android Studio"
                    display notification "$escapedMessage" with title "$escapedTitle" subtitle "$escapedSubtitle" sound name "$DEFAULT_SOUND"
                end tell
            """.trimIndent()

            ProcessBuilder("osascript", "-e", script)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        } catch (e: Exception) {
            log.warn("[TWatcher] Failed to send macOS notification", e)
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
        private const val DEFAULT_SOUND = "Glass"
        private const val NOTIFICATION_GROUP_ID = "Terminal AI Watcher"
        private val lastNotificationTimes = ConcurrentHashMap<String, Long>()

        @Volatile
        private var lastGlobalNotificationTime = 0L
    }
}

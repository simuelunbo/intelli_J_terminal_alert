package com.terminalwatcher.fallback

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.terminalwatcher.notify.NotificationContext
import com.terminalwatcher.notify.Notifier
import com.terminalwatcher.settings.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent

/**
 * Minimal non-Mac, non-Windows notifier. Linux 및 기타 OS에서 사용된다.
 * IDE balloon 알림과 사용자가 지정한 커스텀 사운드 파일 재생만 지원.
 */
@Service(Service.Level.APP)
class FallbackNotifier(private val scope: CoroutineScope) : Notifier {

    private val log = Logger.getInstance(FallbackNotifier::class.java)

    override fun sendNotification(
        toolName: String,
        subtitle: String,
        message: String,
        notificationType: NotificationType,
    ) = sendNotification(toolName, subtitle, message, notificationType, null)

    override fun sendNotification(
        toolName: String,
        subtitle: String,
        message: String,
        notificationType: NotificationType,
        context: NotificationContext?,
    ) {
        val state = SettingsState.getInstance().state
        val locTag = Notifier.buildLocationTag(context).trim()

        if (state.enableIdeBalloon) {
            try {
                val notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification("$toolName — $subtitle", message, notificationType)
                if (locTag.isNotBlank()) notification.subtitle = locTag
                Notifier.attachFocusAction(notification, context)
                notification.notify(null)
            } catch (e: Exception) {
                log.warn("[TWatcher] Failed to send IDE notification", e)
            }
        }
    }

    override fun playSound() {
        val state = SettingsState.getInstance().state
        if (!state.enableSound) return

        val path = state.customSoundPath.orEmpty().trim()
        if (path.isBlank()) return

        scope.launch(Dispatchers.IO) { playFile(path) }
    }

    override fun previewSound(path: String) {
        if (path.isBlank()) return
        scope.launch(Dispatchers.IO) { playFile(path) }
    }

    override fun resetBadge() {
        // Linux 등에서는 배지/작업 표시줄 API가 표준화되어 있지 않아 no-op
    }

    private fun playFile(path: String) {
        val file = File(path)
        if (!file.exists()) {
            log.warn("[TWatcher] Sound file not found: $path")
            return
        }
        try {
            val ais = AudioSystem.getAudioInputStream(file)
            val clip = AudioSystem.getClip()
            clip.open(ais)
            clip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    runCatching { clip.close() }
                    runCatching { ais.close() }
                }
            }
            clip.start()
        } catch (e: Exception) {
            log.warn("[TWatcher] Failed to play sound: $path", e)
        }
    }

    companion object {
        private const val NOTIFICATION_GROUP_ID = "Terminal AI Watcher"


    }
}

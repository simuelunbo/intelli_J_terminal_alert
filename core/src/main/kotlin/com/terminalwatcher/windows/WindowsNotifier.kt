package com.terminalwatcher.windows

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.SystemNotifications
import com.terminalwatcher.notify.NotificationContext
import com.terminalwatcher.notify.Notifier
import com.terminalwatcher.settings.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Taskbar
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import javax.sound.sampled.UnsupportedAudioFileException

/**
 * Windows 10/11 전용 Notifier.
 *
 * ## 알림·배지
 * - IDE balloon: `NotificationGroupManager` (크로스플랫폼).
 * - System notification: `SystemNotifications` — 플랫폼이 Windows Toast로 위임.
 * - Badge: `java.awt.Taskbar.requestUserAttention` — 작업 표시줄 flash로 매핑. 별도 해제 불필요.
 *
 * ## 사운드 재생 (확장자 기반 라우팅)
 * - `.wav` / `.aiff` / `.aif` / `.au` / `.snd`: `javax.sound.sampled` 로 in-process 재생.
 * - 그 외 (`.mp3`, `.m4a`, `.wma`, `.aac` 등): PowerShell + `System.Windows.Media.MediaPlayer`
 *   폴백. JNA·외부 라이브러리 번들 없이 Windows 기본 PowerShell + WPF 만 사용한다.
 *
 * PowerShell 폴백은 알림용이므로 [POWERSHELL_PLAYBACK_TIMEOUT_SECONDS] 상한을 두어
 * 긴 음원이더라도 일정 시간 내에 종료시킨다.
 */
@Service(Service.Level.APP)
class WindowsNotifier(private val scope: CoroutineScope) : Notifier {

    private val log = Logger.getInstance(WindowsNotifier::class.java)

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
        if (isGloballyThrottled()) return
        if (isThrottled(message)) return

        val state = SettingsState.getInstance().state
        val locTag = Notifier.buildLocationTag(context).trim()

        if (state.enableIdeBalloon) {
            try {
                val notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification("$toolName — $subtitle", message, notificationType)
                if (locTag.isNotBlank()) notification.subtitle = locTag
                notification.notify(null)
            } catch (e: Exception) {
                log.warn("[TWatcher] Failed to send IDE notification", e)
            }
        }

        if (state.enableSystemNotification) {
            try {
                val sysBody = if (locTag.isNotBlank()) "$locTag $message" else message
                SystemNotifications.getInstance().notify(
                    SYSTEM_NOTIFICATION_NAME, "$toolName — $subtitle", sysBody,
                )
            } catch (e: Exception) {
                log.warn("[TWatcher] Failed to send system notification", e)
            }
        }

        if (state.enableBadgeCount) {
            requestTaskbarAttention()
        }
    }

    override fun playSound() {
        val state = SettingsState.getInstance().state
        if (!state.enableSound) return

        scope.launch(Dispatchers.IO) {
            val customPath = state.customSoundPath.orEmpty().trim()
            val soundPath = customPath.ifBlank { defaultSoundPath(state.soundName.orEmpty()) }
            if (soundPath.isBlank()) return@launch
            playFile(soundPath)
        }
    }

    override fun previewSound(path: String) {
        if (path.isBlank()) return
        scope.launch(Dispatchers.IO) { playFile(path) }
    }

    override fun resetBadge() {
        // Windows taskbar attention은 사용자 상호작용 시 자동으로 해제된다 → no-op
    }

    private fun playFile(path: String) {
        val file = File(path)
        if (!file.exists()) {
            log.warn("[TWatcher] Sound file not found: $path")
            return
        }
        val ext = file.extension.lowercase()
        val playedByJava = if (ext in JAVA_SOUND_EXTENSIONS) tryJavaSoundPlayback(file) else false
        if (!playedByJava) {
            tryPowerShellMediaPlayer(file)
        }
    }

    private fun tryJavaSoundPlayback(file: File): Boolean {
        return try {
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
            true
        } catch (_: UnsupportedAudioFileException) {
            false
        } catch (e: Exception) {
            log.warn("[TWatcher] javax.sound playback failed: ${file.absolutePath}", e)
            false
        }
    }

    private fun tryPowerShellMediaPlayer(file: File) {
        try {
            // file:/C:/... 형태 URI. PowerShell 싱글쿼트 내 literal, '는 ''로 이스케이프.
            val uri = file.toURI().toString().replace("'", "''")
            val script = buildString {
                append("Add-Type -AssemblyName PresentationCore;")
                append("\$p = New-Object System.Windows.Media.MediaPlayer;")
                append("\$p.Open([System.Uri]'").append(uri).append("');")
                append("\$p.Play();")
                append("Start-Sleep -Seconds ").append(POWERSHELL_PLAYBACK_TIMEOUT_SECONDS).append(";")
                append("\$p.Stop();")
            }
            ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden",
                "-Command", script,
            )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            // waitFor 하지 않음 — 알림 스레드 차단 방지, PowerShell 측 Start-Sleep 상한이 lifecycle 관리
        } catch (e: Exception) {
            log.warn("[TWatcher] PowerShell MediaPlayer playback failed: ${file.absolutePath}", e)
        }
    }

    private fun requestTaskbarAttention() {
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar.getTaskbar().requestUserAttention(true, false)
            }
        } catch (e: Exception) {
            log.debug("[TWatcher] Taskbar attention request not supported: ${e.message}")
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
        private const val POWERSHELL_PLAYBACK_TIMEOUT_SECONDS = 3

        /** javax.sound.sampled 가 JDK 기본 번들로 직접 디코딩 가능한 확장자. */
        private val JAVA_SOUND_EXTENSIONS = setOf("wav", "aiff", "aif", "au", "snd")

        private val lastNotificationTimes = ConcurrentHashMap<String, Long>()

        @Volatile
        private var lastGlobalNotificationTime = 0L

        private fun defaultSoundPath(soundName: String): String {
            if (soundName.isBlank()) return ""
            val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
            return "$systemRoot\\Media\\$soundName.wav"
        }
    }
}

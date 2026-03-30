package com.terminalwatcher.hook

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.IdeFrame
import com.sun.net.httpserver.HttpServer
import com.terminalwatcher.dispatch.NotificationDispatcher
import com.terminalwatcher.mac.MacNotifier
import com.terminalwatcher.terminal.TerminalTabTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class HookHttpServer(
    private val scope: CoroutineScope,
) : Disposable {

    private val log = Logger.getInstance(HookHttpServer::class.java)
    private var server: HttpServer? = null

    private val json = Json { ignoreUnknownKeys = true }

    /** 서버 시작 완료를 알리는 래치. setupAllHooks() 전에 대기용. */
    private val serverReady = CountDownLatch(1)

    var actualPort: Int = 0
        private set

    init {
        scope.launch(Dispatchers.IO) {
            cleanupStalePortFiles()
            startServer()
        }
        registerBadgeResetOnFocus()
    }

    /** 서버가 시작될 때까지 최대 5초 대기. setupAllHooks() 호출 전에 사용. */
    fun awaitReady() {
        serverReady.await(5, TimeUnit.SECONDS)
    }

    private fun startServer() {
        try {
            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            httpServer.createContext("/event") { exchange ->
                try {
                    if (exchange.requestMethod == "POST") {
                        val body = exchange.requestBody.bufferedReader().readText()
                        val query = exchange.requestURI.query.orEmpty()
                        val tool = query.substringAfter("tool=", "").substringBefore("&")
                        log.info("[TWatcher] Hook received: tool=$tool, body=${body.take(300)}")

                        val event = parseToHookEvent(body, tool)
                        if (event != null) {
                            NotificationDispatcher.dispatchHookEvent(event)
                        }
                        exchange.sendResponseHeaders(200, 0)
                    } else {
                        exchange.sendResponseHeaders(405, 0)
                    }
                } catch (e: Exception) {
                    log.warn("[TWatcher] Error handling hook event", e)
                    exchange.sendResponseHeaders(500, 0)
                } finally {
                    exchange.responseBody.close()
                }
            }
            httpServer.executor = null
            httpServer.start()
            server = httpServer
            actualPort = httpServer.address.port

            writePortFile(actualPort)
            log.info("[TWatcher] Hook server started on port $actualPort")
        } catch (e: Exception) {
            log.warn("[TWatcher] Failed to start hook server", e)
        } finally {
            serverReady.countDown()
        }
    }

    /**
     * 이전 IDE 크래시로 남은 stale 포트 파일 정리.
     * PID가 살아있지 않은 파일만 삭제.
     */
    private fun cleanupStalePortFiles() {
        try {
            val portsDir = File(System.getProperty("user.home"), PORTS_DIR)
            if (!portsDir.exists()) return

            portsDir.listFiles()?.forEach { file ->
                if (!file.name.endsWith(".port")) return@forEach
                val pid = file.nameWithoutExtension.toLongOrNull() ?: return@forEach

                val alive = try {
                    ProcessHandle.of(pid).isPresent
                } catch (_: Exception) {
                    false
                }

                if (!alive) {
                    file.delete()
                    log.info("[TWatcher] Cleaned up stale port file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            log.warn("[TWatcher] Failed to cleanup stale port files", e)
        }
    }

    private fun writePortFile(port: Int) {
        try {
            val portsDir = File(System.getProperty("user.home"), PORTS_DIR)
            portsDir.mkdirs()
            val pid = ProcessHandle.current().pid()
            val file = File(portsDir, "$pid.port")

            // Atomic write: 임시 파일에 쓰고 rename
            val tmpFile = File(portsDir, "$pid.port.tmp")
            tmpFile.writeText(port.toString())
            tmpFile.renameTo(file)

            log.info("[TWatcher] Port file written: ${file.absolutePath} = $port")
        } catch (e: Exception) {
            log.warn("[TWatcher] Failed to write port file", e)
        }
    }

    private fun deletePortFile() {
        try {
            val portsDir = File(System.getProperty("user.home"), PORTS_DIR)
            val pid = ProcessHandle.current().pid()
            val file = File(portsDir, "$pid.port")
            if (file.exists()) {
                file.delete()
                log.info("[TWatcher] Port file deleted: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            log.warn("[TWatcher] Failed to delete port file", e)
        }
    }

    private fun parseToHookEvent(rawJson: String, tool: String): HookEvent? {
        return try {
            val payload = json.decodeFromString<HookPayload>(rawJson)

            if (payload.hookEventName == "Notification" &&
                payload.notificationType in FILTERED_NOTIFICATION_TYPES
            ) {
                log.info("[TWatcher] Filtered out notification_type: ${payload.notificationType}")
                return null
            }

            val resolvedTool = tool.ifBlank {
                if (payload.type == "agent-turn-complete") "codex" else "unknown"
            }

            val eventType = when (payload.hookEventName) {
                "Notification" -> HookEventType.PERMISSION
                "Stop", "AfterAgent", "SessionEnd" -> HookEventType.COMPLETE
                else -> HookEventType.COMPLETE
            }

            val message = payload.message
                ?: payload.title
                ?: payload.lastAssistantMessage
                ?: payload.lastAssistantMessageAlt
                ?: payload.promptResponse

            HookEvent(
                tool = resolvedTool,
                eventType = eventType,
                message = message,
                sessionId = payload.sessionId ?: payload.threadId,
                cwd = payload.cwd,
                tabName = TerminalTabTracker.getTabNameByCwd(payload.cwd),
            )
        } catch (e: Exception) {
            log.warn("[TWatcher] Failed to parse hook event JSON", e)
            null
        }
    }

    private fun registerBadgeResetOnFocus() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) {
                    ApplicationManager.getApplication()
                        .getService(MacNotifier::class.java)
                        .resetBadge()
                }
            },
        )
    }

    override fun dispose() {
        server?.stop(0)
        deletePortFile()
        log.info("[TWatcher] Hook server stopped")
    }

    companion object {
        private const val PORTS_DIR = ".terminal-watcher/ports"
        private val FILTERED_NOTIFICATION_TYPES = setOf("idle_prompt", "auth_success")
    }
}

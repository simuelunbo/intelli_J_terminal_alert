package com.terminalwatcher.hook

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.IdeFrame
import com.sun.net.httpserver.HttpServer
import com.terminalwatcher.dispatch.CodexApprovalRouter
import com.terminalwatcher.notify.NotifierProvider
import com.terminalwatcher.notify.PendingFocusService
import com.terminalwatcher.settings.SettingsState
import com.terminalwatcher.terminal.ShellPidResolver
import com.terminalwatcher.terminal.TabRegistry
import com.terminalwatcher.terminal.TerminalFocuser
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
                        val shellPid = query.substringAfter("shell_pid=", "").substringBefore("&")
                            .toLongOrNull()
                        val tabIdHeader = exchange.requestHeaders.getFirst("X-TWatcher-Tab-Id")
                            ?.takeIf { it.isNotBlank() }
                        val projectIdHeader = exchange.requestHeaders.getFirst("X-TWatcher-Project-Id")
                            ?.takeIf { it.isNotBlank() }
                        log.info(
                            "[TWatcher] Hook received: tool=$tool, shell_pid=$shellPid, " +
                                "tab_id=$tabIdHeader, project_id=$projectIdHeader, body=${body.take(300)}",
                        )

                        // Silent-drop: a request carrying a projectId that doesn't match
                        // any open project here was meant for a different IDE instance.
                        if (projectIdHeader != null) {
                            val tabRegistry = ApplicationManager.getApplication()
                                .getService(TabRegistry::class.java)
                            if (tabRegistry?.findProjectByProjectId(projectIdHeader) == null) {
                                log.info("[TWatcher] Silent-drop: projectId=$projectIdHeader has no matching open project here")
                                exchange.sendResponseHeaders(204, -1)
                                return@createContext
                            }
                        }

                        val event = parseToHookEvent(body, tool, shellPid, tabIdHeader, projectIdHeader)
                        if (event != null) {
                            CodexApprovalRouter.route(event)
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

    private fun parseToHookEvent(
        rawJson: String,
        tool: String,
        shellPid: Long?,
        tabIdHeader: String? = null,
        projectIdHeader: String? = null,
    ): HookEvent? {
        return try {
            val payload = json.decodeFromString<HookPayload>(rawJson)

            if (payload.hookEventName == "Notification" &&
                payload.notificationType in FILTERED_NOTIFICATION_TYPES
            ) {
                log.info("[TWatcher] Filtered out notification_type: ${payload.notificationType}")
                return null
            }

            if (isCodexAutoApprovedPermissionRequest(tool, payload)) {
                log.info("[TWatcher] Filtered out auto-approved Codex PermissionRequest: ${payload.toolName}")
                return null
            }

            if (isCodexTitleGenerationTurn(payload)) {
                log.info("[TWatcher] Filtered out Codex thread-title generation turn")
                return null
            }

            val resolvedTool = tool.ifBlank {
                if (payload.type == "agent-turn-complete") "codex" else "unknown"
            }

            val isQuestion = isCodexQuestion(tool, payload)
            val eventType = if (isQuestion) HookEventType.QUESTION else when (payload.hookEventName) {
                "Notification", "PermissionRequest" -> HookEventType.PERMISSION
                "Stop", "AfterAgent", "SessionEnd" -> HookEventType.COMPLETE
                else -> HookEventType.COMPLETE
            }

            val message = if (isQuestion) {
                codexQuestionMessage(payload.toolInput) ?: "Question requested"
            } else {
                payload.message
                    ?: payload.title
                    ?: payload.lastAssistantMessage
                    ?: payload.lastAssistantMessageAlt
                    ?: payload.promptResponse
                    ?: payload.toolName?.let { "Approval requested: $it" }
            }

            // Prefer the exact tab match: the injected tabId resolves straight to the
            // Content that hosts the tab. This covers every terminal the plugin saw
            // start, including tabs restored on IDE restart.
            val tabRegistry = ApplicationManager.getApplication()
                .getService(TabRegistry::class.java)
            val boundContent = tabIdHeader?.let { tabRegistry?.lookup(it) }?.contentRef?.get()

            var resolvedTabId = tabIdHeader
            var tabName = boundContent?.displayName

            if (boundContent == null) {
                // No injected id, or it never got bound. Fall back to identifying the
                // tab from the OS process tree first, then the cwd/selection guesses.
                // `shell_pid` is the hook script's own PID, so its ancestors are what
                // actually contain the tab's shell.
                val chain = ShellPidResolver.resolve(shellPid)
                val resolved = TerminalTabTracker.resolve(chain.candidates, payload.cwd)
                if (resolved != null) {
                    tabName = resolved.content.displayName
                    // Give the Content an id so focus targeting never has to match on
                    // a tab title, which is neither unique nor stable.
                    resolvedTabId = if (tabIdHeader != null && tabRegistry?.lookup(tabIdHeader) != null) {
                        tabRegistry.bindByTabId(tabIdHeader, resolved.content)
                        tabIdHeader
                    } else {
                        tabRegistry?.registerResolved(
                            resolved.content,
                            resolved.project.locationHash,
                            payload.cwd.orEmpty(),
                        ) ?: tabIdHeader
                    }
                }
            }

            HookEvent(
                tool = resolvedTool,
                eventType = eventType,
                message = message,
                sessionId = payload.sessionId ?: payload.threadId,
                cwd = payload.cwd,
                tabName = tabName,
                tabId = resolvedTabId,
                projectId = projectIdHeader,
                transcriptPath = payload.transcriptPath,
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
                    NotifierProvider.get().resetBadge()
                    focusPendingTerminalTab()
                }
            },
        )
    }

    /**
     * macOS/Linux path for "the user clicked the system notification".
     *
     * Those platforms give no click callback: macOS delivers NSUserNotification
     * without a delegate, so the click never reaches Java. Regaining focus shortly
     * after a banner appeared is the only available signal, and the platform itself
     * treats activation as "notifications acknowledged" by clearing them there.
     *
     * The inference is deliberately narrow: the target is only recorded when a
     * banner actually appeared, expires quickly, and is used at most once. Windows
     * is excluded outright because it has the real click event.
     */
    private fun focusPendingTerminalTab() {
        if (SystemInfo.isWindows) return
        val pendingFocus = PendingFocusService.getInstance()
        if (!SettingsState.getInstance().state.focusTerminalOnClick) {
            pendingFocus.clear()
            return
        }
        val target = pendingFocus.consume(ACTIVATION_FOCUS_WINDOW_MS) ?: return
        TerminalFocuser.focus(target.projectId, target.tabId, target.tabName)
    }

    override fun dispose() {
        server?.stop(0)
        deletePortFile()
        log.info("[TWatcher] Hook server stopped")
    }

    companion object {
        private const val PORTS_DIR = ".terminal-watcher/ports"
        private val FILTERED_NOTIFICATION_TYPES = setOf("idle_prompt", "auth_success")

        /**
         * How soon after a system notification a return to the IDE still counts as
         * "came back because of that notification". Short on purpose: this is an
         * inference, and the only false positive it can produce is an unwanted tab
         * switch when the user alt-tabs back for an unrelated reason.
         */
        private const val ACTIVATION_FOCUS_WINDOW_MS = 30_000L
    }
}

package com.terminalwatcher.hook

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

object HookConfigHelper {

    private val log = Logger.getInstance(HookConfigHelper::class.java)
    private const val PORTS_DIR = ".terminal-watcher/ports"

    /**
     * v3 routing — INTELLIJ_TERMINAL_WATCHER_* env vars (injected by
     * TerminalWatcherEnvCustomizer) take priority over PPID-walking. Per-tab
     * routing carried in headers (X-TWatcher-Tab-Id / X-TWatcher-Project-Id).
     * PPID-walk retained as fallback for terminals opened before plugin init,
     * or shells that drop env (e.g. login shells with strict profiles).
     */
    private fun curlCmd(tool: String) =
        "bash -c '" +
            "[ \"\$TERMINAL_EMULATOR\" != \"JetBrains-JediTerm\" ] && exit 0; " +
            "INPUT=\$(cat); " +
            "TAB=\"\${INTELLIJ_TERMINAL_WATCHER_TAB_ID:-}\"; " +
            "PROJ=\"\${INTELLIJ_TERMINAL_WATCHER_PROJECT_ID:-}\"; " +
            "PORT=\"\${INTELLIJ_TERMINAL_WATCHER_PORT:-}\"; " +
            "SHELL_PID=\$\$; " +
            "if [ -z \"\$PORT\" ]; then " +
            "P=\$\$; PREV=\$P; " +
            "while [ \"\$P\" != \"1\" ]; do " +
            "PREV=\$P; " +
            "P=\$(ps -o ppid= -p \"\$P\" 2>/dev/null | xargs); " +
            "[ -z \"\$P\" ] && break; " +
            "if [ -f ~/$PORTS_DIR/\"\$P\".port ]; then " +
            "PORT=\$(cat ~/$PORTS_DIR/\"\$P\".port); SHELL_PID=\$PREV; break; " +
            "fi; " +
            "done; " +
            "fi; " +
            "[ -z \"\$PORT\" ] && exit 0; " +
            "echo \"\$INPUT\" | curl -s -X POST \"http://127.0.0.1:\$PORT/event?tool=$tool&shell_pid=\$SHELL_PID\" " +
            "-H \"Content-Type: application/json\" " +
            "-H \"X-TWatcher-Tab-Id: \$TAB\" " +
            "-H \"X-TWatcher-Project-Id: \$PROJ\" " +
            "-d @-'"

    private val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun setupAllHooks(projectBasePath: String?) {
        // 글로벌 설정 (모든 프로젝트에서 작동)
        setupClaudeHookGlobal()
        setupGeminiHookGlobal()
        setupCodexHook()

        // 프로젝트별 설정 (프로젝트 스코프 우선)
        if (projectBasePath != null) {
            setupClaudeHookProject(projectBasePath)
            setupGeminiHookProject(projectBasePath)
        }
    }

    // ── Claude Hook Entries ──

    private fun buildClaudeNotificationEntry() = buildJsonArray {
        add(buildJsonObject {
            put("matcher", "permission_prompt")
            put("hooks", buildJsonArray {
                add(buildJsonObject {
                    put("type", "command")
                    put("command", curlCmd("claude"))
                    put("timeout", 5)
                })
            })
        })
    }

    private fun buildClaudeStopEntry() = buildJsonArray {
        add(buildJsonObject {
            put("matcher", "")
            put("hooks", buildJsonArray {
                add(buildJsonObject {
                    put("type", "command")
                    put("command", curlCmd("claude"))
                    put("timeout", 5)
                })
            })
        })
    }

    // ── Gemini Hook Entry ──

    private fun buildGeminiHookEntry() = buildJsonArray {
        add(buildJsonObject {
            put("matcher", "*")
            put("hooks", buildJsonArray {
                add(buildJsonObject {
                    put("name", "intellij-terminal-watcher")
                    put("type", "command")
                    put("command", curlCmd("gemini"))
                    put("timeout", 5000)
                })
            })
        })
    }

    // ── Claude: 글로벌 (~/.claude/settings.json) ──

    private fun setupClaudeHookGlobal() {
        val configFile = File(System.getProperty("user.home"), ".claude/settings.json")
        setupJsonHooks(configFile, "Claude (global)") { hooksMap ->
            hooksMap["Notification"] = buildClaudeNotificationEntry()
            hooksMap["Stop"] = buildClaudeStopEntry()
        }
    }

    // ── Claude: 프로젝트별 (.claude/settings.json) ──

    private fun setupClaudeHookProject(projectBasePath: String) {
        val configFile = File(projectBasePath, ".claude/settings.json")
        if (!configFile.parentFile.exists()) configFile.parentFile.mkdirs()
        setupJsonHooks(configFile, "Claude (project)") { hooksMap ->
            hooksMap["Notification"] = buildClaudeNotificationEntry()
            hooksMap["Stop"] = buildClaudeStopEntry()
        }
    }

    // ── Gemini: 글로벌 (~/.gemini/settings.json) ──

    private fun setupGeminiHookGlobal() {
        val configFile = File(System.getProperty("user.home"), ".gemini/settings.json")
        if (!configFile.parentFile.exists()) return
        setupJsonHooks(configFile, "Gemini (global)") { hooksMap ->
            hooksMap["Notification"] = buildGeminiHookEntry()
            hooksMap["AfterAgent"] = buildGeminiHookEntry()
        }
    }

    // ── Gemini: 프로젝트별 (.gemini/settings.json) ──

    private fun setupGeminiHookProject(projectBasePath: String) {
        val configFile = File(projectBasePath, ".gemini/settings.json")
        if (!configFile.parentFile.exists()) configFile.parentFile.mkdirs()
        setupJsonHooks(configFile, "Gemini (project)") { hooksMap ->
            hooksMap["Notification"] = buildGeminiHookEntry()
            hooksMap["AfterAgent"] = buildGeminiHookEntry()
        }
    }

    // ── 공통: JSON 설정 파일에 hooks 블록 추가 ──

    private fun setupJsonHooks(
        configFile: File,
        label: String,
        addHooks: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit,
    ) {
        try {
            val root = if (configFile.exists() && configFile.readText().isNotBlank()) {
                Json.parseToJsonElement(configFile.readText()).jsonObject.toMutableMap()
            } else {
                mutableMapOf()
            }

            val hooksStr = root["hooks"]?.toString().orEmpty()
            val hasStaleConfig = hooksStr.contains("..terminal-watcher") || hooksStr.contains("19876")
            val hasCorrectHooks = hooksStr.contains("terminal-watcher") &&
                hooksStr.contains("JetBrains-JediTerm") &&
                hooksStr.contains("shell_pid") &&
                hooksStr.contains("INTELLIJ_TERMINAL_WATCHER_PORT") &&
                !hasStaleConfig

            if (hasCorrectHooks) {
                log.info("[TWatcher] $label hooks already configured")
                return
            }

            // 잘못된 설정이 있으면 기존 hooks를 덮어씀
            val hooksMap = if (hasStaleConfig) {
                log.info("[TWatcher] $label fixing stale hook config")
                mutableMapOf()
            } else {
                (root["hooks"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            }
            addHooks(hooksMap)
            root["hooks"] = JsonObject(hooksMap)

            configFile.writeText(prettyJson.encodeToString(JsonObject.serializer(), JsonObject(root)))
            log.info("[TWatcher] $label hooks configured in ${configFile.absolutePath}")
        } catch (e: Exception) {
            log.warn("[TWatcher] Failed to setup $label hooks", e)
        }
    }

    // ── Codex: 글로벌만 (~/.codex/config.toml) ──

    private fun setupCodexHook() {
        val codexDir = File(System.getProperty("user.home"), ".codex")
        if (!codexDir.exists()) {
            log.info("[TWatcher] ~/.codex/ directory not found, skipping Codex hook setup")
            return
        }

        try {
            val scriptFile = File(codexDir, "notify-twatcher.sh")
            val scriptContent = if (scriptFile.exists()) scriptFile.readText() else ""
            val hasCorrectScript = scriptContent.contains("terminal-watcher") &&
                scriptContent.contains("JetBrains-JediTerm") &&
                scriptContent.contains("shell_pid") &&
                scriptContent.contains("INTELLIJ_TERMINAL_WATCHER_PORT")

            if (!hasCorrectScript) {
                scriptFile.writeText(
                    """
                    |#!/bin/bash
                    |# terminal-watcher notify v3 — env-var routing with PPID fallback
                    |[ "${'$'}TERMINAL_EMULATOR" != "JetBrains-JediTerm" ] && exit 0
                    |TAB="${'$'}{INTELLIJ_TERMINAL_WATCHER_TAB_ID:-}"
                    |PROJ="${'$'}{INTELLIJ_TERMINAL_WATCHER_PROJECT_ID:-}"
                    |PORT="${'$'}{INTELLIJ_TERMINAL_WATCHER_PORT:-}"
                    |SHELL_PID=${'$'}${'$'}
                    |if [ -z "${'$'}PORT" ]; then
                    |  P=${'$'}${'$'}
                    |  PREV=${'$'}P
                    |  while [ "${'$'}P" != "1" ]; do
                    |    PREV=${'$'}P
                    |    P=${'$'}(ps -o ppid= -p "${'$'}P" 2>/dev/null | xargs)
                    |    [ -z "${'$'}P" ] && break
                    |    if [ -f ~/$PORTS_DIR/"${'$'}P".port ]; then
                    |      PORT=${'$'}(cat ~/$PORTS_DIR/"${'$'}P".port)
                    |      SHELL_PID=${'$'}PREV
                    |      break
                    |    fi
                    |  done
                    |fi
                    |[ -z "${'$'}PORT" ] && exit 0
                    |curl -s -X POST "http://127.0.0.1:${'$'}PORT/event?tool=codex&shell_pid=${'$'}SHELL_PID" \
                    |  -H 'Content-Type: application/json' \
                    |  -H "X-TWatcher-Tab-Id: ${'$'}TAB" \
                    |  -H "X-TWatcher-Project-Id: ${'$'}PROJ" \
                    |  -d "${'$'}1"
                    """.trimMargin() + "\n",
                )
                scriptFile.setExecutable(true)
                log.info("[TWatcher] Codex notify script created: ${scriptFile.absolutePath}")
            }

            val configFile = File(codexDir, "config.toml")
            val content = if (configFile.exists()) configFile.readText() else ""
            var modified = false

            var cleanedContent = content
            if (!content.contains("# Terminal Watcher notify v2")) {
                cleanedContent = content
                    .replace(Regex("""(?m)^# Terminal Watcher.*notify.*$\n?"""), "")
                    .replace(Regex("""(?m)^# Added by Terminal AI Watcher plugin$\n?"""), "")
                    .replace(Regex("""(?m)^notify\s*=\s*\[[^\]]*\]\s*$\n?"""), "")
                    .replace(Regex("""(?m)^notify\s*=\s*"[^"]*notify-twatcher[^"]*"\s*$\n?"""), "")
                    .trimEnd()

                val lines = cleanedContent.lines().toMutableList()
                lines.add(0, "notify = [\"${scriptFile.absolutePath}\"]")
                lines.add(0, "# Terminal Watcher notify v2")
                cleanedContent = lines.joinToString("\n")
                modified = true
            }

            if (!cleanedContent.contains("[tui]")) {
                cleanedContent = cleanedContent.trimEnd() + "\n\n[tui]\n" +
                    "notifications = [\"approval-requested\"]\n" +
                    "notification_method = \"bel\"\n"
                modified = true
            }

            if (modified) {
                configFile.writeText(cleanedContent)
                log.info("[TWatcher] Codex hooks configured in ${configFile.absolutePath}")
            } else {
                log.info("[TWatcher] Codex hooks already configured")
            }
        } catch (e: Exception) {
            log.warn("[TWatcher] Failed to setup Codex hooks", e)
        }
    }
}

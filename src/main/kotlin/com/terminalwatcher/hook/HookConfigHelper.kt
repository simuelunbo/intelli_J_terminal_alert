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
     * 포트 파일에서 동적으로 포트를 읽어 curl 전송.
     * PID가 살아있는 포트 파일만 사용하여 stale 파일 문제 방지.
     * 여러 IDE가 동시 실행 중이면 모든 IDE에 이벤트 전송 (broadcast).
     */
    private fun curlCmd(tool: String) =
        "bash -c '" +
            "[ \"\$TERMINAL_EMULATOR\" != \"JetBrains-JediTerm\" ] && exit 0; " +
            "INPUT=\$(cat); " +
            "P=\$\$; " +
            "while [ \"\$P\" != \"1\" ]; do " +
            "P=\$(ps -o ppid= -p \"\$P\" 2>/dev/null | xargs); " +
            "[ -z \"\$P\" ] && exit 0; " +
            "[ -f ~/$PORTS_DIR/\"\$P\".port ] && { " +
            "PORT=\$(cat ~/$PORTS_DIR/\"\$P\".port); " +
            "echo \"\$INPUT\" | curl -s -X POST \"http://127.0.0.1:\$PORT/event?tool=$tool\" " +
            "-H \"Content-Type: application/json\" -d @-; exit 0; }; " +
            "done'"

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
                hooksStr.contains("JetBrains-JediTerm") && !hasStaleConfig

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
                scriptContent.contains("JetBrains-JediTerm")

            if (!hasCorrectScript) {
                scriptFile.writeText(
                    """
                    |#!/bin/bash
                    |[ "${'$'}TERMINAL_EMULATOR" != "JetBrains-JediTerm" ] && exit 0
                    |P=${'$'}${'$'}
                    |while [ "${'$'}P" != "1" ]; do
                    |  P=${'$'}(ps -o ppid= -p "${'$'}P" 2>/dev/null | xargs)
                    |  [ -z "${'$'}P" ] && exit 0
                    |  if [ -f ~/$PORTS_DIR/"${'$'}P".port ]; then
                    |    PORT=${'$'}(cat ~/$PORTS_DIR/"${'$'}P".port)
                    |    curl -s -X POST "http://127.0.0.1:${'$'}PORT/event?tool=codex" \
                    |      -H 'Content-Type: application/json' -d "${'$'}1"
                    |    exit 0
                    |  fi
                    |done
                    """.trimMargin() + "\n",
                )
                scriptFile.setExecutable(true)
                log.info("[TWatcher] Codex notify script created: ${scriptFile.absolutePath}")
            }

            val configFile = File(codexDir, "config.toml")
            val content = if (configFile.exists()) configFile.readText() else ""
            var modified = false

            var cleanedContent = content
            if (!content.contains("notify-twatcher.sh")) {
                cleanedContent = content
                    .replace(Regex("""# Added by Terminal AI Watcher plugin\n"""), "")
                    .replace(Regex("""notify\s*=\s*\[.*]\n?"""), "")
                    .replace(Regex("""notify\s*=\s*"[^"]*notify-twatcher[^"]*"\n?"""), "")
                    .trimEnd()

                val lines = cleanedContent.lines().toMutableList()
                lines.add(1, "notify = [\"${scriptFile.absolutePath}\"]")
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

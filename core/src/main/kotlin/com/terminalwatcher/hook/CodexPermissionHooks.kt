package com.terminalwatcher.hook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val hookJson = Json { prettyPrint = true }

/** Preserve foreign matchers, handlers and metadata. Keep an existing owned handler's command
 * so reinstalling the IDE plugin does not invalidate Codex's review of that hook; only the
 * one-time `async` upgrade changes it, because a sync hook delays every approval request
 * by the script's startup time even though this hook never returns a decision.
 *
 * Codex trusts a hook by hashing its definition (matcher, command, timeout, async,
 * statusMessage), not the script it runs. Changing any of those fields stops the hook until
 * the user reviews it again in /hooks, so put behavior changes in the notify scripts. */
internal fun ensureCodexPermissionHook(content: String, command: String): String =
    ensureCodexOwnedHook(content, "PermissionRequest", matcher = "", acceptedMatchers = setOf("", ".*"), command)

/** Questions never end the turn or request approval, so only a hook on the question tool sees them. */
internal fun ensureCodexQuestionHook(content: String, command: String): String =
    ensureCodexOwnedHook(content, "PostToolUse", CODEX_QUESTION_MATCHER, setOf(CODEX_QUESTION_MATCHER), command)

private const val CODEX_QUESTION_MATCHER = "^$CODEX_QUESTION_TOOL$"

private fun ensureCodexOwnedHook(
    content: String,
    event: String,
    matcher: String,
    acceptedMatchers: Set<String>,
    command: String,
): String {
    val root = if (content.isBlank()) JsonObject(emptyMap()) else hookJson.parseToJsonElement(content).jsonObject
    val hooks = root["hooks"]?.jsonObject ?: JsonObject(emptyMap())
    val groups = hooks[event]?.jsonArray ?: JsonArray(emptyList())
    var found = false
    val updatedGroups = groups.mapNotNull { group ->
        val obj = group.jsonObject
        val handlers = obj.getValue("hooks").jsonArray
        val updated = handlers.mapNotNull { handler ->
            if (!isTerminalWatcherCodexCommand(handler.jsonObject["command"]?.jsonPrimitive?.content.orEmpty())) {
                handler
            } else {
                require(obj["matcher"]?.jsonPrimitive?.content.orEmpty() in acceptedMatchers) {
                    "Customized Terminal Watcher matcher needs review; existing hooks were preserved"
                }
                val keep = !found
                found = true
                if (keep) JsonObject(handler.jsonObject + ("async" to JsonPrimitive(true))) else null
            }
        }
        if (updated.isEmpty()) null else JsonObject(obj + ("hooks" to JsonArray(updated)))
    }.toMutableList()
    if (!found) {
        updatedGroups.add(buildJsonObject {
            put("matcher", matcher)
            put("hooks", JsonArray(listOf(buildJsonObject {
                put("type", "command")
                put("command", command)
                put("timeout", 5)
                put("async", true)
                put("statusMessage", "Terminal Watcher notification")
            })))
        })
    }
    val updated = JsonObject(root + ("hooks" to JsonObject(hooks + (event to JsonArray(updatedGroups)))))
    return if (updated == root) content else hookJson.encodeToString(JsonObject.serializer(), updated) + "\n"
}

internal fun isTerminalWatcherCodexCommand(command: String): Boolean {
    val path = command.replace('\\', '/').replace(Regex("/+"), "/")
    return path.contains("/.terminal-watcher/notify.ps1") && Regex("\\bcodex\\s*$").containsMatchIn(path) ||
        Regex("/notify-twatcher\\.sh['\"]?\\s*$").containsMatchIn(path)
}

/** Remove only the standalone legacy block emitted by this plugin. Never rewrite arbitrary
 * TOML or trust state. Reordered/customized blocks are left for explicit review. */
internal fun removeLegacyCodexPermissionHook(content: String, command: String): String {
    val escapedCommand = Regex.escape(command)
    val block = Regex(
        "(?m)^\\[\\[hooks\\.PermissionRequest]]\\r?\\n[ \\t]*\\r?\\n" +
            "\\[\\[hooks\\.PermissionRequest\\.hooks]]\\r?\\n" +
            "type = \"command\"\\r?\\n" +
            "command = '$escapedCommand'\\r?\\n" +
            "timeout = 5\\r?\\n" +
            "statusMessage = \"Terminal Watcher notification\"[ \\t]*(?:\\r?\\n|\\z)" +
            "(?=(?:[ \\t]*\\r?\\n)*(?:\\[(?!\\[?hooks\\.PermissionRequest\\.)|\\z))",
    )
    val codeLineStarts = tomlCodeLineStarts(content)
    return block.replace(content) { if (it.range.first in codeLineStarts) "" else it.value }
}

/** A documentation string can contain an entire example hook. Only edit real table headers. */
private fun tomlCodeLineStarts(content: String): Set<Int> {
    val starts = mutableSetOf(0)
    var quote: Char? = null
    var multiline = false
    var index = 0
    while (index < content.length) {
        val char = content[index]
        when {
            quote == null && char == '#' -> {
                index = content.indexOf('\n', index).let { if (it < 0) content.length else it }
                continue
            }
            quote == '"' && char == '\\' -> { index += 2; continue }
            quote == null && (char == '\'' || char == '"') -> {
                quote = char
                multiline = content.startsWith(char.toString().repeat(3), index)
                if (multiline) index += 2
            }
            quote == char -> {
                if (!multiline) quote = null
                else if (content.startsWith(char.toString().repeat(3), index)) {
                    quote = null
                    multiline = false
                    index += 2
                }
            }
        }
        if (char == '\n' && quote == null) starts.add(index + 1)
        index++
    }
    return starts
}

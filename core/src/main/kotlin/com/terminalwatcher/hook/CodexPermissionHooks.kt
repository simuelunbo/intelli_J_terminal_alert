package com.terminalwatcher.hook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val hookJson = Json { prettyPrint = true }

/** Preserve foreign matchers, handlers and metadata. Keep an existing owned handler unchanged
 * so reinstalling the IDE plugin does not invalidate Codex's review of that hook. */
internal fun ensureCodexPermissionHook(content: String, command: String): String {
    val root = if (content.isBlank()) JsonObject(emptyMap()) else hookJson.parseToJsonElement(content).jsonObject
    val hooks = root["hooks"]?.jsonObject ?: JsonObject(emptyMap())
    val groups = hooks["PermissionRequest"]?.jsonArray ?: JsonArray(emptyList())
    var found = false
    val updatedGroups = groups.mapNotNull { group ->
        val obj = group.jsonObject
        val handlers = obj.getValue("hooks").jsonArray
        val updated = handlers.filter { handler ->
            if (!isTerminalWatcherCodexCommand(handler.jsonObject["command"]?.jsonPrimitive?.content.orEmpty())) {
                true
            } else {
                require(obj["matcher"]?.jsonPrimitive?.content.orEmpty() in listOf("", ".*")) {
                    "Customized Terminal Watcher matcher needs review; existing hooks were preserved"
                }
                val keep = !found
                found = true
                keep
            }
        }
        if (updated.isEmpty()) null else JsonObject(obj + ("hooks" to JsonArray(updated)))
    }.toMutableList()
    if (!found) {
        updatedGroups.add(buildJsonObject {
            put("matcher", "")
            put("hooks", JsonArray(listOf(buildJsonObject {
                put("type", "command")
                put("command", command)
                put("timeout", 5)
                put("statusMessage", "Terminal Watcher notification")
            })))
        })
    }
    val updated = JsonObject(root + ("hooks" to JsonObject(hooks + ("PermissionRequest" to JsonArray(updatedGroups)))))
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

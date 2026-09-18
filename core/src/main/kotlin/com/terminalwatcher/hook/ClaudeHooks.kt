package com.terminalwatcher.hook

import kotlinx.serialization.json.*

private val claudeHookJson = Json { prettyPrint = true }

/** Add missing notifications without taking ownership of other handlers or settings. */
internal fun ensureClaudeHooks(content: String, command: String): String {
    val root = if (content.isBlank()) JsonObject(emptyMap()) else claudeHookJson.parseToJsonElement(content).jsonObject
    val hooks = root["hooks"]?.jsonObject ?: JsonObject(emptyMap())
    val updatedHooks = hooks.toMutableMap()
    for ((event, matcher) in mapOf("Notification" to "permission_prompt", "Stop" to "")) {
        val groups = hooks[event]?.jsonArray ?: JsonArray(emptyList())
        val present = groups.any { group ->
            val obj = group.jsonObject
            val handlers = obj.getValue("hooks").jsonArray
            val ownsCommand = handlers.any { handler ->
                val entry = handler.jsonObject
                (entry["type"] as? JsonPrimitive)?.content == "command" &&
                    (entry["command"] as? JsonPrimitive)?.content == command &&
                    entry["args"] == null
            }
            if (ownsCommand) {
                val existingMatcher = (obj["matcher"] as? JsonPrimitive)?.content.orEmpty()
                require(existingMatcher == matcher || event == "Stop" && existingMatcher == "*") {
                    "Customized Claude $event matcher needs review; existing settings were preserved"
                }
            }
            ownsCommand
        }
        if (!present) {
            updatedHooks[event] = JsonArray(groups + buildJsonObject {
                put("matcher", matcher)
                put("hooks", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "command")
                        put("command", command)
                        put("timeout", 5)
                    })
                })
            })
        }
    }
    val updated = JsonObject(root + ("hooks" to JsonObject(updatedHooks)))
    return if (updated == root) content else claudeHookJson.encodeToString(JsonObject.serializer(), updated) + "\n"
}

package com.terminalwatcher.hook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val CODEX_TITLE_PROMPT_PREFIX = "Generate a concise, single-line task title"

/**
 * Codex's TUI names each thread with a hidden model turn, and its legacy `notify` reports that
 * turn as an ordinary completion. Its fixed prompt and its `{"title": ...}` structured answer
 * identify it; the payload carries no thread source to tell it apart otherwise.
 */
internal fun isCodexTitleGenerationTurn(payload: HookPayload): Boolean {
    if (payload.type != "agent-turn-complete") return false
    if (payload.inputMessages?.firstOrNull()?.startsWith(CODEX_TITLE_PROMPT_PREFIX) != true) return false
    val answer = (payload.lastAssistantMessageAlt ?: payload.lastAssistantMessage)?.trim() ?: return false
    val parsed = runCatching { Json.parseToJsonElement(answer) }.getOrNull() as? JsonObject ?: return false
    return parsed.keys == setOf("title")
}

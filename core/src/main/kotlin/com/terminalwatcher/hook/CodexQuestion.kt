package com.terminalwatcher.hook

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The tool behind Codex's mid-task questions (opened with Shift+Left in the TUI). */
internal const val CODEX_QUESTION_TOOL = "request_user_input_async"

internal fun isCodexQuestion(tool: String, payload: HookPayload): Boolean =
    tool == "codex" &&
        payload.hookEventName == "PostToolUse" &&
        payload.toolName == CODEX_QUESTION_TOOL

/** First question title, with a count when Codex asked several at once. */
internal fun codexQuestionMessage(toolInput: JsonElement?): String? {
    val questions = (toolInput as? JsonObject)?.get("questions") as? JsonArray ?: return null
    val title = questions.firstNotNullOfOrNull { question ->
        ((question as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    } ?: return null
    return if (questions.size > 1) "$title (+${questions.size - 1} more)" else title
}

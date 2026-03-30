package com.terminalwatcher.hook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class HookEventType {
    PERMISSION,
    COMPLETE,
    ERROR,
}

enum class SoundType(val fileName: String) {
    ALERT("Glass"),
    GENTLE("Pop"),
}

data class HookEvent(
    val tool: String,
    val eventType: HookEventType,
    val message: String?,
    val sessionId: String?,
    val cwd: String?,
    val tabName: String? = null,
)

/**
 * CLI 도구 Hook 페이로드의 통합 역직렬화 모델.
 * Claude Code, Codex, Gemini CLI의 모든 필드를 nullable로 포함.
 */
@Serializable
data class HookPayload(
    @SerialName("hook_event_name") val hookEventName: String? = null,
    val type: String? = null,
    val message: String? = null,
    val title: String? = null,
    @SerialName("notification_type") val notificationType: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("thread-id") val threadId: String? = null,
    val cwd: String? = null,
    @SerialName("last_assistant_message") val lastAssistantMessage: String? = null,
    @SerialName("last-assistant-message") val lastAssistantMessageAlt: String? = null,
    @SerialName("prompt_response") val promptResponse: String? = null,
    @SerialName("permission_mode") val permissionMode: String? = null,
)

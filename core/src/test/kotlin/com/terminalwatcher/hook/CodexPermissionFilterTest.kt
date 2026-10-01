package com.terminalwatcher.hook

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexPermissionFilterTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun payload(mode: String) = json.decodeFromString<HookPayload>(
        """{"session_id":"s","turn_id":"t","hook_event_name":"PermissionRequest",""" +
            """"permission_mode":"$mode","tool_name":"mcp__device_queue__device_adb","tool_input":{}}""",
    )

    @Test
    fun `Codex 전체 접근 모드의 승인 요청은 사용자에게 묻지 않으므로 알리지 않는다`() {
        assertTrue(isCodexAutoApprovedPermissionRequest("codex", payload("bypassPermissions")))
    }

    @Test
    fun `Codex 기본 모드의 승인 요청은 알린다`() {
        assertFalse(isCodexAutoApprovedPermissionRequest("codex", payload("default")))
    }

    @Test
    fun `Claude 승인 요청은 권한 모드와 관계없이 알린다`() {
        assertFalse(isCodexAutoApprovedPermissionRequest("claude", payload("bypassPermissions")))
    }
}

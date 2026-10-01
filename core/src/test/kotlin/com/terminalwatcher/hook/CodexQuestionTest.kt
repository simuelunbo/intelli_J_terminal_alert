package com.terminalwatcher.hook

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexQuestionTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun payload(toolName: String, questions: String) = json.decodeFromString<HookPayload>(
        """{"session_id":"s","turn_id":"t","hook_event_name":"PostToolUse","permission_mode":"bypassPermissions",""" +
            """"tool_name":"$toolName","tool_use_id":"call_1","tool_input":{"questions":$questions},""" +
            """"tool_response":"{\"accepted\":true}"}""",
    )

    @Test
    fun `Codex 질문 도구 호출은 전체 접근 모드에서도 질문 제목으로 알린다`() {
        val question = payload(
            "request_user_input_async",
            """[{"title":" K22 앞에서 조작할 준비가 됐나요? ","options":["지금 준비됨","아직 준비 중"]}]""",
        )
        assertTrue(isCodexQuestion("codex", question))
        assertFalse(isCodexAutoApprovedPermissionRequest("codex", question))
        assertEquals("K22 앞에서 조작할 준비가 됐나요?", codexQuestionMessage(question.toolInput))
    }

    @Test
    fun `여러 질문은 첫 제목과 나머지 개수를 함께 보여준다`() {
        val question = payload("request_user_input_async", """[{"title":""},{"title":"환경은?"},{"title":"범위는?"}]""")
        assertEquals("환경은? (+2 more)", codexQuestionMessage(question.toolInput))
    }

    @Test
    fun `제목이 없으면 기본 문구를 쓰도록 비워 둔다`() {
        assertNull(codexQuestionMessage(payload("request_user_input_async", """[{"title":null}]""").toolInput))
    }

    @Test
    fun `질문 도구가 아닌 도구 실행 후 훅은 질문으로 보지 않는다`() {
        assertFalse(isCodexQuestion("codex", payload("exec_command", "[]")))
        assertFalse(isCodexQuestion("claude", payload("request_user_input_async", "[]")))
    }
}

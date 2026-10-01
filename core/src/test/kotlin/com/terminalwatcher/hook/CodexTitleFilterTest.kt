package com.terminalwatcher.hook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexTitleFilterTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val titlePrompt = "Generate a concise, single-line task title of at most 36 characters and under five words " +
        "where possible.\n\nUser prompt:\n작업 중 질문해줘"

    private fun turn(input: String, answer: String) = json.decodeFromString<HookPayload>(
        """{"type":"agent-turn-complete","thread-id":"t","turn-id":"u","cwd":"C:\\p","client":"codex-tui",""" +
            """"input-messages":[${JsonPrimitive(input)}],"last-assistant-message":${JsonPrimitive(answer)}}""",
    )

    @Test
    fun `Codex가 스레드 제목을 만드는 숨은 턴은 완료 알림으로 보지 않는다`() {
        assertTrue(isCodexTitleGenerationTurn(turn(titlePrompt, """{"title":"작업 중 질문해줘"}""")))
    }

    @Test
    fun `일반 작업 완료는 그대로 알린다`() {
        assertFalse(isCodexTitleGenerationTurn(turn("배리어프리 진행률 확인해줘", "배리어프리는 주요 수정이 반영됐습니다.")))
    }

    @Test
    fun `사용자 작업 결과가 제목 형식이어도 제목 생성 요청이 아니면 알린다`() {
        assertFalse(isCodexTitleGenerationTurn(turn("제목 JSON만 출력해줘", """{"title":"결과"}""")))
        assertFalse(isCodexTitleGenerationTurn(turn(titlePrompt, "제목 대신 작업을 수행했습니다.")))
    }
}

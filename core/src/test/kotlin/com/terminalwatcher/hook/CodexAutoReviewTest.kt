package com.terminalwatcher.hook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CodexAutoReviewTest {
    private fun turnContext(reviewer: String, policy: String) =
        """{"timestamp":"2026-10-01T00:00:00Z","ordinal":1,"type":"turn_context","payload":{"turn_id":"t",""" +
            """"approval_policy":$policy,"approvals_reviewer":"$reviewer","developer_instructions":"규칙"}}"""

    @Test
    fun `요청마다 묻는 정책에서 자동 검토자를 쓰는 턴만 자동 검토로 본다`() {
        assertTrue(codexTurnRoutesToAutoReview(turnContext("auto_review", "\"on-request\"")))
        assertTrue(codexTurnRoutesToAutoReview(turnContext("guardian_subagent", """{"granular":{"sandbox_approval":true}}""")))
        assertFalse(codexTurnRoutesToAutoReview(turnContext("user", "\"on-request\"")))
        assertFalse(codexTurnRoutesToAutoReview(turnContext("auto_review", "\"untrusted\"")))
        assertFalse(codexTurnRoutesToAutoReview(turnContext("auto_review", "\"never\"")))
    }

    @Test
    fun `세션 기록이 커도 청크 경계를 넘어 가장 최근 턴 설정을 읽는다`(@TempDir dir: File) {
        val latest = turnContext("auto_review", "\"on-request\"")
        val file = File(dir, "rollout.jsonl")
        file.writeText(
            turnContext("user", "\"on-request\"") + "\n" +
                """{"type":"response_item","payload":{"image":"${"A".repeat(5000)}"}}""" + "\n" +
                latest + "\n" +
                """{"type":"event_msg","payload":{"type":"token_count"}}""" + "\n",
        )
        assertEquals(latest, readLastCodexTurnContext(file, chunkSize = 64))
        assertTrue(isCodexAutoReviewSession(file.path))
    }

    @Test
    fun `턴 설정을 찾지 못하면 자동 검토로 보지 않아 알림을 유지한다`(@TempDir dir: File) {
        val file = File(dir, "rollout.jsonl").apply { writeText("""{"type":"session_meta"}""" + "\n") }
        assertNull(readLastCodexTurnContext(file))
        assertFalse(isCodexAutoReviewSession(file.path))
        assertFalse(isCodexAutoReviewSession(File(dir, "missing.jsonl").path))
        assertFalse(isCodexAutoReviewSession(null))
    }
}

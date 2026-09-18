package com.terminalwatcher.hook

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClaudeHooksTest {
    private val command = """powershell -NoProfile -ExecutionPolicy Bypass -File "C:\Users\test user\.terminal-watcher\notify.ps1" claude"""

    private fun hooks(content: String) = Json.parseToJsonElement(content).jsonObject.getValue("hooks").jsonObject

    @Test
    fun `처음 등록할 때 다른 알림과 완료 훅 및 설정을 보존한다`() {
        val original = """{"env":{"KEEP":"value"},"hooks":{"Notification":[{"matcher":"permission_prompt","hooks":[{"type":"command","command":"foreign-notify","timeout":99}]}],"Stop":[{"hooks":[{"type":"command","command":"foreign-stop"}]}],"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"security-check"}]}]}}"""
        val result = ensureClaudeHooks(original, command)
        val before = hooks(original)
        val after = hooks(result)
        for (event in listOf("Notification", "Stop")) {
            assertEquals(before[event]!!.jsonArray.single(), after[event]!!.jsonArray.first())
            assertEquals(2, after[event]!!.jsonArray.size)
        }
        assertEquals(before["PreToolUse"], after["PreToolUse"])
        assertEquals(Json.parseToJsonElement(original).jsonObject["env"], Json.parseToJsonElement(result).jsonObject["env"])
        assertEquals(result, ensureClaudeHooks(result, command))
    }

    @Test
    fun `다른 명령에 구형 표식이 있어도 무관한 훅을 지우지 않는다`() {
        val original = """{"hooks":{"PreToolUse":[{"hooks":[{"type":"command","command":"security-check"}]}],"Stop":[{"hooks":[{"type":"command","command":"send-to-port-19876 ..terminal-watcher"}]}]}}"""
        val result = hooks(ensureClaudeHooks(original, command))
        assertEquals(hooks(original)["PreToolUse"], result["PreToolUse"])
        assertEquals(hooks(original)["Stop"]!!.jsonArray.single(), result["Stop"]!!.jsonArray.first())
    }

    @Test
    fun `완료 훅만 있으면 승인 훅만 추가하고 반대 경우에도 빠진 이벤트를 복구한다`() {
        val complete = hooks(ensureClaudeHooks("", command))
        for (remaining in listOf("Stop", "Notification")) {
            val partial = JsonObject(mapOf("hooks" to JsonObject(mapOf(remaining to complete.getValue(remaining))))).toString()
            val restored = hooks(ensureClaudeHooks(partial, command))
            assertEquals(complete, restored)
            assertEquals(complete[remaining], restored[remaining])
        }
    }

    @Test
    fun `정상 설정은 서식과 사용자 지정 제한시간까지 그대로 유지한다`() {
        val customized = ensureClaudeHooks("", command).replace("\"timeout\": 5", "\"timeout\": 17") + "\n\n"
        assertEquals(customized, ensureClaudeHooks(customized, command))
    }

    @Test
    fun `제이슨이나 훅 구조가 잘못되면 덮어쓸 설정을 생성하지 않는다`() {
        for (invalid in listOf("{invalid", """{"hooks":[]} """, """{"hooks":{"Stop":{}}}""", """{"hooks":{"Stop":[{"hooks":{}}]}}""")) {
            assertThrows(IllegalArgumentException::class.java) { ensureClaudeHooks(invalid, command) }
        }
    }

    @Test
    fun `자체 승인 명령의 매처가 사용자 정의이면 중복 등록하지 않고 검토를 요구한다`() {
        val customized = ensureClaudeHooks("", command).replace("permission_prompt", "idle_prompt")
        assertThrows(IllegalArgumentException::class.java) { ensureClaudeHooks(customized, command) }
    }
}

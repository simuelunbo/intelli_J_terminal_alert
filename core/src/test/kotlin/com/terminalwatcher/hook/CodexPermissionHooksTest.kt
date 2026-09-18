package com.terminalwatcher.hook

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodexPermissionHooksTest {
    private val command = """powershell -NoProfile -ExecutionPolicy Bypass -File "C:\Users\beat Lab\.terminal-watcher\notify.ps1" codex"""
    private val legacy = """
        [[hooks.PermissionRequest]]

        [[hooks.PermissionRequest.hooks]]
        type = "command"
        command = '$command'
        timeout = 5
        statusMessage = "Terminal Watcher notification"
    """.trimIndent()

    @Test
    fun `다른 도구의 훅과 메타데이터를 보존하고 반복 등록해도 변경하지 않는다`() {
        val original = """{"description":"keep", "hooks":{"PermissionRequest":[{"matcher":"Bash","hooks":[{"type":"command","command":"gk --blocking","timeout":86400}]}],"Stop":[{"hooks":[{"command":"other"}]}]}}"""
        val first = ensureCodexPermissionHook(original, command)
        val parsed = Json.parseToJsonElement(first).jsonObject
        val before = Json.parseToJsonElement(original).jsonObject
        assertEquals(before["description"], parsed["description"])
        assertEquals(before["hooks"]!!.jsonObject["Stop"], parsed["hooks"]!!.jsonObject["Stop"])
        val groups = parsed["hooks"]!!.jsonObject["PermissionRequest"]!!.jsonArray
        assertEquals(before["hooks"]!!.jsonObject["PermissionRequest"]!!.jsonArray[0], groups[0])
        assertEquals(2, groups.size)
        assertEquals(first, ensureCodexPermissionHook(first, command))
    }

    @Test
    fun `이미 신뢰한 자체 훅의 정의를 유지하고 중복 자체 훅만 제거한다`() {
        val first = ensureCodexPermissionHook("", command)
        val root = Json.parseToJsonElement(first).jsonObject
        val hookMap = root["hooks"]!!.jsonObject
        val groups = hookMap["PermissionRequest"]!!.jsonArray
        val duplicate = JsonObject(root + ("hooks" to JsonObject(hookMap +
            ("PermissionRequest" to JsonArray(groups + groups))))).toString()
        assertEquals(root, Json.parseToJsonElement(ensureCodexPermissionHook(duplicate, "replacement")))
        assertEquals(first, ensureCodexPermissionHook(first, "replacement"))
    }

    @Test
    fun `기존 톰엘 자체 훅만 제거하고 알림 래퍼와 신뢰 기록은 보존한다`() {
        val head = "notify = [\"wrapper\", \"--previous-notify\", \"keep\"]\n\n"
        val trust = "[hooks.state.\"keep\"]\ntrusted_hash = \"hash\"\n"
        val foreign = "[[hooks.PermissionRequest]]\n[[hooks.PermissionRequest.hooks]]\ncommand = 'other'\n"
        val result = removeLegacyCodexPermissionHook(head + legacy + "\n\n" + foreign + "\n" + trust, command)
        assertFalse(result.contains("Terminal Watcher notification"))
        assertTrue(result.startsWith(head))
        assertTrue(result.contains(foreign))
        assertTrue(result.contains(trust))
        assertEquals(result, removeLegacyCodexPermissionHook(result, command))
        assertEquals("", removeLegacyCodexPermissionHook(legacy.replace("\n", "\r\n"), command))
    }

    @Test
    fun `같은 그룹의 추가 핸들러나 사용자 수정이 있으면 임의로 삭제하지 않는다`() {
        val shared = legacy + "\n\n[[hooks.PermissionRequest.hooks]]\ncommand = 'guard'\n"
        assertEquals(shared, removeLegacyCodexPermissionHook(shared, command))
        val customized = legacy.replace("timeout = 5", "timeout = 10")
        assertEquals(customized, removeLegacyCodexPermissionHook(customized, command))
    }

    @Test
    fun `잘못된 제이슨은 덮어쓰지 않고 실패한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            ensureCodexPermissionHook("{invalid", command)
        }
    }

    @Test
    fun `문자열 안의 훅 예시는 설정 블록으로 취급하지 않는다`() {
        val documentation = "description = \"\"\"\n$legacy\n[example]\nvalue = 'keep'\n\"\"\"\n"
        assertEquals(documentation, removeLegacyCodexPermissionHook(documentation, command))
    }

    @Test
    fun `유닉스 경로에 공백이 있어도 기존 훅을 이관하고 명령을 인용한다`() {
        val unix = "/Users/test user/.codex/notify-twatcher.sh"
        assertEquals("", removeLegacyCodexPermissionHook(legacy.replace(command, unix), unix))
        val config = ensureCodexPermissionHook("", "\"$unix\"")
        assertEquals(config, ensureCodexPermissionHook(config, "\"$unix\""))
    }
}

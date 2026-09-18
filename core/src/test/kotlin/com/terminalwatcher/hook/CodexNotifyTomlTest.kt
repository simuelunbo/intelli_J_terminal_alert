package com.terminalwatcher.hook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexNotifyTomlTest {

    private val winHeader = "# Terminal Watcher notify v3 (powershell)"
    private val winNotify =
        """notify = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "C:\\Users\\beat Lab\\.terminal-watcher\\notify.ps1", "codex"]"""

    // Verbatim line the Codex desktop app wrote on a real machine: our command is embedded inside
    // its --previous-notify argument and the value contains several ']' inside strings.
    private val codexWrapper =
        """notify = [ "C:\\Users\\beat Lab\\AppData\\Local\\OpenAI\\Codex\\runtimes\\cua_node\\415ffebf3d576e9b\\bin\\node_modules\\@oai\\sky\\bin\\windows\\codex-computer-use.exe", "turn-ended", "--previous-notify", "[\"powershell\",\"-NoProfile\",\"-ExecutionPolicy\",\"Bypass\",\"-File\",\"C:\\\\Users\\\\beat Lab\\\\.terminal-watcher\\\\notify.ps1\",\"codex\"]" ]"""

    private val sections = """
        [marketplaces.openai-bundled]
        source_type = "local"

        [plugins."codex-app-tools@openai-bundled"]
        enabled = true
    """.trimIndent()

    private fun upsertWin(content: String): String? =
        CodexNotifyToml.upsertNotify(content, "notify.ps1", winHeader, winNotify)

    private fun countTopLevelNotify(content: String): Int {
        var count = 0
        for (line in content.lines()) {
            val t = line.trimStart()
            if (t.startsWith("[")) break
            if (t.startsWith("notify") && t.substringAfter("notify").trimStart().startsWith("=")) count++
        }
        return count
    }

    @Test
    fun `빈 설정에는 알림 명령을 한 번 등록한다`() {
        val result = upsertWin("")
        assertNotNull(result)
        val lines = result!!.lines()
        assertEquals(winHeader, lines[0])
        assertEquals(winNotify, lines[1])
        assertEquals(1, countTopLevelNotify(result))
    }

    @Test
    fun `주석이 있는 기존 알림 명령을 보존한다`() {
        assertNull(upsertWin("$winHeader\n$winNotify\n\n$sections"))
    }

    @Test
    fun `주석이 없어도 기존 알림 명령을 보존한다`() {
        // The Codex app drops comments when it rewrites the file; the decision must not
        // depend on the marker comment.
        assertNull(upsertWin("$winNotify\n\n$sections"))
    }

    @Test
    fun `주석 유무와 무관하게 코덱스 알림 래퍼를 보존한다`() {
        // This exact content used to trigger the duplicate-key corruption.
        assertNull(upsertWin("$codexWrapper\n\n$sections"))
        assertNull(upsertWin("$winHeader\n$codexWrapper\n\n$sections"))
    }

    @Test
    fun `중복 알림 키를 복구할 때 래퍼를 남긴다`() {
        // The corrupted state the old regex produced: our plain line prepended above the wrapper.
        val broken = "$winHeader\n$winNotify\n$codexWrapper\n\n$sections"
        val result = upsertWin(broken)
        assertNotNull(result)
        assertEquals(1, countTopLevelNotify(result!!))
        assertTrue(result.contains(codexWrapper))
        assertFalse(result.lines().contains(winNotify))
        assertEquals(1, result.lines().count { it == winHeader })
        assertTrue(result.contains(sections))
    }

    @Test
    fun `문자열 내부에 괄호가 있는 기존 명령도 완전히 교체한다`() {
        val foreign = """notify = [ "C:\\other\\tool.exe", "args: [\"a\",\"b\"]" ]"""
        val result = upsertWin("$foreign\n\n$sections")
        assertNotNull(result)
        assertEquals(1, countTopLevelNotify(result!!))
        assertTrue(result.contains(winNotify))
        assertFalse(result.contains("tool.exe"))
        assertFalse(result.contains("args:"))
    }

    @Test
    fun `여러 줄의 기존 알림 배열을 완전히 교체한다`() {
        val foreign = "notify = [\n  \"C:\\\\other\\\\tool.exe\",\n  \"arg\"\n]"
        val result = upsertWin("$foreign\n\n$sections")
        assertNotNull(result)
        assertEquals(1, countTopLevelNotify(result!!))
        assertFalse(result.contains("tool.exe"))
        assertTrue(result.contains(sections))
    }

    @Test
    fun `테이블 내부의 알림 키는 최상위 키와 구분한다`() {
        val config = "$sections\n\n[foo]\nnotify = \"section-local\""
        val result = upsertWin(config)
        assertNotNull(result)
        assertEquals(winNotify, result!!.lines()[1])
        assertTrue(result.contains("notify = \"section-local\""))
    }

    @Test
    fun `명령 교체 시 오래된 자동 생성 주석을 제거한다`() {
        val stale = "# Terminal Watcher notify v2\n# Added by Terminal AI Watcher plugin\nnotify = [\"C:\\\\old\\\\notify-old.cmd\"]"
        val result = upsertWin("$stale\n\n$sections")
        assertNotNull(result)
        assertEquals(1, result!!.lines().count { it.startsWith("# Terminal Watcher") })
        assertFalse(result.contains("Added by Terminal AI Watcher"))
        assertFalse(result.contains("notify-old.cmd"))
    }

    // ── macOS/Linux path (notify-twatcher.sh) ──

    private val unixHeader = "# Terminal Watcher notify v2"
    private val unixNotify = """notify = ["/Users/me/.codex/notify-twatcher.sh"]"""

    private fun upsertUnix(content: String): String? =
        CodexNotifyToml.upsertNotify(content, "notify-twatcher", unixHeader, unixNotify)

    @Test
    fun `유닉스 배열 형식의 기존 알림 명령을 보존한다`() {
        assertNull(upsertUnix("$unixHeader\n$unixNotify\n\n$sections"))
        assertNull(upsertUnix("$unixNotify\n\n$sections"))
    }

    @Test
    fun `유닉스의 오래된 문자열 형식을 배열로 전환한다`() {
        val legacy = "notify = \"/Users/me/.codex/notify-twatcher.sh\""
        val result = upsertUnix("$legacy\n\n$sections")
        assertNotNull(result)
        assertEquals(1, countTopLevelNotify(result!!))
        assertTrue(result.contains(unixNotify))
        assertFalse(result.lines().contains(legacy))
    }

    @Test
    fun `유닉스의 다른 알림 명령을 교체한다`() {
        val result = upsertUnix("notify = [\"/opt/other/hook.sh\"]\n\n$sections")
        assertNotNull(result)
        assertEquals(1, countTopLevelNotify(result!!))
        assertTrue(result.contains(unixNotify))
        assertFalse(result.contains("other/hook.sh"))
    }

}

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
    fun `empty config gets header and our command`() {
        val result = upsertWin("")
        assertNotNull(result)
        val lines = result!!.lines()
        assertEquals(winHeader, lines[0])
        assertEquals(winNotify, lines[1])
        assertEquals(1, countTopLevelNotify(result))
    }

    @Test
    fun `our plain line with marker is left untouched`() {
        assertNull(upsertWin("$winHeader\n$winNotify\n\n$sections"))
    }

    @Test
    fun `our plain line without marker is left untouched`() {
        // The Codex app drops comments when it rewrites the file; the decision must not
        // depend on the marker comment.
        assertNull(upsertWin("$winNotify\n\n$sections"))
    }

    @Test
    fun `codex wrapper embedding our command is respected, marker or not`() {
        // This exact content used to trigger the duplicate-key corruption.
        assertNull(upsertWin("$codexWrapper\n\n$sections"))
        assertNull(upsertWin("$winHeader\n$codexWrapper\n\n$sections"))
    }

    @Test
    fun `duplicate notify keys are repaired keeping the wrapper`() {
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
    fun `foreign notify with brackets inside strings is fully replaced`() {
        val foreign = """notify = [ "C:\\other\\tool.exe", "args: [\"a\",\"b\"]" ]"""
        val result = upsertWin("$foreign\n\n$sections")
        assertNotNull(result)
        assertEquals(1, countTopLevelNotify(result!!))
        assertTrue(result.contains(winNotify))
        assertFalse(result.contains("tool.exe"))
        assertFalse(result.contains("args:"))
    }

    @Test
    fun `multi-line foreign notify array is fully removed`() {
        val foreign = "notify = [\n  \"C:\\\\other\\\\tool.exe\",\n  \"arg\"\n]"
        val result = upsertWin("$foreign\n\n$sections")
        assertNotNull(result)
        assertEquals(1, countTopLevelNotify(result!!))
        assertFalse(result.contains("tool.exe"))
        assertTrue(result.contains(sections))
    }

    @Test
    fun `notify inside a table section is not treated as top-level`() {
        val config = "$sections\n\n[foo]\nnotify = \"section-local\""
        val result = upsertWin(config)
        assertNotNull(result)
        assertEquals(winNotify, result!!.lines()[1])
        assertTrue(result.contains("notify = \"section-local\""))
    }

    @Test
    fun `stale terminal watcher comments are removed on rewrite`() {
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
    fun `unix array form already configured is left untouched`() {
        assertNull(upsertUnix("$unixHeader\n$unixNotify\n\n$sections"))
        assertNull(upsertUnix("$unixNotify\n\n$sections"))
    }

    @Test
    fun `unix legacy string form is upgraded to the array form`() {
        val legacy = "notify = \"/Users/me/.codex/notify-twatcher.sh\""
        val result = upsertUnix("$legacy\n\n$sections")
        assertNotNull(result)
        assertEquals(1, countTopLevelNotify(result!!))
        assertTrue(result.contains(unixNotify))
        assertFalse(result.lines().contains(legacy))
    }

    @Test
    fun `unix foreign notify is replaced`() {
        val result = upsertUnix("notify = [\"/opt/other/hook.sh\"]\n\n$sections")
        assertNotNull(result)
        assertEquals(1, countTopLevelNotify(result!!))
        assertTrue(result.contains(unixNotify))
        assertFalse(result.contains("other/hook.sh"))
    }

    // ── PermissionRequest lifecycle hook block ──

    private val winHookBlock = """
        [[hooks.PermissionRequest]]

        [[hooks.PermissionRequest.hooks]]
        type = "command"
        command = 'powershell -NoProfile -ExecutionPolicy Bypass -File "C:\Users\beat Lab\.terminal-watcher\notify.ps1" codex'
        timeout = 5
        statusMessage = "Terminal Watcher notification"
    """.trimIndent()

    @Test
    fun `permission hook is appended after existing config and is idempotent`() {
        val base = "$winHeader\n$codexWrapper\n\n$sections"
        val result = CodexNotifyToml.ensurePermissionRequestHook(base, "notify.ps1", winHookBlock)
        assertNotNull(result)
        assertTrue(result!!.startsWith("$winHeader\n$codexWrapper"))
        assertTrue(result.contains(winHookBlock))
        assertNull(CodexNotifyToml.ensurePermissionRequestHook(result, "notify.ps1", winHookBlock))
    }

    @Test
    fun `permission hook block stands alone on an empty config`() {
        val result = CodexNotifyToml.ensurePermissionRequestHook("", "notify.ps1", winHookBlock)
        assertEquals(winHookBlock + "\n", result)
    }

    @Test
    fun `foreign permission hook is kept and ours added alongside`() {
        val foreign = "[[hooks.PermissionRequest]]\n\n[[hooks.PermissionRequest.hooks]]\n" +
            "type = \"command\"\ncommand = \"/opt/guard.sh\""
        val result = CodexNotifyToml.ensurePermissionRequestHook(foreign, "notify.ps1", winHookBlock)
        assertNotNull(result)
        assertTrue(result!!.contains("/opt/guard.sh"))
        assertTrue(result.contains("notify.ps1"))
        assertNull(CodexNotifyToml.ensurePermissionRequestHook(result, "notify.ps1", winHookBlock))
    }
}

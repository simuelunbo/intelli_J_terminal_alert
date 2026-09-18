package com.terminalwatcher.hook

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HookConfigFileTest {
    @TempDir lateinit var directory: File

    @Test
    fun `설정을 교체할 때 최초 원문을 백업하고 후속 수정에도 백업을 보존한다`() {
        val file = File(directory, "settings.json").apply { writeText("original") }
        writeHookConfig(file, "original", "updated")
        assertEquals("updated", file.readText())
        val backup = File(directory, "settings.json.terminal-watcher.bak")
        assertEquals("original", backup.readText())
        writeHookConfig(file, "updated", "second")
        assertEquals("second", file.readText())
        assertEquals("original", backup.readText())
        assertFalse(directory.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test
    fun `읽은 뒤 설정이 바뀌거나 새 파일이 생기면 사용자 내용을 덮어쓰지 않는다`() {
        val file = File(directory, "settings.json").apply { writeText("newer edit") }
        assertThrows(IllegalStateException::class.java) { writeHookConfig(file, "old", "plugin") }
        assertThrows(IllegalStateException::class.java) { writeHookConfig(file, null, "plugin") }
        assertEquals("newer edit", file.readText())
        assertEquals(listOf("settings.json"), directory.list()!!.toList())
    }

    @Test
    fun `처음 등록할 사용자 디렉터리를 만들고 같은 설정은 다시 쓰지 않는다`() {
        val file = File(directory, ".claude/settings.json")
        writeHookConfig(file, null, "new settings")
        val before = file.lastModified()
        writeHookConfig(file, "new settings", "new settings")
        assertEquals("new settings", file.readText())
        assertEquals(before, file.lastModified())
        assertEquals(listOf("settings.json"), file.parentFile.list()!!.toList())
    }
}

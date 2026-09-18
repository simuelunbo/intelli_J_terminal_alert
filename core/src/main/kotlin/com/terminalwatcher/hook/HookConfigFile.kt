package com.terminalwatcher.hook

import java.io.File
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardCopyOption

/** Preserve the original and refuse to overwrite an edit detected since reading it. */
internal fun writeHookConfig(file: File, expected: String?, content: String) {
    fun checkUnchanged() {
        check((if (file.exists()) file.readText() else null) == expected) {
            "Hook configuration changed during setup; leaving the newer configuration intact"
        }
    }
    checkUnchanged()
    if (expected == content) return
    Files.createDirectories(file.toPath().toAbsolutePath().parent)
    if (expected != null) {
        val backup = File(file.parentFile, file.name + ".terminal-watcher.bak")
        try {
            Files.writeString(backup.toPath(), expected, java.nio.file.StandardOpenOption.CREATE_NEW)
        } catch (_: FileAlreadyExistsException) {
            // Keep the first pre-migration copy across subsequent repairs.
        }
    }
    val temporary = Files.createTempFile(file.toPath().toAbsolutePath().parent, "twatcher-", ".tmp")
    try {
        Files.writeString(temporary, content)
        checkUnchanged()
        Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

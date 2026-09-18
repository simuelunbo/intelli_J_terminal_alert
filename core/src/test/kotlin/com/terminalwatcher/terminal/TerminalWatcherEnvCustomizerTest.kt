package com.terminalwatcher.terminal

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalWatcherEnvCustomizerTest {
    @Test
    fun `기본 작업 디렉터리는 자동 생성된 위임 메서드 없이 플랫폼 구현을 상속한다`() {
        // A Kotlin implementation generated a bridge here even with no source override.
        // Inspect the compiled class to catch that binary-only dependency returning.
        val method = TerminalWatcherEnvCustomizer::class.java.getMethod(
            "getDefaultStartWorkingDirectory", Project::class.java,
        )
        assertEquals(ShellExecOptionsCustomizer::class.java, method.declaringClass)
    }
}

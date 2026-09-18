package com.terminalwatcher.terminal;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions;
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer;

/**
 * The only boundary to the experimental shell-start API (2026.1+).
 * Java inherits the IDE's default methods without Kotlin compatibility bridges,
 * avoiding an unnecessary call to getDefaultStartWorkingDirectory.
 */
public final class TerminalWatcherEnvCustomizer implements ShellExecOptionsCustomizer {
    @Override
    public void customizeExecOptions(@NotNull Project project, @NotNull MutableShellExecOptions options) {
        TerminalWatcherShellSetup.configure(project, options::setEnvironmentVariable);
    }
}

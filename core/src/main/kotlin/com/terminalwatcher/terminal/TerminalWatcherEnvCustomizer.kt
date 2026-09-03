package com.terminalwatcher.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.terminalwatcher.hook.HookHttpServer
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer
import java.util.UUID

/**
 * Injects `INTELLIJ_TERMINAL_WATCHER_*` environment variables into every new
 * local terminal tab. Child shells inherit these and pass them through hook
 * scripts (curl `-H` headers) so [HookHttpServer] can resolve the originating
 * tab/project exactly — without relying on PPID-walking or cwd heuristics.
 *
 * Mirrors cmux's `CMUX_SURFACE_ID` / `CMUX_WORKSPACE_ID` injection pattern.
 *
 * Implements [ShellExecOptionsCustomizer] (2026.1+, sinceBuild 261) — the
 * platform's designated replacement for the deprecated `LocalTerminalCustomizer`,
 * so the plugin carries no deprecated-API references. The interface is still
 * `ApiStatus.Experimental` as of 262; accepted, since it is the only
 * non-deprecated env-injection point and `LocalTerminalDirectRunner` invokes it
 * on the same startup path for both classic and reworked terminals.
 */
class TerminalWatcherEnvCustomizer : ShellExecOptionsCustomizer {

    private val log = Logger.getInstance(TerminalWatcherEnvCustomizer::class.java)

    // The EP contract is @RequiresBackgroundThread, so blocking briefly in
    // awaitReady() below is legal here.
    override fun customizeExecOptions(project: Project, shellExecOptions: MutableShellExecOptions) {
        try {
            val tabId = UUID.randomUUID().toString()
            val projectId = project.locationHash
            val hookServer = ApplicationManager.getApplication()
                .getService(HookHttpServer::class.java)
            // Wait briefly for the server's async startup to finish — without this,
            // the very first customizer invocation can race ahead of port assignment
            // and inject port=0, leaving curl to dial http://127.0.0.1:0/... and fail.
            hookServer?.awaitReady()
            val port = hookServer?.actualPort ?: 0
            // basePath, not the tab's working directory: cwd only feeds the
            // notification fallback title, and reading the tab cwd would pull the
            // experimental EelPath API into our bytecode for no routing benefit.
            val cwd = project.basePath ?: ""

            shellExecOptions.setEnvironmentVariable(ENV_TAB_ID, tabId)
            shellExecOptions.setEnvironmentVariable(ENV_PROJECT_ID, projectId)
            if (port > 0) shellExecOptions.setEnvironmentVariable(ENV_PORT, port.toString())

            ApplicationManager.getApplication()
                .getService(TabRegistry::class.java)
                ?.registerPending(tabId, projectId, cwd)

            log.info("[TWatcher] Customizer injected tabId=$tabId project=$projectId port=$port cwd=$cwd")

            // Self-bind on the EDT after the toolwindow has processed the new tab.
            // ContentManagerListener doesn't reliably fire in Reworked Terminal, so
            // the customizer does the binding itself — invokeLater queues us after
            // the Content has been added.
            scheduleSelfBind(project, tabId)
        } catch (t: Throwable) {
            log.warn("[TWatcher] Customizer failed (terminal will still launch)", t)
        }
    }

    private fun scheduleSelfBind(project: Project, tabId: String) {
        ApplicationManager.getApplication().invokeLater(
            { attemptSelfBind(project, tabId) },
            ModalityState.any(),
        )
    }

    private fun attemptSelfBind(project: Project, tabId: String) {
        val tabRegistry = ApplicationManager.getApplication()
            .getService(TabRegistry::class.java) ?: return
        if (tabRegistry.lookup(tabId)?.contentRef?.get() != null) return  // already bound

        val tw = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
        if (tw == null) {
            log.info("[TWatcher] Self-bind skipped for tab=$tabId — Terminal toolwindow not registered yet")
            return
        }
        val pick = tw.contentManager.selectedContent ?: tw.contentManager.contents.lastOrNull()
        if (pick == null) {
            log.info("[TWatcher] Self-bind skipped for tab=$tabId — no Content available")
            return
        }
        tabRegistry.bindByTabId(tabId, pick)
    }

    companion object {
        const val ENV_TAB_ID = "INTELLIJ_TERMINAL_WATCHER_TAB_ID"
        const val ENV_PROJECT_ID = "INTELLIJ_TERMINAL_WATCHER_PROJECT_ID"
        const val ENV_PORT = "INTELLIJ_TERMINAL_WATCHER_PORT"
    }
}

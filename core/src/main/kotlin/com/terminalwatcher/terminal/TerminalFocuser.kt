package com.terminalwatcher.terminal

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content

/**
 * Brings the terminal tab that raised a notification back in front of the user.
 *
 * Single entry point shared by every trigger, so the OS-specific parts only have
 * to decide *when* to focus, never *how*:
 * - IDE balloon action link (all platforms)
 * - Windows tray-notification click (exact signal)
 * - IDE activation after a system notification (macOS/Linux heuristic)
 *
 * Resolution order is exact first, guess last. A [tabId] resolves through
 * [TabRegistry] to the actual [Content] object, which is immune to duplicate or
 * renamed tab titles. [tabName] is only consulted when no Content is available.
 */
object TerminalFocuser {

    private val log = Logger.getInstance(TerminalFocuser::class.java)

    private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"

    /** Safe from any thread — hops to the EDT before touching tool windows. */
    fun focus(projectId: String?, tabId: String?, tabName: String?) {
        ApplicationManager.getApplication().invokeLater(
            {
                runCatching { doFocus(projectId, tabId, tabName) }
                    .onFailure { log.warn("[TWatcher] Failed to focus terminal tab", it) }
            },
            ModalityState.any(),
        )
    }

    private fun doFocus(projectId: String?, tabId: String?, tabName: String?) {
        val registry = ApplicationManager.getApplication().getService(TabRegistry::class.java)
        val content = tabId?.let { registry?.lookup(it)?.contentRef?.get() }

        val project = projectId?.let { registry?.findProjectByProjectId(it) }
            ?: content?.let { findProjectOwning(it) }
        if (project == null) {
            log.info("[TWatcher] Focus skipped — no open project for projectId=$projectId")
            return
        }

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
        if (toolWindow == null) {
            log.info("[TWatcher] Focus skipped — Terminal tool window not available in '${project.name}'")
            return
        }

        val contentManager = toolWindow.contentManager
        val target = content
            ?: tabName?.let { name -> contentManager.contents.firstOrNull { it.displayName == name } }

        ProjectUtil.focusProjectWindow(project, true)
        toolWindow.activate(
            {
                // The tab can be closed between the notification and the click.
                if (target != null && contentManager.contents.any { it === target }) {
                    contentManager.setSelectedContent(target, true)
                    log.info("[TWatcher] Focused terminal tab '${target.displayName}' in '${project.name}'")
                } else {
                    log.info("[TWatcher] Terminal tab gone — opened the tool window only")
                }
            },
            true,
            true,
        )
    }

    /** Fallback when no projectId was carried: find whichever project owns this Content. */
    private fun findProjectOwning(content: Content): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { project ->
            runCatching {
                ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
                    ?.contentManager?.contents?.any { it === content } == true
            }.getOrDefault(false)
        }
}

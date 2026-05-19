package com.terminalwatcher.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener

/**
 * Bridges [TerminalWatcherEnvCustomizer]'s pending tab registrations to the
 * resulting IntelliJ [com.intellij.ui.content.Content] objects.
 *
 * The customizer fires *before* the toolwindow Content exists, so the binding
 * is deferred until [ContentManagerListener.contentAdded]. Pairing uses
 * "youngest pending entry within 5s window" — JetBrains creates Content
 * synchronously on the EDT in the same micro-batch as the PTY spawn, so this
 * is reliable in practice.
 */
class TerminalContentListener(
    private val project: Project,
) : ContentManagerListener {

    private val tabRegistry = ApplicationManager.getApplication()
        .getService(TabRegistry::class.java)

    override fun contentAdded(event: ContentManagerEvent) {
        tabRegistry?.bindNextPendingForProject(project.locationHash, event.content)
    }

    override fun contentRemoved(event: ContentManagerEvent) {
        tabRegistry?.unbindByContent(event.content)
    }

    companion object {
        private val log = Logger.getInstance(TerminalContentListener::class.java)

        /**
         * Attaches the listener to the Terminal toolwindow if it exists, or
         * subscribes to [ToolWindowManagerListener] to attach later. Idempotent:
         * safe to call multiple times per project (re-subscription is cheap).
         */
        fun attachTo(project: Project) {
            val listener = TerminalContentListener(project)

            val tw = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            if (tw != null) {
                tw.contentManager.addContentManagerListener(listener)
                log.info("[TWatcher] Attached ContentManagerListener to Terminal toolwindow (project=${project.name})")
                return
            }

            val connection = project.messageBus.connect()
            connection.subscribe(
                ToolWindowManagerListener.TOPIC,
                object : ToolWindowManagerListener {
                    override fun toolWindowsRegistered(ids: MutableList<String>, manager: ToolWindowManager) {
                        if ("Terminal" in ids) {
                            manager.getToolWindow("Terminal")
                                ?.contentManager
                                ?.addContentManagerListener(listener)
                            connection.disconnect()
                            log.info("[TWatcher] Late-attached ContentManagerListener after Terminal toolwindow registered (project=${project.name})")
                        }
                    }
                },
            )
        }
    }
}

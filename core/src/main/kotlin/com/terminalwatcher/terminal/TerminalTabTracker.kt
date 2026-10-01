package com.terminalwatcher.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import com.jediterm.terminal.ProcessTtyConnector
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.Component
import java.awt.Container
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds which terminal tab a hook event came from, for events that carry no
 * injected tabId — terminals that were already open when the plugin loaded.
 *
 * Strategies run strongest-first and stop at the first hit:
 * 1. **Shell PID** — exact. [ShellPidResolver] turns the PID the hook reported
 *    into the tab's shell PID by walking the OS process tree, and that is matched
 *    against each tab's own shell process.
 * 2. **cwd / single-tab / selected-tab** — guesses, kept as a last resort so a
 *    notification still carries some location rather than none.
 *
 * Returns the [Content] object rather than a tab title. Titles are neither unique
 * (the user can rename a tab, and a running CLI can set the title through an
 * escape sequence) nor stable over the life of a tab.
 */
object TerminalTabTracker {

    private val log = Logger.getInstance(TerminalTabTracker::class.java)

    private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"

    data class ResolvedTab(val project: Project, val content: Content)

    /**
     * @param shellPidCandidates the reported PID and its ancestors, from [ShellPidResolver].
     * @param cwd the working directory reported in the hook payload.
     */
    fun resolve(shellPidCandidates: Set<Long>, cwd: String?): ResolvedTab? = onEdt {
        byShellPid(shellPidCandidates)
            ?: byActiveTab(cwd)
            ?: byCwd(cwd)
            ?: bySingleTabProject(cwd)
            ?: bySelectedTab(cwd)
    }

    // ===== strategies =====

    /** Exact match: the tab whose shell process appears in the hook's ancestor chain. */
    private fun byShellPid(candidates: Set<Long>): ResolvedTab? {
        if (candidates.isEmpty()) return null
        for (project in ProjectManager.getInstance().openProjects) {
            for (content in terminalContents(project)) {
                if (log.isDebugEnabled) dumpContentDiagnostics(content)
                val pid = extractTabShellPid(content) ?: continue
                if (pid in candidates) {
                    log.info("[TWatcher] Matched tab by shell pid $pid: ${content.displayName}")
                    return ResolvedTab(project, content)
                }
            }
        }
        log.info("[TWatcher] No tab matched shell pid candidates=$candidates")
        return null
    }

    // Reworked Terminal heuristic: a tab running a CLI (claude/codex/gemini) has no
    // shell broadcasting OSC cwd updates, so getCurrentDirectory() returns null while
    // idle tabs report a cwd. Exactly one null-cwd tab therefore points at the emitter.
    private fun byActiveTab(cwd: String?): ResolvedTab? {
        if (cwd.isNullOrBlank()) return null
        val project = findProjectByCwd(cwd) ?: return null
        val nullCwdTabs = terminalContents(project).filter { extractTabCwd(it) == null }
        if (nullCwdTabs.size != 1) return null
        log.info("[TWatcher] Matched tab by active-tab heuristic: ${nullCwdTabs[0].displayName}")
        return ResolvedTab(project, nullCwdTabs[0])
    }

    private fun byCwd(cwd: String?): ResolvedTab? {
        if (cwd.isNullOrBlank()) return null
        val matches = mutableListOf<ResolvedTab>()
        for (project in ProjectManager.getInstance().openProjects) {
            for (content in terminalContents(project)) {
                if (extractTabCwd(content) == cwd) matches.add(ResolvedTab(project, content))
            }
        }
        val picked = matches.singleOrNull() ?: return null
        log.info("[TWatcher] Matched tab by cwd: ${picked.content.displayName}")
        return picked
    }

    private fun bySingleTabProject(cwd: String?): ResolvedTab? {
        if (cwd.isNullOrBlank()) return null
        val project = findProjectByCwd(cwd) ?: return null
        val contents = terminalContents(project)
        if (contents.size != 1) return null
        log.info("[TWatcher] single-tab-fallback in ${project.name}: ${contents[0].displayName}")
        return ResolvedTab(project, contents[0])
    }

    // Last resort. Even a possibly-wrong tab name beats the name disappearing
    // entirely, per the priority set when this fallback was first added.
    private fun bySelectedTab(cwd: String?): ResolvedTab? {
        if (cwd.isNullOrBlank()) return null
        val project = findProjectByCwd(cwd) ?: return null
        val selected = ToolWindowManager.getInstance(project)
            .getToolWindow(TERMINAL_TOOL_WINDOW_ID)?.contentManager?.selectedContent ?: return null
        log.info("[TWatcher] selected-tab-fallback: ${selected.displayName}")
        return ResolvedTab(project, selected)
    }

    internal fun findProjectByCwd(cwd: String): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { p ->
            val bp = p.basePath ?: return@firstOrNull false
            isSameOrUnderPath(cwd, bp)
        }

    // ===== tab inspection =====

    private fun terminalContents(project: Project): List<Content> = runCatching {
        ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?.contentManager?.contents?.toList().orEmpty()
    }.getOrDefault(emptyList())

    /** The PID of the shell process backing this tab, via the classic path first. */
    private fun extractTabShellPid(content: Content): Long? {
        val widget = resolveTerminalWidget(content)
        if (widget != null) {
            val pid = runCatching {
                (widget.ttyConnector as? ProcessTtyConnector)?.process?.pid()
            }.getOrNull()
            if (pid != null) return pid
        }
        // Reworked Terminal exposes no public PID accessor as of 2026.1, so the
        // session graph is searched reflectively. Failure just falls through.
        val viewImpl = resolveReworkedView(content) ?: return null
        return extractReworkedPid(viewImpl)
    }

    private fun <T> onEdt(action: () -> T?): T? {
        val result = AtomicReference<T?>()
        val app = ApplicationManager.getApplication()
        try {
            if (app.isDispatchThread) {
                result.set(action())
            } else {
                app.invokeAndWait({
                    result.set(runCatching { action() }.getOrNull())
                }, ModalityState.any())
            }
        } catch (t: Throwable) {
            log.warn("[TWatcher] onEdt failed: ${t.message}")
        }
        return result.get()
    }

    private fun resolveTerminalWidget(content: Content): TerminalWidget? {
        val byKey = runCatching { TerminalToolWindowManager.findWidgetByContent(content) }.getOrNull()
        if (byKey != null) return byKey
        return findTerminalWidgetInTree(content.component)
    }

    private fun findTerminalWidgetInTree(component: Component?, depth: Int = 0): TerminalWidget? {
        if (component == null || depth > 20) return null
        if (component is TerminalWidget) return component
        if (component is Container) {
            for (i in 0 until component.componentCount) {
                val found = findTerminalWidgetInTree(component.getComponent(i), depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    // Reworked Terminal: find the TerminalViewImpl inner class in the Swing tree and
    // return its outer instance. TerminalViewImpl officially exposes getCurrentDirectory().
    private fun resolveReworkedView(content: Content): Any? {
        val panel = findByClassNameInTree(content.component) { name ->
            name.startsWith(REWORKED_VIEW_INNER_PREFIX)
        } ?: return null
        return runCatching {
            val f = panel.javaClass.getDeclaredField(OUTER_INSTANCE_FIELD)
            f.isAccessible = true
            f.get(panel)
        }.getOrNull()
    }

    private fun findByClassNameInTree(
        component: Component?,
        depth: Int = 0,
        predicate: (String) -> Boolean,
    ): Component? {
        if (component == null || depth > 25) return null
        if (predicate(component.javaClass.name)) return component
        if (component is Container) {
            for (i in 0 until component.componentCount) {
                val found = findByClassNameInTree(component.getComponent(i), depth + 1, predicate)
                if (found != null) return found
            }
        }
        return null
    }

    private fun extractTabCwd(content: Content): String? {
        // 1) Classic TerminalWidget.getCurrentDirectory()
        val classic = resolveTerminalWidget(content)
        if (classic != null) {
            val cwd = runCatching {
                classic.javaClass.getMethod("getCurrentDirectory").invoke(classic) as? String
            }.getOrNull()
            if (!cwd.isNullOrBlank()) return cwd
        }
        // 2) Reworked TerminalViewImpl.getCurrentDirectory()
        val reworked = resolveReworkedView(content) ?: return null
        return runCatching {
            reworked.javaClass.getMethod("getCurrentDirectory").invoke(reworked) as? String
        }.getOrNull()
    }

    // Reworked Terminal PID: recursive scan of the view graph for a Process or PID,
    // independent of which field currently holds the session.
    private fun extractReworkedPid(viewImpl: Any): Long? {
        return try {
            searchPidRecursive(viewImpl, 0, mutableSetOf())
        } catch (t: Throwable) {
            log.info("[TWatcher] extractReworkedPid failed: ${t.message}")
            null
        }
    }

    private fun searchPidRecursive(obj: Any?, depth: Int, seen: MutableSet<Int>): Long? {
        if (obj == null || depth > 7) return null
        val id = System.identityHashCode(obj)
        if (!seen.add(id)) return null
        val cls = obj.javaClass
        val clsName = cls.name

        if (obj is Process) return runCatching { obj.pid() }.getOrNull()

        // CompletableFuture / CompletableDeferred: descend into the resolved value
        if (clsName == "java.util.concurrent.CompletableFuture" ||
            clsName.endsWith("CompletableDeferred") ||
            clsName.endsWith("CompletableDeferredImpl")
        ) {
            val resolved = runCatching {
                obj.javaClass.getMethod("getNow", Any::class.java).invoke(obj, null)
            }.getOrNull() ?: runCatching {
                obj.javaClass.getMethod("getCompleted").invoke(obj)
            }.getOrNull()
            return if (resolved != null) searchPidRecursive(resolved, depth + 1, seen) else null
        }

        // Package cut-off. Process and Future are handled above.
        if (clsName.startsWith("java.") || clsName.startsWith("javax.") ||
            clsName.startsWith("sun.") || clsName.startsWith("com.sun.") ||
            (clsName.startsWith("kotlin.") && !clsName.contains("coroutines"))
        ) return null

        var c: Class<*>? = cls
        while (c != null && c.name != "java.lang.Object") {
            for (f in c.declaredFields) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    if (v is Process) {
                        val pid = runCatching { v.pid() }.getOrNull()
                        if (pid != null) return pid
                    }
                    val fnLower = f.name.lowercase()
                    if (fnLower == "pid" || fnLower == "shellpid" || fnLower == "processpid" ||
                        fnLower == "myprocesspid"
                    ) {
                        when (v) {
                            is Long -> if (v > 0) return v
                            is Int -> if (v > 0) return v.toLong()
                            is Number -> if (v.toLong() > 0) return v.toLong()
                        }
                    }
                    val found = searchPidRecursive(v, depth + 1, seen)
                    if (found != null) return found
                } catch (_: Throwable) { }
            }
            c = c.superclass
        }
        return null
    }

    // ===== diagnostics (debug level only: the reflection dump is expensive) =====

    private fun dumpContentDiagnostics(content: Content) {
        val tabName = content.displayName
        try {
            val rootClass = content.component?.javaClass?.name ?: "null"
            log.debug("[TWatcher] diag tab=$tabName rootComponent=$rootClass")
            val hits = mutableListOf<String>()
            collectTerminalishNames(content.component, 0, hits, 20)
            if (hits.isNotEmpty()) log.debug("[TWatcher] diag tab=$tabName tree-candidates=$hits")
            val viewImpl = resolveReworkedView(content)
            if (viewImpl != null) {
                log.debug("[TWatcher] diag tab=$tabName reworkedView=${viewImpl.javaClass.name}")
                val fields = mutableListOf<String>()
                collectRelevantFields(viewImpl, "view", 0, fields, 60, mutableSetOf())
                if (fields.isNotEmpty()) log.debug("[TWatcher] diag tab=$tabName viewImpl-fields=$fields")
            }
        } catch (t: Throwable) {
            log.debug("[TWatcher] diag tab=$tabName dump failed: ${t.message}")
        }
    }

    private fun collectTerminalishNames(component: Component?, depth: Int, out: MutableList<String>, limit: Int) {
        if (component == null || depth > 30 || out.size >= limit) return
        val name = component.javaClass.name
        val lower = name.lowercase()
        if (lower.contains("terminal") || lower.contains("jediterm") || lower.contains("shell") ||
            lower.contains("rework") || lower.contains("session")
        ) {
            out.add("d$depth:$name")
        }
        if (component is Container) {
            for (i in 0 until component.componentCount) {
                if (out.size >= limit) return
                collectTerminalishNames(component.getComponent(i), depth + 1, out, limit)
            }
        }
    }

    private fun collectRelevantFields(
        obj: Any?,
        path: String,
        depth: Int,
        out: MutableList<String>,
        limit: Int,
        seen: MutableSet<Int>,
    ) {
        if (obj == null || depth > 5 || out.size >= limit) return
        val id = System.identityHashCode(obj)
        if (!seen.add(id)) return
        val cls = obj.javaClass
        val clsName = cls.name
        if (clsName.startsWith("java.") || clsName.startsWith("javax.") || clsName.startsWith("sun.") ||
            clsName.startsWith("kotlin.") || clsName.startsWith("com.sun.")
        ) return
        var c: Class<*>? = cls
        while (c != null && c.name != "java.lang.Object") {
            for (field in c.declaredFields) {
                if (out.size >= limit) return
                val fn = field.name.lowercase()
                val ft = field.type.name.lowercase()
                val isRelevant = fn.contains("session") || fn.contains("widget") ||
                    fn.contains("process") || fn.contains("pid") || fn.contains("tty") ||
                    fn.contains("cwd") || fn.contains("directory") || fn.contains("terminal") ||
                    ft.contains("terminal") || ft.contains("session") || ft.contains("tty")
                if (!isRelevant) continue
                try {
                    field.isAccessible = true
                    val v = field.get(obj)
                    val vType = v?.javaClass?.name ?: "null"
                    out.add("$path.${field.name}:${field.type.simpleName}=$vType")
                    if (v != null && !vType.startsWith("java.") && !vType.startsWith("javax.") &&
                        !vType.startsWith("kotlin.") && !vType.startsWith("sun.")
                    ) {
                        collectRelevantFields(v, "$path.${field.name}", depth + 1, out, limit, seen)
                    }
                } catch (_: Throwable) { }
            }
            c = c.superclass
        }
    }

    private const val REWORKED_VIEW_INNER_PREFIX =
        "com.intellij.terminal.frontend.view.impl.TerminalViewImpl\$"

    private const val OUTER_INSTANCE_FIELD = "this\$0"
}

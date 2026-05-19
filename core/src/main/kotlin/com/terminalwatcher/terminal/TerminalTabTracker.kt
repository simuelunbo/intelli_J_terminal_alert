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

object TerminalTabTracker {

    private val log = Logger.getInstance(TerminalTabTracker::class.java)

    fun getTabNameByShellPid(shellPid: Long?): String? {
        if (shellPid == null) return null
        return onEdt {
            for (project in ProjectManager.getInstance().openProjects) {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                    ?: continue
                for (content in toolWindow.contentManager.contents) {
                    dumpContentDiagnostics(content)
                    // Classic Terminal 경로
                    val widget = resolveTerminalWidget(content)
                    if (widget != null) {
                        val conn = widget.ttyConnector as? ProcessTtyConnector
                        if (conn != null) {
                            val pid = runCatching { conn.process.pid() }.getOrNull()
                            if (pid == shellPid) {
                                log.info("[TWatcher] Matched tab '${content.displayName}' by shell_pid (classic)")
                                return@onEdt content.displayName
                            }
                        }
                    }
                    // Reworked Terminal 경로 — TerminalViewImpl 로 PID 접근 시도
                    val viewImpl = resolveReworkedView(content)
                    if (viewImpl != null) {
                        val pid = extractReworkedPid(viewImpl)
                        log.info("[TWatcher] Tab '${content.displayName}' reworked-pid=$pid vs shell_pid=$shellPid")
                        if (pid != null && pid == shellPid) {
                            log.info("[TWatcher] Matched tab '${content.displayName}' by shell_pid (reworked)")
                            return@onEdt content.displayName
                        }
                    }
                }
            }
            log.info("[TWatcher] getTabNameByShellPid: no match for shell_pid=$shellPid")
            null
        }
    }

    fun getTabNameByCwd(cwd: String?): String? {
        if (cwd.isNullOrBlank()) return null
        return onEdt {
            val matches = mutableListOf<String>()
            for (project in ProjectManager.getInstance().openProjects) {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                    ?: continue
                for (content in toolWindow.contentManager.contents) {
                    val tabCwd = extractTabCwd(content)
                    log.info("[TWatcher] cwd-match: tab='${content.displayName}' tabCwd=$tabCwd hookCwd=$cwd")
                    if (tabCwd != null && tabCwd == cwd) {
                        content.displayName?.let { matches.add(it) }
                    }
                }
            }
            val picked = matches.singleOrNull()
            if (picked != null) log.info("[TWatcher] Matched tab '$picked' by cwd")
            picked
        }
    }

    // Reworked Terminal 발화원 추정:
    // CLI(claude/codex/gemini) 가 실행 중인 탭은 shell 이 OSC cwd-broadcast 를 못 보내
    // `getCurrentDirectory()` 가 null 을 반환하는 패턴이 관찰됨. 다른 idle 탭은 cwd 가 채워져 있음.
    // 따라서 "프로젝트 내에서 cwd=null 인 탭이 정확히 1개" 면 그 탭을 발화원으로 추정.
    fun getTabNameByActiveTab(cwd: String?): String? {
        if (cwd.isNullOrBlank()) return null
        return onEdt {
            val project = findProjectByCwd(cwd) ?: return@onEdt null
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                ?: return@onEdt null
            val nullCwdTabs = toolWindow.contentManager.contents.filter { extractTabCwd(it) == null }
            log.info("[TWatcher] active-tab: nullCwdTabs=${nullCwdTabs.map { it.displayName }}")
            if (nullCwdTabs.size == 1) {
                val name = nullCwdTabs[0].displayName
                log.info("[TWatcher] Matched tab '$name' by active-tab heuristic")
                name
            } else null
        }
    }

    // 최후 fallback — 5421cc6^ 동작 복원.
    // 정확도 매칭이 모두 실패해도 사용자 포커스 탭의 이름은 항상 표시한다 (잘못된 이름이라도
    // "이름 자체가 사라지는 회귀" 보다 낫다는 사용자 우선순위).
    fun getTabNameBySelectedTab(cwd: String?): String? {
        if (cwd.isNullOrBlank()) return null
        return onEdt {
            val project = findProjectByCwd(cwd) ?: return@onEdt null
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                ?: return@onEdt null
            toolWindow.contentManager.selectedContent?.displayName?.also {
                log.info("[TWatcher] selected-tab-fallback: '$it' (legacy behavior)")
            }
        }
    }

    fun getTabNameForSingleTabProject(cwd: String?): String? {
        if (cwd.isNullOrBlank()) return null
        return onEdt {
            val project = findProjectByCwd(cwd) ?: return@onEdt null
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                ?: return@onEdt null
            val contents = toolWindow.contentManager.contents
            if (contents.size == 1) {
                val name = contents[0].displayName
                log.info("[TWatcher] single-tab-fallback: project '${project.name}' → '$name'")
                name
            } else null
        }
    }

    internal fun findProjectByCwd(cwd: String): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { p ->
            val bp = p.basePath ?: return@firstOrNull false
            cwd == bp || cwd.startsWith("$bp/")
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

    // Reworked Terminal: Swing 트리에서 TerminalViewImpl$InnerClass 를 찾아 outer(this$0) 를 반환.
    // `com.intellij.terminal.frontend.view.impl.TerminalViewImpl` 는 getCurrentDirectory() 를 공식 노출.
    private fun resolveReworkedView(content: Content): Any? {
        val panel = findByClassNameInTree(content.component) { name ->
            name.startsWith("com.intellij.terminal.frontend.view.impl.TerminalViewImpl\$")
        } ?: return null
        return runCatching {
            val f = panel.javaClass.getDeclaredField("this\$0")
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
        val reworked = resolveReworkedView(content)
        if (reworked != null) {
            return runCatching {
                reworked.javaClass.getMethod("getCurrentDirectory").invoke(reworked) as? String
            }.getOrNull()
        }
        return null
    }

    // Reworked Terminal PID — viewImpl 트리 전체를 재귀 탐색해 Process/PID 발견.
    // sessionFuture / sessionDeferred / 다른 보유 필드 무관하게 동작.
    private fun extractReworkedPid(viewImpl: Any): Long? {
        return try {
            searchPidRecursive(viewImpl, 0, mutableSetOf())
        } catch (t: Throwable) {
            log.info("[TWatcher] extractReworkedPid failed: ${t.message}")
            null
        }
    }

    private fun findFieldByNameContaining(cls: Class<*>, token: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null && c.name != "java.lang.Object") {
            for (f in c.declaredFields) {
                if (f.name.contains(token, ignoreCase = true)) return f
            }
            c = c.superclass
        }
        return null
    }

    private fun searchPidRecursive(obj: Any?, depth: Int, seen: MutableSet<Int>): Long? {
        if (obj == null || depth > 7) return null
        val id = System.identityHashCode(obj)
        if (!seen.add(id)) return null
        val cls = obj.javaClass
        val clsName = cls.name

        // Process 객체 발견 시 pid() 호출
        if (obj is Process) return runCatching { obj.pid() }.getOrNull()

        // CompletableFuture / CompletableDeferred / Future-like — 결과 객체로 진입
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

        // 패키지 차단 — Process 와 Future 는 위에서 처리했으므로 일반 java.* 는 스킵
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

    // ===== diagnostics =====
    private fun dumpContentDiagnostics(content: Content) {
        val tabName = content.displayName
        try {
            val rootClass = content.component?.javaClass?.name ?: "null"
            log.info("[TWatcher] diag tab='$tabName' rootComponent=$rootClass")
            val hits = mutableListOf<String>()
            collectTerminalishNames(content.component, 0, hits, 20)
            if (hits.isNotEmpty()) log.info("[TWatcher] diag tab='$tabName' tree-candidates=$hits")
            // Reworked View 내부 필드 덤프 — PID 경로 확정용
            val viewImpl = resolveReworkedView(content)
            if (viewImpl != null) {
                log.info("[TWatcher] diag tab='$tabName' reworkedView=${viewImpl.javaClass.name}")
                val fields = mutableListOf<String>()
                collectRelevantFields(viewImpl, "view", 0, fields, 60, mutableSetOf())
                if (fields.isNotEmpty()) log.info("[TWatcher] diag tab='$tabName' viewImpl-fields=$fields")
            }
        } catch (t: Throwable) {
            log.info("[TWatcher] diag tab='$tabName' dump failed: ${t.message}")
        }
    }

    private fun collectTerminalishNames(component: Component?, depth: Int, out: MutableList<String>, limit: Int) {
        if (component == null || depth > 30 || out.size >= limit) return
        val name = component.javaClass.name
        val lower = name.lowercase()
        if (lower.contains("terminal") || lower.contains("jediterm") || lower.contains("shell") ||
            lower.contains("rework") || lower.contains("session")
        ) {
            out.add("d${depth}:${name}")
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
        if (clsName.startsWith("java.") || clsName.startsWith("javax.") || clsName.startsWith("sun.")
            || clsName.startsWith("kotlin.") || clsName.startsWith("com.sun.")
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
                    out.add("$path.${field.name}:${field.type.simpleName}→$vType")
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
}

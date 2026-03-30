package com.terminalwatcher.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 터미널 탭 이름을 cwd(작업 디렉토리) 기반으로 조회.
 * 공식 ContentManager API만 사용 — reflection 없음.
 */
object TerminalTabTracker {

    private val log = Logger.getInstance(TerminalTabTracker::class.java)

    /**
     * cwd 경로에 해당하는 프로젝트의 현재 선택된 터미널 탭 이름을 반환.
     * 매칭 실패 시 null 반환.
     */
    fun getTabNameByCwd(cwd: String?): String? {
        if (cwd.isNullOrBlank()) return null

        return try {
            val project = findProjectByCwd(cwd) ?: return null
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                ?: return null

            // 현재 선택된 탭의 이름 반환
            val selectedTab = toolWindow.contentManager.selectedContent?.displayName
            if (selectedTab != null) {
                log.info("[TWatcher] Resolved tab '$selectedTab' for cwd: $cwd")
            }
            selectedTab
        } catch (e: Exception) {
            log.debug("[TWatcher] Error resolving terminal tab: ${e.message}")
            null
        }
    }

    private fun findProjectByCwd(cwd: String): Project? {
        return ProjectManager.getInstance().openProjects.firstOrNull { project ->
            project.basePath != null && cwd.startsWith(project.basePath!!)
        }
    }
}

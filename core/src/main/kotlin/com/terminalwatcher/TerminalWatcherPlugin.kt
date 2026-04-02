package com.terminalwatcher

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.terminalwatcher.hook.HookConfigHelper
import com.terminalwatcher.hook.HookHttpServer

class TerminalWatcherPlugin : ProjectActivity {

    private val log = Logger.getInstance(TerminalWatcherPlugin::class.java)

    override suspend fun execute(project: Project) {
        // HTTP 서버 시작 + 서버 준비 대기 (포트 파일이 생성될 때까지)
        val hookServer = ApplicationManager.getApplication().getService(HookHttpServer::class.java)
        hookServer.awaitReady()

        // 서버가 준비된 후 hook 설정 등록
        HookConfigHelper.setupAllHooks(project.basePath)
        log.info("[TWatcher] Plugin initialized for project: ${project.name}, port: ${hookServer.actualPort}")
    }
}

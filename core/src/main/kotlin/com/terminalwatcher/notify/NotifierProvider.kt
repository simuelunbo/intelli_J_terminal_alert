package com.terminalwatcher.notify

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo

/**
 * Resolves the OS-specific [Notifier] implementation via reflective service
 * lookup so that non-current OS classes are never loaded by the classloader
 * on the wrong platform (prevents ClassNotFoundException / LinkageError).
 */
object NotifierProvider {
    private val log = Logger.getInstance(NotifierProvider::class.java)

    @Volatile
    private var cached: Notifier? = null

    fun get(): Notifier = cached ?: synchronized(this) {
        cached ?: create().also { cached = it }
    }

    private fun create(): Notifier {
        val fqn = when {
            SystemInfo.isMac -> "com.terminalwatcher.mac.MacNotifier"
            SystemInfo.isWindows -> "com.terminalwatcher.windows.WindowsNotifier"
            else -> "com.terminalwatcher.fallback.FallbackNotifier"
        }
        log.info("[TWatcher] Selected Notifier implementation: $fqn")
        val cls = Class.forName(fqn)
        return ApplicationManager.getApplication().getService(cls) as Notifier
    }
}

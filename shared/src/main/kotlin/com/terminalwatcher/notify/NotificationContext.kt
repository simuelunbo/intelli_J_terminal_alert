package com.terminalwatcher.notify

/**
 * Resolved routing information attached to a single hook event.
 *
 * Populated from env-var-derived identifiers (`INTELLIJ_TERMINAL_WATCHER_TAB_ID`
 * / `..._PROJECT_ID` passed through HTTP headers) when available, falling back
 * to legacy PID/cwd heuristics. All fields are nullable so consumers can format
 * the location tag (`[project/tab]`) based on whichever identifiers resolved.
 */
data class NotificationContext(
    val projectName: String? = null,
    val tabName: String? = null,
    val projectId: String? = null,
    val tabId: String? = null,
)

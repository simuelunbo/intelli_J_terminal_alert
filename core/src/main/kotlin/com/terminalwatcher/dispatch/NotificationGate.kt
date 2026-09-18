package com.terminalwatcher.dispatch

import com.terminalwatcher.hook.HookEvent
import com.terminalwatcher.hook.HookEventType

/** Rate-limit completion bursts per source; permission requests must never wait behind them. */
internal class NotificationGate(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val lastCompletions = mutableMapOf<List<String?>, Long>()

    @Synchronized
    fun shouldDeliver(event: HookEvent): Boolean {
        // PermissionRequest does not always carry a unique request ID. Identical text can
        // represent distinct approvals, so time/message based deduplication is unsafe.
        if (event.eventType != HookEventType.COMPLETE) return true
        val time = now()
        lastCompletions.entries.removeIf { time - it.value >= 5_000 }
        val source = listOf(event.tool, event.sessionId, event.tabId, event.projectId, event.cwd)
        if (source in lastCompletions) return false
        lastCompletions[source] = time
        return true
    }
}

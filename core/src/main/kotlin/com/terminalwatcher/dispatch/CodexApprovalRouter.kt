package com.terminalwatcher.dispatch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.terminalwatcher.hook.HookEvent
import com.terminalwatcher.hook.HookEventType
import com.terminalwatcher.hook.isCodexAutoReviewSession
import com.terminalwatcher.terminal.TabRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Codex runs PermissionRequest hooks before Auto-review decides, so the hook cannot tell whether
 * the reviewer settles the request or hands it to the user. The Codex TUI titles its tab
 * "[ ! ] Action Required" exactly while it waits on the user, so an Auto-review approval is
 * announced only once its tab shows that state. Every other event is dispatched at once.
 */
object CodexApprovalRouter {

    private val log = Logger.getInstance(CodexApprovalRouter::class.java)
    private val approvalWatches = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val pendingQuestions = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** Auto-review gives up after 90 s and then asks the user; allow for that hand-off. */
    private const val APPROVAL_WATCH_SECONDS = 120
    private const val QUESTION_WATCH_SECONDS = 2 * 60 * 60

    fun route(event: HookEvent) {
        val tabId = event.tabId
        if (event.tool != "codex" || tabId == null) {
            NotificationDispatcher.dispatchHookEvent(event)
            return
        }
        when (event.eventType) {
            HookEventType.PERMISSION -> AppExecutorUtil.getAppExecutorService().execute {
                if (isCodexAutoReviewSession(event.transcriptPath)) watchApproval(event, tabId)
                else NotificationDispatcher.dispatchHookEvent(event)
            }
            HookEventType.QUESTION -> {
                approvalWatches.remove(tabId)?.cancel(false)
                watchQuestion(tabId)
                NotificationDispatcher.dispatchHookEvent(event)
            }
            HookEventType.COMPLETE, HookEventType.ERROR -> {
                approvalWatches.remove(tabId)?.cancel(false)
                NotificationDispatcher.dispatchHookEvent(event)
            }
        }
    }

    private fun watchApproval(event: HookEvent, tabId: String) {
        if (!isCodexManagedTitle(tabTitle(tabId))) {
            // The title cannot reveal a prompt here, so missing one is worse than an extra alert.
            NotificationDispatcher.dispatchHookEvent(event)
            return
        }
        val tracker = ApprovalEscalationTracker()
        var ticks = 0
        poll(approvalWatches, tabId) {
            val title = tabTitle(tabId)
            when {
                tracker.observe(title, questionPending = pendingQuestions.containsKey(tabId)) -> {
                    log.info("[TWatcher] Auto-review handed the approval to the user: tab=$tabId")
                    NotificationDispatcher.dispatchHookEvent(event)
                    false
                }
                title == null || ++ticks >= APPROVAL_WATCH_SECONDS -> {
                    log.info("[TWatcher] Auto-review settled the approval without the user: tab=$tabId")
                    false
                }
                else -> true
            }
        }
    }

    private fun watchQuestion(tabId: String) {
        val tracker = QuestionPendingTracker()
        var ticks = 0
        poll(pendingQuestions, tabId) {
            val title = tabTitle(tabId)
            title != null && tracker.observe(title) && ++ticks < QUESTION_WATCH_SECONDS
        }
    }

    /** Runs [tick] every second while it returns true; the tab's entry in [watches] lives exactly as long. */
    private fun poll(watches: ConcurrentHashMap<String, ScheduledFuture<*>>, tabId: String, tick: () -> Boolean) {
        val self = CompletableFuture<ScheduledFuture<*>>()
        val watch = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            val current = self.join()
            if (!tick()) {
                watches.remove(tabId, current)
                current.cancel(false)
            }
        }, 0, 1, TimeUnit.SECONDS)
        watches.put(tabId, watch)?.cancel(false)
        self.complete(watch)
    }

    private fun tabTitle(tabId: String): String? = ApplicationManager.getApplication()
        .getService(TabRegistry::class.java)
        ?.lookup(tabId)?.contentRef?.get()?.displayName
}

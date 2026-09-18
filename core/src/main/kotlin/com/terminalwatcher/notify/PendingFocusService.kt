package com.terminalwatcher.notify

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the terminal tab a *system* notification currently points at, so a later
 * click can jump there.
 *
 * ## Why a holding slot is needed at all
 * The platform's system-notification API takes a title and a body and nothing
 * else — there is no click callback to attach the target to. So the target is
 * parked here when the notification goes out, and picked up when the click
 * signal arrives from whichever OS-specific trigger fired.
 *
 * ## The three rules that keep this from misfiring
 * 1. **Recorded only when the notification really appeared.** The platform skips
 *    system notifications while the IDE is the foreground app, so callers record
 *    only when the app was inactive. Otherwise there is no banner to click and a
 *    parked target would fire on an unrelated window switch.
 * 2. **Expires.** [consume] refuses a target older than the caller's window, so
 *    coming back to the IDE hours later never drags the user to a stale tab.
 * 3. **Used once.** [consume] clears the slot, so one notification causes at most
 *    one jump instead of hijacking every future activation.
 *
 * Only the newest notification is kept: an older banner the user ignored is not
 * the one they are clicking now.
 */
@Service(Service.Level.APP)
class PendingFocusService {

    private val log = Logger.getInstance(PendingFocusService::class.java)

    data class Target(
        val projectId: String?,
        val tabId: String?,
        val tabName: String?,
        val recordedAt: Long,
    )

    private val slot = AtomicReference<Target?>(null)

    fun record(context: NotificationContext?) {
        if (context == null) return
        if (context.tabId == null && context.tabName == null) return
        slot.set(
            Target(
                projectId = context.projectId,
                tabId = context.tabId,
                tabName = context.tabName,
                recordedAt = System.currentTimeMillis(),
            ),
        )
        log.info("[TWatcher] Pending focus target recorded: tab=${context.tabName}")
    }

    /** Returns the parked target and clears the slot, or null when absent or expired. */
    fun consume(maxAgeMs: Long): Target? {
        val target = slot.getAndSet(null) ?: return null
        val age = System.currentTimeMillis() - target.recordedAt
        if (age > maxAgeMs) {
            log.info("[TWatcher] Pending focus target expired after ${age}ms — ignoring")
            return null
        }
        return target
    }

    fun clear() {
        slot.set(null)
    }

    companion object {
        fun getInstance(): PendingFocusService =
            ApplicationManager.getApplication().getService(PendingFocusService::class.java)
    }
}

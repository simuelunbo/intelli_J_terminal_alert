package com.terminalwatcher.dispatch

/** Codex TUI title while it waits on the user; the marker blinks between `[ ! ]` and `[ . ]`. */
private val ACTION_REQUIRED_TITLE = Regex("""\[ [!.] ] Action Required""")

internal fun isCodexActionRequiredTitle(title: String?): Boolean =
    title != null && ACTION_REQUIRED_TITLE.containsMatchIn(title)

/**
 * Whether the tab title is driven by a working Codex TUI (braille spinner or the action-required
 * marker). Without that, waiting on the title would never see a prompt.
 */
internal fun isCodexManagedTitle(title: String?): Boolean {
    if (title == null) return false
    if (isCodexActionRequiredTitle(title)) return true
    val first = title.removePrefix("● ").firstOrNull() ?: return false
    return first in '⠀'..'⣿'
}

/**
 * Decides when an Auto-review approval has been handed to the user. A pending question keeps the
 * same title, so it never counts; the title must hold for [requiredTicks] polls so a question
 * shown right after the reviewer approved is reported by its own hook first.
 */
internal class ApprovalEscalationTracker(private val requiredTicks: Int = 3) {
    private var streak = 0

    fun observe(title: String?, questionPending: Boolean): Boolean {
        if (!isCodexActionRequiredTitle(title) || questionPending) {
            streak = 0
            return false
        }
        streak++
        return streak >= requiredTicks
    }
}

/** Tracks an unanswered Codex question until its tab stops asking for action. */
internal class QuestionPendingTracker(private val maxTicksBeforeSeen: Int = 10) {
    private var seenActionRequired = false
    private var ticks = 0

    /** @return true while the question is still unanswered. */
    fun observe(title: String?): Boolean {
        ticks++
        if (isCodexActionRequiredTitle(title)) {
            seenActionRequired = true
            return true
        }
        return !seenActionRequired && ticks < maxTicksBeforeSeen
    }
}

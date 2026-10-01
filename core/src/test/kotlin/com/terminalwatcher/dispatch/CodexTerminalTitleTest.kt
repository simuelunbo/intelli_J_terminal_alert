package com.terminalwatcher.dispatch

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexTerminalTitleTest {
    private val working = "⠹ beatAPP-KioskOrder | Working"
    private val actionRequired = "[ ! ] Action Required | beatAPP-KioskOrder"
    private val actionRequiredBlink = "[ . ] Action Required | beatAPP-KioskOrder"

    @Test
    fun `검토자가 사용자에게 넘겨 승인창이 계속 떠 있으면 알린다`() {
        val tracker = ApprovalEscalationTracker(requiredTicks = 3)
        assertFalse(tracker.observe(working, questionPending = false))
        assertFalse(tracker.observe(actionRequired, questionPending = false))
        assertFalse(tracker.observe(actionRequiredBlink, questionPending = false))
        assertTrue(tracker.observe(actionRequired, questionPending = false))
    }

    @Test
    fun `검토자가 처리해 작업 표시만 이어지면 알리지 않는다`() {
        val tracker = ApprovalEscalationTracker(requiredTicks = 3)
        repeat(10) { assertFalse(tracker.observe(working, questionPending = false)) }
    }

    @Test
    fun `답하지 않은 질문 때문에 뜬 표시는 승인 요청으로 보지 않는다`() {
        val tracker = ApprovalEscalationTracker(requiredTicks = 3)
        repeat(5) { assertFalse(tracker.observe(actionRequired, questionPending = true)) }
    }

    @Test
    fun `질문은 표시가 사라질 때까지 대기 중으로 본다`() {
        val tracker = QuestionPendingTracker(maxTicksBeforeSeen = 3)
        assertTrue(tracker.observe(working))
        assertTrue(tracker.observe(actionRequired))
        assertTrue(tracker.observe(actionRequiredBlink))
        assertFalse(tracker.observe(working))
    }

    @Test
    fun `Codex가 제목을 관리하지 않는 탭은 제목으로 판단할 수 없다고 본다`() {
        assertTrue(isCodexManagedTitle(working))
        assertTrue(isCodexManagedTitle(actionRequired))
        assertTrue(isCodexManagedTitle("● ⠋ project"))
        assertFalse(isCodexManagedTitle("Local"))
        assertFalse(isCodexManagedTitle(null))
    }
}

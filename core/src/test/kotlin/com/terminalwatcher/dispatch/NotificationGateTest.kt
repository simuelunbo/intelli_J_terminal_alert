package com.terminalwatcher.dispatch

import com.terminalwatcher.hook.HookEvent
import com.terminalwatcher.hook.HookEventType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationGateTest {
    private fun event(type: HookEventType, session: String = "one") =
        HookEvent("codex", type, "Approval requested: Bash", session, "/project")

    @Test
    fun `완료 직후에도 같은 내용의 승인 요청을 모두 전달한다`() {
        val gate = NotificationGate { 100L }
        assertTrue(gate.shouldDeliver(event(HookEventType.COMPLETE)))
        assertTrue(gate.shouldDeliver(event(HookEventType.PERMISSION)))
        assertTrue(gate.shouldDeliver(event(HookEventType.PERMISSION)))
        assertTrue(gate.shouldDeliver(event(HookEventType.ERROR)))
    }

    @Test
    fun `완료 알림 제한은 같은 세션에만 적용하고 오초 후 해제한다`() {
        var now = 0L
        val gate = NotificationGate { now }
        val event = event(HookEventType.COMPLETE)
        assertTrue(gate.shouldDeliver(event))
        now = 4_999
        assertFalse(gate.shouldDeliver(event))
        assertTrue(gate.shouldDeliver(event.copy(sessionId = "two")))
        assertTrue(gate.shouldDeliver(event.copy(tabId = "another-tab")))
        now = 5_000
        assertTrue(gate.shouldDeliver(event))
    }
}

package com.terminalwatcher.terminal

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PathMatchTest {
    private val windowsBase = "C:/Users/beat Lab/android project/beatAPP-KioskOrder"

    @Test
    fun `윈도우 역슬래시 작업 경로를 슬래시 프로젝트 경로와 같은 위치로 본다`() {
        assertTrue(isSameOrUnderPath("""C:\Users\beat Lab\android project\beatAPP-KioskOrder""", windowsBase, ignoreCase = true))
        assertTrue(isSameOrUnderPath("""c:\users\beat lab\android project\beatAPP-KioskOrder\app\""", windowsBase, ignoreCase = true))
    }

    @Test
    fun `이름이 겹치는 형제 폴더는 프로젝트 하위로 보지 않는다`() {
        assertFalse(isSameOrUnderPath("""C:\Users\beat Lab\android project\beatAPP-KioskOrder2""", windowsBase, ignoreCase = true))
    }

    @Test
    fun `대소문자를 구분하는 파일 시스템에서는 다른 경로로 본다`() {
        assertTrue(isSameOrUnderPath("/Users/test/project/app", "/Users/test/project", ignoreCase = false))
        assertFalse(isSameOrUnderPath("/users/test/project", "/Users/test/project", ignoreCase = false))
    }
}

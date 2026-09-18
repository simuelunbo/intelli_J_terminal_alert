package com.terminalwatcher.windows

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WindowsSoundTest {
    @TempDir lateinit var media: File

    @Test
    fun `맥 기본음과 빈 기본음은 존재하는 윈도우 사운드로 대체한다`() {
        val chimes = File(media, "chimes.wav").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(chimes.absolutePath, resolveWindowsSoundPath("Glass", "", media))
        assertEquals(chimes.absolutePath, resolveWindowsSoundPath("", "", media))
    }

    @Test
    fun `선택한 기본음과 사용자 파일은 우선 사용한다`() {
        File(media, "chimes.wav").writeBytes(byteArrayOf(1))
        val ding = File(media, "ding.wav").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(ding.absolutePath, resolveWindowsSoundPath("ding", "", media))
        assertEquals("custom.mp3", resolveWindowsSoundPath("ding", " custom.mp3 ", media))
    }

    @Test
    fun `첫 대체음이 없으면 다음 파일을 찾고 모두 없으면 경로를 만들지 않는다`() {
        assertNull(resolveWindowsSoundPath("Glass", "", media))
        val chord = File(media, "chord.wav").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(chord.absolutePath, resolveWindowsSoundPath("Glass", "", media))
    }
}

package com.example.aibuds.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PromptClockTest {
    private val t = ZonedDateTime.of(2026, 11, 14, 14, 3, 0, 0, ZoneId.of("Asia/Seoul"))

    @Test fun formatsKoreanDateTime() {
        assertEquals("현재 시각은 2026년 11월 14일 토요일 오후 2시 3분입니다 (Asia/Seoul, UTC+09:00).", PromptClock.line(t))
    }

    @Test fun appendsAfterPrompt() {
        assertEquals("SYS\n\n" + PromptClock.line(t), PromptClock.append("SYS  ", t))
        assertEquals(PromptClock.line(t), PromptClock.append("", t))
    }
}

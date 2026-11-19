package com.example.aibuds.core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 시스템 프롬프트 끝에 붙이는 현재 날짜/시각. 모델은 이걸 보고 "오늘", "지금 몇 시" 등에 답한다. */
object PromptClock {
    private val format = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE a h시 m분", Locale.KOREAN)

    fun line(now: ZonedDateTime = ZonedDateTime.now()): String =
        "현재 시각은 ${now.format(format)}입니다 (${now.zone.id}, UTC${now.offset.id.replace("Z", "+00:00")})."

    fun append(systemPrompt: String, now: ZonedDateTime = ZonedDateTime.now()): String =
        if (systemPrompt.isBlank()) line(now) else systemPrompt.trimEnd() + "\n\n" + line(now)
}

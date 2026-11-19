package com.example.aibuds.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSplitterTest {
    private fun run(vararg tokens: String): List<String> {
        val s = SentenceSplitter()
        val out = mutableListOf<String>()
        tokens.forEach { out += s.feed(it) }
        s.flush()?.let { out += it }
        return out
    }

    @Test fun splitsKoreanSentencesAcrossTokens() {
        assertEquals(
            listOf("안녕하세요, 반갑습니다.", "오늘 날씨가 좋네요."),
            run("안녕", "하세요, 반갑", "습니다. ", "오늘 날씨가", " 좋네요."),
        )
    }

    @Test fun doesNotSplitDecimals() {
        assertEquals(listOf("파이는 3.14 정도예요."), run("파이는 3", ".14 정도예요."))
    }

    @Test fun mergesShortFragments() {
        assertEquals(listOf("네. 알겠습니다 그렇게 할게요."), run("네. ", "알겠습니다 그렇게 할게요."))
    }

    @Test fun englishSentences() {
        assertEquals(listOf("Hello there, friend.", "How are you today?"), run("Hello there, friend. ", "How are you", " today?"))
    }

    @Test fun stripsMarkdown() {
        assertEquals(listOf("이건 굵은 글씨입니다."), run("이건 **굵은** 글씨입니다."))
    }

    @Test fun forcesSplitOnLongText() {
        val s = SentenceSplitter(maxLen = 20)
        val out = s.feed("가나다라 마바사아 자차카타 파하가나 다라마바 사아자차")
        assert(out.isNotEmpty())
    }
}

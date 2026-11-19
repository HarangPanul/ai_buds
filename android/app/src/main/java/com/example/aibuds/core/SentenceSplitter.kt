package com.example.aibuds.core

/**
 * 토큰 스트림을 문장 단위로 끊는다.
 * - 종결 부호(. ? ! 。 ！ ？ …) 뒤에 공백/개행이 확인돼야 경계로 인정 (3.14 같은 숫자 보호)
 * - 개행은 항상 경계
 * - [minLen]보다 짧은 조각은 다음 문장과 합침
 * - [maxLen]을 넘으면 쉼표/공백에서 강제 분할
 */
class SentenceSplitter(private val minLen: Int = 6, private val maxLen: Int = 120) {
    private val buf = StringBuilder()

    fun feed(token: String): List<String> {
        buf.append(token)
        val out = mutableListOf<String>()
        while (true) {
            val cut = findCut() ?: break
            val sentence = clean(buf.substring(0, cut))
            buf.delete(0, cut)
            if (sentence.isNotEmpty()) out += sentence
        }
        return out
    }

    fun flush(): String? {
        val rest = clean(buf.toString())
        buf.clear()
        return rest.ifEmpty { null }
    }

    private fun findCut(): Int? {
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            val isNewline = c == '\n'
            val isTerminator = c in TERMINATORS && i + 1 < buf.length && buf[i + 1].isWhitespace()
            if (isNewline || isTerminator) {
                val cut = i + 1
                if (isNewline || clean(buf.substring(0, cut)).length >= minLen) return cut
            }
            i++
        }
        if (buf.length > maxLen) {
            val window = buf.substring(0, maxLen)
            val at = maxOf(window.lastIndexOf(','), window.lastIndexOf('，'), window.lastIndexOf(' '))
            return if (at > 0) at + 1 else maxLen
        }
        return null
    }

    private fun clean(s: String): String =
        s.replace(Regex("[*#`_]+"), "").trim()

    private companion object {
        val TERMINATORS = setOf('.', '?', '!', '。', '！', '？', '…')
    }
}

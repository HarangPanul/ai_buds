package com.example.aibuds.platform.store

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 기기에서 무슨 일이 있었는지 남기는 작은 기록 파일 (adb 없이 진단용).
 * 프로세스가 죽었다 살아나도 남도록 파일에 쓴다. 설정 화면 맨 아래에서 보고 복사할 수 있다.
 * 파일이 [maxBytes]를 넘으면 뒤쪽 절반만 남긴다.
 */
class EventLog(private val file: File, private val maxBytes: Long = 256 * 1024) {
    private val writer = Executors.newSingleThreadExecutor()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(message: String) {
        val line = "${format.format(Date())} $message\n"
        writer.execute {
            try {
                file.appendText(line)
                if (file.length() > maxBytes) {
                    val text = file.readText()
                    file.writeText(text.substring(text.indexOf('\n', text.length / 2) + 1))
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 최근 [n]줄, 최신이 먼저. 쓰기 대기 중인 줄까지 반영한다. */
    fun recent(n: Int = 80): List<String> =
        try {
            writer.submit<List<String>> { if (file.exists()) file.readLines().takeLast(n).reversed() else emptyList() }.get()
        } catch (_: Exception) {
            emptyList()
        }
}

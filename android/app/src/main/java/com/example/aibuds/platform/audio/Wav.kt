package com.example.aibuds.platform.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV 녹음의 간단한 진단 값. peak/rms는 PCM16 기준(0..32767). */
data class WavStats(val seconds: Float, val peak: Int, val rms: Int) {
    fun describe() = "%.1f초 · peak %d · rms %d".format(seconds, peak, rms)
}

object Wav {
    /** [encode]가 만든 44바이트 헤더 WAV의 길이/피크/RMS를 계산한다. */
    fun stats(wav: ByteArray, sampleRate: Int = 16000): WavStats {
        val n = (wav.size - 44).coerceAtLeast(0) / 2
        var peak = 0
        var sumSq = 0.0
        for (i in 0 until n) {
            val s = ((wav[44 + 2 * i + 1].toInt() shl 8) or (wav[44 + 2 * i].toInt() and 0xFF)).toShort().toInt()
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
            sumSq += s.toDouble() * s
        }
        val rms = if (n == 0) 0 else kotlin.math.sqrt(sumSq / n).toInt()
        return WavStats(n.toFloat() / sampleRate, peak, rms)
    }

    /** PCM16 mono 데이터를 44바이트 RIFF 헤더가 붙은 WAV로 감싼다. */
    fun encode(pcm: ByteArray, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
        val byteRate = sampleRate * channels * 2
        val buf = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        buf.putInt(sampleRate).putInt(byteRate).putShort((channels * 2).toShort()).putShort(16)
        buf.put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return buf.array()
    }
}

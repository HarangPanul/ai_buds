package com.example.aibuds.platform.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.example.aibuds.core.Cue
import com.example.aibuds.core.Cues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 신호음. ToneGenerator 대신 직접 만든 PCM을 AudioTrack으로 재생한다.
 *
 * 블루투스 이어폰은 소리가 한동안 없으면 A2DP 링크가 쉬고 있어서, 깨어나는 동안 앞부분 수백 ms가 잘린다.
 * 짧은 삐 소리는 통째로 사라질 수 있으므로 앞에 무음을 깔아 링크를 먼저 깨운다.
 * 또 듣기 시작 직후 통화 경로(SCO)로 바뀌면 A2DP가 끊겨 아직 이어폰에 도착하지 않은 소리가 버려지므로,
 * 마지막 샘플이 실제로 출력될 때까지(AudioTimestamp) 기다린 뒤에 반환한다.
 */
class Beeper(
    /** 진단 기록. */
    private val log: (String) -> Unit = {},
) : Cues {

    /** 신호음이 안 나더라도 턴은 계속돼야 하므로 실패는 삼킨다. */
    override suspend fun play(cue: Cue) {
        try {
            log("cue: $cue")
            when (cue) {
                // 도 → 미 (올라감). 소리가 이어폰에서 다 나온 뒤 반환한다.
                Cue.ListenStart -> play(listOf(Tone(C5, 110), Tone(E5, 150)), volume = 0.6f)
                // 미 → 도 (내려감). 통화 경로가 해제되어 음악 출력으로 돌아온 뒤에 울리도록 잠깐 기다린다 (아래 둘도 같다).
                Cue.Heard -> { delay(250); play(listOf(Tone(E5, 110), Tone(C5, 150)), volume = 0.6f) }
                // 낮은 삐삐.
                Cue.NothingHeard -> { delay(400); play(listOf(Tone(440.0, 130), Tone(0.0, 100), Tone(440.0, 130)), volume = 0.8f) }
                // 높은 삐 한 번.
                Cue.ReplyStart -> play(listOf(Tone(880.0, 150)), volume = 0.8f)
                // 낮게 내려가는 세 음.
                Cue.Error -> { delay(400); play(listOf(Tone(523.25, 140), Tone(392.0, 140), Tone(261.63, 260)), volume = 0.8f) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private data class Tone(val hz: Double, val ms: Int)

    private suspend fun play(tones: List<Tone>, volume: Float) = withContext(Dispatchers.IO) {
        val pcm = render(tones, volume, LEAD_SILENCE_MS)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            awaitPresented(track, pcm.size)
        } finally {
            track.stop()
            track.release()
        }
    }

    /** 마지막 프레임이 출력 장치에 나갈 때까지 대기. 타임스탬프를 못 받는 기기는 재생 위치 + 여유 시간으로 대신한다. */
    private suspend fun awaitPresented(track: AudioTrack, frames: Int) {
        val durationMs = frames * 1000L / RATE
        val deadline = System.currentTimeMillis() + durationMs + MAX_EXTRA_LATENCY_MS
        val ts = AudioTimestamp()
        var sawTimestamp = false
        while (System.currentTimeMillis() < deadline) {
            if (track.getTimestamp(ts)) {
                sawTimestamp = true
                // ts.framePosition은 ts.nanoTime에 출력된 프레임. 지금 시점의 출력 위치로 보정한다.
                val now = System.nanoTime()
                val presented = ts.framePosition + (now - ts.nanoTime) * RATE / 1_000_000_000L
                if (presented >= frames) return
            } else if (!sawTimestamp && track.playbackHeadPosition >= frames) {
                delay(FALLBACK_LATENCY_MS)
                return
            }
            delay(20)
        }
    }

    private fun render(tones: List<Tone>, volume: Float, leadMs: Int): ShortArray {
        val lead = RATE * leadMs / 1000
        val tail = RATE * TAIL_SILENCE_MS / 1000
        val total = lead + tones.sumOf { RATE * it.ms / 1000 } + tail
        val out = ShortArray(total)
        var pos = lead
        val fade = RATE * 5 / 1000 // 5ms 페이드로 딸깍 소리 방지
        for (t in tones) {
            val n = RATE * t.ms / 1000
            if (t.hz > 0) for (i in 0 until n) {
                val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                out[pos + i] = (sin(2 * PI * t.hz * i / RATE) * env * volume * Short.MAX_VALUE).toInt().toShort()
            }
            pos += n
        }
        return out
    }

    companion object {
        private const val RATE = 44100
        private const val C5 = 523.25
        private const val E5 = 659.25
        /** A2DP 링크를 깨우는 무음. 이 동안 잘려도 상관없다. */
        private const val LEAD_SILENCE_MS = 300
        private const val TAIL_SILENCE_MS = 30
        /** 블루투스 출력 지연 상한 (보통 150~300ms). */
        private const val MAX_EXTRA_LATENCY_MS = 700L
        private const val FALLBACK_LATENCY_MS = 300L
    }
}

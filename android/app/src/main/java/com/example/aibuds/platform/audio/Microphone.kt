package com.example.aibuds.platform.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * 마이크 입력. 듣는 쪽(폰 STT, 나중의 서버 STT·음성 모델)은 모두 이걸 통해 소리를 받는다.
 * 그래서 어떤 엔진을 쓰든 같은 소리를 듣고, 무음 판단 기준이 같고, 그 턴의 오디오를 저장할 수 있다.
 */
class Microphone(private val route: AudioRoute) {
    /**
     * 이어폰 마이크 경로를 잡고 녹음을 시작한 뒤 [block]을 실행한다. 끝나면(실패·취소 포함) 녹음을 멈추고 경로를 놓는다.
     * @param soundThresholdRms 소리 크기(PCM16 RMS)가 이 값을 넘어야 "소리가 난다"고 본다.
     */
    suspend fun <T> use(mode: MicMode, soundThresholdRms: Int = 300, block: suspend (Recording) -> T): T {
        val device = route.acquire()
        try {
            val recording = Recording(device, mode, soundThresholdRms)
            try {
                return block(recording)
            } finally {
                recording.close()
            }
        } finally {
            route.release()
        }
    }

    companion object {
        /** 녹음 형식: 16 kHz mono PCM16. */
        const val SAMPLE_RATE = 16000
    }
}

/** 진행 중인 녹음 한 번. [Microphone.use] 안에서만 쓴다. */
class Recording internal constructor(device: AudioDeviceInfo?, val mode: MicMode, private val soundThresholdRms: Int) {
    /** 어떤 마이크로 듣고 있는지 (화면·기록용). */
    val mic: String = (device?.let { "${it.productName} · ${connection(it.type)}" } ?: "폰 마이크") +
        " · " + (if (mode == MicMode.Recognition) "음성 인식 모드" else "통화 모드")

    private val lock = Any()
    private val pcm = ByteArrayOutputStream()
    private var sink: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var readSide: ParcelFileDescriptor? = null
    private val lastSoundAt = AtomicLong(SystemClock.elapsedRealtime())
    private var record: AudioRecord? = null
    private val thread: Thread

    init {
        val rec = open(device, mode)
        record = rec
        rec.startRecording()
        thread = Thread({ pump(rec) }, "Microphone").apply { start() }
    }

    @SuppressLint("MissingPermission") // 권한은 화면에서 요청한다
    private fun open(device: AudioDeviceInfo?, mode: MicMode): AudioRecord {
        val rec = AudioRecord.Builder()
            .setAudioSource(mode.audioSource)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Microphone.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(MIN_BUFFER * 4)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("마이크를 열 수 없어요")
        }
        device?.let { rec.preferredDevice = it }
        return rec
    }

    private fun pump(rec: AudioRecord) {
        val buf = ByteArray(MIN_BUFFER)
        while (true) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) break
            val out = synchronized(lock) { pcm.write(buf, 0, n); sink }
            if (rms(buf, n) > soundThresholdRms) noteSound()
            // 받는 쪽이 먼저 닫아도 녹음(저장용)은 계속한다.
            try { out?.write(buf, 0, n) } catch (_: IOException) { synchronized(lock) { sink = null } }
        }
    }

    /**
     * 녹음되는 소리를 처음부터 그대로 읽을 수 있는 스트림 (16 kHz mono PCM16). 녹음이 끝나면 EOF가 온다.
     * 한 번만, 녹음을 시작하자마자 부른다. 읽는 쪽이 없으면 버퍼가 차서 녹음이 밀린다.
     */
    fun stream(): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        val out = ParcelFileDescriptor.AutoCloseOutputStream(write)
        synchronized(lock) {
            check(readSide == null) { "stream()은 한 번만 부를 수 있어요" }
            out.write(pcm.toByteArray()) // 그사이 녹음된 앞부분
            sink = out
            readSide = read
        }
        return read
    }

    /** 소리 말고 다른 근거(인식기의 중간 결과 등)로 아직 말하는 중임을 알린다. */
    fun noteSound() = lastSoundAt.set(SystemClock.elapsedRealtime())

    /** [timeoutMs] 동안 소리가 없을 때까지 기다린다 = 말이 끝났다. */
    suspend fun awaitSilence(timeoutMs: Long) {
        while (SystemClock.elapsedRealtime() - lastSoundAt.get() < timeoutMs) delay(100)
    }

    /** 녹음을 멈춘다 ([stream]에는 EOF가 간다). 여러 번 불러도 된다. 그 뒤 [wav]로 전체 오디오를 꺼낸다. */
    fun finish() {
        val rec = record ?: return
        record = null
        try { rec.stop() } catch (_: IllegalStateException) {}
        thread.join(500)
        rec.release()
        val out = synchronized(lock) { sink.also { sink = null } }
        try { out?.close() } catch (_: IOException) {}
    }

    /** 지금까지 녹음된 전체 오디오 (WAV). */
    fun wav(): ByteArray = Wav.encode(synchronized(lock) { pcm.toByteArray() }, Microphone.SAMPLE_RATE)

    internal fun close() {
        finish()
        try { readSide?.close() } catch (_: IOException) {}
    }

    private fun rms(buf: ByteArray, n: Int): Int {
        val count = n / 2
        if (count == 0) return 0
        var sum = 0.0
        for (i in 0 until count) {
            val s = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
        }
        return kotlin.math.sqrt(sum / count).toInt()
    }

    private companion object {
        fun connection(type: Int) = when (type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "LE Audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "블루투스 통화 연결"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "유선"
            else -> "종류 $type"
        }

        val MIN_BUFFER = AudioRecord.getMinBufferSize(Microphone.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    }
}

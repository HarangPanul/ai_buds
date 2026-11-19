package com.example.aibuds.platform.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.aibuds.core.Listener
import com.example.aibuds.core.Utterance
import com.example.aibuds.platform.audio.MicMode
import com.example.aibuds.platform.audio.Microphone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 폰의 기본 음성 인식 서비스(삼성/구글)로 받아 적는다. 턴마다 새로 만든다.
 * 인식기가 마이크를 직접 열게 두지 않고, [Microphone]으로 녹음한 소리를 넘겨준다 (Android 13+).
 * 언제 말이 끝났는지도 인식기가 아니라 마이크의 무음 판단을 따른다.
 */
class PhoneListener(
    private val context: Context,
    private val microphone: Microphone,
    private val config: Config = Config(),
    private val log: (String) -> Unit = {},
) : Listener {

    data class Config(
        val locale: String = "ko-KR",
        val micMode: MicMode = MicMode.Communication,
        /** 이 시간 동안 소리가 없으면 듣기를 끝낸다. */
        val silenceTimeoutMs: Long = 3000,
        /** 입력을 끝낸 뒤 인식기가 이 시간 안에 결과를 안 주면 중간 결과로 마무리한다 (인식기가 응답 없이 멈추는 경우 대비). */
        val resultTimeoutMs: Long = 5000,
    )

    // SpeechRecognizer는 메인 스레드에서만 쓸 수 있다.
    override suspend fun listen(onPartial: (String) -> Unit): Utterance = withContext(Dispatchers.Main.immediate) {
        check(SpeechRecognizer.isRecognitionAvailable(context)) { "음성 인식 서비스가 없어요" }
        microphone.use(config.micMode) { recording ->
            val result = CompletableDeferred<String>()
            var lastPartial = ""
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            try {
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(b: Bundle?) {
                        result.complete(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: lastPartial)
                    }
                    override fun onPartialResults(b: Bundle?) {
                        // 구글/삼성 인식기는 확정된 앞부분을 RESULTS_RECOGNITION에, 아직 확정 안 된 끝부분을 UNSTABLE_TEXT에 따로 담아 보낸다.
                        val stable = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        val unstable = b?.getStringArrayList(UNSTABLE_TEXT)?.firstOrNull().orEmpty()
                        val text = (stable + unstable).trim()
                        if (text.isNotEmpty() && text != lastPartial) {
                            lastPartial = text
                            recording.noteSound() // 글자가 늘고 있으면 아직 말하는 중이다
                            onPartial(text)
                        }
                    }
                    override fun onError(error: Int) {
                        log("recognizer: error $error")
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> result.complete(lastPartial)
                            else -> result.completeExceptionally(IllegalStateException("음성 인식 오류 ($error)"))
                        }
                    }
                    override fun onReadyForSpeech(p: Bundle?) { log("recognizer: ready") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(t: Int, p: Bundle?) {}
                })
                recognizer.startListening(intent(recording.stream()))

                val text = coroutineScope {
                    val endOfSpeech = launch {
                        recording.awaitSilence(config.silenceTimeoutMs)
                        recording.finish() // 인식기에 EOF가 가서 마무리하게 된다
                        recognizer.stopListening()
                        delay(config.resultTimeoutMs)
                        if (result.complete(lastPartial)) log("recognizer: no result in ${config.resultTimeoutMs}ms")
                    }
                    try {
                        result.await()
                    } finally {
                        endOfSpeech.cancel()
                    }
                }
                recording.finish()
                Utterance(text = text, audio = recording.wav(), mic = recording.mic, micMode = recording.mode.id, engine = "phone")
            } finally {
                recognizer.destroy()
            }
        }
    }

    private fun intent(audio: ParcelFileDescriptor) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.locale)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // 인식기가 말 중간의 쉼에서 먼저 끝내지 않도록 무음 허용 시간을 늘려 요청한다 (일부 인식기는 무시한다).
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, config.silenceTimeoutMs)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, config.silenceTimeoutMs)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Microphone.SAMPLE_RATE)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)

    private companion object {
        const val UNSTABLE_TEXT = "android.speech.extra.UNSTABLE_TEXT"
    }
}

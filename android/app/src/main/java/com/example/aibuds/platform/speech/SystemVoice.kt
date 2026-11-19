package com.example.aibuds.platform.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.aibuds.core.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Android 내장 TTS. 문장마다 한국어/영어를 판별해 로케일을 바꾼다. 엔진 준비가 느려서 앱이 사는 동안 하나를 계속 쓴다. */
class SystemVoice(context: Context) : Voice {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, () -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.invoke() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.invoke() } }
        })
    }

    override suspend fun speak(sentence: String) {
        check(ready.await()) { "TTS 엔진을 시작하지 못했어요" }
        tts.language = if (isMostlyHangul(sentence)) Locale.KOREAN else Locale.US
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            pending[id] = { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation {
                pending.remove(id)
                tts.stop()
            }
            if (tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                pending.remove(id)
                cont.resume(Unit)
            }
        }
    }

    companion object {
        /** 한글 글자 비율이 높으면 한국어로 읽는다. */
        fun isMostlyHangul(s: String): Boolean {
            val letters = s.count { it.isLetter() }
            if (letters == 0) return true
            return s.count { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' } * 3 >= letters
        }
    }
}

package com.example.aibuds.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 듣기 → LLM → 말하기로 이루어진 턴.
 * 턴마다 새로 만든다: 어떤 [Listener]/[Brain]을 쓸지와 [Config]는 턴을 시작할 때의 설정으로 정해지고, 도중에 바뀌지 않는다.
 */
class PipelineTurn(
    private val listener: Listener,
    private val brain: Brain,
    private val voice: Voice,
    private val cues: Cues,
    private val config: Config = Config(),
    private val newSplitter: () -> SentenceSplitter = { SentenceSplitter() },
) : TurnEngine {

    data class Config(
        val systemPrompt: String = "",
        /** true면 모델의 thinking을 실제 답변이 시작될 때까지 소리 내어 읽는다. */
        val speakThinking: Boolean = false,
        /** LLM에 보낼 최근 메시지 수. */
        val historyMessages: Int = 20,
    )

    override suspend fun run(history: History, emit: suspend (TurnEvent) -> Unit) {
        // 시작음이 인식에 섞이지 않게, 다 울린 뒤에 듣는다.
        cues.play(Cue.ListenStart)
        emit(TurnEvent.Listening)
        val heard = listen(emit)
        val text = heard.text.trim()
        if (text.isEmpty()) {
            emit(TurnEvent.NothingHeard(heard))
            cues.play(Cue.NothingHeard)
            return
        }
        emit(TurnEvent.Heard(heard))
        history.add(Message(Role.User, text))
        coroutineScope {
            launch { cues.play(Cue.Heard) } // 기다리지 않고 바로 LLM으로 넘어간다
            reply(history, emit)
        }
    }

    private suspend fun listen(emit: suspend (TurnEvent) -> Unit): Utterance = coroutineScope {
        // 인식기 콜백은 suspend할 수 없으므로 채널로 받아 넘긴다. 밀리면 최신 것만 남긴다.
        val partials = Channel<String>(Channel.CONFLATED)
        val forward = launch { for (p in partials) emit(TurnEvent.Partial(p)) }
        try {
            listener.listen { partials.trySend(it) }
        } finally {
            forward.cancel()
        }
    }

    private suspend fun reply(history: History, emit: suspend (TurnEvent) -> Unit) {
        emit(TurnEvent.Thinking)
        val reply = StringBuilder()
        try {
            coroutineScope {
                val thoughts = Channel<String>(Channel.UNLIMITED)
                val thoughtSplitter = newSplitter()
                val thinkAloud: Job? = if (!config.speakThinking) null else launch {
                    for (s in thoughts) {
                        emit(TurnEvent.Speaking(s, thinking = true))
                        voice.speak(s)
                    }
                }
                var thoughtAloud = false

                val sentences = Channel<String>(Channel.UNLIMITED)
                val splitter = newSplitter()
                var speaking: Job? = null // 답변의 첫 조각이 오면 시작한다

                brain.chat(history.messages(config.systemPrompt, config.historyMessages)).collect { chunk ->
                    when (chunk) {
                        is BrainChunk.Reasoning -> {
                            // 답변이 시작된 뒤에 온 thinking은 버린다.
                            if (thinkAloud != null && speaking == null) {
                                thoughtAloud = true
                                thoughtSplitter.feed(chunk.text).forEach { thoughts.trySend(it) }
                            }
                        }
                        is BrainChunk.Content -> {
                            if (speaking == null) {
                                // thinking 읽기를 문장 중간이라도 끊고, 신호음으로 "여기부터 답변"을 알린다.
                                thinkAloud?.cancelAndJoin()
                                if (thoughtAloud) cues.play(Cue.ReplyStart)
                                speaking = launch {
                                    for (s in sentences) {
                                        emit(TurnEvent.Speaking(s))
                                        voice.speak(s)
                                    }
                                }
                            }
                            reply.append(chunk.text)
                            emit(TurnEvent.ReplyDelta(chunk.text))
                            splitter.feed(chunk.text).forEach { sentences.trySend(it) }
                        }
                    }
                }
                thinkAloud?.cancelAndJoin()
                splitter.flush()?.let { sentences.trySend(it) }
                sentences.close() // 남은 문장을 다 읽으면 speaking이 끝나고, coroutineScope가 그걸 기다린다
            }
        } finally {
            // 중단되거나 실패해도 그때까지 받은 답변은 대화 내용에 남긴다.
            val said = reply.toString().trim()
            if (said.isNotEmpty()) history.add(Message(Role.Assistant, said))
        }
    }
}

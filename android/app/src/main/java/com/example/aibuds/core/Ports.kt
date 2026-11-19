package com.example.aibuds.core

import kotlinx.coroutines.flow.Flow

/*
 * core가 바깥(platform)에 요구하는 것들.
 * 공통 규칙: suspend 함수는 코루틴이 취소되면 하던 일(녹음, 재생, HTTP)을 즉시 멈추고 자원을 정리해야 한다.
 * 따로 stop()/cancel()을 두지 않는다.
 */

/** 사용자의 말 한 번. */
class Utterance(
    /** 인식된 글. 못 알아들었으면 빈 문자열. */
    val text: String,
    /** 그 말의 오디오(WAV). 엔진이 내주지 않으면 null. */
    val audio: ByteArray? = null,
    /** 어떤 마이크로 들었는지 (화면·기록용). */
    val mic: String = "",
    /** 마이크 입력 모드 (학습용 저장 시 구분용). 예: "communication", "recognition". */
    val micMode: String = "",
    /** 어떤 엔진이 받아 적었는지 (학습용 저장 시 구분용). 예: "phone", "server". */
    val engine: String = "",
)

/** 듣기: 마이크를 열고, 말이 끝나면(일정 시간 무음) 스스로 마치고 결과를 돌려준다. */
fun interface Listener {
    /** [onPartial]은 말하는 도중의 중간 인식 결과. 아무 스레드에서나 불러도 된다. */
    suspend fun listen(onPartial: (String) -> Unit): Utterance
}

/** LLM이 흘려보내는 조각. 나중에 도구 요청 같은 종류가 추가될 수 있다. */
sealed interface BrainChunk {
    /** 모델의 thinking. 화면·대화 내용에는 넣지 않고, 설정에 따라 읽어 주기만 한다. */
    data class Reasoning(val text: String) : BrainChunk
    /** 실제 답변. */
    data class Content(val text: String) : BrainChunk
}

/** 생각하기: 대화 내용을 주면 답이 조각으로 흘러나온다. 수집이 취소되면 연결을 닫아야 한다. */
fun interface Brain {
    fun chat(messages: List<Message>): Flow<BrainChunk>
}

/** 말하기: 한 문장을 다 읽을 때까지 suspend. */
fun interface Voice {
    suspend fun speak(sentence: String)
}

enum class Cue {
    /** 이제 말해도 된다. */
    ListenStart,
    /** 말을 알아들었다. */
    Heard,
    /** 아무 말도 못 들었다. */
    NothingHeard,
    /** thinking 읽기를 끝내고 실제 답변을 시작한다. */
    ReplyStart,
    /** 턴이 실패했다 (화면을 안 보고 있어도 알 수 있게). */
    Error,
}

/** 신호음: 다 울릴 때까지 suspend. 실패해도 예외를 던지지 않는다. */
fun interface Cues {
    suspend fun play(cue: Cue)
}

/**
 * 한 턴을 실행하는 방법. 지금은 듣기 → LLM → 말하기인 [PipelineTurn] 하나지만,
 * 음성을 바로 음성으로 바꾸는 모델처럼 단계가 다른 방식도 이 인터페이스로 끼울 수 있다.
 *
 * 진행 상황은 [emit]으로 알린다. 시작([TurnEvent.Triggered])과 끝(Done/Failed/Cancelled)은 [Conversation]이 알리므로 내보내지 않는다.
 * 실패는 예외로 던진다.
 */
fun interface TurnEngine {
    suspend fun run(history: History, emit: suspend (TurnEvent) -> Unit)
}

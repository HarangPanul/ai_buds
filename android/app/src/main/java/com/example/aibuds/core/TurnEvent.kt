package com.example.aibuds.core

/** 턴이 진행되며 내보내는 일들. 화면, 동작 기록, 학습용 녹음 저장 등이 [Conversation.events]로 구독한다. */
sealed interface TurnEvent {
    /** 트리거를 받아 턴을 시작한다. */
    data object Triggered : TurnEvent
    /** 시작음이 끝나고 듣기 시작. */
    data object Listening : TurnEvent
    /** 말하는 도중의 중간 인식 결과. */
    data class Partial(val text: String) : TurnEvent
    data class Heard(val utterance: Utterance) : TurnEvent
    /** 아무 말도 인식되지 않았다. 오디오는 있을 수 있다 (나중에 직접 받아 적어 학습에 쓸 수 있다). */
    data class NothingHeard(val utterance: Utterance) : TurnEvent
    /** LLM에 요청을 보냈다. */
    data object Thinking : TurnEvent
    /** 답변 글자가 더 도착했다. */
    data class ReplyDelta(val text: String) : TurnEvent
    /** 이 문장을 읽기 시작한다. [thinking]이면 답변이 아니라 모델의 thinking. */
    data class Speaking(val sentence: String, val thinking: Boolean = false) : TurnEvent
    /** 턴이 정상적으로 끝났다 (못 들은 경우 포함). */
    data object Done : TurnEvent
    data class Failed(val message: String) : TurnEvent
    /** 새 트리거나 취소로 중단됐다. */
    data object Cancelled : TurnEvent
}

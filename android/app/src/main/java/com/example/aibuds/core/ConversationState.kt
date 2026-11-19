package com.example.aibuds.core

enum class Phase { Idle, Listening, Thinking, Speaking }

/** 화면이 그릴 현재 상태. 이벤트를 차례로 [after]에 넣어 만든다. */
data class ConversationState(
    val phase: Phase = Phase.Idle,
    /** 끝난 말들. */
    val messages: List<Message> = emptyList(),
    /** 듣는 중의 중간 인식 결과. */
    val partial: String = "",
    /** 지금 도착하고 있는 답변. 턴이 끝나면 [messages]로 옮겨진다. */
    val reply: String = "",
    /** 직전 턴이 실패했으면 그 이유. 다음 턴이 시작되면 지워진다. */
    val error: String? = null,
    /** 마지막으로 들을 때 쓴 마이크 ([Utterance.mic]). */
    val mic: String = "",
) {
    fun after(event: TurnEvent): ConversationState = when (event) {
        TurnEvent.Triggered -> copy(phase = Phase.Listening, partial = "", error = null)
        TurnEvent.Listening -> this
        is TurnEvent.Partial -> copy(partial = event.text)
        is TurnEvent.Heard -> copy(messages = messages + Message(Role.User, event.utterance.text.trim()), partial = "", mic = event.utterance.mic)
        is TurnEvent.NothingHeard -> copy(partial = "", mic = event.utterance.mic)
        TurnEvent.Thinking -> copy(phase = Phase.Thinking)
        is TurnEvent.ReplyDelta -> copy(reply = reply + event.text)
        is TurnEvent.Speaking -> if (event.thinking) this else copy(phase = Phase.Speaking)
        TurnEvent.Done, TurnEvent.Cancelled -> ended()
        is TurnEvent.Failed -> ended().copy(error = event.message)
    }

    private fun ended(): ConversationState {
        val said = reply.trim()
        return copy(
            phase = Phase.Idle,
            messages = if (said.isEmpty()) messages else messages + Message(Role.Assistant, said),
            partial = "",
            reply = "",
        )
    }
}

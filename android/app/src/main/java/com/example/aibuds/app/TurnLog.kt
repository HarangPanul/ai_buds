package com.example.aibuds.app

import com.example.aibuds.core.TurnEvent

/** 턴 이벤트를 동작 기록 한 줄로 바꾼다. 기록하지 않을 이벤트(너무 잦은 것)는 null. 말한 내용은 남기지 않고 길이만 남긴다. */
internal fun logLine(event: TurnEvent): String? = when (event) {
    TurnEvent.Triggered -> "turn: trigger"
    TurnEvent.Listening -> "turn: listening"
    is TurnEvent.Partial -> null
    is TurnEvent.Heard -> "turn: heard ${event.utterance.text.trim().length} chars · ${event.utterance.mic}"
    is TurnEvent.NothingHeard -> "turn: nothing heard · ${event.utterance.mic}"
    TurnEvent.Thinking -> "turn: thinking"
    is TurnEvent.ReplyDelta -> null
    is TurnEvent.Speaking -> "turn: speak ${event.sentence.length} chars" + if (event.thinking) " (thinking)" else ""
    TurnEvent.Done -> "turn: done"
    is TurnEvent.Failed -> "turn: failed: ${event.message}"
    TurnEvent.Cancelled -> "turn: cancelled"
}

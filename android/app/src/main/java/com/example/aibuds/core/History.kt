package com.example.aibuds.core

/** 대화 내용. 앱 프로세스가 사는 동안, 또는 새 대화를 시작할 때까지 유지된다. */
class History {
    private val items = mutableListOf<Message>()

    @Synchronized fun add(message: Message) { items += message }
    @Synchronized fun clear() { items.clear() }

    /** LLM에 보낼 메시지: 시스템 프롬프트(있으면) + 최근 [maxMessages]개. */
    @Synchronized
    fun messages(systemPrompt: String, maxMessages: Int): List<Message> = buildList {
        if (systemPrompt.isNotBlank()) add(Message(Role.System, systemPrompt))
        addAll(items.takeLast(maxMessages))
    }
}

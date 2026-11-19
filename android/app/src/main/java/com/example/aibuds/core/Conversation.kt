package com.example.aibuds.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 대화의 입구. 트리거(볼륨 제스처, 화면 버튼)는 모두 여기로 들어온다.
 * 한 번에 한 턴만 돈다. 새 트리거가 오면 돌던 턴을 중단하고 새로 시작한다.
 *
 * [trigger]/[cancel]/[newConversation]은 한 스레드(메인)에서만 부른다.
 *
 * @param newTurn 턴을 시작할 때마다 불린다. 그 순간의 설정으로 [TurnEngine]을 만들어 돌려준다.
 */
class Conversation(
    private val scope: CoroutineScope,
    private val cues: Cues,
    private val newTurn: () -> TurnEngine,
) {
    private val history = History()
    private var job: Job? = null

    /** 앞 턴의 정리(마이크·재생 해제, Cancelled 알림)가 끝난 뒤에야 다음 턴이 시작되게 한다. */
    private val oneAtATime = Mutex()

    private val _events = MutableSharedFlow<TurnEvent>(extraBufferCapacity = 64)
    /** 구독자가 밀리면 턴이 기다린다. 이벤트를 받은 자리에서 오래 걸리는 일을 하지 말 것. */
    val events: SharedFlow<TurnEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    /** 새 턴을 시작한다. 돌던 턴이 있으면 중단한다 (끼어들기). */
    fun trigger() = replaceJob {
        publish(TurnEvent.Triggered)
        try {
            newTurn().run(history, ::publish)
            publish(TurnEvent.Done)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { publish(TurnEvent.Cancelled) }
            throw e
        } catch (e: Exception) {
            publish(TurnEvent.Failed(e.message ?: e.javaClass.simpleName))
            cues.play(Cue.Error)
        }
    }

    /** 돌던 턴을 중단한다. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /** 돌던 턴을 중단하고 대화 내용을 비운다. */
    fun newConversation() = replaceJob {
        history.clear()
        _state.value = ConversationState()
    }

    private fun replaceJob(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { oneAtATime.withLock { block() } }
    }

    private suspend fun publish(event: TurnEvent) {
        _state.update { it.after(event) }
        _events.emit(event)
    }
}

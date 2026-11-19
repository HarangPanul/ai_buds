package com.example.aibuds.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationTest {

    // ---- 가짜 구현 ----

    private class FakeListener(vararg texts: String) : Listener {
        private val queue = ArrayDeque(texts.toList())
        var calls = 0
        var partials: List<String> = emptyList()
        /** 설정하면 중간 결과를 보낸 뒤 이게 완료될 때까지 듣는다. */
        var gate: CompletableDeferred<Unit>? = null
        var error: Exception? = null
        val log = mutableListOf<String>()

        override suspend fun listen(onPartial: (String) -> Unit): Utterance {
            calls++
            log += "open"
            try {
                error?.let { throw it }
                partials.forEach(onPartial)
                gate?.await()
                return Utterance(queue.removeFirst(), audio = byteArrayOf(1), mic = "fake", engine = "fake")
            } finally {
                log += "close"
            }
        }
    }

    /** 조각 사이에 가상 시간 10ms를 둬서, 그 사이에 말하기가 진행될 수 있게 한다. */
    private class FakeBrain(private vararg val chunks: BrainChunk, private val failAfter: Boolean = false) : Brain {
        val requests = mutableListOf<List<Message>>()
        override fun chat(messages: List<Message>): Flow<BrainChunk> {
            requests += messages
            return flow {
                chunks.forEach { emit(it); delay(10) }
                if (failAfter) error("서버 끊김")
            }
        }
    }

    private class FakeVoice : Voice {
        val log = mutableListOf<String>()
        /** 이 글자가 든 문장은 취소될 때까지 끝나지 않는다. */
        var hangOn: String? = null
        val spoken get() = log.filter { it.startsWith("end:") }.map { it.removePrefix("end:") }

        override suspend fun speak(sentence: String) {
            log += "start:$sentence"
            try {
                if (hangOn?.let { it in sentence } == true) awaitCancellation()
                log += "end:$sentence"
            } catch (e: kotlinx.coroutines.CancellationException) {
                log += "cancel:$sentence"
                throw e
            }
        }
    }

    private class FakeCues : Cues {
        val played = mutableListOf<Cue>()
        override suspend fun play(cue: Cue) { played += cue }
    }

    private fun c(text: String) = BrainChunk.Content(text)
    private fun r(text: String) = BrainChunk.Reasoning(text)

    private class Rig(scope: TestScope, val listener: FakeListener, var brain: FakeBrain, var config: PipelineTurn.Config) {
        val voice = FakeVoice()
        val cues = FakeCues()
        val events = mutableListOf<TurnEvent>()
        // backgroundScope의 일은 advanceUntilIdle이 끝까지 돌려 주지 않아서, 같은 가상 시계를 쓰는 별도 scope를 쓴다.
        val conversation = Conversation(CoroutineScope(StandardTestDispatcher(scope.testScheduler)), cues) {
            PipelineTurn(listener, brain, voice, cues, config)
        }
        val state get() = conversation.state.value
        val kinds get() = events.map { it::class.simpleName }

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { conversation.events.toList(events) }
        }
    }

    private fun TestScope.rig(
        listener: FakeListener = FakeListener("안녕"),
        brain: FakeBrain = FakeBrain(BrainChunk.Content("반갑습니다. "), BrainChunk.Content("무엇을 도와드릴까요?")),
        config: PipelineTurn.Config = PipelineTurn.Config(systemPrompt = "SYS"),
    ) = Rig(this, listener, brain, config)

    // ---- 테스트 ----

    @Test fun fullTurn() = runTest {
        val rig = rig()
        rig.conversation.trigger(); advanceUntilIdle()

        assertEquals(
            listOf("Triggered", "Listening", "Heard", "Thinking", "ReplyDelta", "Speaking", "ReplyDelta", "Speaking", "Done"),
            rig.kinds,
        )
        assertEquals(listOf("반갑습니다.", "무엇을 도와드릴까요?"), rig.voice.spoken)
        assertEquals(listOf(Cue.ListenStart, Cue.Heard), rig.cues.played)
        assertEquals(listOf(Message(Role.System, "SYS"), Message(Role.User, "안녕")), rig.brain.requests.single())
        assertEquals(
            ConversationState(messages = listOf(Message(Role.User, "안녕"), Message(Role.Assistant, "반갑습니다. 무엇을 도와드릴까요?")), mic = "fake"),
            rig.state,
        )
    }

    @Test fun phasesFollowTheTurn() = runTest {
        val listening = CompletableDeferred<Unit>()
        val rig = rig(listener = FakeListener("안녕").apply { gate = listening; partials = listOf("안") })
        rig.voice.hangOn = "반갑"
        rig.conversation.trigger(); runCurrent()
        assertEquals(Phase.Listening, rig.state.phase)
        assertEquals("안", rig.state.partial)

        listening.complete(Unit); advanceUntilIdle()
        assertEquals(Phase.Speaking, rig.state.phase)
        assertEquals("", rig.state.partial)
        assertEquals("반갑습니다. 무엇을 도와드릴까요?", rig.state.reply)
        assertEquals(listOf(Message(Role.User, "안녕")), rig.state.messages)
    }

    @Test fun nothingHeardSkipsBrain() = runTest {
        val rig = rig(listener = FakeListener("  "))
        rig.conversation.trigger(); advanceUntilIdle()

        assertEquals(listOf("Triggered", "Listening", "NothingHeard", "Done"), rig.kinds)
        assertEquals(listOf(Cue.ListenStart, Cue.NothingHeard), rig.cues.played)
        assertEquals(0, rig.brain.requests.size)
        // 못 들은 녹음도 구독자가 저장할 수 있게 오디오가 실려 온다.
        assertEquals(1, (rig.events[2] as TurnEvent.NothingHeard).utterance.audio?.size)
        assertEquals(ConversationState(mic = "fake"), rig.state)
    }

    @Test fun listenFailurePlaysErrorCue() = runTest {
        val rig = rig(listener = FakeListener("x").apply { error = IllegalStateException("마이크를 못 열었어요") })
        rig.conversation.trigger(); advanceUntilIdle()

        assertEquals(listOf("Triggered", "Listening", "Failed"), rig.kinds)
        assertEquals(listOf(Cue.ListenStart, Cue.Error), rig.cues.played)
        assertEquals(Phase.Idle, rig.state.phase)
        assertEquals("마이크를 못 열었어요", rig.state.error)
    }

    @Test fun brainFailureKeepsPartialReply() = runTest {
        val rig = rig(listener = FakeListener("하나", "둘"), brain = FakeBrain(c("여기까지는 "), c("받았어요"), failAfter = true))
        rig.conversation.trigger(); advanceUntilIdle()

        assertEquals("Failed", rig.kinds.last())
        assertEquals("서버 끊김", rig.state.error)
        assertEquals(Message(Role.Assistant, "여기까지는 받았어요"), rig.state.messages.last())

        // 다음 턴: 오류 표시는 지워지고, 받다 만 답변도 LLM에 보내는 대화 내용에 들어 있다.
        rig.brain = FakeBrain(c("네."))
        rig.conversation.trigger(); runCurrent()
        assertNull(rig.state.error)
        advanceUntilIdle()
        assertEquals(listOf("SYS", "하나", "여기까지는 받았어요", "둘"), rig.brain.requests.single().map { it.text })
    }

    @Test fun historyCarriesOverAndIsLimited() = runTest {
        val rig = rig(listener = FakeListener("하나", "둘", "셋"), brain = FakeBrain(c("네, 알겠습니다.")))
        rig.conversation.trigger(); advanceUntilIdle()
        rig.conversation.trigger(); advanceUntilIdle()
        assertEquals(listOf("SYS", "하나", "네, 알겠습니다.", "둘"), rig.brain.requests[1].map { it.text })

        rig.config = rig.config.copy(historyMessages = 2, systemPrompt = "")
        rig.conversation.trigger(); advanceUntilIdle()
        assertEquals(listOf("네, 알겠습니다.", "셋"), rig.brain.requests[2].map { it.text })
    }

    @Test fun triggerWhileSpeakingInterrupts() = runTest {
        val rig = rig(listener = FakeListener("첫째", "둘째"))
        rig.voice.hangOn = "반갑"
        rig.conversation.trigger(); advanceUntilIdle()
        assertEquals(Phase.Speaking, rig.state.phase)

        rig.voice.hangOn = null
        rig.conversation.trigger(); advanceUntilIdle()

        assertEquals(listOf("start:반갑습니다.", "cancel:반갑습니다."), rig.voice.log.take(2))
        // 앞 턴이 완전히 끝났다고 알린 뒤에 새 턴이 시작된다.
        val cancelled = rig.kinds.indexOf("Cancelled")
        assertEquals("Triggered", rig.kinds[cancelled + 1])
        assertEquals("Done", rig.kinds.last())
        // 끊긴 답변도 대화 내용에 남아 다음 요청에 들어간다.
        assertEquals(listOf("SYS", "첫째", "반갑습니다. 무엇을 도와드릴까요?", "둘째"), rig.brain.requests[1].map { it.text })
    }

    @Test fun rapidTriggersListenOnce() = runTest {
        val rig = rig()
        rig.conversation.trigger(); rig.conversation.trigger(); rig.conversation.trigger()
        advanceUntilIdle()
        assertEquals(1, rig.listener.calls)
        assertEquals(listOf("Triggered", "Listening", "Heard", "Thinking", "ReplyDelta", "Speaking", "ReplyDelta", "Speaking", "Done"), rig.kinds)
    }

    @Test fun triggerWhileListeningClosesMicBeforeReopening() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = rig(listener = FakeListener("둘째").apply { this.gate = gate })
        rig.conversation.trigger(); runCurrent()
        rig.conversation.trigger(); runCurrent()
        assertEquals(listOf("open", "close", "open"), rig.listener.log)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("Done", rig.kinds.last())
    }

    @Test fun cancelStopsTheTurn() = runTest {
        val rig = rig()
        rig.voice.hangOn = "반갑"
        rig.conversation.trigger(); advanceUntilIdle()
        rig.conversation.cancel(); advanceUntilIdle()

        assertEquals("Cancelled", rig.kinds.last())
        assertEquals(Phase.Idle, rig.state.phase)
        assertEquals("cancel:반갑습니다.", rig.voice.log.last())
    }

    @Test fun newConversationClearsEverything() = runTest {
        val rig = rig(listener = FakeListener("하나", "둘"))
        rig.voice.hangOn = "반갑"
        rig.conversation.trigger(); advanceUntilIdle()
        rig.conversation.newConversation(); advanceUntilIdle()
        assertEquals(ConversationState(), rig.state)

        rig.voice.hangOn = null
        rig.conversation.trigger(); advanceUntilIdle()
        assertEquals(listOf("SYS", "둘"), rig.brain.requests[1].map { it.text })
    }

    @Test fun thinkingIsReadAloudUntilTheReplyStarts() = runTest {
        val rig = rig(
            brain = FakeBrain(r("질문을 살펴보는 중입니다. "), r("거의 다 됐습니다. "), c("답은 사십이입니다."), r("뒤늦은 생각입니다. 버려져야 합니다. ")),
            config = PipelineTurn.Config(speakThinking = true),
        )
        rig.conversation.trigger(); advanceUntilIdle()

        assertEquals(listOf("질문을 살펴보는 중입니다.", "거의 다 됐습니다.", "답은 사십이입니다."), rig.voice.spoken)
        assertEquals(listOf(Cue.ListenStart, Cue.Heard, Cue.ReplyStart), rig.cues.played)
        assertEquals(
            listOf(true, true, false),
            rig.events.filterIsInstance<TurnEvent.Speaking>().map { it.thinking },
        )
        // thinking은 화면에도 대화 내용에도 들어가지 않는다.
        assertEquals(Message(Role.Assistant, "답은 사십이입니다."), rig.state.messages.last())
    }

    @Test fun replyInterruptsThinkingSpeech() = runTest {
        val rig = rig(
            brain = FakeBrain(r("아주 길게 생각하는 중입니다. "), c("답입니다, 여기요.")),
            config = PipelineTurn.Config(speakThinking = true),
        )
        rig.voice.hangOn = "생각"
        rig.conversation.trigger(); advanceUntilIdle()

        assertEquals(
            listOf("start:아주 길게 생각하는 중입니다.", "cancel:아주 길게 생각하는 중입니다.", "start:답입니다, 여기요.", "end:답입니다, 여기요."),
            rig.voice.log,
        )
        assertEquals("Done", rig.kinds.last())
    }

    @Test fun thinkingIsSilentWhenOff() = runTest {
        val rig = rig(brain = FakeBrain(r("생각하는 중입니다. "), c("답입니다, 여기요.")))
        rig.conversation.trigger(); advanceUntilIdle()

        assertEquals(listOf("답입니다, 여기요."), rig.voice.spoken)
        assertEquals(listOf(Cue.ListenStart, Cue.Heard), rig.cues.played)
    }

    @Test fun onlyThinkingNoReplyStillEnds() = runTest {
        val rig = rig(brain = FakeBrain(r("생각만 하다가 끝났습니다. ")), config = PipelineTurn.Config(speakThinking = true))
        rig.voice.hangOn = "생각"
        rig.conversation.trigger(); advanceUntilIdle()

        assertEquals("Done", rig.kinds.last())
        assertEquals(listOf(Message(Role.User, "안녕")), rig.state.messages)
    }
}

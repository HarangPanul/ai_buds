package com.example.aibuds.app

import android.content.Context
import android.os.SystemClock
import com.example.aibuds.core.Conversation
import com.example.aibuds.core.PipelineTurn
import com.example.aibuds.core.PromptClock
import com.example.aibuds.core.TurnEngine
import com.example.aibuds.core.TurnEvent
import com.example.aibuds.core.VolumeTrigger
import com.example.aibuds.platform.audio.AudioRoute
import com.example.aibuds.platform.audio.Beeper
import com.example.aibuds.platform.audio.MicMode
import com.example.aibuds.platform.audio.Microphone
import com.example.aibuds.platform.llm.Dialect
import com.example.aibuds.platform.llm.LlamaModels
import com.example.aibuds.platform.llm.OpenAiBrain
import com.example.aibuds.platform.llm.ServerCheck
import com.example.aibuds.platform.settings.SettingsStore
import com.example.aibuds.platform.speech.PhoneListener
import com.example.aibuds.platform.speech.SystemVoice
import com.example.aibuds.platform.store.EventLog
import com.example.aibuds.platform.store.FinetuneDataset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 앱의 부품을 만들어 잇는 곳. 앱 프로세스가 사는 동안 유지된다. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 동작 기록. adb 없이 기기에서 무슨 일이 있었는지 보려고 파일에 남긴다 (설정 화면에서 보고 복사). */
    val log = EventLog(appContext.filesDir.resolve("events.log"))

    val settings = SettingsStore(appContext)

    private val route = AudioRoute(appContext)
    private val microphone = Microphone(route)
    private val voice = SystemVoice(appContext)
    /** 모델 목록 조회와 미리 올리기 (설정 화면용). 목록 조회는 OpenAI 호환 서버면 어디서나 된다. */
    val models = LlamaModels()
    val serverCheck = ServerCheck()
    private val cues = Beeper(log::log)

    val conversation = Conversation(scope, cues, ::newTurn)

    /** 볼륨 올림 → 내림. 화면이 켜져 있을 때의 키 입력(MainActivity)과 백그라운드의 볼륨 변경 방송(ConversationService)이 모두 여기로 온다. */
    val volumeTrigger = VolumeTrigger(
        now = SystemClock::elapsedRealtime,
        earphoneConnected = route::isEarphoneConnected,
        log = log::log,
        onTrigger = conversation::trigger,
    )

    private val dataset = FinetuneDataset(appContext)

    init {
        // 부가 기능은 턴 코드에 넣지 않고, 턴이 내보내는 이벤트를 받아서 한다.
        scope.launch { conversation.events.collect { event -> logLine(event)?.let(log::log) } }
        scope.launch { conversation.events.collect(::saveForFinetune) }
    }

    /** 학습용 녹음 저장: 알아들은 말과 못 들은 말 모두. 턴을 붙잡지 않게 따로 돌린다. */
    private fun saveForFinetune(event: TurnEvent) {
        val utterance = when (event) {
            is TurnEvent.Heard -> event.utterance
            is TurnEvent.NothingHeard -> event.utterance
            else -> return
        }
        if (settings.value.saveFinetune) scope.launch {
            log.log(if (dataset.save(utterance)) "finetune: saved" else "finetune: not saved")
        }
    }

    /** 턴을 시작할 때마다 그 순간의 설정으로 한 턴을 꾸린다. 턴 도중에 설정을 바꿔도 그 턴에는 영향이 없다. */
    private fun newTurn(): TurnEngine {
        val s = settings.value
        return PipelineTurn(
            listener = PhoneListener(appContext, microphone, PhoneListener.Config(micMode = MicMode.of(s.micSource)), log::log),
            brain = OpenAiBrain(
                OpenAiBrain.Config(baseUrl = s.llmBaseUrl, model = s.llmModel, temperature = s.temperature),
                Dialect.of(s.llmServer, models),
            ),
            voice = voice,
            cues = cues,
            config = PipelineTurn.Config(
                systemPrompt = if (s.includeDateTime) PromptClock.append(s.systemPrompt) else s.systemPrompt,
                speakThinking = s.speakThinking,
                historyMessages = s.historyTurns,
            ),
        )
    }
}

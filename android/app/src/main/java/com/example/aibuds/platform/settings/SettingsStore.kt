package com.example.aibuds.platform.settings

import android.content.Context
import android.content.SharedPreferences
import com.example.aibuds.platform.audio.MicMode
import com.example.aibuds.platform.llm.Dialect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val llmBaseUrl: String = "http://127.0.0.1:8080",
    /** LLM 서버 종류. [Dialect.LLAMA_CPP] 또는 [Dialect.OPENAI](ollama, vllm 등). */
    val llmServer: String = Dialect.LLAMA_CPP,
    /** 요청에 실을 모델 이름. 비우면 보내지 않는다. */
    val llmModel: String = "",
    val systemPrompt: String = "당신은 이어폰으로 대화하는 음성 비서입니다. 짧고 자연스러운 구어체로 답하세요. " +
        "마크다운, 목록, 이모지, URL은 쓰지 마세요. " +
        "사용자의 말은 음성을 전사한 텍스트라서, 발음이 비슷한 다른 단어로 잘못 적혀 있을 수 있습니다. " +
        "문맥상 어색한 단어가 있으면 발음이 비슷한 단어로 의도를 추측해서 답하세요.",
    /** true면 매 요청마다 시스템 프롬프트 끝에 현재 날짜/시각을 붙인다. */
    val includeDateTime: Boolean = true,
    val temperature: Float = 0.7f,
    /** LLM에 보낼 최근 메시지 수. */
    val historyTurns: Int = 20,
    /** true면 모델의 thinking을 실제 답변이 시작될 때까지 소리 내어 읽는다. */
    val speakThinking: Boolean = true,
    /** 마이크 입력 모드. [MicMode.id] 값: "communication"(통화용) 또는 "recognition"(음성 인식용). */
    val micSource: String = MicMode.Communication.id,
    /** true면 턴마다 (녹음, 받아 적은 글) 쌍을 학습용으로 저장한다. 못 들은 녹음도 저장한다. */
    val saveFinetune: Boolean = false,
    /** true면 메인 화면에 디버그용 정보(LLM 주소, 사용한 마이크)를 보여준다. */
    val showDebug: Boolean = false,
)

/**
 * 설정 저장소. 이전 앱과 같은 파일·같은 키를 써서, 덮어쓰기 설치하면 쓰던 값이 그대로 이어진다.
 * 새 설정을 추가할 때는 [Settings]에 필드, [load]와 [save]에 한 줄씩 넣는다.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load(prefs))
    val flow: StateFlow<Settings> = _flow.asStateFlow()
    val value: Settings get() = _flow.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_flow.value)
        save(prefs, next)
        _flow.value = next
    }

    private fun load(p: SharedPreferences): Settings {
        val d = Settings()
        return Settings(
            llmBaseUrl = p.getString("llmBaseUrl", null) ?: d.llmBaseUrl,
            llmServer = p.getString("llmServer", null) ?: d.llmServer,
            llmModel = p.getString("llmModel", null) ?: d.llmModel,
            systemPrompt = p.getString("systemPrompt", null) ?: d.systemPrompt,
            includeDateTime = p.getBoolean("includeDateTime", d.includeDateTime),
            temperature = p.getFloat("temperature", d.temperature),
            historyTurns = p.getInt("historyTurns", d.historyTurns),
            speakThinking = p.getBoolean("speakThinking", d.speakThinking),
            micSource = p.getString("micSource", null) ?: d.micSource,
            saveFinetune = p.getBoolean("saveFinetune", d.saveFinetune),
            showDebug = p.getBoolean("showDebug", d.showDebug),
        )
    }

    private fun save(p: SharedPreferences, s: Settings) {
        p.edit()
            .putString("llmBaseUrl", s.llmBaseUrl)
            .putString("llmServer", s.llmServer)
            .putString("llmModel", s.llmModel)
            .putString("systemPrompt", s.systemPrompt)
            .putBoolean("includeDateTime", s.includeDateTime)
            .putFloat("temperature", s.temperature)
            .putInt("historyTurns", s.historyTurns)
            .putBoolean("speakThinking", s.speakThinking)
            .putString("micSource", s.micSource)
            .putBoolean("saveFinetune", s.saveFinetune)
            .putBoolean("showDebug", s.showDebug)
            .apply()
    }
}

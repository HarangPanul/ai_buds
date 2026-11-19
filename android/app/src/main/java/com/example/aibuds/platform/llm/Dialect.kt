package com.example.aibuds.platform.llm

import org.json.JSONObject

/**
 * LLM 서버마다 다른 부분. OpenAI 호환 API의 공통 부분(요청 형식, 스트리밍 해석)은 [OpenAiBrain]이 맡는다.
 * 새 서버에서 다른 점이 발견되면 여기에 구현을 하나 추가한다.
 */
interface Dialect {
    fun chatUrl(baseUrl: String, model: String): String = baseUrl.trimEnd('/') + "/v1/chat/completions"

    /** 요청 본문에 이 서버만의 필드를 더한다. */
    fun extend(body: JSONObject) {}

    /** 서버가 오류 코드로 답했을 때 고쳐 볼 기회. true를 돌려주면 요청을 한 번 더 보낸다. */
    suspend fun recover(baseUrl: String, model: String): Boolean = false

    companion object {
        const val LLAMA_CPP = "llamacpp"
        const val OPENAI = "openai"

        /** 설정에 저장된 이름으로 고른다. 모르는 이름이면 표준 OpenAI 호환. */
        fun of(id: String, llamaModels: LlamaModels): Dialect = if (id == LLAMA_CPP) LlamaCpp(llamaModels) else OpenAiCompatible
    }
}

/** 표준 OpenAI 호환 그대로. ollama, vllm은 이걸로 쓴다. */
object OpenAiCompatible : Dialect

/** llama.cpp의 llama-server. 프롬프트 캐시를 켜고, 라우터 모드에서 모델이 내려가 있으면 올린 뒤 다시 시도한다. */
class LlamaCpp(private val models: LlamaModels) : Dialect {
    /** 모델을 지정했으면, 내려가 있어도(--no-models-autoload) 요청 시 올리도록 한다. */
    override fun chatUrl(baseUrl: String, model: String): String =
        super.chatUrl(baseUrl, model) + if (model.isBlank()) "" else "?autoload=true"

    override fun extend(body: JSONObject) {
        body.put("cache_prompt", true)
    }

    /** autoload를 모르는 버전이나 로딩 중 503에 대비해, 모델을 올리고 다 될 때까지 기다린다. */
    override suspend fun recover(baseUrl: String, model: String): Boolean {
        if (model.isBlank()) return false
        models.ensureLoaded(baseUrl, model)
        return true
    }
}

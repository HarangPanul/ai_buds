package com.example.aibuds.platform.llm

import com.example.aibuds.core.Brain
import com.example.aibuds.core.BrainChunk
import com.example.aibuds.core.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** OpenAI 호환 `/v1/chat/completions` 스트리밍으로 대화한다. 서버별 차이는 [Dialect]가 맡는다. 턴마다 새로 만든다. */
class OpenAiBrain(
    private val config: Config,
    private val dialect: Dialect = OpenAiCompatible,
    private val client: OkHttpClient = Http.client,
) : Brain {

    data class Config(
        val baseUrl: String,
        /** 요청에 실을 모델 이름. 비우면 보내지 않는다 (모델이 하나뿐인 서버). */
        val model: String = "",
        val temperature: Float = 0.7f,
    )

    private val model = config.model.trim()

    override fun chat(messages: List<Message>): Flow<BrainChunk> = callbackFlow {
        val call = AtomicReference<Call?>()
        launch(Dispatchers.IO) {
            try {
                open(messages, call).use { response ->
                    val source = response.body!!.source()
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (Sse.isDone(line)) break
                        Sse.parse(line)?.let { trySend(it) }
                    }
                }
                close()
            } catch (e: Exception) {
                close(e)
            }
        }
        // 수집이 취소되면 연결을 끊어, 응답을 기다리며 막혀 있는 읽기를 풀어 준다.
        awaitClose { call.get()?.cancel() }
    }.buffer(Channel.UNLIMITED)

    private suspend fun open(messages: List<Message>, call: AtomicReference<Call?>): Response =
        try {
            request(messages, call)
        } catch (e: HttpStatusException) {
            if (!dialect.recover(config.baseUrl, model)) throw e
            request(messages, call)
        }

    private suspend fun request(messages: List<Message>, call: AtomicReference<Call?>): Response {
        val body = JSONObject()
            .put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role.name.lowercase()).put("content", it.text)) }
            })
            .put("stream", true)
            .put("temperature", config.temperature.toDouble())
        if (model.isNotEmpty()) body.put("model", model)
        dialect.extend(body)

        val request = Request.Builder()
            .url(dialect.chatUrl(config.baseUrl, model))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).also { call.set(it) }.await()
        if (!response.isSuccessful) {
            val detail = response.body?.string()?.take(200)
            response.close()
            throw HttpStatusException("LLM 서버 오류 ${response.code}: $detail")
        }
        return response
    }
}

/** 스트리밍 응답(SSE)의 한 줄 해석. */
object Sse {
    fun isDone(line: String) = line.startsWith("data:") && line.removePrefix("data:").trim() == "[DONE]"

    /** 답변이나 thinking 조각이 든 줄이면 그 조각, 아니면 null. */
    fun parse(line: String): BrainChunk? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        val delta = try {
            JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
        } catch (e: Exception) {
            null
        } ?: return null
        delta.text("content")?.let { return BrainChunk.Content(it) }
        // thinking은 서버에 따라 reasoning_content(llama.cpp, vllm) 또는 reasoning(ollama, 최신 vllm)으로 온다.
        return (delta.text("reasoning_content") ?: delta.text("reasoning"))?.let { BrainChunk.Reasoning(it) }
    }

    /** JSON null이면 Android의 optString이 문자열 "null"을 돌려주므로 먼저 걸러낸다. */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key, "").ifEmpty { null }
}

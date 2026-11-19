package com.example.aibuds.platform.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** 서버가 가진 모델. status는 라우터 모드에서 "loaded"/"loading"/"unloaded"/"sleeping" 등, 단일 모델 서버면 빈 문자열. */
data class ServerModel(val id: String, val status: String, val failed: Boolean = false)

/** llama-server(llama.cpp) 모델 조회/로드. 라우터 모드(-m 없이 실행)에서는 models-dir/preset의 모델이 모두 나온다. */
class LlamaModels(
    private val client: OkHttpClient = Http.client,
    private val pollMs: Long = 1000,
    private val loadTimeoutMs: Long = 180_000,
) {
    /** 라우터의 `/models`(상태 포함)를 먼저, 없으면 `/v1/models`. */
    suspend fun list(baseUrl: String): List<ServerModel> = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        for (path in listOf("/models", "/v1/models")) {
            client.newCall(Request.Builder().url(base + path).build()).execute().use { r ->
                if (r.isSuccessful) return@withContext parse(r.body!!.string())
                if (r.code != 404) throw IOException("모델 목록 오류 ${r.code}")
            }
        }
        throw IOException("모델 목록 오류 404")
    }

    /** 모델이 loaded가 될 때까지 로드 요청 후 대기. 목록에 없거나 로드 실패면 예외. */
    suspend fun ensureLoaded(baseUrl: String, id: String) {
        val first = list(baseUrl).find { it.id == id } ?: throw IOException("서버에 없는 모델: $id")
        if (first.status == "loaded" || first.status.isEmpty()) return
        if (first.status != "loading") requestLoad(baseUrl, id)
        val start = System.currentTimeMillis()
        try {
            withTimeout(loadTimeoutMs) {
                var sawLoading = first.status == "loading"
                while (true) {
                    delay(pollMs)
                    val m = list(baseUrl).find { it.id == id } ?: throw IOException("서버에 없는 모델: $id")
                    when {
                        m.status == "loaded" -> return@withTimeout
                        m.status == "loading" -> sawLoading = true
                        // 이전 시도의 failed 표시가 남아 있을 수 있어, 로딩을 본 뒤나 몇 초 지난 뒤에만 실패로 본다
                        m.failed && (sawLoading || System.currentTimeMillis() - start > 5000) ->
                            throw IOException("모델 로드 실패: $id")
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IOException("모델 로드 시간 초과: $id")
        }
    }

    private suspend fun requestLoad(baseUrl: String, id: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("model", id).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/models/load").post(body).build()
        // 이미 로딩 중 등으로 실패 응답이 와도 상태 확인으로 판단한다
        client.newCall(req).execute().close()
    }

    companion object {
        fun parse(json: String): List<ServerModel> {
            val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
            return (0 until data.length()).mapNotNull { i ->
                val m = data.optJSONObject(i) ?: return@mapNotNull null
                val id = m.optString("id").ifEmpty { return@mapNotNull null }
                val st = m.optJSONObject("status")
                val status = st?.optString("value") ?: m.optString("status").takeUnless { it == "null" } ?: ""
                ServerModel(id, status, st?.optBoolean("failed") ?: false)
            }
        }
    }
}

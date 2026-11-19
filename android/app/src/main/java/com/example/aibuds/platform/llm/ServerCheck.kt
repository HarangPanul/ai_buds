package com.example.aibuds.platform.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** LLM 서버에 닿는지 확인한다. OpenAI 호환 서버라면 어디에나 있는 `/v1/models`를 불러 본다. */
class ServerCheck(client: OkHttpClient = Http.client) {
    // 끊겨 있을 때 화면이 오래 "확인 중"에 머물지 않게 짧게 끊는다.
    private val client = client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()

    suspend fun isUp(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(baseUrl.trim().trimEnd('/') + "/v1/models").build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}

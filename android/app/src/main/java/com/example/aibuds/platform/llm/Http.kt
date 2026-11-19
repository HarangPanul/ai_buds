package com.example.aibuds.platform.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    /** SSH 터널 너머 서버라 읽기 타임아웃을 넉넉히 둔다 (첫 토큰 대기, 모델 로딩 대기). */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}

/** 서버가 응답은 했지만 성공 코드가 아닌 경우 (연결 실패와 구분한다). */
class HttpStatusException(message: String) : IOException(message)

/** 취소 가능한 비동기 실행. 응답은 호출한 쪽이 닫는다. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, _, _ -> response.close() }
    })
}

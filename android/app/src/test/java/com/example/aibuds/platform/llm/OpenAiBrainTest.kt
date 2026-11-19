package com.example.aibuds.platform.llm

import com.example.aibuds.core.BrainChunk
import com.example.aibuds.core.Message
import com.example.aibuds.core.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

class OpenAiBrainTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun url() = server.url("/").toString()
    private fun brain(model: String = "", dialect: Dialect = OpenAiCompatible) =
        OpenAiBrain(OpenAiBrain.Config(url(), model, temperature = 0.5f), dialect)
    private val hi = listOf(Message(Role.System, "SYS"), Message(Role.User, "hi"))
    private fun content(text: String) = """data: {"choices":[{"delta":{"content":"$text"}}]}""" + "\n\n"
    private fun stream(vararg lines: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(lines.joinToString(""))

    @Test fun parsesLines() {
        assertEquals(BrainChunk.Content("안녕"), Sse.parse("""data: {"choices":[{"delta":{"content":"안녕"}}]}"""))
        assertTrue(Sse.isDone("data: [DONE]"))
        assertNull(Sse.parse("data: [DONE]"))
        assertNull(Sse.parse(": keep-alive"))
        assertNull(Sse.parse("""data: {"choices":[{"delta":{}}]}"""))
        assertNull(Sse.parse("""data: {"choices":[{"delta":{"role":"assistant","content":null}}]}"""))
        assertNull(Sse.parse("data: not-json"))
    }

    @Test fun parsesThinkingUnderEitherName() {
        assertEquals(BrainChunk.Reasoning("음"), Sse.parse("""data: {"choices":[{"delta":{"content":null,"reasoning_content":"음"}}]}"""))
        assertEquals(BrainChunk.Reasoning("음"), Sse.parse("""data: {"choices":[{"delta":{"content":"","reasoning":"음"}}]}"""))
        assertEquals(BrainChunk.Content("안녕"), Sse.parse("""data: {"choices":[{"delta":{"reasoning_content":null,"content":"안녕"}}]}"""))
    }

    @Test fun streamsChunksAndSendsStandardRequest() = runBlocking {
        server.enqueue(stream("""data: {"choices":[{"delta":{"role":"assistant"}}]}""" + "\n\n", content("안녕"), content("하세요"), "data: [DONE]\n\n"))
        assertEquals(listOf<BrainChunk>(BrainChunk.Content("안녕"), BrainChunk.Content("하세요")), brain().chat(hi).toList())

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertTrue(body.getBoolean("stream"))
        assertEquals(0.5, body.getDouble("temperature"), 1e-6)
        assertFalse(body.has("model"))
        assertFalse(body.has("cache_prompt"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("SYS", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test fun sendsModelWhenSet() = runBlocking {
        server.enqueue(stream("data: [DONE]\n\n"))
        brain(model = " qwen3-14b ").chat(hi).toList()
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("qwen3-14b", JSONObject(req.body.readUtf8()).getString("model"))
    }

    @Test fun httpErrorFails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("loading"))
        val e = runCatching { brain(model = "m").chat(hi).toList() }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("503") == true)
        assertEquals(1, server.requestCount)
    }

    @Test fun llamaCppAddsItsExtras() = runBlocking {
        server.enqueue(stream("data: [DONE]\n\n"))
        brain(model = "m", dialect = LlamaCpp(LlamaModels())).chat(hi).toList()
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions?autoload=true", req.path)
        assertTrue(JSONObject(req.body.readUtf8()).getBoolean("cache_prompt"))

        server.enqueue(stream("data: [DONE]\n\n"))
        brain(dialect = LlamaCpp(LlamaModels())).chat(hi).toList()
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    @Test fun llamaCppLoadsModelAndRetries() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("model not loaded"))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"m","status":{"value":"unloaded"}}]}"""))
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"m","status":{"value":"loading"}}]}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"m","status":{"value":"loaded"}}]}"""))
        server.enqueue(stream(content("ok"), "data: [DONE]\n\n"))
        val chunks = brain(model = "m", dialect = LlamaCpp(LlamaModels(pollMs = 1))).chat(hi).toList()
        assertEquals(listOf<BrainChunk>(BrainChunk.Content("ok")), chunks)
        assertEquals(
            listOf("/v1/chat/completions?autoload=true", "/models", "/models/load", "/models", "/models", "/v1/chat/completions?autoload=true"),
            (1..6).map { server.takeRequest().path },
        )
    }

    @Test fun llamaCppReportsLoadFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"m","status":{"value":"unloaded"}}]}"""))
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"m","status":{"value":"loading"}}]}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"m","status":{"value":"unloaded","failed":true}}]}"""))
        val e = runCatching { brain(model = "m", dialect = LlamaCpp(LlamaModels(pollMs = 1))).chat(hi).toList() }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("로드 실패") == true)
    }

    @Test(timeout = 20_000) fun cancellingWhileWaitingForTheResponseReturnsAtOnce() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { brain().chat(hi).toList() }
        withContext(Dispatchers.IO) { server.takeRequest() } // 요청이 서버에 도착했다
        withTimeout(3000) { job.cancelAndJoin() }
    }

    @Test(timeout = 20_000) fun cancellingMidStreamReturnsAtOnce() = runBlocking {
        // 첫 조각만 보내고는 연결을 연 채 아무것도 더 보내지 않는 서버.
        ServerSocket(0).use { stalled ->
            val serving = launch(Dispatchers.IO) {
                stalled.accept().use { socket ->
                    val out = socket.getOutputStream()
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n" + content("첫")).toByteArray())
                    out.flush()
                    runCatching { while (socket.getInputStream().read() >= 0) Unit } // 상대가 끊을 때까지 기다린다
                }
            }
            val got = CompletableDeferred<BrainChunk>()
            val brain = OpenAiBrain(OpenAiBrain.Config("http://127.0.0.1:${stalled.localPort}"))
            val job = launch { brain.chat(hi).collect { got.complete(it) } }
            assertEquals(BrainChunk.Content("첫"), withTimeout(5000) { got.await() })
            withTimeout(3000) { job.cancelAndJoin() }
            withTimeout(3000) { serving.join() } // 서버 쪽에서도 연결이 끊긴 게 보인다
        }
    }

    @Test fun listsLlamaRouterModels() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"object":"list","data":[{"id":"qwen3-14b","object":"model","status":{"value":"loaded"}},{"id":"qwen3-asr","status":{"value":"unloaded"}},{"id":"plain"}]}""",
        ))
        assertEquals(
            listOf(ServerModel("qwen3-14b", "loaded"), ServerModel("qwen3-asr", "unloaded"), ServerModel("plain", "")),
            LlamaModels().list(url()),
        )
        assertEquals("/models", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"single"}]}"""))
        assertEquals(listOf(ServerModel("single", "")), LlamaModels().list(url()))
    }
}

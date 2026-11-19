package com.example.aibuds.platform.llm

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCheckTest {
    @Test fun upWhenModelsEndpointAnswers() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            assertTrue(ServerCheck().isUp(server.url("/").toString()))
            assertEquals("/v1/models", server.takeRequest().path)
        }
    }

    @Test fun downOnErrorStatus() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(502))
            assertFalse(ServerCheck().isUp(server.url("/").toString()))
        }
    }

    @Test fun downWhenNothingListensOrAddressIsBroken() = runBlocking {
        val closed = MockWebServer().also { it.start(); it.shutdown() }
        assertFalse(ServerCheck().isUp("http://127.0.0.1:${closed.port}"))
        assertFalse(ServerCheck().isUp("주소 아님"))
    }
}

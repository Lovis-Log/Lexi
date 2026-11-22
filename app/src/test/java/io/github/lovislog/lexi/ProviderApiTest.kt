package io.github.lovislog.lexi

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderApiTest {
    @Test
    fun modelDiscoveryUsesSiblingEndpointAndReturnsDistinctIds() = runBlocking {
        val server = ProviderFixture(200 to """{"data":[{"id":"beta"},{"id":"alpha"},{"id":"beta"},{}]}""")
        val models = server.service.listModels(ProviderConfig("https://example.com/v1/chat/completions/", "", Key))
        assertEquals(listOf("alpha", "beta"), models)
        assertEquals("GET", server.requests.single().method)
        assertEquals("https://example.com/v1/models", server.requests.single().url)
        assertEquals("Bearer $Key", server.requests.single().authorization)
        assertEquals("https://api.openai.com/v1/models", modelsEndpoint("https://api.openai.com").toString())
    }

    @Test
    fun aModelErrorSurfacesTheProviderReasonWithoutEchoingTheKey() = runBlocking {
        val server = ProviderFixture(400 to """{"error":{"message":"Model does not exist. Bearer $Key"}}""")
        val error = runCatching {
            server.service.explain(LookupInput("apple"), ProviderConfig("https://example.com/v1", "wrong", Key))
        }.exceptionOrNull()
        assertTrue(error is LookupFailure)
        assertTrue(error?.message.orEmpty().contains("HTTP 400"))
        assertTrue(error?.message.orEmpty().contains("Model does not exist"))
        assertFalse(error?.message.orEmpty().contains(Key))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun anExplicitJsonModeRejectionFallsBackAndUsesThatForTheNextLookup() = runBlocking {
        val server = ProviderFixture(
            400 to """{"error":{"param":"response_format","message":"JSON mode is not supported"}}""",
            200 to reply("```json\n$Apple\n```"),
            200 to reply(Apple),
        )
        val config = ProviderConfig("https://example.com/v1", "compatible", Key)
        assertEquals("苹果", server.service.explain(LookupInput("apple"), config).meaningZh)
        assertEquals("苹果", server.service.explain(LookupInput("apple"), config).meaningZh)
        assertTrue(server.requests[0].body.contains("\"response_format\""))
        assertFalse(server.requests[1].body.contains("\"response_format\""))
        assertFalse(server.requests[2].body.contains("\"response_format\""))
    }

    @Test
    fun tokenParameterFallbackOnlyAppliesAfterAnExplicitRejection() = runBlocking {
        val server = ProviderFixture(
            422 to """{"error":{"param":"max_tokens","code":"unsupported_parameter"}}""",
            200 to reply(Apple),
        )
        server.service.explain(LookupInput("apple"), ProviderConfig("https://example.com/v1", "compatible", Key))
        assertTrue(server.requests[0].body.contains("\"max_tokens\""))
        assertFalse(server.requests[1].body.contains("\"max_tokens\""))
        assertTrue(server.requests[1].body.contains("\"max_completion_tokens\""))
        assertTrue(server.requests[1].body.contains("\"response_format\""))
    }

    @Test
    fun deepSeekUsesTheDocumentedNonThinkingOptionForDictionaryLookups() = runBlocking {
        val server = ProviderFixture(200 to reply(Apple))
        server.service.explain(LookupInput("apple"), ProviderConfig(DefaultBaseUrl, "deepseek-flash", Key))
        assertTrue(server.requests.single().body.contains("\"thinking\":{\"type\":\"disabled\"}"))
    }

    private class ProviderFixture(vararg responses: Pair<Int, String>) : Interceptor {
        private val pending = ArrayDeque(responses.toList())
        val requests = mutableListOf<SentRequest>()
        val service = LookupService(OkHttpClient.Builder().addInterceptor(this).build())

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            requests += SentRequest(request.method, request.url.toString(), request.header("Authorization"), body)
            val (status, json) = pending.removeFirst()
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("Fixture")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private data class SentRequest(val method: String, val url: String, val authorization: String?, val body: String)

    private companion object {
        const val Key = "fixture-token-value"
        const val Apple = """{"intent":"UNDERSTAND","kind":"WORD","answerEn":"apple","meaningZh":"苹果","usageEn":"A common fruit.","senses":[{"pos":"n.","en":"An edible fruit.","zh":"苹果"}]}"""

        fun reply(content: String) = buildJsonObject {
            put("choices", JsonArray(listOf(buildJsonObject {
                put("finish_reason", "stop")
                put("message", buildJsonObject { put("content", content) })
            })))
        }.toString()
    }
}

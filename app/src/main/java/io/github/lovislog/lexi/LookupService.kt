package io.github.lovislog.lexi

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val DefaultBaseUrl = "https://api.deepseek.com"
internal const val LookupSchemaVersion = 4

internal open class LookupFailure(
    message: String,
    val english: String = "The operation could not be completed. Please try again.",
    val opensSettings: Boolean = false,
) : Exception(message)

internal fun providerRoot(baseUrl: String): HttpUrl? = baseUrl.trim().toHttpUrlOrNull()
    ?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() &&
            it.query == null && it.fragment == null
    }
    ?.let { url ->
        val path = url.encodedPath.trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/models")
        val prefix = if (path.isEmpty() && url.host == "api.openai.com") "/v1" else path
        url.newBuilder().encodedPath("$prefix/").build()
    }

internal fun endpoint(baseUrl: String): HttpUrl? = providerRoot(baseUrl)?.newBuilder()
    ?.addPathSegments("chat/completions")?.build()

internal fun modelsEndpoint(baseUrl: String): HttpUrl? = providerRoot(baseUrl)?.newBuilder()
    ?.addPathSegment("models")?.build()

internal class LookupService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(55, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
) {
    private val compatibility = ConcurrentHashMap<Pair<HttpUrl, String>, ChatOptions>()

    suspend fun listModels(config: ProviderConfig): List<String> {
        val url = modelsEndpoint(config.baseUrl) ?: throw LookupFailure("请输入有效的 HTTPS 接口地址。", "Enter a valid HTTPS endpoint.")
        val request = authenticated(url, config).get().build()
        val response = try {
            execute(request, config.apiKey, 4 * 1024 * 1024L)
        } catch (failure: ProviderFailure) {
            if (failure.status in setOf(404, 405, 501)) {
                throw LookupFailure("模型列表不可用（HTTP ${failure.status}），可以手动填写 Model。", "Model listing is unavailable (HTTP ${failure.status}). You can enter the model ID manually.")
            }
            throw failure
        }
        val models = try {
            lexiJson.decodeFromString<ModelList>(response).data.mapNotNull { entry ->
                entry.id?.trim()?.takeIf { value -> value.isNotEmpty() && value.length <= 150 }
            }.distinct().sorted()
        } catch (_: SerializationException) {
            throw LookupFailure("接口返回的模型列表格式不正确，可以手动填写 Model。", "The model list was invalid. You can enter the model ID manually.")
        }
        if (models.isEmpty()) throw LookupFailure("接口没有返回模型，可以手动填写 Model。", "The endpoint returned no models. You can enter the model ID manually.")
        return models
    }



    suspend fun verifyProvider(config: ProviderConfig) {
        val prompt = """这是模型连接测试。只返回一个 JSON 对象，例如 {"ok":true}。"""
        val user = JsonPrimitive("返回 JSON 格式的连接确认。")
        completeValidated(config, prompt, user) { json ->
            lexiJson.parseToJsonElement(json) as? JsonObject ?: throw AnalysisFormatFailure("JSON")
        }
    }

    suspend fun explain(input: LookupInput, config: ProviderConfig): Explanation {
        val user = AnalysisRequest(input.text, input.context.takeIf(String::isNotBlank))
        return completeValidated(config, input.format.systemPrompt, JsonPrimitive(lexiJson.encodeToString(user))) {
            input.decodeAnalysis(it)
        }
    }

    private suspend fun <T> completeValidated(
        config: ProviderConfig,
        prompt: String,
        user: JsonElement,
        validate: (String) -> T,
    ): T {
        suspend fun request(instructions: String): T {
            val content = complete(config, instructions, user)
            return try {
                validate(content)
            } catch (_: SerializationException) {
                throw AnalysisFormatFailure("JSON")
            }
        }
        return try {
            request(prompt)
        } catch (failure: AnalysisFormatFailure) {
            val retry = "\n\n上次输出未通过校验。请完整重新生成 JSON，检查 ${failure.field}，" +
                "按模板保留所有字段和类型，尤其是 JSON null、空数组、标点、空格和复习卡正反面。"
            request(prompt + retry)
        }
    }

    suspend fun explainImage(base64Jpeg: String, context: String, config: ProviderConfig): LookupResult {
        val imageModel = config.visionModel.ifBlank {
            if (endpoint(config.baseUrl)?.host == "api.deepseek.com") "deepseek-flash" else config.model
        }
        val content = JsonArray(listOf(
            buildJsonObject {
                put("type", "text")
                put("text", lexiJson.encodeToString(AnalysisRequest("", context.takeIf(String::isNotBlank))))
            },
            buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$base64Jpeg") })
            },
        ))
        return completeValidated(config.copy(model = imageModel), visionSystemPrompt, content) { decodeVisionAnalysis(it, context) }
    }

    private suspend fun complete(config: ProviderConfig, prompt: String, user: JsonElement): String {
        val url = endpoint(config.baseUrl) ?: throw LookupFailure("请输入有效的 HTTPS 接口地址。", "Enter a valid HTTPS endpoint.")
        val profile = url to config.model
        var options = compatibility[profile] ?: ChatOptions(completionTokens = url.host == "api.openai.com")
        while (true) {
            try {
                val content = requestContent(config, url, options, prompt, user)
                compatibility[profile] = options
                return content
            } catch (failure: ProviderFailure) {
                options = when {
                    !options.completionTokens && failure.rejects("max_tokens") ->
                        options.copy(completionTokens = true)
                    options.temperature && failure.rejects("temperature") ->
                        options.copy(temperature = false)
                    else -> throw failure
                }
            }
        }
    }

    private suspend fun requestContent(
        config: ProviderConfig,
        url: HttpUrl,
        options: ChatOptions,
        prompt: String,
        user: JsonElement,
    ): String {
        val deepSeek = url.host == "api.deepseek.com" &&
            (config.model == "deepseek-flash" || config.model.startsWith("deepseek-v4"))
        val body = ChatRequest(
            model = config.model,
            messages = listOf(Message("system", JsonPrimitive(prompt)), Message("user", user)),
            response_format = Format("json_object"),
            temperature = if (options.temperature) 0.4 else null,
            max_tokens = if (options.completionTokens) null else 8192,
            max_completion_tokens = if (options.completionTokens) 8192 else null,
            thinking = if (deepSeek) Format("disabled") else null,
        )
        val request = authenticated(url, config)
            .post(lexiJson.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .build()
        val response = execute(request, config.apiKey, 256 * 1024L)
        return decodeResponse {
            val choice = lexiJson.decodeFromString<ChatResponse>(response).choices.firstOrNull()
                ?: throw LookupFailure("AI 没有返回答案，可以重试。", "The model returned no answer. Try again.")
            if (choice.finish_reason == "length") {
                throw LookupFailure("AI 输出被截断，可以重试或换一个模型。", "The answer was cut off. Try again or select another model.")
            }
            val content = choice.message.content?.trim()
            if (choice.finish_reason != null && choice.finish_reason != "stop") {
                throw LookupFailure("AI 没有返回完整答案，可以重试。", "The model returned an incomplete answer. Try again.")
            }
            if (content.isNullOrEmpty()) throw AnalysisFormatFailure("JSON")
            JsonFence.matchEntire(content)?.groupValues?.get(1) ?: content
        }
    }

    private inline fun <T> decodeResponse(block: () -> T): T = try {
        block()
    } catch (_: SerializationException) {
        throw LookupFailure("AI 返回格式不正确，可以重试。", "The answer had an invalid format. Try again.")
    }

    private fun authenticated(url: HttpUrl, config: ProviderConfig): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${config.apiKey}")
        .header("Accept", "application/json")

    private suspend fun execute(request: Request, key: String, limit: Long): String =
        try {
            receive(request, key, limit)
        } catch (_: IOException) {
            throw LookupFailure("连接超时或网络不可用，请检查接口地址后重试。", "The network is unavailable or the request timed out. Check the endpoint and try again.")
        }

    private suspend fun receive(request: Request, key: String, limit: Long): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val source = it.body?.source()
                        if (!it.isSuccessful) {
                            val error = if (source != null && !source.request(16 * 1024L + 1)) source.readUtf8() else ""
                            throw providerFailure(it.code, error, key)
                        }
                        if (source == null) throw LookupFailure("AI 返回为空，可以重试。", "The model returned an empty response. Try again.")
                        if (source.request(limit + 1)) {
                            throw LookupFailure("AI 返回内容过长，可以重试。", "The response was too large. Try again.")
                        }
                        continuation.resume(source.readUtf8())
                    } catch (failure: LookupFailure) {
                        continuation.resumeWithException(failure)
                    } catch (failure: IOException) {
                        continuation.resumeWithException(failure)
                    }
                }
            }
        })
    }
}

private data class ChatOptions(
    val completionTokens: Boolean = false,
    val temperature: Boolean = true,
)

private val JsonFence = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
private val CredentialPattern = Regex("(?i)\\b(?:Bearer\\s+|(?:sk|AIza)[-_])[A-Za-z0-9_.*-]+")

private class ProviderFailure(
    val status: Int,
    private val detail: String,
    message: String,
    english: String,
) : LookupFailure(message, english, opensSettings = status in setOf(400, 401, 403, 404, 422)) {
    fun rejects(vararg names: String): Boolean {
        if (status != 400 && status != 422) return false
        val lower = detail.lowercase()
        return names.any { it in lower } && listOf(
            "not support", "unsupported", "not allowed", "only the default", "unknown", "unrecognized", "unexpected",
            "not implemented", "不支持", "未知参数",
        ).any { it in lower }
    }
}

private fun providerFailure(status: Int, body: String, key: String): ProviderFailure {
    val root = try {
        lexiJson.parseToJsonElement(body) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
    val error = root?.get("error")
    val details = (error as? JsonObject) ?: root
    val reason = listOf("code", "param", "message").mapNotNull { field ->
        (details?.get(field) as? JsonPrimitive)?.contentOrNull
    }.ifEmpty { listOfNotNull((error as? JsonPrimitive)?.contentOrNull) }.joinToString(" · ")
    val redacted = reason.replace(key, "[密钥已隐藏]").replace(CredentialPattern, "[密钥已隐藏]")
        .replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ").trim()
    val summary = when (status) {
        400, 422 -> "接口拒绝了请求，请检查设置中的模型和接口地址"
        401 -> "API Key 无效或已过期，请检查设置"
        403 -> "接口拒绝访问，请检查 API Key 的权限"
        402 -> "供应商账户余额不足"
        404 -> "接口或模型不存在，请检查设置"
        429 -> "接口已限流或额度用尽"
        else -> "AI 接口请求失败"
    }
    val message = "$summary（HTTP $status）" + if (redacted.isEmpty()) "。" else "\n${redacted.take(220)}"
    val english = when (status) {
        400, 422 -> "The provider rejected the request. Check your model and endpoint"
        401 -> "The API key is invalid or expired"
        403 -> "Access denied. Check your API key permissions"
        402 -> "The provider account has insufficient credit"
        404 -> "The endpoint or model could not be found"
        429 -> "The provider rate limit or quota has been reached"
        else -> "The AI request failed"
    }
    return ProviderFailure(status, redacted, message, "$english (HTTP $status)." +
        if (redacted.isEmpty()) "" else "\n${redacted.take(220)}")
}

@Serializable
private data class ModelList(val data: List<ModelEntry>)

@Serializable
private data class ModelEntry(val id: String? = null)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val response_format: Format,
    val temperature: Double? = null,
    val max_tokens: Int? = null,
    val max_completion_tokens: Int? = null,
    val thinking: Format? = null,
)

@Serializable
private data class Format(val type: String)

@Serializable
private data class Message(val role: String, val content: JsonElement)

@Serializable
private data class ChatResponse(val choices: List<Choice>)

@Serializable
private data class Choice(val message: Reply, val finish_reason: String? = null)

@Serializable
private data class Reply(val content: String? = null)

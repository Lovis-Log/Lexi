package io.github.lovislog.lexi

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNull
import kotlin.random.Random
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
    fun jsonModeRemainsRequiredWhenTheProviderRejectsIt() = runBlocking {
        val server = ProviderFixture(400 to """{"error":{"param":"response_format","message":"JSON mode is not supported"}}""")
        val config = ProviderConfig("https://example.com/v1", "compatible", Key)
        val failure = runCatching { server.service.explain(LookupInput("apple"), config) }.exceptionOrNull()
        assertTrue(failure is LookupFailure)
        assertTrue(server.requests.single().body.contains("\"response_format\":{\"type\":\"json_object\"}"))
    }

    @Test
    fun providerTestAcceptsJsonWithoutRequiringADictionaryEntry() = runBlocking {
        val server = ProviderFixture(200 to reply("""{"ok":true}"""))
        server.service.verifyProvider(Config)
        val body = server.requests.single().body
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_object\"}"))
        assertFalse(body.contains("review_card_summary"))
    }

    @Test
    fun providerTestRetriesABlankCompletion() = runBlocking {
        val server = ProviderFixture(200 to reply(" "), 200 to reply("""{"ok":true}"""))
        server.service.verifyProvider(Config)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun aMissingLookupFieldPromptsOneRepairAndOnlyAcceptsACompleteEntry() = runBlocking {
        val missingField = Apple.replace("\"en\":\"A defined usage.\",", "")
        val server = ProviderFixture(200 to reply(missingField), 200 to reply(Apple))
        val answer = server.service.explain(LookupInput("apple"), Config)
        assertEquals(payload(Apple), answer.analysisPayload)
        assertEquals(2, server.requests.size)
        assertFalse(server.requests.first().body.contains("上次输出未通过校验"))
        assertTrue(server.requests.last().body.contains("上次输出未通过校验"))
        assertTrue(server.requests.last().body.contains("检查 definitions"))
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
        val input = LookupInput("apple")
        val explanation = server.service.explain(input, ProviderConfig(DefaultBaseUrl, "deepseek-flash", Key))
        val request = server.requests.single().body
        assertTrue(request.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(request.contains("\"temperature\":0.4"))
        assertTrue(request.contains("review_card_summary"))
        assertFalse(request.contains("alternative_translations"))
        val saved = lexiJson.decodeFromString<LookupResult>(lexiJson.encodeToString(LookupResult(input, explanation)))
        assertTrue(saved.explanation.isValid())
        assertEquals("She sliced a crisp ___.", saved.explanation.wordAnalysis?.reviewCardSummary?.expressionClozeEn)
        assertEquals("她切了一个脆苹果。", saved.explanation.wordAnalysis?.collocations?.single()?.exampleZh)
    }

    @Test
    fun aChineseLookupKeepsBothVariantsAndReviewHintsAfterSerialization() = runBlocking {
        val server = ProviderFixture(200 to reply(Translation))
        val input = LookupInput("我稍后答复你。", "职场")
        val answer = server.service.explain(input, ProviderConfig(DefaultBaseUrl, "compatible", Key))
        val request = server.requests.single().body
        assertTrue(request.contains("alternative_translations"))
        assertFalse(request.contains("review_card_summary"))
        val saved = lexiJson.decodeFromString<LookupResult>(lexiJson.encodeToString(LookupResult(input, answer)))
        assertTrue(saved.explanation.isValid())
        assertEquals(LookupIntent.EXPRESS, answer.intent)
        assertEquals("I'll get back to you later.", answer.answerEn)
        assertEquals(listOf("职场正式", "简短口语"), saved.explanation.translation?.alternativeTranslations?.map { it.tone })
        assertEquals(listOf("get back to"), saved.explanation.translation?.reviewCard?.frontHints)
    }

    @Test
    fun mixedLookupsCanReturnEitherShape() = runBlocking {
        val server = ProviderFixture(200 to reply(Apple), 200 to reply(Translation.replace("\"首选译文保持专业、礼貌。\"", "null")))
        val config = ProviderConfig(DefaultBaseUrl, "compatible", Key)
        assertEquals("apple", server.service.explain(LookupInput("apple 是什么意思？"), config).answerEn)
        assertEquals(LookupIntent.EXPRESS, server.service.explain(LookupInput("我稍后答复你。用 email 表达"), config).intent)
        assertTrue(server.requests.all { it.body.contains("alternative_translations") && it.body.contains("review_card_summary") })
    }

    @Test
    fun malformedResponseCannotBecomeAPartialLookupResult() = runBlocking {
        val malformed = reply(Apple.replace("\"en\":\"A defined usage.\",", ""))
        val server = ProviderFixture(200 to malformed, 200 to malformed)
        val failure = runCatching {
            server.service.explain(LookupInput("apple"), ProviderConfig(DefaultBaseUrl, "compatible", Key))
        }.exceptionOrNull()
        assertTrue(failure is LookupFailure)
    }

    @Test
    fun aProviderThatRequiresDefaultTemperatureIsRetriedWithoutTheParameter() = runBlocking {
        val server = ProviderFixture(
            400 to """{"error":{"param":"temperature","message":"Only the default (1) value is supported."}}""",
            200 to reply(Apple),
            200 to reply(Apple),
        )
        val config = ProviderConfig(DefaultBaseUrl, "compatible", Key)
        repeat(2) { server.service.explain(LookupInput("apple"), config) }
        assertTrue(server.requests.first().body.contains("\"temperature\":0.4"))
        assertTrue(server.requests.drop(1).all { !it.body.contains("\"temperature\"") })
    }


    @Test
    fun attentionKeepsTheCompleteLookupAndShowsTheRecognitionAnswer() = runBlocking {
        val result = accepted("attention", Attention)
        assertEquals(payload(Attention), savedRoundTrip(result).explanation.analysisPayload)
        val review = result.reviewFaces(false)
        assertEquals("attention", review.front.title)
        assertEquals("留意最后一步。", review.back.supportingText)
        assertEquals(listOf("pay attention to  留意", "draw attention  引起注意",
            "pay attention 后面用 to 引出注意的内容。"), review.back.notes)
    }

    @Test
    fun makeUpLinksTheWholePhraseAndStaysInSessionUntilGood() = runBlocking {
        val result = accepted("make up", MakeUp)
        assertEquals(EnglishSegment("make up", "make up", true), result.explanation.headSegments.single())
        assertTrue(result.explanation.examples.single().clickableSegments.any {
            it.text == "made up" && it.query == "make up" && it.isPhrase
        })
        val rest = (1..7).map { "card-$it" }
        val queue = ReviewSession(sessionQueue = (listOf(result.id) + rest).map { ReviewQueueEntry(it) })
        val forgotten = queue.rated(result.id, 1, Rating.AGAIN, Random(12))
        assertTrue(forgotten.sessionQueue.indexOfFirst { it.id == result.id } in 2..3)
        assertEquals(1, forgotten.sessionQueue.first { it.id == result.id }.requeueCount)
        val hard = forgotten.focus(result.id).rated(result.id, 2, Rating.HARD, Random(7))
        assertTrue(hard.sessionQueue.indexOfFirst { it.id == result.id } in 5..6)
        assertEquals(2, hard.sessionQueue.first { it.id == result.id }.requeueCount)
        assertFalse(hard.rated(result.id, 3, Rating.GOOD).sessionQueue.any { it.id == result.id })
        val short = queue.copy(sessionQueue = queue.sessionQueue.take(2)).rated(result.id, 1, Rating.HARD)
        assertEquals(result.id, short.sessionQueue.last().id)
    }

    @Test
    fun shampooUsesVocabularySchemaAndKeepsCompanionsAndAlignedCloze() = runBlocking {
        val result = savedRoundTrip(accepted("洗发水", Shampoo))
        assertEquals("shampoo", result.explanation.answerEn)
        assertEquals("body wash", result.explanation.sceneCompanions.first().en)
        val review = result.reviewFaces(true)
        assertEquals("洗发水", review.front.title)
        assertEquals("I bought a bottle of ___.", review.front.supportingText)
        assertEquals("I bought a bottle of shampoo.", review.back.supportingText)
    }

    @Test
    fun contextIsSentWithTheQueryAndTheAdaptedTranslationIsRetained() = runBlocking {
        val server = ProviderFixture(200 to reply(ContextTranslation))
        val input = LookupInput("我今天心情不好", "发邮件给国外导师请假")
        val result = savedRoundTrip(LookupResult(input, server.service.explain(input, Config)))
        val messages = payload(server.requests.single().body).getValue("messages") as JsonArray
        val user = payload(messages[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(input.text, user.getValue("query").jsonPrimitive.content)
        assertEquals(input.context, user.getValue("user_context").jsonPrimitive.content)
        assertTrue(result.explanation.contextAdaptationNote.orEmpty().contains("导师"))
        assertEquals(result.explanation.answerEn, result.explanation.headSegments.joinToString("") { it.text })
        assertEquals(result.explanation.answerEn, result.reviewFaces(true).back.title)
    }

    @Test
    fun englishSentenceKeepsTheSourcePhraseAndUsesAlignedReviewFaces() = runBlocking {
        val result = savedRoundTrip(accepted("They argued but soon made up.", EnglishSentence))
        val answer = result.explanation
        assertEquals(ContentKind.SENTENCE, answer.kind)
        assertEquals(EnglishSegment("made up", "make up", true), answer.headSegments.first { it.isPhrase })
        assertEquals(result.input.text, answer.headSegments.joinToString("") { it.text })
        assertEquals(result.input.text, result.reviewFaces(false).front.title)
        assertEquals(answer.meaningZh, result.reviewFaces(false).back.title)
        assertEquals(answer.meaningZh, result.reviewFaces(true).front.title)
        assertEquals(result.input.text, result.reviewFaces(true).back.title)
        assertNull(answer.contextAdaptationNote)
    }

    @Test
    fun selectedImageUsesVisionJsonModeAndRetainsTheCompletePayload() = runBlocking {
        val response = """{"extracted_text":"They argued but soon made up.","detected_type":"en_sentence","payload":$EnglishSentence}"""
        val server = ProviderFixture(200 to reply(response))
        val result = server.service.explainImage("aW1hZ2U=", "", Config)
        assertEquals(payload(EnglishSentence), result.explanation.analysisPayload)
        assertTrue(server.requests.single().body.contains("data:image/jpeg;base64,aW1hZ2U="))
        assertEquals("deepseek-flash", payload(server.requests.single().body).getValue("model").jsonPrimitive.content)

        val custom = ProviderFixture(200 to reply(response))
        custom.service.explainImage("aW1hZ2U=", "", Config.copy(baseUrl = "https://example.com", visionModel = "vision-model"))
        assertEquals("vision-model", payload(custom.requests.single().body).getValue("model").jsonPrimitive.content)
        assertTrue(server.requests.single().body.contains("\"response_format\":{\"type\":\"json_object\"}"))
        val error = runCatching {
            decodeVisionAnalysis("""{"error":"unreadable","message_zh":"未识别到清晰文字，请重新框选"}""", "")
        }.exceptionOrNull()
        assertTrue(error is LookupFailure)
    }


    @Test
    fun brokenEnglishSentenceSegmentsAndReviewAnswersRecoverFromTheSource() {
        val sentence = LookupInput("They argued but soon made up.")
        val brokenSegments = EnglishSentence.replace("\"text\":\" but soon \"", "\"text\":\" but soon\"")
        val brokenAnswer = EnglishSentence.replace("\"expression_back_en\":\"They argued but soon made up.\"",
            "\"expression_back_en\":\"They argued.\"")
        val segments = sentence.decodeAnalysis(brokenSegments)
        assertTrue(segments.headSegments.isEmpty())
        val answer = sentence.decodeAnalysis(brokenAnswer)
        assertEquals(sentence.text, LookupResult(sentence, answer).reviewFaces(true).back.title)
    }

    @Test
    fun chineseSentenceRecoversAnInvalidReviewCardWithoutDiscardingTheTranslation() {
        val input = LookupInput("我心情很不好")
        val answer = input.decodeAnalysis(
            """{
                "query_type":"zh_sentence","source_zh":"我心情很不好",
                "primary_translation":{"en":"I'm in a bad mood.","clickable_segments":[
                    {"text":"I'm in a bad mood.","query":null,"is_phrase":false}]},
                "review_card":{"front_hints":4,"back_answer_segments":{}}
            }""",
        )
        val card = answer.translation!!.reviewCard
        assertEquals(input.text, card.frontPromptZh)
        assertEquals("I'm in a bad mood.", card.backAnswerEn)
        assertEquals(card.backAnswerEn, card.backAnswerTtsText)
        assertEquals(answer.headSegments, card.backAnswerSegments)
        assertTrue(card.frontHints.isEmpty())
        assertTrue(answer.isValid())
    }

    @Test
    fun chineseCandidatesSurviveSerializationAndAppearTogetherOnTheExpressionCard() {
        val input = LookupInput("进位")
        val result = savedRoundTrip(LookupResult(input, input.decodeAnalysis(
            """{
                "query_type":"zh_term","source_zh":"进位","sub_type":"zh_vocab","word":"carry over",
                "definitions":[{"pos":"v.","en":"Transfer a digit to the next position.","zh":"进位"}],
                "english_equivalents":[
                    {"en":"carry over","phonetic_us":"ˈkæri ˈoʊvər","phonetic_uk":"ˈkæri ˈəʊvə","pos":"v.","usage_label_zh":"通用动词短语","nuance_zh":"向高位进一"},
                    {"en":"carry the one","phonetic_us":"","phonetic_uk":"","pos":"v.","usage_label_zh":"口语","nuance_zh":"把进位的 1 写到上一位"}
                ]
            }""",
        )))
        assertEquals(LibraryLanguage.ZH, result.langGroup)
        assertEquals(LibrarySubType.ZH_VOCAB, result.subType)
        assertEquals(listOf("carry over", "carry the one"), result.englishEquivalents.map { it.en })
        assertEquals("进位", result.reviewFaces(true).front.title)
        assertEquals("carry over", result.reviewFaces(true).back.title)
        assertTrue(result.reviewFaces(true).back.notes.any { it.contains("carry the one") })
    }

    @Test
    fun englishSentenceWithCompanionsCanOmitTheReviewCard() {
        val input = LookupInput("The ship left the harbor.")
        val answer = input.decodeAnalysis(
            """{
                "query_type":"en_sentence","source_en":"The ship left the harbor.",
                "natural_translation_zh":"船离开了港口。",
                "scene_companions":[{"en":"dock","zh":"码头","phonetic":"","relation_zh":"船舶停靠"}]
            }""",
        )
        assertEquals("dock", answer.sceneCompanions.single().en)
        assertEquals(input.text, LookupResult(input, answer).reviewFaces(true).back.title)
        assertTrue(answer.isValid())
    }

    private suspend fun accepted(text: String, answer: String): LookupResult {
        val server = ProviderFixture(200 to reply(answer))
        val input = LookupInput(text)
        return LookupResult(input, server.service.explain(input, Config))
    }

    private fun savedRoundTrip(result: LookupResult): LookupResult =
        lexiJson.decodeFromString(lexiJson.encodeToString(result))

    private fun payload(json: String): JsonObject = lexiJson.parseToJsonElement(json).jsonObject

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
        val Config = ProviderConfig(DefaultBaseUrl, "fixture-model", Key)
        val Apple = """{
            "word":"apple",
            "syllables":"ap·ple",
            "pos_tags":["noun"],
            "frequency_level":"Common",
            "cefr_level":"A1",
            "phonetics":{"us":"/ˈæpəl/","uk":"/ˈæpəl/"},
            "core_image":{"zh":"一口咬下去嘎嘣脆的果子","en":"a crisp bite"},
            "grammar_skeleton":["slice an apple","a variety of apples"],
            "definitions":[{"pos":"n.","en":"A defined usage.","zh":"苹果"}],
            "collocations":[{"example_zh":"她切了一个脆苹果。","phrase":"a crisp apple","meaning_zh":"脆苹果","example_en":"She sliced a crisp apple.","tts_text":"She sliced a crisp apple.","clickable_segments":[{"text":"She sliced a crisp ","query":null,"is_phrase":false},{"text":"apple","query":"apple","is_phrase":false},{"text":".","query":null,"is_phrase":false}]}],
            "context_examples":[{"scene":"Everyday","en":"She sliced a crisp apple.","zh":"她切了一个脆苹果。","tts_text":"She sliced a crisp apple.","clickable_segments":[{"text":"She sliced a crisp ","query":null,"is_phrase":false},{"text":"apple","query":"apple","is_phrase":false},{"text":".","query":null,"is_phrase":false}]}],
            "synonyms":[],
            "etymology_network":{"root_analysis_zh":"源自古英语 æppel。","related_words":[]},
            "chinglish_warnings":[{"mistake":"I ate apple.","correction":"I ate an apple.","reason_zh":"指一整个苹果时要加冠词。"}],
            "query_type":"word",
            "tts_text":"apple",
            "word_segments":[{"text":"apple","query":"apple","is_phrase":false}],
            "context_adaptation_note":null,
            "scene_companions":[],
            "review_card_summary":{"recognition_front_example_en":"She sliced a crisp apple.","recognition_front_example_zh":"她切了一个脆苹果。","recognition_example_segments":[{"text":"She sliced a crisp ","query":null,"is_phrase":false},{"text":"apple","query":"apple","is_phrase":false},{"text":".","query":null,"is_phrase":false}],"expression_front_prompt_zh":"苹果","expression_cloze_en":"She sliced a crisp ___.","expression_cloze_answer_en":"She sliced a crisp apple.","back_quick_tip_zh":"单数可数名词前常用 an。"}
        }""".trimIndent()

        val Translation = """{
            "source_zh":"我稍后答复你。",
            "scene_tags":["日常口语","职场沟通"],
            "primary_translation":{"en":"I'll get back to you later.","tone":"日常自然","explanation_zh":"get back to you 表示稍后回复。","tts_text":"I'll get back to you later.","clickable_segments":[{"text":"I'll ","query":null,"is_phrase":false},{"text":"get back to","query":"get back to","is_phrase":true},{"text":" you later.","query":null,"is_phrase":false}]},
            "alternative_translations":[{"tone":"职场正式","en":"I'll follow up with you later.","note_zh":"适合工作场景。","tts_text":"I'll follow up with you later.","clickable_segments":[{"text":"I'll follow up with you later.","query":null,"is_phrase":false}]},{"tone":"简短口语","en":"I'll let you know later.","note_zh":"适合熟人之间。","tts_text":"I'll let you know later.","clickable_segments":[{"text":"I'll let you know later.","query":null,"is_phrase":false}]}],
            "sentence_skeletons":[{"pattern":"I'll get back to you [time].","meaning_zh":"我会在某个时间回复你。"}],
            "key_chunks":[{"chunk":"get back to","meaning_zh":"回复","usage_note_zh":"to 后接回复对象。"}],
            "chinglish_pitfall":{"bad_translation":"I'll reply you later.","reason_zh":"reply 引出人时要加 to。"},
            "review_card":{"front_prompt_zh":"我稍后答复你。","front_hints":["get back to"],"back_key_takeaway_zh":"get back to 后接人。","back_answer_en":"I'll get back to you later.","back_answer_tts_text":"I'll get back to you later.","back_answer_segments":[{"text":"I'll ","query":null,"is_phrase":false},{"text":"get back to","query":"get back to","is_phrase":true},{"text":" you later.","query":null,"is_phrase":false}]},
            "query_type":"zh_sentence",
            "context_adaptation_note":"首选译文保持专业、礼貌。",
            "scene_companions":[]
        }""".trimIndent()

        val Attention = """{
            "word":"attention",
            "syllables":"ap·ple",
            "pos_tags":["noun"],
            "frequency_level":"Common",
            "cefr_level":"A1",
            "phonetics":{"us":"/ˈæpəl/","uk":"/ˈæpəl/"},
            "core_image":{"zh":"一口咬下去嘎嘣脆的果子","en":"a crisp bite"},
            "grammar_skeleton":["slice an apple","a variety of apples"],
            "definitions":[{"pos":"n.","en":"A defined usage.","zh":"注意力"}],
            "collocations":[{"phrase":"pay attention to","meaning_zh":"留意","example_en":"Pay attention to the last step.","example_zh":"留意最后一步。","tts_text":"Pay attention to the last step.","clickable_segments":[{"text":"Pay attention to","query":"pay attention to","is_phrase":true},{"text":" the last step.","query":null,"is_phrase":false}]},{"phrase":"draw attention","meaning_zh":"引起注意","example_en":"The sound drew attention.","example_zh":"声音引起了注意。","tts_text":"The sound drew attention.","clickable_segments":[{"text":"The sound drew ","query":null,"is_phrase":false},{"text":"attention","query":"attention","is_phrase":false},{"text":".","query":null,"is_phrase":false}]}],
            "context_examples":[{"scene":"Everyday","en":"Pay attention to the last step.","zh":"留意最后一步。","tts_text":"Pay attention to the last step.","clickable_segments":[{"text":"Pay ","query":null,"is_phrase":false},{"text":"attention","query":"attention","is_phrase":false},{"text":" to the last step.","query":null,"is_phrase":false}]}],
            "synonyms":[],
            "etymology_network":{"root_analysis_zh":"源自古英语 æppel。","related_words":[]},
            "chinglish_warnings":[{"mistake":"I ate apple.","correction":"I ate an apple.","reason_zh":"指一整个苹果时要加冠词。"}],
            "query_type":"word",
            "tts_text":"attention",
            "word_segments":[{"text":"attention","query":"attention","is_phrase":false}],
            "context_adaptation_note":null,
            "scene_companions":[],
            "review_card_summary":{"recognition_front_example_en":"Pay attention to the last step.","recognition_front_example_zh":"留意最后一步。","recognition_example_segments":[{"text":"Pay ","query":null,"is_phrase":false},{"text":"attention","query":"attention","is_phrase":false},{"text":" to the last step.","query":null,"is_phrase":false}],"expression_front_prompt_zh":"注意力","expression_cloze_en":"Pay ___ to the last step.","expression_cloze_answer_en":"Pay attention to the last step.","back_quick_tip_zh":"pay attention 后面用 to 引出注意的内容。"}
        }""".trimIndent()

        val MakeUp = """{
            "word":"make up",
            "syllables":"ap·ple",
            "pos_tags":["noun"],
            "frequency_level":"Common",
            "cefr_level":"A1",
            "phonetics":{"us":"/ˈæpəl/","uk":"/ˈæpəl/"},
            "core_image":{"zh":"一口咬下去嘎嘣脆的果子","en":"a crisp bite"},
            "grammar_skeleton":["slice an apple","a variety of apples"],
            "definitions":[{"pos":"n.","en":"A defined usage.","zh":"和好"}],
            "collocations":[{"phrase":"make up with","meaning_zh":"与某人和好","example_en":"I made up with my friend.","example_zh":"我跟朋友和好了。","tts_text":"I made up with my friend.","clickable_segments":[{"text":"I ","query":null,"is_phrase":false},{"text":"made up","query":"make up","is_phrase":true},{"text":" with my friend.","query":null,"is_phrase":false}]}],
            "context_examples":[{"scene":"Everyday","en":"They argued but soon made up.","zh":"他们吵了一架，但很快就和好了。","tts_text":"They argued but soon made up.","clickable_segments":[{"text":"They argued but soon ","query":null,"is_phrase":false},{"text":"made up","query":"make up","is_phrase":true},{"text":".","query":null,"is_phrase":false}]}],
            "synonyms":[],
            "etymology_network":{"root_analysis_zh":"源自古英语 æppel。","related_words":[]},
            "chinglish_warnings":[{"mistake":"I ate apple.","correction":"I ate an apple.","reason_zh":"指一整个苹果时要加冠词。"}],
            "query_type":"phrase",
            "tts_text":"make up",
            "word_segments":[{"text":"make up","query":"make up","is_phrase":true}],
            "context_adaptation_note":null,
            "scene_companions":[],
            "review_card_summary":{"recognition_front_example_en":"They argued but soon made up.","recognition_front_example_zh":"他们吵了一架，但很快就和好了。","recognition_example_segments":[{"text":"They argued but soon ","query":null,"is_phrase":false},{"text":"made up","query":"make up","is_phrase":true},{"text":".","query":null,"is_phrase":false}],"expression_front_prompt_zh":"和好","expression_cloze_en":"They argued but soon ___.","expression_cloze_answer_en":"They argued but soon made up.","back_quick_tip_zh":"与某人和好用 make up with。"}
        }""".trimIndent()

        val Shampoo = """{
            "word":"shampoo",
            "syllables":"ap·ple",
            "pos_tags":["noun"],
            "frequency_level":"Common",
            "cefr_level":"A1",
            "phonetics":{"us":"/ˈæpəl/","uk":"/ˈæpəl/"},
            "core_image":{"zh":"一口咬下去嘎嘣脆的果子","en":"a crisp bite"},
            "grammar_skeleton":["slice an apple","a variety of apples"],
            "definitions":[{"pos":"n.","en":"A defined usage.","zh":"洗发水"}],
            "collocations":[],
            "context_examples":[{"scene":"Everyday","en":"I bought a bottle of shampoo.","zh":"我买了一瓶洗发水。","tts_text":"I bought a bottle of shampoo.","clickable_segments":[{"text":"I bought a bottle of ","query":null,"is_phrase":false},{"text":"shampoo","query":"shampoo","is_phrase":false},{"text":".","query":null,"is_phrase":false}]}],
            "synonyms":[],
            "etymology_network":{"root_analysis_zh":"源自古英语 æppel。","related_words":[]},
            "chinglish_warnings":[{"mistake":"I ate apple.","correction":"I ate an apple.","reason_zh":"指一整个苹果时要加冠词。"}],
            "query_type":"zh_term",
            "tts_text":"shampoo",
            "word_segments":[{"text":"shampoo","query":"shampoo","is_phrase":false}],
            "context_adaptation_note":null,
            "scene_companions":[{"en":"body wash","phonetic":"","zh":"沐浴露","relation_zh":"同为洗护用品"},{"en":"conditioner","phonetic":"","zh":"护发素","relation_zh":"同为洗护用品"},{"en":"shower gel","phonetic":"","zh":"沐浴啫喱","relation_zh":"同为洗护用品"}],
            "review_card_summary":{"recognition_front_example_en":"I bought a bottle of shampoo.","recognition_front_example_zh":"我买了一瓶洗发水。","recognition_example_segments":[{"text":"I bought a bottle of ","query":null,"is_phrase":false},{"text":"shampoo","query":"shampoo","is_phrase":false},{"text":".","query":null,"is_phrase":false}],"expression_front_prompt_zh":"洗发水","expression_cloze_en":"I bought a bottle of ___.","expression_cloze_answer_en":"I bought a bottle of shampoo.","back_quick_tip_zh":"通常用 a bottle of shampoo 表达一瓶。"}
        }""".trimIndent()

        val ContextTranslation = """{
            "source_zh":"我今天心情不好",
            "scene_tags":["日常口语","职场沟通"],
            "primary_translation":{"en":"I'm not feeling well emotionally today.","tone":"日常自然","explanation_zh":"委婉表达情绪状态。","tts_text":"I'm not feeling well emotionally today.","clickable_segments":[{"text":"I'm not ","query":null,"is_phrase":false},{"text":"feeling well","query":"feel well","is_phrase":true},{"text":" emotionally today.","query":null,"is_phrase":false}]},
            "alternative_translations":[{"tone":"职场正式","en":"I'll follow up with you later.","note_zh":"适合工作场景。","tts_text":"I'll follow up with you later.","clickable_segments":[{"text":"I'll follow up with you later.","query":null,"is_phrase":false}]},{"tone":"简短口语","en":"I'll let you know later.","note_zh":"适合熟人之间。","tts_text":"I'll let you know later.","clickable_segments":[{"text":"I'll let you know later.","query":null,"is_phrase":false}]}],
            "sentence_skeletons":[{"pattern":"I'll get back to you [time].","meaning_zh":"我会在某个时间回复你。"}],
            "key_chunks":[{"chunk":"get back to","meaning_zh":"回复","usage_note_zh":"to 后接回复对象。"}],
            "chinglish_pitfall":{"bad_translation":"I'll reply you later.","reason_zh":"reply 引出人时要加 to。"},
            "review_card":{"front_prompt_zh":"我今天心情不好","front_hints":["feel well"],"back_key_takeaway_zh":"get back to 后接人。","back_answer_en":"I'm not feeling well emotionally today.","back_answer_tts_text":"I'm not feeling well emotionally today.","back_answer_segments":[{"text":"I'm not ","query":null,"is_phrase":false},{"text":"feeling well","query":"feel well","is_phrase":true},{"text":" emotionally today.","query":null,"is_phrase":false}]},
            "query_type":"zh_sentence",
            "context_adaptation_note":"给国外导师的邮件适合用较克制的方式描述情绪。",
            "scene_companions":[]
        }""".trimIndent()

        val EnglishSentence = """{
            "query_type":"en_sentence",
            "source_en":"They argued but soon made up.",
            "tts_text":"They argued but soon made up.",
            "source_segments":[{"text":"They ","query":null,"is_phrase":false},{"text":"argued","query":"argue","is_phrase":false},{"text":" but soon ","query":null,"is_phrase":false},{"text":"made up","query":"make up","is_phrase":true},{"text":".","query":null,"is_phrase":false}],
            "context_adaptation_note":null,
            "natural_translation_zh":"他们吵了一架，但很快和好了。",
            "tone_and_scene":"叙述争执后的关系修复。",
            "grammar_breakdown_zh":"but 连接两个分句。",
            "sentence_skeletons":[{"pattern":"They [verb] but soon [verb].","meaning_zh":"他们先做某事，但很快又做了另一件事。"}],
            "key_chunks":[{"chunk":"make up","in_sentence":"made up","meaning_zh":"和好","usage_note_zh":"表示争执双方和好。"}],
            "review_card":{"recognition_front_en":"They argued but soon made up.","recognition_back_zh":"他们吵了一架，但很快和好了。","expression_front_zh":"他们吵了一架，但很快和好了。","expression_front_hints":["make up"],"expression_back_en":"They argued but soon made up.","back_key_takeaway_zh":"make up 可表示争吵之后和好。"}
        }""".trimIndent()

        fun reply(content: String) = buildJsonObject {
            put("choices", JsonArray(listOf(buildJsonObject {
                put("finish_reason", "stop")
                put("message", buildJsonObject { put("content", content) })
            })))
        }.toString()
    }
}

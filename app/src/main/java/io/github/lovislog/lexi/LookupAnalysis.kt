package io.github.lovislog.lexi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class EnglishSegment(val text: String, val query: String?, @SerialName("is_phrase") val isPhrase: Boolean)

@Serializable
data class SceneCompanion(
    val en: String,
    val phonetic: String,
    val zh: String,
    @SerialName("relation_zh") val relationZh: String,
)

@Serializable
data class EnglishEquivalent(
    val en: String,
    @SerialName("phonetic_us") val phoneticUs: String = "",
    @SerialName("phonetic_uk") val phoneticUk: String = "",
    val pos: String = "",
    @SerialName("usage_label_zh") val usageLabelZh: String = "",
    @SerialName("nuance_zh") val nuanceZh: String = "",
)

@Serializable
data class WordAnalysis(
    val word: String,
    val syllables: String? = null,
    @SerialName("pos_tags") val posTags: List<String> = emptyList(),
    @SerialName("frequency_level") val frequencyLevel: String = "",
    @SerialName("cefr_level") val cefrLevel: String = "",
    val phonetics: Phonetics = Phonetics("", ""),
    @SerialName("core_image") val coreImage: CoreImage = CoreImage("", ""),
    @SerialName("grammar_skeleton") val grammarSkeleton: List<String> = emptyList(),
    val definitions: List<Sense>,
    val collocations: List<Collocation> = emptyList(),
    @SerialName("context_examples") val contextExamples: List<Example> = emptyList(),
    val synonyms: List<SynonymContrast> = emptyList(),
    @SerialName("etymology_network") val etymologyNetwork: EtymologyNetwork = EtymologyNetwork("", emptyList()),
    @SerialName("chinglish_warnings") val chinglishWarnings: List<ChinglishWarning> = emptyList(),
    @SerialName("review_card_summary") val reviewCardSummary: WordReviewSummary = WordReviewSummary(),
    @SerialName("query_type") val queryType: String? = null,
    @SerialName("tts_text") val ttsText: String = "",
    @SerialName("word_segments") val wordSegments: List<EnglishSegment> = emptyList(),
    @SerialName("context_adaptation_note") val contextAdaptationNote: String? = null,
    @SerialName("scene_companions") val sceneCompanions: List<SceneCompanion> = emptyList(),
    @SerialName("source_zh") val sourceZh: String? = null,
    @SerialName("english_equivalents") val englishEquivalents: List<EnglishEquivalent> = emptyList(),
    @SerialName("sub_type") val chineseSubType: String? = null,
) {
    internal fun isValid() = invalidField() == null

    internal fun invalidField(): String? = when {
        word.isBlank() -> "word"
        definitions.isEmpty() || definitions.any { it.zh.isBlank() || it.en.isBlank() } -> "definitions"
        else -> null
    }

    internal fun toExplanation() = Explanation(
        intent = LookupIntent.UNDERSTAND,
        kind = when {
            queryType == "word" || queryType == "zh_term" -> ContentKind.WORD
            queryType == null && posTags.any { it.equals("sentence", ignoreCase = true) } -> ContentKind.SENTENCE
            queryType == "phrase" || word.any(Char::isWhitespace) -> ContentKind.PHRASE
            else -> ContentKind.WORD
        },
        answerEn = word,
        meaningZh = definitions.first().zh,
        usageEn = definitions.first().en,
        senses = definitions,
        examples = contextExamples,
        wordAnalysis = this,
    )
}

@Serializable
data class Phonetics(val us: String, val uk: String)

@Serializable
data class CoreImage(val zh: String, val en: String)

@Serializable
data class Collocation(
    val phrase: String,
    @SerialName("meaning_zh") val meaningZh: String,
    @SerialName("example_en") val exampleEn: String,
    @SerialName("example_zh") val exampleZh: String,
    @SerialName("tts_text") val ttsText: String = "",
    @SerialName("clickable_segments") val clickableSegments: List<EnglishSegment> = emptyList(),
)

@Serializable
data class SynonymContrast(
    val word: String,
    @SerialName("difference_zh") val differenceZh: String,
    @SerialName("contrast_example") val contrastExample: String,
)

@Serializable
data class EtymologyNetwork(
    @SerialName("root_analysis_zh") val rootAnalysisZh: String,
    @SerialName("related_words") val relatedWords: List<RelatedWord>,
)

@Serializable
data class RelatedWord(val word: String, val pos: String, val zh: String)

@Serializable
data class ChinglishWarning(val mistake: String, val correction: String, @SerialName("reason_zh") val reasonZh: String)

@Serializable
data class WordReviewSummary(
    @SerialName("recognition_front_example_en") val recognitionFrontExampleEn: String = "",
    @SerialName("recognition_front_example_zh") val recognitionFrontExampleZh: String = "",
    @SerialName("expression_cloze_en") val expressionClozeEn: String = "",
    @SerialName("back_quick_tip_zh") val backQuickTipZh: String = "",
    @SerialName("recognition_example_segments") val recognitionExampleSegments: List<EnglishSegment> = emptyList(),
    @SerialName("expression_front_prompt_zh") val expressionFrontPromptZh: String = "",
    @SerialName("expression_cloze_answer_en") val expressionClozeAnswerEn: String = "",
) {
    internal fun hasAlignedCloze(): Boolean {
        val pieces = expressionClozeEn.split("___")
        return pieces.size == 2 &&
            expressionClozeAnswerEn.startsWith(pieces.first()) &&
            expressionClozeAnswerEn.endsWith(pieces.last()) &&
            expressionClozeAnswerEn.length > pieces.first().length + pieces.last().length
    }
}

@Serializable
data class TranslationAnalysis(
    @SerialName("source_zh") val sourceZh: String,
    @SerialName("scene_tags") val sceneTags: List<String> = emptyList(),
    @SerialName("primary_translation") val primaryTranslation: PrimaryTranslation,
    @SerialName("alternative_translations") val alternativeTranslations: List<TranslationVariant> = emptyList(),
    @SerialName("sentence_skeletons") val sentenceSkeletons: List<SentenceSkeleton> = emptyList(),
    @SerialName("key_chunks") val keyChunks: List<KeyChunk> = emptyList(),
    @SerialName("chinglish_pitfall") val chinglishPitfall: TranslationPitfall = TranslationPitfall("", ""),
    @SerialName("review_card") val reviewCard: TranslationReviewSummary,
    @SerialName("query_type") val queryType: String? = null,
    @SerialName("context_adaptation_note") val contextAdaptationNote: String? = null,
    @SerialName("scene_companions") val sceneCompanions: List<SceneCompanion> = emptyList(),
    @SerialName("usage_note_en") val usageNoteEn: String = "",
    @SerialName("context_examples") val contextExamples: List<Example> = emptyList(),
) {
    internal fun isValid() = sourceZh.isNotBlank() && primaryTranslation.en.isNotBlank()

    internal fun toExplanation() = Explanation(
        intent = LookupIntent.EXPRESS,
        kind = ContentKind.SENTENCE,
        answerEn = primaryTranslation.en,
        meaningZh = sourceZh,
        usageEn = primaryTranslation.explanationZh,
        examples = contextExamples,
        alternatives = alternativeTranslations.map { Alternative(it.tone, it.en) },
        translation = this,
    )
}

@Serializable
data class PrimaryTranslation(
    val en: String,
    val tone: String = "",
    @SerialName("explanation_zh") val explanationZh: String = "",
    @SerialName("tts_text") val ttsText: String = "",
    @SerialName("clickable_segments") val clickableSegments: List<EnglishSegment> = emptyList(),
)

@Serializable
data class TranslationVariant(
    val tone: String,
    val en: String,
    @SerialName("note_zh") val noteZh: String,
    @SerialName("tts_text") val ttsText: String = "",
    @SerialName("clickable_segments") val clickableSegments: List<EnglishSegment> = emptyList(),
)

@Serializable
data class SentenceSkeleton(val pattern: String, @SerialName("meaning_zh") val meaningZh: String)

@Serializable
data class KeyChunk(
    val chunk: String,
    @SerialName("meaning_zh") val meaningZh: String,
    @SerialName("usage_note_zh") val usageNoteZh: String,
)

@Serializable
data class TranslationPitfall(
    @SerialName("bad_translation") val badTranslation: String,
    @SerialName("reason_zh") val reasonZh: String,
)

@Serializable
data class TranslationReviewSummary(
    @SerialName("front_prompt_zh") val frontPromptZh: String,
    @SerialName("front_hints") val frontHints: List<String>,
    @SerialName("back_key_takeaway_zh") val backKeyTakeawayZh: String,
    @SerialName("back_answer_en") val backAnswerEn: String = "",
    @SerialName("back_answer_tts_text") val backAnswerTtsText: String = "",
    @SerialName("back_answer_segments") val backAnswerSegments: List<EnglishSegment> = emptyList(),
)

@Serializable
data class EnglishSentenceAnalysis(
    @SerialName("query_type") val queryType: String,
    @SerialName("source_en") val sourceEn: String,
    @SerialName("tts_text") val ttsText: String = "",
    @SerialName("source_segments") val sourceSegments: List<EnglishSegment> = emptyList(),
    @SerialName("context_adaptation_note") val contextAdaptationNote: String? = null,
    @SerialName("natural_translation_zh") val naturalTranslationZh: String,
    @SerialName("tone_and_scene") val toneAndScene: String = "",
    @SerialName("grammar_breakdown_zh") val grammarBreakdownZh: String = "",
    @SerialName("sentence_skeletons") val sentenceSkeletons: List<SentenceSkeleton> = emptyList(),
    @SerialName("key_chunks") val keyChunks: List<EnglishSentenceChunk> = emptyList(),
    @SerialName("review_card") val reviewCard: EnglishSentenceReview,
    @SerialName("scene_companions") val sceneCompanions: List<SceneCompanion> = emptyList(),
    @SerialName("usage_note_en") val usageNoteEn: String = "",
    @SerialName("context_examples") val contextExamples: List<Example> = emptyList(),
) {
    internal fun isValid() = sourceEn.isNotBlank() && naturalTranslationZh.isNotBlank()

    internal fun toExplanation() = Explanation(
        LookupIntent.UNDERSTAND, ContentKind.SENTENCE, sourceEn, naturalTranslationZh, grammarBreakdownZh,
        examples = contextExamples, englishSentence = this,
    )
}

@Serializable
data class EnglishSentenceChunk(
    val chunk: String,
    @SerialName("in_sentence") val inSentence: String,
    @SerialName("meaning_zh") val meaningZh: String,
    @SerialName("usage_note_zh") val usageNoteZh: String,
)

@Serializable
data class EnglishSentenceReview(
    @SerialName("recognition_front_en") val recognitionFrontEn: String,
    @SerialName("recognition_back_zh") val recognitionBackZh: String,
    @SerialName("expression_front_zh") val expressionFrontZh: String,
    @SerialName("expression_front_hints") val expressionFrontHints: List<String>,
    @SerialName("expression_back_en") val expressionBackEn: String,
    @SerialName("back_key_takeaway_zh") val backKeyTakeawayZh: String,
)

internal fun List<EnglishSegment>.recreate(text: String) = text.isNotBlank() &&
    joinToString("") { it.text } == text && all {
        it.text.isNotEmpty() && if (it.query == null) !it.isPhrase
        else it.text.isNotBlank() && LookupInput(it.query, text).isValid()
    }

private val Han = Regex("\\p{sc=Han}")
internal fun String.containsHan() = Han.containsMatchIn(this)
private val Latin = Regex("\\p{sc=Latin}")
private val EnglishSubject = Regex("^(?:I|you|he|she|it|we|they|there|this|that)\\s", RegexOption.IGNORE_CASE)
private val EnglishWords = Regex("\\s+")
private val PhrasalParticles = setOf("away", "back", "by", "down", "for", "in", "into", "off", "on", "out", "over", "through", "to", "up", "with")
private val ChineseClause = Regex("^(?:我[们想要会能该在把被没不已今天]?|你[们想要会能在没不]?|他[们想要会能在没不]?|她[们想要会能在没不]?|请|如何|怎么|能否|可以|今天|昨天|明天).{2,}")

internal enum class LookupFormat { WORD, TRANSLATION, ENGLISH_SENTENCE, MIXED }

internal val LookupInput.format: LookupFormat
    get() {
        val han = Han.containsMatchIn(text)
        val latin = Latin.containsMatchIn(text)
        if (han && latin) return LookupFormat.MIXED
        if (han) {
            return when {
                text.isKnownChineseExpression() -> LookupFormat.WORD
                text.any { it in "。！？!?" } || ChineseClause.containsMatchIn(text) -> LookupFormat.TRANSLATION
                text.any { it in "；，、;" } -> LookupFormat.MIXED
                text.count { Han.matches(it.toString()) } <= 4 -> LookupFormat.WORD
                else -> LookupFormat.MIXED
            }
        }
        val words = text.trim().split(EnglishWords)
        return when {
            words.size == 1 -> LookupFormat.WORD
            text.last() in ".!?" || EnglishSubject.containsMatchIn(text) -> LookupFormat.ENGLISH_SENTENCE
            words.size == 2 && words.last().lowercase() in PhrasalParticles -> LookupFormat.WORD
            else -> LookupFormat.MIXED
        }
    }

@Serializable
internal data class AnalysisRequest(val query: String, @SerialName("user_context") val userContext: String?)

internal fun LookupInput.decodeAnalysis(json: String): Explanation =
    decodeAnalysis(lexiJson.parseToJsonElement(json) as? JsonObject ?: throw invalidAnalysis())

internal fun LookupInput.decodeAnalysis(
    payload: JsonObject,
    expectedFormat: LookupFormat = format,
    exactSource: Boolean = expectedFormat != LookupFormat.MIXED ||
        !(Han.containsMatchIn(text) && Latin.containsMatchIn(text)),
): Explanation {
    val type = (payload["query_type"] as? JsonPrimitive)?.content ?: throw AnalysisFormatFailure("query_type")
    val schema = lookupSchemas[type] ?: throw AnalysisFormatFailure("query_type")
    val actualFormat = when (type) {
        "zh_sentence" -> LookupFormat.TRANSLATION
        "en_sentence" -> LookupFormat.ENGLISH_SENTENCE
        else -> LookupFormat.WORD
    }
    val incompatibleFormat = expectedFormat != LookupFormat.MIXED && expectedFormat != actualFormat
    val sourceIsChinese = type == "zh_term" || type == "zh_sentence"
    val wrongLanguage = exactSource && sourceIsChinese != text.containsHan()
    if (incompatibleFormat || wrongLanguage) throw AnalysisFormatFailure("query_type")
    val data = payload.compatibleFields(schema).toMutableMap()
    val analysis = when (actualFormat) {
        LookupFormat.WORD -> decodeWord(payload, schema, data, type).toExplanation()
        LookupFormat.TRANSLATION -> decodeChineseSentence(payload, schema, data).toExplanation()
        LookupFormat.ENGLISH_SENTENCE -> decodeEnglishSentence(payload, schema, data).toExplanation()
        LookupFormat.MIXED -> error("A response must use a single schema.")
    }
    val source = analysis.englishSentence?.sourceEn ?: analysis.translation?.sourceZh ?: analysis.wordAnalysis?.sourceZh
    if (exactSource && source != null && source != text) {
        throw AnalysisFormatFailure(if (sourceIsChinese) "source_zh" else "source_en")
    }
    return analysis.copy(analysisPayload = payload, analysisSchemaVersion = LookupSchemaVersion)
}

private fun LookupInput.decodeWord(
    payload: JsonObject, schema: JsonObject, data: MutableMap<String, JsonElement>, type: String,
): WordAnalysis {
    val word = payload.requiredText("word")
    val definitions = data["definitions"] as? JsonArray
    if (definitions.isNullOrEmpty()) throw AnalysisFormatFailure("definitions")
    val review = payload.optionalObject("review_card_summary", schema)
    data["review_card_summary"] = review
    val raw = lexiJson.decodeFromJsonElement<WordAnalysis>(JsonObject(data))
    raw.invalidField()?.let { throw AnalysisFormatFailure(it) }
    val chinese = type == "zh_term"
    val candidates = raw.englishEquivalents.filter { LookupInput(it.en).isValid() && !it.en.containsHan() }
        .distinctBy { it.en.lowercase() }.take(3)
    val equivalents = if (chinese) candidates.ifEmpty {
        listOf(EnglishEquivalent(word, raw.phonetics.us, raw.phonetics.uk, raw.posTags.firstOrNull().orEmpty()))
    } else emptyList()
    val head = equivalents.firstOrNull()?.en ?: word
    val examples = raw.contextExamples.map {
        it.copy(ttsText = it.ttsText.ifBlank { it.en }, clickableSegments = it.clickableSegments.alignedWith(it.en))
    }
    val firstExample = examples.firstOrNull()
    val card = raw.reviewCardSummary
    val hasRecognitionExample = card.recognitionFrontExampleEn.isNotBlank() && card.recognitionFrontExampleZh.isNotBlank()
    val recognitionEn = if (hasRecognitionExample) card.recognitionFrontExampleEn else firstExample?.en.orEmpty()
    val recognitionZh = if (hasRecognitionExample) card.recognitionFrontExampleZh else firstExample?.zh.orEmpty()
    val recognitionSegments = if (hasRecognitionExample) card.recognitionExampleSegments.alignedWith(recognitionEn)
        else firstExample?.clickableSegments.orEmpty()
    val clozeMatch = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(head)}(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE).find(recognitionEn)
    val hasCloze = card.hasAlignedCloze()
    return raw.copy(
        word = head, ttsText = if (head == word) raw.ttsText.ifBlank { head } else head,
        wordSegments = raw.wordSegments.alignedWith(head),
        contextExamples = examples,
        collocations = raw.collocations.map {
            it.copy(ttsText = it.ttsText.ifBlank { it.exampleEn }, clickableSegments = it.clickableSegments.alignedWith(it.exampleEn))
        },
        sourceZh = if (chinese) raw.sourceZh?.takeIf(String::isNotBlank) ?: text else null,
        englishEquivalents = equivalents,
        chineseSubType = raw.chineseSubType?.takeIf { chinese && it in setOf("zh_vocab", "zh_idiom") },
        sceneCompanions = raw.sceneCompanions.usable(),
        contextAdaptationNote = usableContext(raw.contextAdaptationNote),
        reviewCardSummary = card.copy(
            recognitionFrontExampleEn = recognitionEn, recognitionFrontExampleZh = recognitionZh,
            recognitionExampleSegments = recognitionSegments,
            expressionFrontPromptZh = card.expressionFrontPromptZh.ifBlank { raw.definitions.first().zh },
            expressionClozeEn = if (hasCloze) card.expressionClozeEn
                else clozeMatch?.let { recognitionEn.replaceRange(it.range, "___") }.orEmpty(),
            expressionClozeAnswerEn = if (hasCloze) card.expressionClozeAnswerEn
                else recognitionEn.takeIf { clozeMatch != null }.orEmpty(),
            backQuickTipZh = card.backQuickTipZh.ifBlank { raw.definitions.first().zh },
        ),
    )
}

private fun LookupInput.decodeChineseSentence(
    payload: JsonObject, schema: JsonObject, data: MutableMap<String, JsonElement>,
): TranslationAnalysis {
    val source = payload.requiredText("source_zh")
    val primary = payload["primary_translation"] as? JsonObject ?: throw AnalysisFormatFailure("primary_translation.en")
    val english = primary.requiredText("en", "primary_translation.en")
    val draft = lexiJson.decodeFromJsonElement<PrimaryTranslation>(payload.optionalObject("primary_translation", schema))
    val translation = draft.copy(
        ttsText = draft.ttsText.ifBlank { english },
        clickableSegments = draft.clickableSegments.alignedWith(english),
    )
    val review = payload.optionalObject("review_card", schema)
    val card = TranslationReviewSummary(
        source, review.hints("front_hints"), review.text("back_key_takeaway_zh").ifBlank { draft.explanationZh },
        english, translation.ttsText, translation.clickableSegments,
    )
    data["primary_translation"] = lexiJson.encodeToJsonElement(translation)
    data["review_card"] = lexiJson.encodeToJsonElement(card)
    val raw = lexiJson.decodeFromJsonElement<TranslationAnalysis>(JsonObject(data))
    return raw.copy(
        sceneCompanions = raw.sceneCompanions.usable(),
        contextAdaptationNote = usableContext(raw.contextAdaptationNote),
        alternativeTranslations = raw.alternativeTranslations.map {
            it.copy(ttsText = it.ttsText.ifBlank { it.en }, clickableSegments = it.clickableSegments.alignedWith(it.en))
        },
    )
}

private fun LookupInput.decodeEnglishSentence(
    payload: JsonObject, schema: JsonObject, data: MutableMap<String, JsonElement>,
): EnglishSentenceAnalysis {
    val source = payload.requiredText("source_en")
    val translation = payload.requiredText("natural_translation_zh")
    val review = payload.optionalObject("review_card", schema)
    data["review_card"] = lexiJson.encodeToJsonElement(
        EnglishSentenceReview(
            source, translation, translation, review.hints("expression_front_hints"), source,
            review.text("back_key_takeaway_zh").ifBlank { payload.text("grammar_breakdown_zh") },
        ),
    )
    val raw = lexiJson.decodeFromJsonElement<EnglishSentenceAnalysis>(JsonObject(data))
    return raw.copy(
        ttsText = raw.ttsText.ifBlank { source },
        sourceSegments = raw.sourceSegments.alignedWith(source),
        sceneCompanions = raw.sceneCompanions.usable(),
        contextAdaptationNote = usableContext(raw.contextAdaptationNote),
    )
}

private fun JsonObject.text(field: String) =
    (get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.requiredText(field: String, label: String = field) =
    text(field).takeIf { it.isNotBlank() && it.length <= 1600 } ?: throw AnalysisFormatFailure(label)

private fun JsonObject.hints(field: String) = (get(field) as? JsonArray).orEmpty()
    .mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString && value.content.isNotBlank() }?.content }

private fun JsonObject.compatibleFields(schema: JsonObject): Map<String, JsonElement> =
    filter { (key, value) -> schema[key]?.let { value.schemaIssue(it, key) == null } == true }

private fun JsonObject.optionalObject(key: String, schema: JsonObject): JsonObject =
    JsonObject((get(key) as? JsonObject)?.compatibleFields(schema.getValue(key) as JsonObject).orEmpty())

private fun List<EnglishSegment>.alignedWith(text: String) = takeIf { it.recreate(text) }.orEmpty()

private fun List<SceneCompanion>.usable() = filter { LookupInput(it.en).isValid() && it.zh.isNotBlank() }

private fun LookupInput.usableContext(note: String?) = note?.takeIf { context.isNotBlank() && it.isNotBlank() }

private fun JsonElement.schemaIssue(schema: JsonElement, path: String): String? {
    val label = path.ifEmpty { "JSON" }
    return when (schema) {
        is JsonObject -> {
            if (this !is JsonObject) return label
            val issue = schema.entries.firstNotNullOfOrNull { (key, shape) ->
                val field = if (path.isEmpty()) key else "$path.$key"
                val value = get(key)
                if (value == null) field else value.schemaIssue(shape, field)
            }
            issue ?: label.takeIf { keys.size != schema.keys.size }
        }
        is JsonArray -> {
            if (this !is JsonArray) return label
            withIndex().firstNotNullOfOrNull { (index, item) ->
                item.schemaIssue(schema.single(), "$path[$index]")
            }
        }
        is JsonPrimitive -> {
            val valid = when (schema.content) {
                "string" -> this is JsonPrimitive && isString
                "string | null" -> this == JsonNull || this is JsonPrimitive && isString
                "boolean" -> this is JsonPrimitive && !isString && booleanOrNull != null
                else -> this is JsonPrimitive && isString && content in schema.content.split(" | ")
            }
            label.takeUnless { valid }
        }
    }
}


@Serializable
private data class VisionAnalysis(
    @SerialName("extracted_text") val extractedText: String,
    @SerialName("detected_type") val detectedType: String,
    val payload: JsonObject,
)

internal fun decodeVisionAnalysis(json: String, context: String): LookupResult {
    val root = lexiJson.parseToJsonElement(json) as? JsonObject ?: throw invalidAnalysis()
    if (root["error"] == JsonPrimitive("unreadable")) {
        throw LookupFailure("未识别到清晰文字，请重新框选", "No clear text found. Adjust the selection and try again.")
    }
    if (root.keys != setOf("extracted_text", "detected_type", "payload")) throw invalidAnalysis()
    val vision = lexiJson.decodeFromJsonElement<VisionAnalysis>(root)
    val format = when (vision.detectedType) {
        "word_or_phrase" -> LookupFormat.WORD
        "zh_sentence" -> LookupFormat.TRANSLATION
        "en_sentence" -> LookupFormat.ENGLISH_SENTENCE
        else -> throw invalidAnalysis()
    }
    val extracted = LookupInput(vision.extractedText, context).normalized()
    if (!extracted.isValid()) throw invalidAnalysis()
    val explanation = LookupInput(vision.extractedText, extracted.context).decodeAnalysis(vision.payload, format)
    return LookupResult(extracted, explanation)
}

internal class AnalysisFormatFailure(val field: String) : LookupFailure(
    "AI 返回的 $field 未通过校验。",
    "The model returned an invalid value or structure for $field.",
)

private fun invalidAnalysis() = AnalysisFormatFailure("JSON")

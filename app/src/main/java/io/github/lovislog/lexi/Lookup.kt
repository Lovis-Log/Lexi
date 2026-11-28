package io.github.lovislog.lexi

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

internal val lexiJson = Json { ignoreUnknownKeys = true }

@Serializable
enum class LookupIntent { UNDERSTAND, EXPRESS }

@Serializable
enum class ContentKind(val label: String) {
    WORD("单词"), PHRASE("短语"), SENTENCE("句子")
}

@Serializable
data class Example(
    val en: String, val zh: String, val scene: String = "",
    @SerialName("tts_text") val ttsText: String = "",
    @SerialName("clickable_segments") val clickableSegments: List<EnglishSegment> = emptyList(),
)

@Serializable
data class Sense(val pos: String, val en: String, val zh: String)

@Serializable
data class Alternative(val label: String, val en: String)

@Serializable
data class Explanation(
    val intent: LookupIntent,
    val kind: ContentKind,
    val answerEn: String,
    val meaningZh: String,
    val usageEn: String,
    val tag: String = "",
    val ipa: String = "",
    val senses: List<Sense> = emptyList(),
    val examples: List<Example> = emptyList(),
    val alternatives: List<Alternative> = emptyList(),
    val partOfSpeech: String = "",
    val frequency: String = "",
    val cefr: String = "",
    val syllables: String = "",
    val ipaUs: String = "",
    val ipaUk: String = "",
    val grammar: String = "",
    val imagery: String = "",
    val collocations: List<Alternative> = emptyList(),
    val synonymNotes: List<Alternative> = emptyList(),
    val etymology: String = "",
    val memoryNetwork: String = "",
    val pitfalls: String = "",
    val wordAnalysis: WordAnalysis? = null,
    val translation: TranslationAnalysis? = null,
    val englishSentence: EnglishSentenceAnalysis? = null,
    val analysisPayload: JsonObject? = null,
    val analysisSchemaVersion: Int? = null,
)

@Serializable
data class LookupInput(val text: String, val context: String = "") {
    fun normalized() = LookupInput(text.trim(), context.trim())

    fun isValid() = text.isNotBlank() && text.length <= MaxQueryLength &&
        context.length <= MaxContextLength && '\u0000' !in text && '\u0000' !in context

    val identity: String
        get() = sha256(canonicalText + '\u0000' + collapseWhitespace(context))

    internal val canonicalText: String get() = collapseWhitespace(text)

    private fun collapseWhitespace(value: String) = value.trim().replace(Whitespace, " ")

    companion object {
        internal const val MaxQueryLength = 800
        internal const val MaxContextLength = 1600
        private val Whitespace = Regex("\\s+")
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class LookupResult(
    val input: LookupInput,
    val explanation: Explanation,
    @EncodeDefault @SerialName("sub_type") val subType: LibrarySubType = classifyEntry(input, explanation),
    @EncodeDefault @SerialName("lang_group") val langGroup: LibraryLanguage = subType.language,
) {
    val id: String get() = input.identity
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal val LookupResult.reviewWordKey: String
    get() = LookupInput(explanation.answerEn).canonicalText.lowercase(Locale.ROOT)

internal val Explanation.hasAnalysis: Boolean get() = wordAnalysis != null || translation != null || englishSentence != null
internal val Explanation.analysisVersion: Int get() = when {
    analysisPayload != null -> analysisSchemaVersion ?: 4
    hasAnalysis -> 3
    else -> 1
}
internal val Explanation.contextAdaptationNote: String?
    get() = wordAnalysis?.contextAdaptationNote ?: translation?.contextAdaptationNote ?: englishSentence?.contextAdaptationNote
internal val Explanation.sceneCompanions: List<SceneCompanion>
    get() = wordAnalysis?.sceneCompanions ?: translation?.sceneCompanions ?: englishSentence?.sceneCompanions.orEmpty()

internal val Explanation.headSegments: List<EnglishSegment>
    get() = wordAnalysis?.wordSegments ?: translation?.primaryTranslation?.clickableSegments ?: englishSentence?.sourceSegments.orEmpty()

internal fun Explanation.isValid(): Boolean {
    if (answerEn.isBlank() || meaningZh.isBlank() ||
        answerEn.length > 1600 || meaningZh.length > 1600 || usageEn.length > 1600) return false
    if (listOfNotNull(wordAnalysis, translation, englishSentence).size > 1) return false
    wordAnalysis?.let { return intent == LookupIntent.UNDERSTAND && it.isValid() }
    translation?.let { return intent == LookupIntent.EXPRESS && it.isValid() }
    englishSentence?.let { return intent == LookupIntent.UNDERSTAND && it.isValid() }
    return usageEn.isNotBlank() && tag.length <= 80 && ipa.length <= 120 && senses.size <= 5 && examples.size <= 6 &&
        alternatives.size <= 3 && collocations.size <= 6 && synonymNotes.size <= 6 &&
        listOf(partOfSpeech, frequency, cefr, syllables, ipaUs, ipaUk).all { it.length <= 120 } &&
        listOf(grammar, imagery, etymology, memoryNetwork, pitfalls).all { it.length <= 1600 } &&
        (intent != LookupIntent.UNDERSTAND || kind != ContentKind.WORD || senses.isNotEmpty()) &&
        senses.all { it.pos.length <= 80 && it.en.isNotBlank() && it.en.length <= 500 &&
            it.zh.isNotBlank() && it.zh.length <= 500 } &&
        examples.all { it.scene.length <= 80 && it.en.isNotBlank() && it.en.length <= 500 &&
            it.zh.isNotBlank() && it.zh.length <= 500 } &&
        (alternatives + collocations + synonymNotes).all { it.label.isNotBlank() && it.label.length <= 80 &&
            it.en.isNotBlank() && it.en.length <= 500 }
}

package io.github.lovislog.lexi

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val lexiJson = Json { ignoreUnknownKeys = true }

@Serializable
enum class LookupIntent { UNDERSTAND, EXPRESS }

@Serializable
enum class ContentKind(val label: String) {
    WORD("单词"), PHRASE("短语"), SENTENCE("句子")
}

@Serializable
data class Example(val en: String, val zh: String)

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
)

@Serializable
data class LookupInput(val text: String, val context: String = "") {
    fun normalized() = LookupInput(normalize(text), normalize(context))

    fun isValid() = text.isNotBlank() && text.length <= 800 &&
        context.length <= 800 && '\u0000' !in text && '\u0000' !in context

    val identity: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest((text + '\u0000' + context).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun normalize(value: String) = value.trim().replace(Regex("\\s+"), " ")
}

@Serializable
data class LookupResult(val input: LookupInput, val explanation: Explanation) {
    val id: String get() = input.identity
}

internal fun Explanation.isValid(): Boolean {
    return answerEn.isNotBlank() && meaningZh.isNotBlank() && usageEn.isNotBlank() &&
        answerEn.length <= 1600 && meaningZh.length <= 1600 && usageEn.length <= 1600 &&
        tag.length <= 80 && ipa.length <= 120 && senses.size <= 3 && examples.size <= 2 &&
        alternatives.size <= 3 &&
        (intent != LookupIntent.UNDERSTAND || kind != ContentKind.WORD || senses.isNotEmpty()) &&
        senses.all { it.pos.length <= 80 && it.en.isNotBlank() && it.en.length <= 500 &&
            it.zh.isNotBlank() && it.zh.length <= 500 } &&
        examples.all { it.en.isNotBlank() && it.en.length <= 500 &&
            it.zh.isNotBlank() && it.zh.length <= 500 } &&
        alternatives.all { it.label.isNotBlank() && it.label.length <= 80 &&
            it.en.isNotBlank() && it.en.length <= 500 }
}

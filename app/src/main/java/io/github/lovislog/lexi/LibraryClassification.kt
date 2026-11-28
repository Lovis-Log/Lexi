package io.github.lovislog.lexi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LibraryLanguage(val zh: String, val en: String) {
    @SerialName("en") EN("英文", "English"),
    @SerialName("zh") ZH("中文", "Chinese"),
}

@Serializable
enum class LibrarySubType(val language: LibraryLanguage, val zh: String, val en: String) {
    @SerialName("word") WORD(LibraryLanguage.EN, "单词", "Word"),
    @SerialName("phrase") PHRASE(LibraryLanguage.EN, "短语", "Phrase"),
    @SerialName("sentence") SENTENCE(LibraryLanguage.EN, "句子", "Sentence"),
    @SerialName("zh_vocab") ZH_VOCAB(LibraryLanguage.ZH, "词汇", "Vocabulary"),
    @SerialName("zh_idiom") ZH_IDIOM(LibraryLanguage.ZH, "表达", "Expression"),
    @SerialName("zh_sentence") ZH_SENTENCE(LibraryLanguage.ZH, "句子", "Sentence"),
    ;

    internal fun label(english: Boolean = false) =
        if (english) "${language.en} · $en" else "${language.zh} · $zh"
}

internal fun classifyEntry(input: LookupInput, answer: Explanation): LibrarySubType {
    val word = answer.wordAnalysis
    val chinese = answer.translation != null || word?.queryType == "zh_term" ||
        input.text.containsHan() || answer.answerEn.containsHan()
    if (!chinese) return when (word?.queryType) {
        "word" -> LibrarySubType.WORD
        "phrase" -> LibrarySubType.PHRASE
        else -> when (answer.kind) {
            ContentKind.WORD -> LibrarySubType.WORD
            ContentKind.PHRASE -> LibrarySubType.PHRASE
            ContentKind.SENTENCE -> LibrarySubType.SENTENCE
        }
    }
    if (answer.translation != null || (word?.queryType != "zh_term" && answer.kind == ContentKind.SENTENCE)) {
        return LibrarySubType.ZH_SENTENCE
    }
    return when (word?.chineseSubType) {
        "zh_idiom" -> LibrarySubType.ZH_IDIOM
        "zh_vocab" -> LibrarySubType.ZH_VOCAB
        else -> {
            val labels = word?.posTags.orEmpty() + answer.partOfSpeech + answer.tag + answer.senses.map { it.pos }
            val fixedExpression = labels.any { ChineseExpressionLabel.containsMatchIn(it) } ||
                input.text.isKnownChineseExpression()
            if (fixedExpression) LibrarySubType.ZH_IDIOM else LibrarySubType.ZH_VOCAB
        }
    }
}

private val ChineseExpressionLabel = Regex("成语|熟语|俗语|惯用语|固定说法|\\bidiom(?:atic)?\\b", RegexOption.IGNORE_CASE)
internal fun String.isKnownChineseExpression() = trim() in CommonChineseExpressions

private val CommonChineseExpressions = setOf(
    "半途而废", "画蛇添足", "守株待兔", "亡羊补牢", "杯弓蛇影", "一举两得", "一石二鸟", "滥竽充数",
    "对牛弹琴", "自相矛盾", "一帆风顺", "实事求是", "恍然大悟", "水到渠成", "不知所措", "事半功倍",
    "随心所欲", "莫名其妙", "无能为力", "得不偿失", "开门见山", "雪中送炭", "一针见血", "手忙脚乱",
    "另请高明", "走后门", "打圆场", "碰钉子", "我行我素", "说曹操曹操到", "说曹操，曹操到",
    "塞翁失马，焉知非福",
)

internal val LookupResult.chineseHeadword: String
    get() = explanation.wordAnalysis?.sourceZh?.takeIf(String::isNotBlank) ?: explanation.translation?.sourceZh ?: input.text

internal val LookupResult.englishEquivalents: List<EnglishEquivalent>
    get() {
        val word = explanation.wordAnalysis
        return word?.englishEquivalents?.takeIf { it.isNotEmpty() } ?: listOf(
            EnglishEquivalent(
                explanation.answerEn,
                word?.phonetics?.us.orEmpty().ifBlank { explanation.ipaUs.ifBlank { explanation.ipa } },
                word?.phonetics?.uk.orEmpty().ifBlank { explanation.ipaUk },
                word?.posTags?.firstOrNull() ?: explanation.partOfSpeech,
            ),
        )
    }

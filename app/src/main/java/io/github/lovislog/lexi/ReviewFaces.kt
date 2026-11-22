package io.github.lovislog.lexi

internal data class ReviewFace(
    val title: String,
    val titleSegments: List<EnglishSegment> = emptyList(),
    val supportingText: String = "",
    val supportingSegments: List<EnglishSegment> = emptyList(),
    val notes: List<String> = emptyList(),
    val hints: List<String> = emptyList(),
)

internal data class ReviewFaces(val front: ReviewFace, val back: ReviewFace)

internal fun LookupResult.reviewFaces(expressionMode: Boolean): ReviewFaces {
    val answer = explanation
    answer.translation?.let { translation ->
        val card = translation.reviewCard
        return ReviewFaces(
            ReviewFace(card.frontPromptZh, hints = card.frontHints),
            ReviewFace(card.backAnswerEn.ifBlank { answer.answerEn }, card.backAnswerSegments,
                notes = listOf(card.backKeyTakeawayZh)),
        )
    }
    answer.englishSentence?.let { sentence ->
        val card = sentence.reviewCard
        return if (expressionMode) {
            ReviewFaces(
                ReviewFace(card.expressionFrontZh, hints = card.expressionFrontHints),
                ReviewFace(card.expressionBackEn, sentence.sourceSegments),
            )
        } else {
            ReviewFaces(
                ReviewFace(card.recognitionFrontEn, sentence.sourceSegments),
                ReviewFace(card.recognitionBackZh, notes = listOf(card.backKeyTakeawayZh)),
            )
        }
    }
    answer.wordAnalysis?.let { word ->
        val card = word.reviewCardSummary
        return if (expressionMode) {
            val clozeAnswer = card.expressionClozeAnswerEn.ifBlank {
                val pieces = card.expressionClozeEn.split("___")
                card.recognitionFrontExampleEn.takeIf {
                    pieces.size == 2 && it.startsWith(pieces.first()) && it.endsWith(pieces.last()) &&
                        it.length > pieces.first().length + pieces.last().length
                }.orEmpty()
            }
            ReviewFaces(
                ReviewFace(card.expressionFrontPromptZh.ifBlank { answer.meaningZh },
                    supportingText = card.expressionClozeEn.takeIf { clozeAnswer.isNotBlank() }.orEmpty()),
                ReviewFace(word.word, word.wordSegments, supportingText = clozeAnswer),
            )
        } else {
            val definitions = word.definitions.map { it.zh }
            val collocations = word.collocations.take(2).map { it.phrase + "  " + it.meaningZh }
            ReviewFaces(
                ReviewFace(word.word, word.wordSegments, card.recognitionFrontExampleEn, card.recognitionExampleSegments),
                ReviewFace(definitions.joinToString(" / "), supportingText = card.recognitionFrontExampleZh,
                    notes = collocations + card.backQuickTipZh),
            )
        }
    }
    return ReviewFaces(
        ReviewFace(input.text, supportingText = input.context),
        ReviewFace(answer.answerEn, notes = listOf(answer.meaningZh, answer.usageEn)),
    )
}
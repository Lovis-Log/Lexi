package io.github.lovislog.lexi

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

internal fun writeVocabularyPdf(output: OutputStream, cards: List<SavedCard>, english: Boolean) {
    val width = 595
    val height = 842
    val margin = 44
    val pdf = PdfDocument()
    var pageNumber = 0
    var page: PdfDocument.Page? = null

    fun finishPage() {
        page?.let(pdf::finishPage)
        page = null
    }

    fun newPage() {
        finishPage()
        page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, ++pageNumber).create())
    }

    try {
        newPage()
        var y = margin
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 34, 54) }

        fun draw(text: String, size: Float = 11f, bold: Boolean = false, spacing: Int = 7) {
            if (text.isBlank()) return
            paint.textSize = size
            paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - 2 * margin)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(3f, 1f)
                .build()
            var first = 0
            while (first < layout.lineCount) {
                val top = layout.getLineTop(first)
                var end = first
                while (end < layout.lineCount && y + layout.getLineBottom(end) - top <= height - margin) end++
                if (end == first) {
                    newPage()
                    y = margin
                    continue
                }
                val chunkHeight = layout.getLineBottom(end - 1) - top
                checkNotNull(page).canvas.run {
                    save()
                    clipRect(margin, y, width - margin, y + chunkHeight)
                    translate(margin.toFloat(), (y - top).toFloat())
                    layout.draw(this)
                    restore()
                }
                y += chunkHeight
                first = end
                if (first < layout.lineCount) {
                    newPage()
                    y = margin
                }
            }
            y += spacing
        }

        draw(if (english) "Lexi Vocabulary" else "Lexi 词库", size = 26f, bold = true, spacing = 16)
        draw(if (english) "${cards.size} cards" else "${cards.size} 张卡片", spacing = 20)
        cards.forEach { card ->
            if (y > height - margin - 72) {
                newPage()
                y = margin
            }
            val answer = card.result.explanation
            draw(answer.answerEn, size = 16f, bold = true)
            draw(answer.meaningZh)
            if (card.result.input.context.isNotBlank()) draw(card.result.input.context)
            answer.senses.forEach { draw("${it.pos}  ${it.en}\n${it.zh}") }
            answer.examples.forEach { draw("${it.en}\n${it.zh}") }
            val word = answer.wordAnalysis
            if (word != null) {
                word.collocations.forEach { draw("${it.phrase}  ${it.meaningZh}\n${it.exampleEn}\n${it.exampleZh}") }
                draw(word.coreImage.zh, spacing = 19)
            } else {
                answer.collocations.forEach { draw("${it.label}  ${it.en}") }
                draw(answer.usageEn, spacing = 19)
            }
        }
        finishPage()
        pdf.writeTo(output)
    } finally {
        try {
            finishPage()
        } finally {
            pdf.close()
        }
    }
}

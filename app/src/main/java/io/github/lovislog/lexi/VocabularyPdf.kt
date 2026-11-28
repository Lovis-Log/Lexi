package io.github.lovislog.lexi

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import java.io.OutputStream
import java.time.LocalDate
import kotlin.math.ceil

private const val PageWidth = 595
private const val PageHeight = 842
private const val A4Top = 51f
private const val A4Side = 45.35f
private const val A4Bottom = 56.69f
private const val ContentWidth = PageWidth - 2 * A4Side
private const val ContentHeight = PageHeight - A4Top - A4Bottom
private const val CardGap = 9f
private const val BrandLine = "一词一境，温故知新"
private const val NoData = "暂无"

private val Ink = 0xFF1E293B.toInt()
private val Secondary = 0xFF475569.toInt()
private val Muted = 0xFF64748B.toInt()
private val Border = 0xFFE2E8F0.toInt()
private val Pale = 0xFFF8FAFE.toInt()
private val Capsule = 0xFFF1F5F9.toInt()
private val Purple = 0xFF6D28D9.toInt()
private val Blue = 0xFF1A73E8.toInt()

internal fun writeVocabularyPdf(output: OutputStream, cards: List<SavedCard>) {
    val pages = mutableListOf(PdfBlock(ContentWidth))
    var current = pages.last()
    current.stack(vocabularyHeader(cards, LocalDate.now()), 15f)

    LibraryLanguage.entries.forEach { language ->
        val library = cards.filter { it.result.langGroup == language }
        if (library.isEmpty()) return@forEach
        var languageHeading: PdfBlock? = languageHeading(language)
        LibrarySubType.entries.filter { it.language == language }.forEach { type ->
            val entries = library.filter { it.result.subType == type }
            var typeHeading: PdfBlock? = typeHeading(type)
            entries.forEach { entry ->
                val card = vocabularyCard(entry.result)
                val headings = listOfNotNull(languageHeading, typeHeading)
                val headingHeight = headings.sumOf { (it.height + CardGap).toDouble() }.toFloat()
                val scale = minOf(1f, (ContentHeight - headingHeight) / card.height)
                if (current.height + headingHeight + card.height * scale > ContentHeight + 0.01f) {
                    current = PdfBlock(ContentWidth)
                    pages.add(current)
                }
                headings.forEach { current.stack(it, CardGap) }
                current.stack(card, CardGap, scale)
                languageHeading = null
                typeHeading = null
            }
        }
    }
    if (cards.isEmpty()) current.line("词库暂无已收录的词条。", color = Muted)

    val footer = PdfBlock(ContentWidth)
    footer.line("Lexi · $BrandLine", size = 9f, color = Muted)
    val pdf = PdfDocument()
    try {
        pages.forEachIndexed { index, content ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(PageWidth, PageHeight, index + 1).create())
            try {
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                content.draw(canvas, A4Side, A4Top)
                footer.draw(canvas, A4Side, PageHeight - 34f)
                PdfBlock(ContentWidth).apply {
                    line("第 ${index + 1} 页 / 共 ${pages.size} 页", size = 9f, color = Muted, right = true)
                }.draw(canvas, A4Side, PageHeight - 34f)
            } finally {
                pdf.finishPage(page)
            }
        }
        pdf.writeTo(output)
    } finally {
        pdf.close()
    }
}

private fun vocabularyHeader(cards: List<SavedCard>, date: LocalDate) = PdfBlock(ContentWidth).apply {
    val logoSize = 31.5f
    operations.add { canvas -> drawLogo(canvas, 0f, 5f, logoSize) }
    val brand = PdfBlock(230f).apply {
        line(styledLine("Lexi", "  |  $BrandLine", firstColor = Ink, secondColor = Secondary, firstSize = 22),
            size = 11f)
        line("个人双语语境词库导出手册", size = 9.5f, color = Muted)
    }
    val meta = PdfBlock(168f).apply {
        val month = date.monthValue.toString().padStart(2, '0')
        val day = date.dayOfMonth.toString().padStart(2, '0')
        line("导出日期：${date.year}年${month}月${day}日", color = Secondary, size = 9.5f, right = true)
        line("词库范围：全部已收录词条", color = Secondary, size = 9.5f, right = true)
    }
    place(brand, logoSize + 10f, 0f)
    place(meta, width - meta.width, maxOf(0f, brand.height - meta.height))
    height = maxOf(logoSize + 5f, brand.height, meta.height) + 10f
    rect(0f, height, width, 1.5f, Purple)
    height += 13.5f

    val englishCount = cards.count { it.result.langGroup == LibraryLanguage.EN }
    val stats = listOf("词条总数" to "${cards.size} 条", "英文词库" to "$englishCount 条",
        "中文词库" to "${cards.size - englishCount} 条", "调度引擎" to "FSRS")
    val gap = 7f
    val boxWidth = (width - gap * (stats.size - 1)) / stats.size
    val tiles = stats.map { (label, value) ->
        PdfBlock(boxWidth - 18f).apply {
            line(label, size = 9.5f, color = Secondary)
            line(value, size = 13f, bold = true, gap = 1f)
        }
    }
    val tileHeight = tiles.maxOf { it.height } + 15f
    tiles.forEachIndexed { index, tile ->
        val x = index * (boxWidth + gap)
        rect(x, height, boxWidth, tileHeight, Pale, radius = 6f, border = Border)
        place(tile, x + 9f, height + 7f)
    }
    height += tileHeight
}

private fun languageHeading(language: LibraryLanguage) = PdfBlock(ContentWidth).apply {
    val english = language == LibraryLanguage.EN
    val text = PdfBlock(width - 22f).apply {
        line("${if (english) "一" else "二"}、${language.zh}词库（${language.en}）", size = 14f, bold = true)
    }
    height = text.height + 12f
    rect(0f, 0f, width, height, Pale, radius = 4f)
    rect(0f, 0f, 3f, height, if (english) Blue else Purple)
    place(text, 12f, 6f)
}

private fun typeHeading(type: LibrarySubType) = PdfBlock(ContentWidth).apply {
    line(type.zh, size = 10.5f, color = if (type.language == LibraryLanguage.EN) Blue else Purple, bold = true)
}

private fun vocabularyCard(result: LookupResult): PdfBlock {
    val answer = result.explanation
    val word = answer.wordAnalysis
    val sentence = answer.englishSentence
    val translation = answer.translation
    val chinese = result.langGroup == LibraryLanguage.ZH
    val isSentence = result.subType in setOf(LibrarySubType.ZH_SENTENCE, LibrarySubType.SENTENCE)
    val title = if (chinese) result.chineseHeadword else sentence?.sourceEn ?: answer.answerEn
    val pos = word?.posTags.orEmpty().filter(String::isNotBlank).joinToString(" / ")
        .ifBlank { answer.partOfSpeech.ifBlank { answer.senses.map { it.pos }.filter(String::isNotBlank).distinct().joinToString(" / ") } }
    val cefr = word?.cefrLevel.orEmpty().ifBlank { answer.cefr }
    val headerTags = listOf(result.subType.label(), pos, cefr).filter(String::isNotBlank)
    val senses = if (isSentence) {
        listOf(Sense("用法",
            sentence?.usageNoteEn.orEmpty().ifBlank { translation?.usageNoteEn.orEmpty() }
                .ifBlank { answer.usageEn.takeIf { sentence == null && translation == null && !it.containsHan() }.orEmpty() },
            listOfNotNull(translation?.primaryTranslation?.explanationZh, sentence?.toneAndScene, sentence?.grammarBreakdownZh)
                .filter(String::isNotBlank).distinct().joinToString("  ").ifBlank { answer.meaningZh }))
    } else answer.senses.ifEmpty { listOf(Sense(pos, answer.usageEn, answer.meaningZh)) }
    val examples = answer.examples.ifEmpty {
        if (isSentence) listOf(Example(answer.answerEn, if (chinese) result.chineseHeadword else answer.meaningZh))
        else emptyList()
    }

    val inside = PdfBlock(ContentWidth - 22f).apply {
        val tagWidth = minOf(width * 0.44f, headerTags.sumOf { (textWidth(it, 8f, true) + 16f).toDouble() }.toFloat())
        val tags = PdfBlock(tagWidth).apply {
            pills(headerTags, size = 8f, alignmentRight = true, firstAccent = if (chinese) Purple else Blue)
        }
        val head = PdfBlock(width - tagWidth - 7f).apply {
            line(title, size = 14f, bold = true)
            if (!chinese) {
                val ipa = phonetics(word?.phonetics?.us.orEmpty().ifBlank { answer.ipaUs.ifBlank { answer.ipa } },
                    word?.phonetics?.uk.orEmpty().ifBlank { answer.ipaUk })
                if (ipa.isNotBlank()) line(ipa, size = 9.5f, color = Secondary, gap = 1f)
            }
        }
        place(head, 0f, 0f)
        place(tags, width - tags.width, 0f)
        height = maxOf(head.height, tags.height) + 7f

        if (chinese) {
            val equivalents = translation?.let {
                listOf(EnglishEquivalent(it.primaryTranslation.en,
                    usageLabelZh = it.primaryTranslation.tone.ifBlank { "首选译文" }, nuanceZh = it.primaryTranslation.explanationZh)) +
                    it.alternativeTranslations.map { candidate ->
                        EnglishEquivalent(candidate.en, usageLabelZh = candidate.tone, nuanceZh = candidate.noteZh)
                    }
            } ?: result.englishEquivalents
            candidateGrid(equivalents.filter { it.en.isNotBlank() && !it.en.containsHan() }.take(3))
        }

        senses.forEachIndexed { index, sense ->
            val number = "${index + 1}. ${sense.pos} ".replace("  ", " ")
            val en = sense.en.ifBlank { "暂无英文用法说明".takeIf { isSentence } ?: NoData }
            val zh = sense.zh.ifBlank { NoData }
            val numbered = SpannableString("$number$en  $zh").apply {
                setSpan(ForegroundColorSpan(Purple), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Secondary), number.length + en.length + 2, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            line(numbered, gap = if (index == 0) 0f else 3f)
        }

        height += 7f
        val exampleBox = PdfBlock(width - 16f).apply {
            if (examples.isEmpty()) line("场景例句：$NoData", size = 9.5f, color = Secondary)
            examples.forEachIndexed { index, example ->
                line(example.en.ifBlank { NoData }, size = 10f, gap = if (index == 0) 0f else 4.5f)
                line(example.zh.ifBlank { NoData }, size = 9.5f, color = Secondary)
            }
        }
        rect(0f, height, width, exampleBox.height + 12f, Pale, radius = 6f)
        place(exampleBox, 8f, height + 5f)
        height += exampleBox.height + 18f

        val sceneLabel = "同境表达："
        val labelWidth = textWidth(sceneLabel, 9.5f, false) + 3f
        val companions = answer.sceneCompanions.map { "${it.en} ${it.zh}" }.ifEmpty { listOf(NoData) }
        val chips = PdfBlock(width - labelWidth).apply { pills(companions, size = 9f) }
        val label = PdfBlock(labelWidth).apply { line(sceneLabel, size = 9.5f, color = Secondary) }
        place(label, 0f, height + 2f)
        place(chips, labelWidth, height)
        height += maxOf(label.height + 2f, chips.height)
    }

    return PdfBlock(ContentWidth).apply {
        height = inside.height + 19f
        rect(0.4f, 0.4f, width - 0.8f, height - 0.8f, Color.WHITE, radius = 8f, border = Border)
        place(inside, 11f, 9f)
    }
}

private fun PdfBlock.candidateGrid(candidates: List<EnglishEquivalent>) {
    val gap = 6f
    val cellWidth = (width - gap) / 2f
    val entries = candidates.ifEmpty { listOf(EnglishEquivalent(NoData)) }
    entries.chunked(2).forEach { row ->
        val tiles = row.map { candidate ->
            PdfBlock(cellWidth - 14f).apply {
                line(styledLine(candidate.en, candidate.usageLabelZh.takeIf(String::isNotBlank)?.let { "  $it" }.orEmpty(), Purple, Secondary))
                val ipa = phonetics(candidate.phoneticUs, candidate.phoneticUk)
                if (ipa.isNotBlank()) line(ipa, size = 9f, color = Secondary, gap = 1f)
                if (candidate.nuanceZh.isNotBlank()) line(candidate.nuanceZh, size = 10f, gap = 1f)
            }
        }
        val tileHeight = tiles.maxOf { it.height } + 12f
        tiles.forEachIndexed { index, tile ->
            val x = index * (cellWidth + gap)
            rect(x, height, cellWidth, tileHeight, Pale, radius = 5f, border = Border)
            place(tile, x + 7f, height + 6f)
        }
        height += tileHeight + gap
    }
    height += 2f
}

private fun phonetics(us: String, uk: String): String =
    listOf("US" to us, "UK" to uk).mapNotNull { (label, text) ->
        formatIpa(text).takeIf(String::isNotBlank)?.let { "$label $it" }
    }.joinToString(" · ")

private fun styledLine(
    first: String, rest: String, firstColor: Int, secondColor: Int, firstSize: Int? = null,
): CharSequence = SpannableString(first + rest).apply {
    setSpan(ForegroundColorSpan(firstColor), 0, first.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    setSpan(StyleSpan(Typeface.BOLD), 0, first.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    firstSize?.let { setSpan(android.text.style.AbsoluteSizeSpan(it), 0, first.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    if (rest.isNotEmpty()) setSpan(ForegroundColorSpan(secondColor), first.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

private class PdfBlock(val width: Float) {
    var height = 0f
    val operations = mutableListOf<(Canvas) -> Unit>()

    fun line(
        text: CharSequence, size: Float = 10.5f, color: Int = Ink,
        bold: Boolean = false, right: Boolean = false, gap: Float = 0f,
    ) {
        if (text.isBlank()) return
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint(size, color, bold), width.toInt().coerceAtLeast(1))
            .setIncludePad(false)
            .setLineSpacing(0f, 1.28f)
            .setAlignment(if (right) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
            .build()
        val top = height + gap
        operations.add { canvas ->
            canvas.save()
            canvas.translate(0f, top)
            layout.draw(canvas)
            canvas.restore()
        }
        height = top + layout.height
    }

    fun pills(labels: List<String>, size: Float, alignmentRight: Boolean = false, firstAccent: Int? = null) {
        val gap = 4f
        val pills = labels.mapIndexed { index, label ->
            val accent = firstAccent.takeIf { index == 0 }
            val pillWidth = minOf(width, ceil(textWidth(label, size, true)) + 10f)
            val pill = PdfBlock(pillWidth)
            val text = PdfBlock(pillWidth - 10f).apply {
                line(label, size = size, color = accent ?: Secondary, bold = true)
            }
            pill.height = text.height + 6f
            val fill = when (accent) {
                Purple -> 0xFFF3E8FF.toInt()
                Blue -> 0xFFEFF6FF.toInt()
                else -> Capsule
            }
            pill.rect(0f, 0f, pillWidth, pill.height, fill, radius = 5f)
            pill.place(text, 5f, 3f)
            pill
        }
        val rows = mutableListOf<MutableList<PdfBlock>>()
        var occupied = 0f
        pills.forEach { pill ->
            if (rows.isEmpty() || occupied + gap + pill.width > width) {
                rows.add(mutableListOf())
                occupied = 0f
            }
            val row = rows.last()
            occupied += pill.width + if (row.isEmpty()) 0f else gap
            row.add(pill)
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) height += gap
            val rowWidth = row.sumOf { it.width.toDouble() }.toFloat() + gap * (row.size - 1)
            var x = if (alignmentRight) width - rowWidth else 0f
            row.forEach { pill ->
                place(pill, x, height)
                x += pill.width + gap
            }
            height += row.maxOf { it.height }
        }
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float = 0f, border: Int? = null) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val outline = border?.let {
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = it; style = Paint.Style.STROKE; strokeWidth = 0.7f }
        }
        operations.add { canvas ->
            canvas.drawRoundRect(x, y, x + w, y + h, radius, radius, fill)
            if (outline != null) canvas.drawRoundRect(x, y, x + w, y + h, radius, radius, outline)
        }
    }

    fun place(block: PdfBlock, x: Float, y: Float, scale: Float = 1f) {
        operations.add { block.draw(it, x, y, scale) }
    }

    fun stack(block: PdfBlock, gap: Float, scale: Float = 1f) {
        place(block, (width - block.width * scale) / 2, height, scale)
        height += block.height * scale + gap
    }

    fun draw(canvas: Canvas, x: Float, y: Float, scale: Float = 1f) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        operations.forEach { it(canvas) }
        canvas.restore()
    }
}

private fun textPaint(size: Float, color: Int, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    textSize = size
    typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    fontFeatureSettings = "tnum"
}

private fun textWidth(text: String, size: Float, bold: Boolean) = textPaint(size, Ink, bold).measureText(text)

private fun drawLogo(canvas: Canvas, x: Float, y: Float, size: Float) {
    canvas.save()
    canvas.translate(x, y)
    canvas.scale(size / 44f, size / 44f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xFFEDE9FE.toInt()
    canvas.drawRoundRect(9f, 5f, 37f, 38f, 6f, 6f, paint)
    paint.style = Paint.Style.STROKE
    paint.color = 0xFF7C3AED.toInt()
    paint.strokeWidth = 2f
    canvas.drawRoundRect(9f, 5f, 37f, 38f, 6f, 6f, paint)
    paint.style = Paint.Style.FILL
    paint.color = Color.WHITE
    canvas.drawRoundRect(5f, 8f, 33f, 40f, 6f, 6f, paint)
    paint.style = Paint.Style.STROKE
    paint.color = Ink
    paint.strokeWidth = 2.2f
    canvas.drawRoundRect(5f, 8f, 33f, 40f, 6f, 6f, paint)
    paint.strokeWidth = 3.2f
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    val monogram = Path().apply { moveTo(13.5f, 16f); lineTo(13.5f, 29.5f); lineTo(23.5f, 29.5f) }
    canvas.drawPath(monogram, paint)
    paint.style = Paint.Style.FILL
    paint.color = 0xFF7C3AED.toInt()
    canvas.drawCircle(25.5f, 15.5f, 2.6f, paint)
    canvas.restore()
}

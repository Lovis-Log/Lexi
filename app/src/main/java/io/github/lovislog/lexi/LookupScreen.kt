package io.github.lovislog.lexi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow

@Composable
internal fun LookupPanel(
    query: String,
    context: String,
    state: LookupState,
    saved: List<SavedCard>,
    working: Boolean,
    onQuery: (String) -> Unit,
    onContext: (String) -> Unit,
    onSearch: () -> Unit,
    onSave: (LookupResult) -> Unit,
    onSettings: () -> Unit,
    onLinkedLookup: (LookupInput) -> Unit,
    onCamera: (() -> Unit)? = null,
    compact: Boolean = false,
    recent: (@Composable () -> Unit)? = null,
) {
    var showContext by rememberSaveable { mutableStateOf(context.isNotBlank()) }
    val loading = state is LookupState.Loading
    val focus = LocalFocusManager.current
    val queryLabel = tr("要查的词句", "Word or expression")
    val placeholder = tr(
        "输入单词或中文场景，如 get away with / 如何委婉拒绝邀请",
        "Enter a word or describe a situation, e.g. get away with",
    )

    LaunchedEffect(context.isNotBlank()) {
        if (context.isNotBlank()) showContext = true
    }

    FrostedCard(Modifier.fillMaxWidth(), radius = 26) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                BasicTextField(
                    value = query,
                    onValueChange = { if (it.length <= LookupInput.MaxQueryLength || it.length < query.length) onQuery(it) },
                    modifier = Modifier.weight(1f)
                        .semantics { contentDescription = queryLabel },
                    enabled = !loading,
                    minLines = if (compact) 2 else 3,
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { input ->
                        Box(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                            if (query.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            input()
                        }
                    },
                )
                if (onCamera != null) {
                    val cameraLabel = tr("拍照或从相册识字", "Read text from a photo")
                    IconButton(onClick = { focus.clearFocus(); onCamera() }, enabled = !loading,
                        modifier = Modifier.semantics { contentDescription = cameraLabel }) { CameraMark() }
                }
            }
            AnimatedVisibility(showContext) {
                OutlinedTextField(
                    value = context,
                    onValueChange = { if (it.length <= LookupInput.MaxContextLength || it.length < context.length) onContext(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("原句或语境（选填）", "Source sentence or context")) },
                    placeholder = { Text(tr("例如：对老板说 / 邮件里用 / 吵架后哄人 / 法律语境",
                        "For example: talking to your boss / in an email / after an argument / legal context")) },
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 4,
                    enabled = !loading,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showContext = !showContext }) {
                    Text(
                        if (showContext) tr("− 收起上下文", "− Hide context")
                        else tr("+ 补充上下文", "+ Add context"),
                    )
                }
                Button(
                    enabled = !loading && query.isNotBlank(),
                    onClick = {
                        focus.clearFocus()
                        onSearch()
                    },
                ) { Text(if (loading) tr("查询中", "Looking up") else tr("查词  →", "Lookup  →")) }
            }
        }
    }
    recent?.invoke()

    when (state) {
        LookupState.Idle -> if (compact) {
            Text(
                tr("新查询会发送到已配置的 AI 接口。", "New queries are sent to the configured AI endpoint."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LookupState.Loading -> FrostedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("正在生成语境解析…", "Generating a contextual explanation…"))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        is LookupState.Failed -> FrostedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(state.message.display(), color = MaterialTheme.colorScheme.error)
                if (state.opensSettings) TextButton(onClick = onSettings) {
                    Text(tr("打开 Lexi 设置", "Open Lexi Settings"))
                }
            }
        }
        is LookupState.Ready -> key(state.result.id) {
            val existing = saved.firstOrNull { it.id == state.result.id }
            val canUpdate = existing != null &&
                existing.result.explanation.analysisVersion < state.result.explanation.analysisVersion
            LookupExplanation(
                state.result, onLinkedLookup, saved = existing != null && !canUpdate,
                working = working, canUpdate = canUpdate,
                onSave = { onSave(state.result) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LookupExplanation(
    result: LookupResult,
    onLookup: (LookupInput) -> Unit,
    saved: Boolean = false,
    working: Boolean = false,
    canUpdate: Boolean = false,
    onSave: (() -> Unit)? = null,
) = LookupReading {
    val answer = result.explanation
    val word = answer.wordAnalysis
    val translation = answer.translation
    val englishSentence = answer.englishSentence
    val colors = MaterialTheme.colorScheme
    FrostedCard(Modifier.fillMaxWidth(), radius = 22) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            answer.contextAdaptationNote?.let { note ->
                ResultCaption(tr("语境适配", "Context adaptation"), note,
                    Modifier.fillMaxWidth().background(colors.primaryContainer, RoundedCornerShape(12.dp)).padding(10.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val labels = translation?.sceneTags ?: word?.posTags?.takeUnless { answer.kind == ContentKind.SENTENCE }
                    ?: listOf(answer.partOfSpeech.ifBlank { answer.senses.firstOrNull()?.pos.orEmpty() }
                        .ifBlank { answer.kind.display() })
                labels.forEach { ResultPill(it, colors.primaryContainer, colors.onPrimaryContainer) }
                val frequency = word?.frequencyLevel ?: answer.frequency.ifBlank { answer.tag }
                if (frequency.isNotBlank()) {
                    val frequent = frequency.lowercase() in setOf("common", "very common", "high frequency", "常用", "高频")
                    if (frequent) ResultPill(frequency, Color(0xFFECFDF5), Color(0xFF047857))
                    else ResultPill(frequency, Color(0xFFFFFBEB), Color(0xFFB45309))
                }
                val cefr = word?.cefrLevel ?: answer.cefr
                if (cefr.isNotBlank()) ResultPill(cefr)
            }
            if (translation != null) {
                ResultCaption(tr("中文原句", "Chinese source"), translation.sourceZh)
                ResultSectionLabel(tr("首选译文", "Recommended translation"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    InteractiveEnglishText(answer.headSegments, answer.answerEn, onLookup,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                    val syllables = word?.syllables ?: answer.syllables
                    if (syllables.isNotBlank()) Text(syllables, style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant)
                }
                if (onSave != null) {
                    FilledTonalButton(
                        onClick = onSave, enabled = !saved && !working,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        val label = when {
                            saved -> tr("已加入词库", "Saved")
                            canUpdate -> tr("更新词条", "Update entry")
                            else -> tr("+ 加入词库", "+ Save")
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            when {
                translation != null -> {

                    ResultCaption(translation.primaryTranslation.tone, translation.primaryTranslation.explanationZh)
                }
                englishSentence != null -> {

                    ResultCaption(tr("自然译文", "Natural translation"), englishSentence.naturalTranslationZh)
                    ResultCaption(tr("语气与场景", "Tone and situation"), englishSentence.toneAndScene)
                }
                else -> ResultPhonetics(answer)
            }
            val grammar = word?.grammarSkeleton?.joinToString("\n") ?: answer.grammar
            if (grammar.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF8FAFC)).height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.fillMaxHeight().width(3.dp).background(colors.primary))
                    ResultCaption(tr("语法骨架", "Grammar patterns"), grammar, Modifier.padding(8.dp))
                }
            }
            val imagery = word?.let { "${it.coreImage.zh}\n${it.coreImage.en}" } ?: answer.imagery
            if (imagery.isNotBlank()) {
                ResultCaption(
                    tr("核心意象", "Core image"), imagery,
                    Modifier.fillMaxWidth().background(
                        Brush.linearGradient(listOf(colors.primaryContainer.copy(alpha = 0.55f), Color(0xFFF8FAFC))),
                        RoundedCornerShape(14.dp),
                    ).padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            if (translation == null && englishSentence == null) Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                ResultSectionLabel(tr("双语释义 · ENGLISH & 中文", "BILINGUAL DEFINITIONS · ENGLISH & 中文"))
                if (answer.senses.isEmpty()) {
                    Text(answer.usageEn, style = MaterialTheme.typography.bodyMedium)
                    Text(answer.meaningZh, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                } else {
                    answer.senses.forEachIndexed { index, sense ->
                        if (index != 0) HorizontalDivider(color = colors.surfaceVariant)
                        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}.", style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold, color = colors.primary)
                                if (sense.pos.isNotBlank()) ResultPill(sense.pos)
                            }
                            Text(sense.en, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(sense.zh, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }

            if (answer.examples.isNotEmpty()) {
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResultSectionLabel(tr("场景例句", "Example scenes"))
                    answer.examples.forEach { example ->
                        Surface(color = colors.surfaceVariant.copy(alpha = 0.4f), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                if (example.scene.isNotBlank()) ResultSectionLabel(example.scene)
                                ResultExample(example.en, example.zh, example.clickableSegments, onLookup)
                            }
                        }
                    }
                }
            }
            if (answer.sceneCompanions.isNotEmpty()) {
                ResultSectionLabel(tr("同境表达", "Related expressions"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    answer.sceneCompanions.forEach { companion ->
                        AssistChip(onClick = { onLookup(LookupInput(companion.en)) },
                            label = {
                                Text(companion.en + "  " + companion.zh, style = MaterialTheme.typography.labelLarge)
                            }, shape = CircleShape)
                    }
                }
            }
        }
    }

    when {
        translation != null -> {
            TranslationSections(translation, onLookup)
            return@LookupReading
        }
        word != null -> {
            WordSections(word, onLookup)
            return@LookupReading
        }
        englishSentence != null -> {
            EnglishSentenceSections(englishSentence, onLookup)
            return@LookupReading
        }
    }

    val noNotes = tr("这条查询暂无相关信息。", "No notes for this entry.")
    FoldingCard(
        tr("高频搭配与句型", "Common collocations and patterns"),
        trailing = tr("${answer.collocations.size} 个搭配", "${answer.collocations.size} collocations"),
        radius = 24,
    ) {
        if (answer.collocations.isEmpty()) Text(noNotes, style = MaterialTheme.typography.bodyMedium)
        answer.collocations.forEachIndexed { index, entry ->
            if (index != 0) HorizontalDivider(color = colors.surfaceVariant)
            ResultTerm(entry.label, entry.en)
        }
    }
    FoldingCard(tr("近义词深度辨析", "Synonym distinctions"),
        trailing = tr("语境与正式度差异", "Context and register"), radius = 24) {
        if (answer.synonymNotes.isEmpty() && answer.alternatives.isEmpty()) {
            Text(noNotes, style = MaterialTheme.typography.bodyMedium)
        }
        answer.synonymNotes.forEach { ResultTerm(it.label, it.en) }
        LinkedNotes(tr("类似表达", "Alternative expressions"), answer.alternatives)
    }
    FoldingCard(tr("词源词根 & 记忆网络", "Etymology & memory network"),
        trailing = tr("同根串联", "Related roots"), radius = 24) {
        if (answer.etymology.isBlank() && answer.memoryNetwork.isBlank()) {
            Text(noNotes, style = MaterialTheme.typography.bodyMedium)
        }
        if (answer.etymology.isNotBlank()) ResultCaption(tr("词源词根", "Etymology and roots"), answer.etymology)
        if (answer.memoryNetwork.isNotBlank()) ResultCaption(tr("记忆网络", "Memory connections"), answer.memoryNetwork)
    }
    FoldingCard(tr("中式英语避坑提示", "Pitfalls for Mandarin speakers"),
        trailing = tr("常见误区", "Common mistakes"), radius = 24) {
        Text(answer.pitfalls.ifBlank { noNotes }, style = MaterialTheme.typography.bodyMedium,
            color = if (answer.pitfalls.isBlank()) colors.onSurfaceVariant else Color(0xFF93470C))
    }
}

@Composable
private fun WordSections(word: WordAnalysis, onLookup: (LookupInput) -> Unit) {
    if (word.collocations.isNotEmpty()) FoldingCard(tr("高频搭配", "Common collocations"), radius = 24) {
        word.collocations.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                ResultTerm(entry.phrase, entry.meaningZh)
                ResultExample(entry.exampleEn, entry.exampleZh, entry.clickableSegments, onLookup)
            }
        }
    }
    if (word.synonyms.isNotEmpty()) FoldingCard(tr("近义词辨析", "Synonym distinctions"), radius = 24) {
        word.synonyms.forEach { entry ->
            ResultTerm(entry.word, "${entry.differenceZh}\n${entry.contrastExample}")
        }
    }
    val etymology = word.etymologyNetwork
    if (etymology.rootAnalysisZh.isNotBlank() || etymology.relatedWords.isNotEmpty()) {
        FoldingCard(tr("词源与同根词", "Etymology and related words"), radius = 24) {
            if (etymology.rootAnalysisZh.isNotBlank()) Text(etymology.rootAnalysisZh, style = MaterialTheme.typography.bodyMedium)
            etymology.relatedWords.forEach { entry -> ResultTerm(entry.word, "${entry.pos}  ${entry.zh}") }
        }
    }
    if (word.chinglishWarnings.isNotEmpty()) FoldingCard(tr("中式英语避坑", "Pitfalls for Mandarin speakers"), radius = 24) {
        word.chinglishWarnings.forEach { entry ->
            ResultCaption("❌ ${entry.mistake}", "✅ ${entry.correction}\n${entry.reasonZh}")
        }
    }
}

@Composable
private fun TranslationSections(translation: TranslationAnalysis, onLookup: (LookupInput) -> Unit) {
    FoldingCard(tr("不同语气的表达", "Translations by tone"), initiallyExpanded = true, radius = 24) {
        translation.alternativeTranslations.forEach { entry ->
            ResultSectionLabel(entry.tone)
            ResultExample(entry.en, entry.noteZh, entry.clickableSegments, onLookup)
        }
    }
    FoldingCard(tr("可迁移句型", "Reusable sentence patterns"), radius = 24) {
        translation.sentenceSkeletons.forEach { entry -> ResultTerm(entry.pattern, entry.meaningZh) }
    }
    FoldingCard(tr("核心词块", "Key chunks"), radius = 24) {
        translation.keyChunks.forEach { entry ->
            ResultTerm(entry.chunk, "${entry.meaningZh}\n${entry.usageNoteZh}")
        }
    }
    FoldingCard(tr("中式直译避坑", "Literal translation pitfalls"), radius = 24) {
        val pitfall = translation.chinglishPitfall
        ResultCaption("❌ ${pitfall.badTranslation}", pitfall.reasonZh)
    }
}

@Composable

private fun ResultExample(
    en: String, note: String, segments: List<EnglishSegment>, onLookup: (LookupInput) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        InteractiveEnglishText(segments, en, onLookup, style = MaterialTheme.typography.bodyMedium)
        Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EnglishSentenceSections(sentence: EnglishSentenceAnalysis, onLookup: (LookupInput) -> Unit) {
    FoldingCard(tr("句法拆解", "Sentence structure"), initiallyExpanded = true, radius = 24) {
        Text(sentence.grammarBreakdownZh, style = MaterialTheme.typography.bodyMedium)
        sentence.sentenceSkeletons.forEach { ResultCaption(it.pattern, it.meaningZh) }
    }
    if (sentence.keyChunks.isNotEmpty()) FoldingCard(tr("核心词块", "Key chunks"), radius = 24) {
        sentence.keyChunks.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.inSentence, Modifier.clickable { onLookup(LookupInput(entry.chunk, sentence.sourceEn)) },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Text(entry.meaningZh + "\n" + entry.usageNoteZh, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ResultSectionLabel(label: String) {
    Text(label, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ResultCaption(label: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultTerm(term: String, meaning: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(term, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (meaning.isNotBlank()) {
            Text(meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultPill(
    label: String,
    background: Color = Color(0xFFF1F5F9),
    ink: Color = Color(0xFF475569),
) {
    Text(label, Modifier.background(background, RoundedCornerShape(50))
        .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium, color = ink, fontWeight = FontWeight.SemiBold)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResultPhonetics(answer: Explanation) {
    val us = formatIpa(answer.wordAnalysis?.phonetics?.us.orEmpty()).ifBlank { formatIpa(answer.ipaUs) }
    val uk = formatIpa(answer.wordAnalysis?.phonetics?.uk.orEmpty()).ifBlank { formatIpa(answer.ipaUk) }
    val general = formatIpa(answer.ipa)
    if (us.isBlank() && uk.isBlank() && general.isBlank()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (us.isNotBlank()) PhoneticLabel(tr("美式 US", "US"), us)
        if (uk.isNotBlank()) PhoneticLabel(tr("英式 UK", "UK"), uk)
        if (us.isBlank() && uk.isBlank()) PhoneticLabel(tr("音标", "IPA"), general)
    }
}

@Composable
private fun PhoneticLabel(accent: String, ipa: String) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelMedium
    Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceVariant.copy(alpha = 0.55f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(accent, style = style, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
            Text(ipa, Modifier.weight(1f, fill = false), style = style.copy(
                fontFamily = FontFamily.Serif, fontFeatureSettings = "", letterSpacing = 0.sp,
            ), color = colors.onSurface)
        }
    }
}

@Composable
internal fun CoreMeaning(answer: Explanation) {
    SectionTitle(tr("核心释义", "Core meaning"))
    Text(answer.meaningZh, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    answer.senses.forEach { sense ->
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (sense.pos.isNotBlank()) Tag(sense.pos)
            Text(sense.en, style = MaterialTheme.typography.bodyMedium)
            Text(sense.zh, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun Examples(examples: List<Example>, onLookup: (LookupInput) -> Unit) {
    if (examples.isEmpty()) return
    SectionTitle(tr("语境例句", "Examples in context"))
    examples.forEach { example ->
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(15.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (example.scene.isNotBlank()) ResultSectionLabel(example.scene)
            ResultExample(example.en, example.zh, example.clickableSegments, onLookup)
        }
    }
}

@Composable
internal fun LinkedNotes(title: String, entries: List<Alternative>) {
    if (entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        SectionTitle(title)
        entries.forEach { entry ->
            ResultTerm(entry.label, entry.en)
        }
    }
}

@Composable
internal fun Note(title: String, body: String) {
    if (body.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(title)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer)
}

@Composable
internal fun Tag(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun FoldingCard(
    title: String,
    trailing: String? = null,
    initiallyExpanded: Boolean = false,
    radius: Int = 22,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    LaunchedEffect(initiallyExpanded) { if (initiallyExpanded) expanded = true }
    FrostedCard(Modifier.fillMaxWidth(), radius = radius) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) tr("收起", "Collapse") else tr("展开", "Expand")) {
                    expanded = !expanded
                }.padding(horizontal = 16.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                trailing?.let {
                    Text(it, Modifier.widthIn(max = 115.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Chevron(expanded)
            }
            AnimatedVisibility(expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                }
            }
        }
    }
}

@Composable
internal fun Chevron(up: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(16.dp)) {
        val edgeY = size.height * if (up) 0.62f else 0.38f
        val pointY = size.height - edgeY
        val stroke = 1.7.dp.toPx()
        drawLine(color, Offset(size.width * 0.22f, edgeY), Offset(center.x, pointY), stroke, StrokeCap.Round)
        drawLine(color, Offset(center.x, pointY), Offset(size.width * 0.78f, edgeY), stroke, StrokeCap.Round)
    }
}

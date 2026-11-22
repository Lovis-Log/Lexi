package io.github.lovislog.lexi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun ReviewPage(

    modifier: Modifier, cards: List<SavedCard>, working: Boolean, session: ReviewSession,
    onSync: (ReviewDay, Set<String>, List<SavedCard>) -> Unit, onFocus: (String) -> Unit,
    onRate: (SavedCard, Rating) -> Unit, onLookup: (LookupInput) -> Unit,
) {
    var productionOnly by rememberSaveable { mutableStateOf(false) }
    var showDue by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val limit = LocalAppearance.current.dailyLimit
    val day = reviewDay(now)
    val active = remember(cards) { cards.filterNot { it.archived }.associateBy { it.id } }
    val reviewedWords = remember(cards, day) {
        cards.filter { day.includes(it.schedule.lastReviewAt) }.mapTo(mutableSetOf()) { it.result.reviewWordKey }
    }
    val reviewed = cards.count { day.includes(it.lastCompletedAt) }
    val remaining = (limit - reviewedWords.size).coerceAtLeast(0)
    val newDue = remember(active, remaining, now, reviewedWords) {
        buildReviewQueue(active.values.toList(), remaining, now, reviewedWords)
    }
    LaunchedEffect(day, active.keys, newDue) { onSync(day, active.keys, newDue) }

    val eligible = active.values.filter { !productionOnly || it.usesExpression(true) }
    val admissible = newDue.mapTo(mutableSetOf()) { it.id }
    val queue = session.sessionQueue.takeIf { session.dayStart == day.start }.orEmpty().mapNotNull { entry ->
        val item = active[entry.id] ?: return@mapNotNull null
        if (productionOnly && !item.usesExpression(true)) return@mapNotNull null
        if (entry.requeueCount == 0 && entry.id !in admissible) return@mapNotNull null
        entry to item
    }
    val due = queue.map { it.second }
    val head = queue.firstOrNull()
    val card = head?.second

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageTitle(tr("复习", "Review"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(tr("待复习 ${due.size}", "Due ${due.size}"), style = MaterialTheme.typography.titleMedium)
            FilledTonalButton(
                onClick = { showDue = true },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
            ) { Text(tr("查看全部", "View all")) }
        }
        Text(tr("今日已复习 $reviewed 张", "Reviewed today: $reviewed"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoicePill(tr("综合", "Mixed"), !productionOnly) {
                productionOnly = false
            }
            ChoicePill(tr("表达", "Expression"), productionOnly) {
                productionOnly = true
            }
        }
        if (card == null) {
            val next = eligible.minOfOrNull {
                if (remaining == 0 && it.result.reviewWordKey !in reviewedWords) maxOf(it.schedule.dueAt, day.end)
                else it.schedule.dueAt
            }
            FrostedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val reachedLimit = remaining == 0 && eligible.any { it.result.reviewWordKey !in reviewedWords && it.schedule.dueAt <= now }
                    Text(
                        if (reachedLimit) tr("已达到今日复习上限", "Daily review limit reached")
                        else tr("当前没有待复习卡片", "No cards due"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (next != null) {
                        Text(tr("下次复习  ", "Next review  ") + formatDate(next),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(tr("加入词库的词句会出现在这里。", "Saved vocabulary will appear here."),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            key(card.id, head.first.expectedReviews, productionOnly) {
                ReviewCard(card, now, due.size, working || card.schedule.reviews < head.first.expectedReviews,
                    card.usesExpression(productionOnly), head.first.requeueCount > 0, onRate, onLookup) {
                    detailId = card.id
                }
            }
        }
    }

    cards.firstOrNull { it.id == detailId }?.let { detail ->
        LibraryDetail(detail, onLookup, fullExplanation = true) { detailId = null }
    }

    if (showDue) LexiSheet(tr("待复习清单", "Due cards"), onDismiss = { showDue = false }) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (due.isEmpty()) item { Text(tr("当前没有待复习卡片", "No cards due")) }
            items(due, key = SavedCard::id) { item ->
                Surface(
                    onClick = {
                        onFocus(item.id)
                        showDue = false
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.result.input.text, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Tag(if (item.usesExpression(productionOnly)) tr("表达", "Expression") else tr("认读", "Recognition"))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(

    card: SavedCard, now: Long, count: Int, working: Boolean, expression: Boolean, relearning: Boolean,
    onRate: (SavedCard, Rating) -> Unit, onLookup: (LookupInput) -> Unit, onDetails: () -> Unit,
) {
    var flipped by rememberSaveable { mutableStateOf(false) }
    var showHints by rememberSaveable { mutableStateOf(false) }
    val angle by animateFloatAsState(
        if (flipped) 180f else 0f, tween(420, easing = FastOutSlowInEasing), label = "Card flip",
    )
    val back = angle > 90f
    val answer = card.result.explanation
    val faces = remember(card.result, expression) { card.result.reviewFaces(expression) }
    val face = if (back) faces.back else faces.front
    val mode = if (expression) tr("表达", "Expression") else tr("认读", "Recognition")
    val status = when {
        relearning -> tr("本轮再学", "Relearning")
        card.schedule.phase == ReviewPhase.NEW -> tr("新卡", "New")
        else -> tr("待复习", "Due")
    }
    val badge = if (back) tr("答案解析", "Answer") else "$mode · $status"
    val flipLabel = if (flipped) tr("翻回题面", "Flip to prompt") else tr("翻到答案", "Flip to answer")
    val flipHint = tr("轻触卡片翻转", "Tap card to flip")
    val shape = RoundedCornerShape(24.dp)

    Box(Modifier.fillMaxWidth().padding(bottom = if (count > 1) 14.dp else 0.dp)) {
        if (count > 2) {
            Box(Modifier.matchParentSize().offset(y = 14.dp).padding(horizontal = 24.dp)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f), shape))
        }
        if (count > 1) {
            Box(Modifier.matchParentSize().offset(y = 7.dp).padding(horizontal = 12.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, shape))
        }
        FrostedCard(
            Modifier.fillMaxWidth().graphicsLayer {
                rotationY = if (angle <= 90f) angle else angle - 180f
                cameraDistance = 14f * density
            }, radius = 25,
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 278.dp).clickable(onClickLabel = flipLabel) { flipped = !flipped }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Tag(badge)

                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InteractiveEnglishText(face.titleSegments, face.title, onLookup,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                    if (answer.kind != ContentKind.SENTENCE && back == expression) ResultPhonetics(answer)
                    if (face.supportingText.isNotBlank()) {
                        InteractiveEnglishText(face.supportingSegments, face.supportingText, onLookup,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    face.notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (face.hints.isNotEmpty()) {
                        TextButton(onClick = { showHints = !showHints }) {
                            Text(if (showHints) tr("收起提示", "Hide hints") else tr("显示提示", "Show hints"))
                        }
                        if (showHints) Text(face.hints.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(flipHint, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (back && flipped) {
                        TextButton(onClick = onDetails) { Text(tr("查看详情", "View details")) }
                    }
                }
            }
        }
    }
    AnimatedVisibility(flipped && back) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Rating.entries.forEach { rating ->
                val dueAt = ReviewScheduler.rate(card.schedule, rating, now).dueAt
                val (label, tint) = when (rating) {
                    Rating.AGAIN -> tr("忘了", "Forgot") to Color(0xFFFCE8E6)
                    Rating.HARD -> tr("模糊", "Unsure") to Color(0xFFFFF1D8)
                    Rating.GOOD -> tr("掌握", "Got it") to Color(0xFFE3F4E9)
                }
                Surface(
                    onClick = { if (flipped) onRate(card, rating) }, enabled = !working,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(15.dp), color = tint,
                ) {
                    Column(Modifier.padding(horizontal = 5.dp, vertical = 11.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        val interval = when (rating) {
                            Rating.AGAIN -> tr("隔 2–3 张", "After 2–3 cards")
                            Rating.HARD -> tr("隔 5–6 张", "After 5–6 cards")
                            Rating.GOOD -> formatInterval(dueAt - now)
                        }
                        Text(interval, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun formatInterval(milliseconds: Long): String {
    val minute = 60_000
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        milliseconds >= day -> "${milliseconds / day}d"
        milliseconds >= hour -> "${milliseconds / hour}h"
        else -> "${milliseconds / minute}m"
    }
}

private enum class LibraryFilter(val kind: ContentKind?) {
    ALL(null), WORD(ContentKind.WORD), PHRASE(ContentKind.PHRASE), SENTENCE(ContentKind.SENTENCE), PAUSED(null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryPage(
    modifier: Modifier, cards: List<SavedCard>, working: Boolean, onArchive: (SavedCard) -> Unit,
    onLookup: (LookupInput) -> Unit, onAdd: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = remember(cards, filter, search) {
        val query = search.trim()
        cards.filter { card ->
            val answer = card.result.explanation
            card.archived == (filter == LibraryFilter.PAUSED) && (filter.kind == null || answer.kind == filter.kind) &&
                listOf(card.result.input.text, card.result.input.context, answer.answerEn, answer.meaningZh,
                    answer.tag, answer.cefr, answer.frequency, answer.partOfSpeech)
                    .any { it.contains(query, ignoreCase = true) }
        }
    }

    LazyColumn(
        modifier, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    PageTitle(tr("词库", "Library"))
                    Text(tr("${visible.size} 张复习卡", "${visible.size} cards"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onAdd, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp)) {
                    Text(tr("+ 新增词句", "+ Add vocabulary"))
                }
            }
        }
        item { SearchField(search, { search = it }, tr("搜索词句、释义或标签", "Search words, meanings, or tags")) }
        item {
            FlowRow(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LibraryFilter.entries.forEach { choice ->
                    val label = choice.kind?.display(plural = true) ?: if (choice == LibraryFilter.ALL) tr("全部", "All") else tr("已暂停", "Paused")
                    ChoicePill(label, filter == choice) { filter = choice }
                }
            }
        }
        if (visible.isEmpty()) item {
            Text(if (cards.isEmpty()) tr("加入词库的词句会出现在这里。", "Saved vocabulary will appear here.")
                else tr("没有匹配的卡片。", "No matching cards."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(visible, key = SavedCard::id) { card ->
            LibraryRow(card, working, onOpen = { selected = card.id }, onArchive = { onArchive(card) })
        }
    }
    cards.firstOrNull { it.id == selected }?.let { card ->
        key(card.id) { LibraryDetail(card, onLookup) { selected = null } }
    }
}

@Composable
private fun LibraryRow(card: SavedCard, working: Boolean, onOpen: () -> Unit, onArchive: () -> Unit) {
    val answer = card.result.explanation
    val dot = when (answer.kind) {
        ContentKind.WORD -> Color(0xFF1A73E8)
        ContentKind.PHRASE -> Color(0xFF8B5CF6)
        ContentKind.SENTENCE -> Color(0xFF10B981)
    }
    FrostedCard(Modifier.fillMaxWidth(), radius = 18) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = tr("查看详情", "Open details"), onClick = onOpen)
            .padding(start = 13.dp, end = 5.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(answer.answerEn, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(answer.meaningZh, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(answer.kind.display() + " · " +
                    if (card.archived) tr("已暂停", "Paused") else formatDate(card.schedule.dueAt),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onArchive, enabled = !working, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (card.archived) tr("恢复", "Resume") else tr("暂停", "Pause"))
            }
        }
    }
}

@Composable
private fun LibraryDetail(
    card: SavedCard, onLookup: (LookupInput) -> Unit,
    fullExplanation: Boolean = false, onDismiss: () -> Unit,
) {
    val answer = card.result.explanation
    val schedule = card.schedule
    val english = LocalAppearance.current.english
    val stability = String.format(if (english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE, "%.2f", schedule.stability)
    val difficulty = String.format(Locale.ROOT, "%.1f", schedule.difficulty)
    LexiSheet(
        if (fullExplanation) tr("词句详情", "Vocabulary details") else answer.answerEn,
        expandable = true, initiallyExpanded = fullExplanation, onDismiss = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (fullExplanation || answer.hasAnalysis) {
                LookupExplanation(card.result, onLookup)
                LookupReading {
                    if (!answer.hasAnalysis) Note(tr("用法解析", "Usage"), answer.usageEn)
                    Note(tr("原句", "Source context"), card.result.input.context)
                }
            } else {
                LookupReading {
                    Tag(answer.kind.display())
                    ResultPhonetics(answer)
                    CoreMeaning(answer)
                    Examples(answer.examples, onLookup)
                    LinkedNotes(tr("常见搭配", "Common collocations"), answer.collocations)
                    Note(tr("用法解析", "Usage"), answer.usageEn)
                    Note(tr("语法骨架", "Grammar pattern"), answer.grammar)
                }
            }
            HorizontalDivider()
            SectionTitle(tr("FSRS 记忆状态", "FSRS memory state"))
            val phase = when (schedule.phase) {
                ReviewPhase.NEW -> tr("新卡", "New")
                ReviewPhase.LEARNING -> tr("学习中", "Learning")
                ReviewPhase.REVIEW -> tr("复习中", "Review")
                ReviewPhase.RELEARNING -> tr("重新学习", "Relearning")
            }
            Text(phase + if (card.archived) tr(" · 已暂停", " · Paused") else "")
            Text(tr("复习 ${schedule.reviews} 次 · 遗忘 ${schedule.lapses} 次",
                "${schedule.reviews} reviews · ${schedule.lapses} lapses"))
            if (schedule.phase != ReviewPhase.NEW) {
                Text(tr("稳定度 $stability 天 · 难度 $difficulty", "Stability $stability days · Difficulty $difficulty"))
            }
            schedule.lastReviewAt?.let { Text(tr("上次复习  ", "Last review  ") + formatDate(it)) }
            Text(tr("到期时间  ", "Due  ") + formatDate(schedule.dueAt))
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) }, shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = BorderStroke(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant),
    )
}

private fun SavedCard.usesExpression(expressionMode: Boolean): Boolean =
    result.explanation.intent == LookupIntent.EXPRESS ||
        (expressionMode && (result.explanation.wordAnalysis != null || result.explanation.englishSentence != null))

package io.github.lovislog.lexi

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
internal fun ReviewPage(
    modifier: Modifier,
    cards: List<SavedCard>,
    working: Boolean,
    onRate: (SavedCard, Rating) -> Unit,
    onSpeak: (String) -> Unit,
) {
    var productionOnly by rememberSaveable { mutableStateOf(false) }
    var now by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val eligible = cards.filter { !it.archived && (!productionOnly || it.isProduction) }
    val due = eligible.filter { it.schedule.dueAt <= now }.sortedBy { it.schedule.dueAt }
    val card = due.firstOrNull()

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageTitle("复习", if (due.isNotEmpty()) "待复习 ${due.size} 张" else "当前没有到期卡片")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !productionOnly, onClick = { productionOnly = false }, label = { Text("混合") })
            FilterChip(selected = productionOnly, onClick = { productionOnly = true }, label = { Text("只练输出") })
        }
        if (card == null) {
            val next = eligible.minOfOrNull { it.schedule.dueAt }
            Text(
                if (next != null) "下次复习  ${formatDate(next)}" else "查词后收藏，卡片就会出现在这里。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ReviewCard(card, working, onRate, onSpeak)
        }
    }
}

@Composable
private fun ReviewCard(
    card: SavedCard,
    working: Boolean,
    onRate: (SavedCard, Rating) -> Unit,
    onSpeak: (String) -> Unit,
) {
    var flipped by rememberSaveable(card.id, card.schedule.reviews) { mutableStateOf(false) }
    val answer = card.result.explanation
    val context = card.result.input.context.ifBlank { answer.examples.firstOrNull()?.en.orEmpty() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Tag(if (card.isProduction) "中译英" else "认读")
                Text(card.result.input.text, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
                if (card.isProduction && answer.tag.isNotBlank()) {
                    Text(answer.tag, style = MaterialTheme.typography.bodyMedium)
                }
                if (!card.isProduction && context.isNotBlank()) {
                    Text(context, style = MaterialTheme.typography.bodyMedium)
                }
                if (!card.isProduction) {
                    TextButton(onClick = { onSpeak(answer.answerEn) }) { Text("发音") }
                }
                if (!flipped) Button(onClick = { flipped = true }) { Text("显示答案") }
            }
        }
        if (flipped) {
            ExplanationCard(card.result, onSpeak)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Rating.entries.forEach { rating ->
                    if (rating == Rating.GOOD) {
                        Button(onClick = { onRate(card, rating) }, enabled = !working, modifier = Modifier.weight(1f)) {
                            Text(rating.label)
                        }
                    } else {
                        OutlinedButton(onClick = { onRate(card, rating) }, enabled = !working, modifier = Modifier.weight(1f)) {
                            Text(rating.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibraryPage(
    modifier: Modifier,
    cards: List<SavedCard>,
    working: Boolean,
    onArchive: (SavedCard) -> Unit,
    onSpeak: (String) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf<ContentKind?>(null) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val query = search.trim()
    val visible = cards.filter { card ->
        val answer = card.result.explanation
        card.archived == showArchived && (kind == null || answer.kind == kind) &&
            listOf(card.result.input.text, answer.answerEn, answer.meaningZh, answer.tag)
                .any { it.contains(query, ignoreCase = true) }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageTitle("词库", "${cards.count { !it.archived }} 张复习卡")
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索词句、释义或标签") },
                singleLine = true,
            )
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text("全部") })
                ContentKind.entries.forEach { item ->
                    FilterChip(selected = kind == item, onClick = { kind = if (kind == item) null else item }, label = { Text(item.label) })
                }
                FilterChip(selected = showArchived, onClick = { showArchived = !showArchived }, label = { Text("已暂停") })
            }
        }
        if (visible.isEmpty()) {
            item { Text(if (cards.isEmpty()) "收藏的词句会出现在这里。" else "没有符合条件的卡片。") }
        }
        items(visible, key = SavedCard::id) { card ->
            val answer = card.result.explanation
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(answer.answerEn, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    Text(answer.meaningZh, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${if (card.isProduction) "输出" else "认读"} · ${answer.kind.label}" +
                            if (card.archived) " · 已暂停" else " · ${formatDate(card.schedule.dueAt)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { expanded = if (expanded == card.id) null else card.id }) {
                            Text(if (expanded == card.id) "收起" else "详情")
                        }
                        TextButton(onClick = { onArchive(card) }, enabled = !working) {
                            Text(if (card.archived) "恢复复习" else "暂停复习")
                        }
                    }
                    if (expanded == card.id) ExplanationCard(card.result, onSpeak)
                }
            }
        }
    }
}

private val reviewDate = DateTimeFormatter.ofPattern("M月d日 HH:mm")

private fun formatDate(time: Long): String =
    Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(reviewDate)

private val SavedCard.isProduction: Boolean
    get() = result.explanation.intent == LookupIntent.EXPRESS

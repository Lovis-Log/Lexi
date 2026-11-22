package io.github.lovislog.lexi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun RecentLookups(
    history: List<HistoryRow>, busy: Boolean, onOpen: (LookupInput) -> Unit, showHistory: () -> Unit,
) {
    FoldingCard(tr("最近查询", "Recent lookups"), tr("${history.size} 条", "${history.size}")) {
        if (history.isEmpty()) Text(tr("暂无查询记录", "No recent lookups"), style = MaterialTheme.typography.bodySmall)
        history.take(4).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { item ->
                    Surface(
                        onClick = { onOpen(item.input) }, enabled = !busy,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatDate(item.viewedAt), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        FilledTonalButton(onClick = showHistory, modifier = Modifier.fillMaxWidth()) {
            Text(tr("显示更多  →", "Show more  →"))
        }
    }
}

@Composable
internal fun HistoryPage(
    modifier: Modifier, history: List<HistoryRow>, working: Boolean, onBack: () -> Unit,
    onOpen: (LookupInput) -> Unit, onDelete: (String) -> Unit, onClear: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val visible = remember(history, search) {
        val query = search.trim()
        history.filter { it.text.contains(query, ignoreCase = true) || it.context.contains(query, ignoreCase = true) }
    }
    LazyColumn(
        modifier, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text(tr("← 返回查词", "← Back to lookup")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PageTitle(tr("历史记录", "Lookup history")) }
                TextButton(enabled = history.isNotEmpty() && !working, onClick = { confirmClear = true }) {
                    Text(tr("清空全部", "Clear all"), color = if (history.isNotEmpty() && !working)
                        MaterialTheme.colorScheme.error else LocalContentColor.current)
                }
            }
        }
        item { SearchField(search, { search = it }, tr("搜索历史记录", "Search history")) }
        if (visible.isEmpty()) item {
            Text(if (history.isEmpty()) tr("暂无查询记录", "No lookup history")
                else tr("没有匹配的记录", "No matching lookups"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(visible, key = HistoryRow::id) { item ->
            FrostedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clickable { onOpen(item.input) }.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (item.context.isNotBlank()) Text(item.context, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                        Text(formatDate(item.viewedAt), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(enabled = !working, onClick = { onDelete(item.id) }) {
                        Text(tr("删除", "Delete"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(tr("清空查询历史？", "Clear lookup history?")) },
        text = { Text(tr("将删除所有查询历史记录。已加入词库的卡片可继续复习。",
            "This clears all lookup history. Saved cards will still be available for review.")) },
        confirmButton = {
            TextButton(onClick = { confirmClear = false; onClear() }, enabled = !working) {
                Text(tr("清空", "Clear"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange, modifier = Modifier.fillMaxWidth().background(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(50),
        ),
        placeholder = { Text(label) }, singleLine = true, shape = RoundedCornerShape(50),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
internal fun formatDate(time: Long): String {
    val english = LocalAppearance.current.english
    val pattern = if (english) "MMM d, yyyy · HH:mm" else "yyyy年M月d日 HH:mm"
    return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(pattern, if (english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE))
}

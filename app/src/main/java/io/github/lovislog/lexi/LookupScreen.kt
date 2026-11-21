package io.github.lovislog.lexi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

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
    onSpeak: (String) -> Unit,
    onSettings: () -> Unit,
    compact: Boolean = false,
) {
    var showContext by rememberSaveable { mutableStateOf(false) }
    val loading = state is LookupState.Loading
    val focus = LocalFocusManager.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("查询内容") },
            placeholder = { Text("get away with / 如何委婉拒绝邀请") },
            minLines = if (compact) 1 else 2,
            maxLines = 5,
            enabled = !loading,
        )
        if (showContext || context.isNotBlank()) {
            OutlinedTextField(
                value = context,
                onValueChange = onContext,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("原句或上下文（选填）") },
                maxLines = 4,
                enabled = !loading,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (!showContext && context.isBlank()) {
                TextButton(onClick = { showContext = true }) { Text("补充上下文") }
            } else {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        onContext("")
                        showContext = false
                    },
                ) { Text("清除上下文") }
            }
            Button(
                enabled = !loading && query.isNotBlank(),
                onClick = {
                    focus.clearFocus()
                    onSearch()
                },
            ) { Text(if (loading) "查询中" else "查词") }
        }

        when (state) {
            LookupState.Idle -> if (compact) {
                Text("缓存未命中时，查询文字会发送到已配置的 AI 接口。", style = MaterialTheme.typography.bodySmall)
            }
            LookupState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is LookupState.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (state.message.contains("设置")) {
                    TextButton(onClick = onSettings) { Text("打开 Lexi 设置") }
                }
            }
            is LookupState.Ready -> ExplanationCard(
                state.result,
                onSpeak,
                saved.any { it.id == state.result.id },
                working,
                onSave = { onSave(state.result) },
            )
        }
    }
}

@Composable
internal fun ExplanationCard(
    result: LookupResult,
    onSpeak: (String) -> Unit,
    saved: Boolean = true,
    working: Boolean = false,
    onSave: (() -> Unit)? = null,
) {
    val answer = result.explanation
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(answer.kind.label)
                if (answer.tag.isNotBlank()) Tag(answer.tag)
            }
            Text(answer.answerEn, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
            if (answer.ipa.isNotBlank()) Text(answer.ipa, style = MaterialTheme.typography.bodyMedium)
            Text(answer.meaningZh, style = MaterialTheme.typography.titleMedium)
            Text(answer.usageEn, style = MaterialTheme.typography.bodyMedium)
            if (result.input.context.isNotBlank()) {
                Text("原句  ${result.input.context}", style = MaterialTheme.typography.bodySmall)
            }
            if (answer.senses.isNotEmpty()) {
                HorizontalDivider()
                answer.senses.forEach { sense ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(listOf(sense.pos, sense.en).filter(String::isNotBlank).joinToString("  "))
                        Text(sense.zh, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (answer.examples.isNotEmpty()) {
                HorizontalDivider()
                Text("例句", style = MaterialTheme.typography.titleSmall)
                answer.examples.forEach { example ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(example.en)
                        Text(example.zh, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (answer.alternatives.isNotEmpty()) {
                HorizontalDivider()
                Text("类似表达", style = MaterialTheme.typography.titleSmall)
                answer.alternatives.forEach { alternative ->
                    Text("${alternative.label}  ${alternative.en}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSpeak(answer.answerEn) }) { Text("发音") }
                onSave?.let { Button(enabled = !saved && !working, onClick = it) { Text(if (saved) "已收藏" else "收藏") } }
            }
        }
    }
}

@Composable
internal fun Tag(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

package io.github.lovislog.lexi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Page(val label: String) {
    LOOKUP("查词"), REVIEW("复习"), LIBRARY("词库"), SETTINGS("设置")
}

@Composable
internal fun LexiApp(model: LexiModel, openSettings: Boolean) {
    var page by rememberSaveable { mutableStateOf(if (openSettings) Page.SETTINGS else Page.LOOKUP) }
    var query by rememberSaveable { mutableStateOf("") }
    var context by rememberSaveable { mutableStateOf("") }
    val lookup by model.lookup.collectAsStateWithLifecycle()
    val cards by model.cards.collectAsStateWithLifecycle()
    val working by model.working.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val snippets = remember { SnackbarHostState() }
    val speak = rememberSpeech { message -> model.showNotice(message) }

    LaunchedEffect(notice) {
        notice?.let {
            snippets.showSnackbar(it)
            model.clearNotice()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snippets) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Page.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == page,
                        onClick = { page = item },
                        icon = { PageIcon(item) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            val modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
            when (page) {
                Page.LOOKUP -> Column(
                    modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    PageTitle("Lexi", "查一个词，或者问一句英文怎么说。")
                    LookupPanel(
                        query = query,
                        context = context,
                        state = lookup,
                        saved = cards,
                        working = working,
                        onQuery = {
                            query = it
                            model.clearLookup()
                        },
                        onContext = {
                            context = it
                            model.clearLookup()
                        },
                        onSearch = { model.search(query, context) },
                        onSave = model::save,
                        onSpeak = speak,
                        onSettings = { page = Page.SETTINGS },
                    )
                }
                Page.REVIEW -> ReviewPage(modifier, cards, working, model::rate, speak)
                Page.LIBRARY -> LibraryPage(modifier, cards, working, model::archive, speak)
                Page.SETTINGS -> SettingsPage(modifier, model)
            }
        }
    }
}

@Composable
internal fun SelectionWindow(
    model: LexiModel,
    selected: String,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(selected) }
    var context by rememberSaveable { mutableStateOf("") }
    val lookup by model.lookup.collectAsStateWithLifecycle()
    val cards by model.cards.collectAsStateWithLifecycle()
    val working by model.working.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val speak = rememberSpeech(model::showNotice)

    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.78f),
        color = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Lexi", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
                TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("关闭") }
            }
            LookupPanel(
                query, context, lookup, cards, working,
                onQuery = {
                    query = it
                    model.clearLookup()
                },
                onContext = {
                    context = it
                    model.clearLookup()
                },
                onSearch = { model.search(query, context) },
                onSave = model::save,
                onSpeak = speak,
                onSettings = onOpenApp,
                compact = true,
            )
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun PageTitle(name: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(name, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Serif)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PageIcon(page: Page) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(1.6f * unit)
        when (page) {
            Page.LOOKUP -> {
                drawCircle(color, 6f * unit, Offset(10f * unit, 10f * unit), style = stroke)
                drawLine(color, Offset(14.5f * unit, 14.5f * unit), Offset(20f * unit, 20f * unit), stroke.width)
            }
            Page.REVIEW -> {
                drawRoundRect(color, Offset(5f * unit, 3f * unit), Size(14f * unit, 18f * unit), CornerRadius(2f * unit), style = stroke)
                drawLine(color, Offset(9f * unit, 9f * unit), Offset(15f * unit, 9f * unit), stroke.width)
                drawLine(color, Offset(9f * unit, 14f * unit), Offset(13f * unit, 14f * unit), stroke.width)
            }
            Page.LIBRARY -> {
                for (x in listOf(4f, 10f, 16f)) {
                    drawRoundRect(color, Offset(x * unit, 4f * unit), Size(4f * unit, 16f * unit), CornerRadius(unit), style = stroke)
                }
            }
            Page.SETTINGS -> {
                for (y in listOf(6f, 12f, 18f)) {
                    val center = (if (y == 12f) 15f else 9f) * unit
                    drawLine(color, Offset(4f * unit, y * unit), Offset(center - 2.5f * unit, y * unit), stroke.width)
                    drawCircle(color, 2.2f * unit, Offset(center, y * unit), style = stroke)
                    drawLine(color, Offset(center + 2.5f * unit, y * unit), Offset(20f * unit, y * unit), stroke.width)
                }
            }
        }
    }
}

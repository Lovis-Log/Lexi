package io.github.lovislog.lexi

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal enum class Page(val label: LocalizedText) {
    LOOKUP(LocalizedText("查词", "Lookup")),
    REVIEW(LocalizedText("复习", "Review")),
    LIBRARY(LocalizedText("词库", "Library")),
    SETTINGS(LocalizedText("设置", "Settings")),
}

internal data class AppLocation(
    val page: Page,
    val historyOpen: Boolean,
    val openAi: Boolean,
    val query: String,
    val context: String,
    val lookup: LookupState,
    val scroll: Int,
)

@Composable
internal fun LexiApp(model: LexiModel, openSettings: Boolean) {
    var page by rememberSaveable { mutableStateOf(if (openSettings) Page.SETTINGS else Page.LOOKUP) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var openAi by rememberSaveable { mutableStateOf(openSettings) }
    var query by rememberSaveable { mutableStateOf("") }
    var context by rememberSaveable { mutableStateOf("") }
    var showPhotos by rememberSaveable { mutableStateOf(false) }
    var photoSubmitted by rememberSaveable { mutableStateOf(false) }
    val backStack = model.backStack
    var lookupVisit by rememberSaveable { mutableIntStateOf(0) }
    var scrollStart by rememberSaveable { mutableIntStateOf(0) }
    val lookupScroll = rememberSaveable(lookupVisit, saver = ScrollState.Saver) { ScrollState(scrollStart) }
    val lookup by model.lookup.collectAsStateWithLifecycle()
    val cards by model.cards.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val reviewSession by model.reviewSession.collectAsStateWithLifecycle()
    val working by model.working.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val appearance = LocalAppearance.current
    val snackbar = remember { SnackbarHostState() }
    val pageState = rememberSaveableStateHolder()


    LaunchedEffect(Unit) { model.loadProvider() }
    LaunchedEffect(lookup) {
        (lookup as? LookupState.Ready)?.result?.input?.let {
            query = it.text
            context = it.context
        }
    }
    LaunchedEffect(notice, appearance.english) {
        notice?.let {
            snackbar.showSnackbar(it.inLanguage(appearance.english))
            model.clearNotice()
        }
    }
    fun location() = AppLocation(page, historyOpen, openAi, query, context, lookup, lookupScroll.value)

    fun rememberLocation(from: AppLocation = location()) {
        if (backStack.size == 20) backStack.removeAt(0)
        if (backStack.lastOrNull() != from) backStack.add(from)
    }

    fun resetLookupScroll(position: Int = 0) {
        scrollStart = position
        lookupVisit++
    }

    fun goBack() {
        if (historyOpen) {
            historyOpen = false
            return
        }
        val previous = backStack.removeLastOrNull()
        if (previous != null) {
            val current = location()
            page = previous.page
            historyOpen = previous.historyOpen
            openAi = previous.openAi
            query = previous.query
            context = previous.context
            if (current.query != previous.query || current.context != previous.context || current.scroll != previous.scroll) {
                resetLookupScroll(previous.scroll)
            }
            model.restoreLookup(previous.lookup)
        } else if (page != Page.LOOKUP) {
            page = Page.LOOKUP
            openAi = false
        } else if (lookup != LookupState.Idle) {
            model.clearLookup()
            resetLookupScroll()
        } else {
            query = ""
            context = ""
        }
    }

    val canGoBack = historyOpen || backStack.isNotEmpty() || page != Page.LOOKUP ||
        lookup != LookupState.Idle || query.isNotBlank() || context.isNotBlank()
    BackHandler(enabled = canGoBack && !showPhotos, onBack = ::goBack)

    fun openAiSettings(from: AppLocation = location()) {
        rememberLocation(from)
        historyOpen = false
        openAi = true
        page = Page.SETTINGS
    }

    fun lookupAgain(input: LookupInput) {
        rememberLocation()
        page = Page.LOOKUP
        query = input.text
        context = input.context
        historyOpen = false
        resetLookupScroll()
        model.search(query, context)
    }

    var pointer by remember(appearance.atmosphere) { mutableStateOf(Offset.Unspecified) }
    val trackPointer = if (appearance.atmosphere == Atmosphere.GRID) Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                pointer = event.changes.firstOrNull {
                    event.type != PointerEventType.Exit && (it.pressed || it.type == PointerType.Mouse)
                }?.position ?: Offset.Unspecified
            }
        }
    } else Modifier
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(trackPointer)) {
        AtmosphereLayer(Modifier.matchParentSize()) { pointer }
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                FloatingNavigation(page) { selected ->
                    if (selected != page || historyOpen) {
                        rememberLocation()
                        historyOpen = false
                        openAi = false
                        page = selected
                    }
                }
            },
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                val modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                pageState.SaveableStateProvider(if (historyOpen) "History" else page.name) {
                    if (historyOpen) {
                        HistoryPage(modifier, history, working, { historyOpen = false }, ::lookupAgain,
                            model::deleteHistory, model::clearHistory)
                    } else when (page) {
                        Page.LOOKUP -> Column(
                            modifier.verticalScroll(lookupScroll).padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (canGoBack) TextButton(onClick = ::goBack) { Text(tr("‹ 返回", "‹ Back")) }
                                BrandHeading(Modifier.weight(1f))
                            }
                            LookupPanel(
                                query = query,
                                context = context,
                                state = lookup,
                                saved = cards,
                                working = working,
                                onQuery = {
                                    if (lookup is LookupState.Ready) rememberLocation()
                                    query = it
                                    model.clearLookup()
                                },
                                onContext = {
                                    if (lookup is LookupState.Ready) rememberLocation()
                                    context = it
                                    model.clearLookup()
                                },
                                onSearch = { model.search(query, context) },
                                onSave = model::save,
                                onSettings = { openAiSettings() },
                                onLinkedLookup = ::lookupAgain,
                                onCamera = { showPhotos = true },
                                recent = {
                                    RecentLookups(history, lookup is LookupState.Loading, ::lookupAgain) { historyOpen = true }
                                },
                            )
                        }
                        Page.REVIEW -> ReviewPage(modifier, cards, working, reviewSession,
                            model::syncReviewSession, model::focusReviewCard, model::rate, ::lookupAgain)
                        Page.LIBRARY -> LibraryPage(modifier, cards, working, model::archive, ::lookupAgain) {
                            rememberLocation()
                            resetLookupScroll()
                            query = ""
                            context = ""
                            model.clearLookup()
                            page = Page.LOOKUP
                        }
                        Page.SETTINGS -> SettingsPage(modifier, model, openAi)
                    }
                }
            }
        }
    }

    if (showPhotos) {
        fun clearPhotoRequest() {
            if (photoSubmitted) model.restoreLookup(model.photoOrigin?.lookup ?: LookupState.Idle)
            photoSubmitted = false
            model.photoOrigin = null
        }

        PhotoLookup(
            sending = photoSubmitted && lookup is LookupState.Loading,
            completed = photoSubmitted && lookup is LookupState.Ready,
            failure = (lookup as? LookupState.Failed)?.takeIf { photoSubmitted },
            onDismiss = { clearPhotoRequest(); showPhotos = false },
            onComplete = {
                model.photoOrigin?.let { rememberLocation(it) }
                photoSubmitted = false
                model.photoOrigin = null
                resetLookupScroll()
                showPhotos = false
            },
            onClearRequest = ::clearPhotoRequest,
            onSettings = {
                val origin = model.photoOrigin ?: location()
                clearPhotoRequest()
                showPhotos = false
                openAiSettings(origin)
            },
            onVision = { image ->
                if (!photoSubmitted) model.photoOrigin = location()
                photoSubmitted = true
                model.searchImage(image, context)
            },
        )
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
    val previous = remember { mutableStateListOf<Pair<LookupInput, LookupState>>() }
    fun goBack() {
        val (input, state) = previous.removeLastOrNull() ?: return
        query = input.text
        context = input.context
        model.restoreLookup(state)
    }
    BackHandler(enabled = previous.isNotEmpty(), onBack = ::goBack)

    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.78f),
        color = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (previous.isNotEmpty()) TextButton(onClick = ::goBack) { Text(tr("‹ 返回", "‹ Back")) }
                BrandHeading(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(tr("关闭", "Close")) }
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
                onSettings = onOpenApp,
                onLinkedLookup = {
                    if (previous.size == 20) previous.removeAt(0)
                    previous.add(LookupInput(query, context) to lookup)
                    query = it.text
                    context = it.context
                    model.search(query, context)
                },
                compact = true,
            )
            notice?.let { Text(it.display(), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun BrandHeading(modifier: Modifier = Modifier) {
    val slogan = tr("一词一境，温故知新", "Every word, in its living context.")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.lexi_icon), contentDescription = null,
                modifier = Modifier.size(40.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
        }
        Text("Lexi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("|", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
        Text(slogan, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun PageTitle(name: String) {
    Text(name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun FloatingNavigation(page: Page, select: (Page) -> Unit) {
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        FrostedCard(Modifier.widthIn(max = 510.dp).fillMaxWidth(), radius = 40) {
            BoxWithConstraints(Modifier.padding(5.dp).selectableGroup()) {
                val tabWidth = maxWidth / Page.entries.size
                val offset by animateDpAsState(
                    tabWidth * page.ordinal,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                    label = "Selected tab",
                )
                Box(
                    Modifier.offset(x = offset).width(tabWidth).height(53.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                )
                Row(Modifier.fillMaxWidth()) {
                    Page.entries.forEach { tab ->
                        val active = tab == page
                        val color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        Column(
                            Modifier.weight(1f).height(53.dp).clip(CircleShape)
                                .selectable(selected = active, role = Role.Tab, onClick = { select(tab) }),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            PageIcon(tab, color)
                            Text(tab.label.display(), style = MaterialTheme.typography.labelMedium,
                                color = color, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageIcon(page: Page, color: androidx.compose.ui.graphics.Color) {
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

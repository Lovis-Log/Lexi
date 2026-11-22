package io.github.lovislog.lexi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch

@Composable
internal fun LexiSheet(
    title: String, expandable: Boolean = false, initiallyExpanded: Boolean = false, onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var drag by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val closeLabel = tr("关闭面板", "Close sheet")
    val toggleLabel = if (expanded) tr("缩小详情", "Collapse details") else tr("展开详情", "Expand details")
    fun close() {
        closing = true
        visibility.targetState = false
    }
    LaunchedEffect(closing, visibility.isIdle) {
        if (closing && visibility.isIdle) dismiss()
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.matchParentSize().background(Color(0xFF172844).copy(alpha = 0.23f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = closeLabel) { close() },
            )
            val maxSheetHeight = maxHeight * 0.92f
            val peekHeight = maxHeight * 0.56f
            val density = LocalDensity.current
            val target = if (expanded) maxSheetHeight else peekHeight
            val settled = remember { Animatable(target.value) }
            val motion = tween<Float>(270)
            LaunchedEffect(target) { settled.animateTo(target.value, motion) }
            fun heightAt(offset: Float) =
                (settled.value.dp - with(density) { offset.toDp() }).coerceIn(peekHeight * 0.75f, maxSheetHeight)
            AnimatedVisibility(
                visibleState = visibility,
                enter = slideInVertically(tween(260)) { it },
                exit = slideOutVertically(tween(200)) { it },
            ) {
                Surface(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth()
                        .height(heightAt(drag)),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 27.dp, topEnd = 27.dp),
                    shadowElevation = 16.dp,
                ) {
                    Column(Modifier.navigationBarsPadding()) {
                        Column(
                            Modifier.fillMaxWidth().pointerInput(expandable, expanded, target) {
                                detectVerticalDragGestures(
                                    onDragStart = { drag = 0f },
                                    onDragCancel = { drag = 0f },
                                    onVerticalDrag = { change, amount ->
                                        change.consume()
                                        drag += amount
                                    },
                                    onDragEnd = {
                                        val dragged = heightAt(drag)
                                        val shouldClose = drag > 52.dp.toPx() && !expanded
                                        val nextExpanded = when {
                                            drag < -34.dp.toPx() && expandable -> true
                                            drag > 40.dp.toPx() -> false
                                            else -> expanded
                                        }
                                        scope.launch {
                                            settled.snapTo(dragged.value)
                                            drag = 0f
                                            when {
                                                shouldClose -> close()
                                                expanded != nextExpanded -> expanded = nextExpanded
                                                else -> settled.animateTo(target.value, motion)
                                            }
                                        }
                                    },
                                )
                            }.padding(start = 18.dp, end = 11.dp, top = 9.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(width = 37.dp, height = 4.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (expandable) IconButton(onClick = { expanded = !expanded },
                                    modifier = Modifier.semantics { contentDescription = toggleLabel }) { Chevron(!expanded) }
                                IconButton(onClick = ::close,
                                    modifier = Modifier.semantics { contentDescription = closeLabel }) {
                                    Text("×", style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }
                        content()
                    }
                }
            }
        }
    }
}

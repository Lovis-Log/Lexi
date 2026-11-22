package io.github.lovislog.lexi

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

@Composable
internal fun InteractiveEnglishText(
    segments: List<EnglishSegment>,
    fullSentence: String,
    onLookup: (LookupInput) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val valid = remember(segments, fullSentence) { segments.recreate(fullSentence) && segments.any { it.query != null } }
    if (!valid) {
        Text(fullSentence, modifier, style = style)
        return
    }
    val colors = MaterialTheme.colorScheme
    var layout by remember(segments, fullSentence) { mutableStateOf<TextLayoutResult?>(null) }
    val label = buildAnnotatedString {
        segments.forEach { segment ->
            val query = segment.query
            if (query == null) append(segment.text)
            else withLink(LinkAnnotation.Clickable(
                tag = query,
                styles = TextLinkStyles(focusedStyle = SpanStyle(background = colors.primaryContainer)),
                linkInteractionListener = { onLookup(LookupInput(query, fullSentence)) },
            )) { append(segment.text) }
        }
    }

    Text(label, modifier.drawBehind {
        val result = layout ?: return@drawBehind
        var start = 0
        segments.forEach { segment ->
            val end = start + segment.text.length
            if (segment.query != null) {
                val color = if (segment.isPhrase) colors.primary else colors.onSurfaceVariant
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))
                val firstLine = result.getLineForOffset(start)
                val lastLine = result.getLineForOffset(end - 1)
                for (line in firstLine..lastLine) {
                    val first = maxOf(start, result.getLineStart(line))
                    val last = minOf(end, result.getLineEnd(line, visibleEnd = true)) - 1
                    if (last < first) continue
                    val left = result.getBoundingBox(first)
                    val right = result.getBoundingBox(last)
                    val y = result.getLineBaseline(line) + 3.dp.toPx()
                    drawLine(color, Offset(minOf(left.left, right.left), y),
                        Offset(maxOf(left.right, right.right), y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                }
            }
            start = end
        }
    }, style = style, onTextLayout = { layout = it })
}
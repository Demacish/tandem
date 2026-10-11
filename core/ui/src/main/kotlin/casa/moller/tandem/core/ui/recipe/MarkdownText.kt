package casa.moller.tandem.core.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.markdown.Markdown
import casa.moller.tandem.core.ui.markdown.MarkdownBlock
import casa.moller.tandem.core.ui.markdown.MarkdownInline

/**
 * Renders a step instruction written in Markdown (see [Markdown] for what is
 * supported). Headings are TalkBack headings, links open in the browser through Compose's link
 * handling (only http, https and mailto links exist after parsing).
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    MarkdownText(blocks = blocks, modifier = modifier, style = style)
}

/** Renders already parsed Markdown [blocks]. */
@Composable
fun MarkdownText(blocks: List<MarkdownBlock>, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val spacing = TandemTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> Text(text = block.inlines.toAnnotatedString(linkColor, codeBackground), style = style)

                is MarkdownBlock.Heading -> Text(
                    text = block.inlines.toAnnotatedString(linkColor, codeBackground),
                    style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )

                is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    block.items.forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            Text(
                                text = if (block.ordered) "${block.start + index}." else "•",
                                style = style,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.widthIn(min = 20.dp),
                            )
                            Text(text = item.toAnnotatedString(linkColor, codeBackground), style = style)
                        }
                    }
                }
            }
        }
    }
}

/** The inline Markdown as styled text with clickable links. */
fun List<MarkdownInline>.toAnnotatedString(linkColor: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    val linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    appendInlines(this@toAnnotatedString, linkStyles, codeBackground)
}

private fun AnnotatedString.Builder.appendInlines(inlines: List<MarkdownInline>, linkStyles: TextLinkStyles, codeBackground: Color) {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInline.Text -> append(inline.text)

            is MarkdownInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                append(inline.text)
            }

            is MarkdownInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlines(inline.children, linkStyles, codeBackground)
            }

            is MarkdownInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlines(inline.children, linkStyles, codeBackground)
            }

            is MarkdownInline.Link -> withLink(LinkAnnotation.Url(inline.url, linkStyles)) {
                appendInlines(inline.children, linkStyles, codeBackground)
            }

            MarkdownInline.LineBreak -> append('\n')
        }
    }
}

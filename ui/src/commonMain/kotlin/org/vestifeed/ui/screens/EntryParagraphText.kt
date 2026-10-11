package org.vestifeed.ui.screens

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Renders a paragraph of the entry reader into an [AnnotatedString]: its plain
 * text, a clickable [LinkAnnotation.Url] for every link span whose target
 * resolves (via [resolveUrl]), and find-in-entry highlights over [matches]
 * ([currentMatch] gets [currentStyle]).
 *
 * Kept out of the composable so the span arithmetic can be unit-tested without
 * a Compose host.
 */
internal fun paragraphAnnotatedString(
    paragraph: EntryBlock.Paragraph,
    matches: List<IntRange>,
    currentMatch: Int,
    matchStyle: SpanStyle,
    currentStyle: SpanStyle,
    linkStyle: SpanStyle,
    resolveUrl: (String) -> String?,
    onOpenLink: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    append(paragraph.text)

    paragraph.links.forEach { span ->
        val url = resolveUrl(span.url) ?: return@forEach
        addLink(
            LinkAnnotation.Url(
                url = url,
                styles = TextLinkStyles(style = linkStyle),
                linkInteractionListener = object : LinkInteractionListener {
                    override fun onClick(link: LinkAnnotation) = onOpenLink(url)
                },
            ),
            start = span.start,
            end = span.end,
        )
    }

    matches.forEachIndexed { index, range ->
        addStyle(
            style = if (index == currentMatch) currentStyle else matchStyle,
            start = range.first,
            end = range.last + 1,
        )
    }
}

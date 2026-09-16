package com.inkwell.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.inkwell.render.Markdown

/**
 * Turn a card-body Markdown string into a Compose [AnnotatedString] using the pure
 * [Markdown] parser (Stage 10). The parsing/span computation is unit-tested on the JVM
 * ([com.inkwell.render.Markdown]); this thin wrapper only maps the parsed style set onto
 * Compose [SpanStyle]s, so it needs the Compose runtime and is not itself a pure test.
 *
 *  - `**bold**` → bold weight, `*italic*` → italic, `` `code` `` → monospace family.
 *  - bullet lines are prefixed with "• "; every source line is separated by a newline.
 *  - headings and links flow through as plain text (no special styling).
 */
fun markdownToAnnotatedString(markdown: String): AnnotatedString {
    val doc = Markdown.parse(markdown)
    return buildAnnotatedString {
        doc.lines.forEachIndexed { index, line ->
            if (index > 0) append("\n")
            if (line.bullet) append("• ")
            line.spans.forEach { span ->
                val style = SpanStyle(
                    fontWeight = if (Markdown.Style.BOLD in span.styles) FontWeight.Bold else null,
                    fontStyle = if (Markdown.Style.ITALIC in span.styles) FontStyle.Italic else null,
                    fontFamily = if (Markdown.Style.CODE in span.styles) FontFamily.Monospace else null,
                )
                withStyle(style) { append(span.text) }
            }
        }
    }
}

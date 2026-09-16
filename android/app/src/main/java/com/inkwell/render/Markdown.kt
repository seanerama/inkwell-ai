package com.inkwell.render

/**
 * A tiny, dependency-free Markdown parser for card bodies (Stage 10). It is deliberately
 * pure (Android-free, no Compose) so the parsing is JVM-unit-testable in isolation; the
 * Compose layer ([com.inkwell.ui.markdownToAnnotatedString]) turns the [Doc] span model
 * into an `AnnotatedString`.
 *
 * Supported inline: `**bold**`, `*italic*`, `` `code` ``. Block: bullet lists (`- ` or
 * `* ` prefixes) and line breaks (each source line is one [Line]). Headings (`# `…) and
 * links (`[text](url)`) are intentionally NOT styled — they render as plain text, i.e.
 * their raw characters flow through the inline parser like any other text (SPEC §4.7,
 * Stage-10 spec: "headings and links render as plain text").
 */
object Markdown {

    /** Inline styling that can apply to a run of text (a run may carry more than one). */
    enum class Style { BOLD, ITALIC, CODE }

    /** A run of text sharing one style set. */
    data class Span(val text: String, val styles: Set<Style> = emptySet())

    /** One source line: its spans, and whether it is a bullet-list item. */
    data class Line(val spans: List<Span>, val bullet: Boolean = false)

    /** The parsed document: one [Line] per source line, in order. */
    data class Doc(val lines: List<Line>)

    /** Parse [markdown] into a [Doc]. Never throws; unmatched markers are kept literally. */
    fun parse(markdown: String): Doc {
        val lines = markdown.split("\n").map { raw ->
            val (bullet, content) = stripBullet(raw)
            Line(spans = parseInline(content), bullet = bullet)
        }
        return Doc(lines)
    }

    /** The document's visible text with all markup removed (accessibility / plain fallback). */
    fun plain(markdown: String): String =
        parse(markdown).lines.joinToString("\n") { line ->
            val body = line.spans.joinToString("") { it.text }
            if (line.bullet) "• $body" else body
        }

    /** Detect and strip a leading bullet marker (`- ` or `* `), tolerating leading spaces. */
    private fun stripBullet(raw: String): Pair<Boolean, String> {
        val trimmed = raw.trimStart()
        return if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            true to trimmed.substring(2)
        } else {
            false to raw
        }
    }

    /**
     * Inline scanner. Recognises `` `code` `` (no nesting), then `**bold**`, then
     * `*italic*`. A marker with no matching close is emitted literally. Bold/italic can
     * nest (e.g. `**a *b* c**`); code is a literal span (its inner markers are not parsed).
     */
    private fun parseInline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        parseRange(text, 0, text.length, emptySet(), spans)
        return coalesce(spans)
    }

    private fun parseRange(s: String, start: Int, end: Int, styles: Set<Style>, out: MutableList<Span>) {
        val buf = StringBuilder()
        var i = start
        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(Span(buf.toString(), styles))
                buf.clear()
            }
        }
        while (i < end) {
            val c = s[i]
            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close in (i + 1) until end) {
                    flush()
                    out.add(Span(s.substring(i + 1, close), styles + Style.CODE))
                    i = close + 1
                    continue
                }
            } else if (c == '*' && i + 1 < end && s[i + 1] == '*') {
                val close = indexOfMarker(s, i + 2, end, "**")
                if (close != -1) {
                    flush()
                    parseRange(s, i + 2, close, styles + Style.BOLD, out)
                    i = close + 2
                    continue
                }
            } else if (c == '*') {
                val close = indexOfMarker(s, i + 1, end, "*")
                if (close != -1) {
                    flush()
                    parseRange(s, i + 1, close, styles + Style.ITALIC, out)
                    i = close + 1
                    continue
                }
            }
            buf.append(c)
            i++
        }
        flush()
    }

    /** First index of [marker] in `s[from, end)`, ignoring a `**` when looking for `*`. */
    private fun indexOfMarker(s: String, from: Int, end: Int, marker: String): Int {
        var i = from
        while (i < end) {
            if (marker == "*") {
                if (s[i] == '*' && !(i + 1 < end && s[i + 1] == '*') && !(i > from && s[i - 1] == '*')) {
                    return i
                }
            } else { // "**"
                if (s[i] == '*' && i + 1 < end && s[i + 1] == '*') return i
            }
            i++
        }
        return -1
    }

    /** Merge adjacent spans that share the same style set (keeps the model tidy for tests). */
    private fun coalesce(spans: List<Span>): List<Span> {
        val out = mutableListOf<Span>()
        for (span in spans) {
            val last = out.lastOrNull()
            if (last != null && last.styles == span.styles) {
                out[out.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                out.add(span)
            }
        }
        return out
    }
}

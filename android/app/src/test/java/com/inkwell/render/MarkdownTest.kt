package com.inkwell.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure [Markdown] parser used by Stage-10 card bodies. Each case
 * checks the span model the Compose layer consumes: inline `**bold**`, `*italic*`,
 * `` `code` ``, bullet lists, line breaks, and the plain-text fallbacks for headings and
 * links.
 */
class MarkdownTest {

    private fun styles(md: String) = Markdown.parse(md).lines.single().spans

    @Test
    fun bold_is_a_bold_span() {
        val spans = styles("**hi**")
        assertEquals(1, spans.size)
        assertEquals("hi", spans[0].text)
        assertEquals(setOf(Markdown.Style.BOLD), spans[0].styles)
    }

    @Test
    fun italic_is_an_italic_span() {
        val spans = styles("*hi*")
        assertEquals("hi", spans[0].text)
        assertEquals(setOf(Markdown.Style.ITALIC), spans[0].styles)
    }

    @Test
    fun code_is_a_code_span_and_inner_markers_are_literal() {
        val spans = styles("`a*b*c`")
        assertEquals("a*b*c", spans[0].text)
        assertEquals(setOf(Markdown.Style.CODE), spans[0].styles)
    }

    @Test
    fun mixed_inline_text_splits_into_styled_and_plain_runs() {
        val spans = styles("say **bold** and *soft*")
        assertEquals(listOf("say ", "bold", " and ", "soft"), spans.map { it.text })
        assertEquals(emptySet<Markdown.Style>(), spans[0].styles)
        assertEquals(setOf(Markdown.Style.BOLD), spans[1].styles)
        assertEquals(setOf(Markdown.Style.ITALIC), spans[3].styles)
    }

    @Test
    fun nested_italic_inside_bold() {
        val spans = styles("**a *b* c**")
        // "a ", "b" (bold+italic), " c" — all under bold.
        assertTrue(spans.all { Markdown.Style.BOLD in it.styles })
        assertTrue(spans.any { it.text == "b" && Markdown.Style.ITALIC in it.styles })
    }

    @Test
    fun bullet_lines_are_flagged_and_marker_stripped() {
        val doc = Markdown.parse("- one\n* two\nplain")
        assertTrue(doc.lines[0].bullet)
        assertEquals("one", doc.lines[0].spans.single().text)
        assertTrue(doc.lines[1].bullet)
        assertEquals("two", doc.lines[1].spans.single().text)
        assertFalse(doc.lines[2].bullet)
    }

    @Test
    fun line_breaks_produce_one_line_each() {
        assertEquals(3, Markdown.parse("a\nb\nc").lines.size)
    }

    @Test
    fun unmatched_marker_is_literal() {
        val spans = styles("2 * 3 = 6")
        assertEquals("2 * 3 = 6", spans.single().text)
        assertEquals(emptySet<Markdown.Style>(), spans.single().styles)
    }

    @Test
    fun headings_and_links_render_as_plain_text() {
        // No heading style; the visible characters flow through unchanged.
        val heading = Markdown.parse("# Title").lines.single()
        assertFalse(heading.bullet)
        assertEquals("# Title", heading.spans.single().text)
        val link = styles("see [docs](http://x)")
        assertEquals("see [docs](http://x)", link.joinToString("") { it.text })
    }

    @Test
    fun plain_flattens_bullets_and_markup() {
        assertEquals("• buy milk\n• call bank", Markdown.plain("- **buy** milk\n- call bank"))
    }
}

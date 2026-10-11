package casa.moller.tandem.core.domain.markdown

import casa.moller.tandem.core.domain.markdown.MarkdownInline.Code
import casa.moller.tandem.core.domain.markdown.MarkdownInline.Emphasis
import casa.moller.tandem.core.domain.markdown.MarkdownInline.LineBreak
import casa.moller.tandem.core.domain.markdown.MarkdownInline.Link
import casa.moller.tandem.core.domain.markdown.MarkdownInline.Strong
import casa.moller.tandem.core.domain.markdown.MarkdownInline.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarkdownTest {

    @Test
    fun `plain text is one paragraph`() {
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(Text("Koka pastan.")))), Markdown.parse("Koka pastan."))
    }

    @Test
    fun `blank input gives no blocks`() {
        assertEquals(emptyList<MarkdownBlock>(), Markdown.parse(""))
        assertEquals(emptyList<MarkdownBlock>(), Markdown.parse("  \n\n "))
    }

    @Test
    fun `single newlines are line breaks, blank lines split paragraphs`() {
        val blocks = Markdown.parse("Rad ett\r\nRad två\n\nNytt stycke")
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph(listOf(Text("Rad ett"), LineBreak, Text("Rad två"))),
                MarkdownBlock.Paragraph(listOf(Text("Nytt stycke"))),
            ),
            blocks,
        )
    }

    @Test
    fun `strong, emphasis and code`() {
        assertEquals(
            listOf(Text("Koka "), Strong(listOf(Text("8–10 minuter"))), Text(", sedan "), Emphasis(listOf(Text("al dente"))), Text(".")),
            Markdown.parseInline("Koka **8–10 minuter**, sedan *al dente*."),
        )
        assertEquals(listOf(Strong(listOf(Text("fett")))), Markdown.parseInline("__fett__"))
        assertEquals(listOf(Text("Värm till "), Code("180 °C")), Markdown.parseInline("Värm till `180 °C`"))
    }

    @Test
    fun `emphasis inside strong`() {
        assertEquals(
            listOf(Strong(listOf(Text("mycket "), Emphasis(listOf(Text("varmt")))))),
            Markdown.parseInline("**mycket _varmt_**"),
        )
    }

    @Test
    fun `underscores inside words and lone stars stay text`() {
        assertEquals(listOf(Text("snake_case_name")), Markdown.parseInline("snake_case_name"))
        assertEquals(listOf(Text("2 * 3 = 6")), Markdown.parseInline("2 * 3 = 6"))
        assertEquals(listOf(Text("**ej stängd")), Markdown.parseInline("**ej stängd"))
    }

    @Test
    fun `escapes`() {
        assertEquals(listOf(Text("*inte kursiv*")), Markdown.parseInline("\\*inte kursiv\\*"))
    }

    @Test
    fun `links, autolinks and bare urls`() {
        assertEquals(
            listOf(Text("Se "), Link("https://example.com/a", listOf(Text("receptet"))), Text(".")),
            Markdown.parseInline("Se [receptet](https://example.com/a \"titel\")."),
        )
        assertEquals(
            listOf(Link("https://example.com", listOf(Text("https://example.com")))),
            Markdown.parseInline("<https://example.com>"),
        )
        assertEquals(
            listOf(Text("Källa: "), Link("https://example.com/x", listOf(Text("https://example.com/x"))), Text(".")),
            Markdown.parseInline("Källa: https://example.com/x."),
        )
    }

    @Test
    fun `unsafe link schemes stay text`() {
        assertEquals(listOf(Text("klicka")), Markdown.parseInline("[klicka](javascript:alert(1))"))
        assertEquals(listOf(Text("fil")), Markdown.parseInline("[fil](file:///etc/passwd)"))
    }

    @Test
    fun `images become their alt text`() {
        assertEquals(listOf(Text("Bild: en kaka")), Markdown.parseInline("Bild: ![en kaka](https://example.com/k.jpg)"))
    }

    @Test
    fun `br tags are line breaks`() {
        assertEquals(listOf(Text("a"), LineBreak, Text("b")), Markdown.parseInline("a<br>b"))
    }

    @Test
    fun `headings`() {
        assertEquals(
            listOf(MarkdownBlock.Heading(2, listOf(Text("Sås"))), MarkdownBlock.Paragraph(listOf(Text("Rör om.")))),
            Markdown.parse("## Sås ##\nRör om."),
        )
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(Text("#hashtag")))), Markdown.parse("#hashtag"))
    }

    @Test
    fun `bullet and ordered lists with continuation lines`() {
        val blocks = Markdown.parse("Gör så här:\n- Skala\n- Skiva\n  tunt\n\n3. Tre\n4) Fyra")
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph(listOf(Text("Gör så här:"))),
                MarkdownBlock.ListBlock(ordered = false, start = 1, items = listOf(listOf(Text("Skala")), listOf(Text("Skiva tunt")))),
                MarkdownBlock.ListBlock(ordered = true, start = 3, items = listOf(listOf(Text("Tre")), listOf(Text("Fyra")))),
            ),
            blocks,
        )
    }

    @Test
    fun `thematic breaks are dropped and quotes are paragraphs`() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(Text("ett"))), MarkdownBlock.Paragraph(listOf(Text("citat")))),
            Markdown.parse("ett\n\n---\n\n> citat"),
        )
    }

    @Test
    fun `plain text of blocks`() {
        assertEquals("Rubrik\nKoka 5 min\nett\ntvå", Markdown.plainText(Markdown.parse("# Rubrik\nKoka **5 min**\n\n- ett\n- två")))
    }

    @Test
    fun `never throws on odd input`() {
        listOf("[", "](", "**", "_", "`", "<", "[a](", "![", "h", "http://", "***a**", "\\", "a\\").forEach { input ->
            Markdown.parse(input)
        }
    }
}

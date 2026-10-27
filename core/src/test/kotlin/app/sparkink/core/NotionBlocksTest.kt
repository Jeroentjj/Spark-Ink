package app.sparkink.core

import kotlin.test.Test
import kotlin.test.assertEquals

class NotionBlocksTest {
    @Test
    fun convertsMarkdown() {
        val b = NotionBlocks.fromMarkdown("# Titel\n\nTekst hier\n- een\n* twee\n1. eerste\n2) tweede\n## Sub")
        assertEquals(listOf(
            Block("heading_1", "Titel"), Block("paragraph", "Tekst hier"),
            Block("bulleted_list_item", "een"), Block("bulleted_list_item", "twee"),
            Block("numbered_list_item", "eerste"), Block("numbered_list_item", "tweede"),
            Block("heading_2", "Sub"),
        ), b)
    }

    @Test
    fun respectsNotionLimits() {
        assertEquals(2, NotionBlocks.fromMarkdown("x".repeat(3000)).size)
        val many = NotionBlocks.fromMarkdown((1..250).joinToString("\n") { "regel $it" })
        assertEquals(100, many.size)
        assertEquals(true, many.last().text.contains("regel 250"))
    }

    @Test
    fun titleAndIds() {
        assertEquals("Boodschappen", NotionBlocks.title("\n## Boodschappen\n- melk", "x"))
        assertEquals("fallback", NotionBlocks.title("  \n ", "fallback"))
        val id = "0123456789abcdef0123456789abcdef"
        assertEquals(id, NotionBlocks.extractId(id))
        assertEquals(id, NotionBlocks.extractId("https://www.notion.so/ws/Mijn-DB-$id?v=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertEquals(id, NotionBlocks.extractId("01234567-89ab-cdef-0123-456789abcdef"))
        assertEquals(null, NotionBlocks.extractId("geen id"))
    }
}

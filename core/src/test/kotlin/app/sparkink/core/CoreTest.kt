package app.sparkink.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTest {
    private fun samplePage(): ByteArray = PenFileBuilder().apply {
        strokeHeader()
        point(1000, 2000, 500)
        delta(3, -2)
        delta(3, -2)
        endStroke()
        strokeHeader()
        point(5000, 6000, 100)
        eof()
    }.build()

    @Test
    fun bcdRoundTrip() {
        val t = 1_761_400_000L
        assertEquals(t, bcdToEpoch(epochToBcd(t)))
        // 2025-10-25 13:46:40 UTC is 25 10 25 13 46 40 as hex digits
        assertContentEquals(byteArrayOf(0x25, 0x10, 0x25, 0x13, 0x46, 0x40), epochToBcd(t))
    }

    @Test
    fun parsesStrokesWithAccumulatingDeltas() {
        val r = StrokeParser.parse(samplePage())
        assertEquals(emptyList(), r.warnings)
        assertEquals(2, r.strokes.size)
        assertEquals(listOf(Pt(1000, 2000, 500), Pt(1003, 1998, 500), Pt(1009, 1994, 500)), r.strokes[0])
        assertEquals(listOf(Pt(5000, 6000, 100)), r.strokes[1])
    }

    @Test
    fun garbageDoesNotCrash() {
        val r = StrokeParser.parse(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(r.warnings.isNotEmpty())
        val truncated = samplePage().copyOf(20)
        StrokeParser.parse(truncated)
    }

    @Test
    fun rotationKeepsPointsInsideThePage() {
        val s = Sketch(listOf(listOf(Pt(100, 200, 1), Pt(21000, 14800, 1))), 21000, 14800)
        for (deg in listOf(0, 90, 180, 270)) {
            val r = s.rotated(deg)
            for (p in r.strokes[0]) assertTrue(p.x in 0..r.width && p.y in 0..r.height, "deg $deg $p")
        }
        assertEquals(14800, s.rotated(90).width)
    }

    @Test
    fun groupsStrokesIntoLines() {
        fun stroke(x: Int, y: Int) = listOf(Pt(x, y, 1), Pt(x + 300, y + 500, 1))
        val sk = Sketch(listOf(stroke(3000, 1000), stroke(500, 1000), stroke(500, 4000), stroke(1000, 4050)), 21000, 14800)
        val lines = sk.lines()
        assertEquals(2, lines.size)
        assertEquals(500, lines[0][0][0].x) // sorted left to right
        assertEquals(2, lines[1].size)
    }

    @Test
    fun sparkSyncDownloadsAndDeletes() {
        val pad = FakePad(PadKind.SPARK, mutableListOf(1_761_400_000L to samplePage(), 1_761_400_100L to samplePage()))
        val got = ArrayList<RawPage>()
        val n = WacomSession(pad, PadKind.SPARK).syncAll(hexToBytes("a1b2c3d4e5f6")) { got.add(it); true }
        assertEquals(2, n)
        assertEquals(2, pad.deleted)
        assertEquals(1_761_400_000L, got[0].timestamp)
        assertEquals(2, StrokeParser.parse(got[0].raw).strokes.size)
        assertEquals(87, got[0].info.batteryPercent)
        assertEquals(21000, got[0].info.width)
    }

    @Test
    fun slateSyncReadsDimensionsAndDeletes() {
        val pad = FakePad(PadKind.SLATE, mutableListOf(1_761_400_000L to samplePage()))
        val got = ArrayList<RawPage>()
        WacomSession(pad, PadKind.SLATE).syncAll(hexToBytes("a1b2c3d4e5f6")) { got.add(it); true }
        assertEquals(1, pad.deleted)
        assertEquals(21600, got[0].info.width)
        assertEquals(14800, got[0].info.height)
    }

    @Test
    fun pageIsKeptOnPadWhenSavingFails() {
        val pad = FakePad(PadKind.SPARK, mutableListOf(1L to samplePage()))
        WacomSession(pad, PadKind.SPARK).syncAll(hexToBytes("a1b2c3d4e5f6")) { false }
        assertEquals(0, pad.deleted)
    }

    @Test
    fun eraseDeletesAllThenResets() {
        val pages = mutableListOf(1L to samplePage())
        val pad = FakePad(PadKind.SPARK, pages)
        val r = WacomSession(pad, PadKind.SPARK).eraseAndReset()
        assertEquals(listOf("delete all pages: ok", "reset to defaults: ok"), r)
        assertEquals(listOf(0xe2, 0xe1), pad.sent)
        assertTrue(pages.isEmpty())
    }

    @Test
    fun wipeTriesDirectlyBeforeRegistering() {
        val pad = FakePad(PadKind.SPARK, mutableListOf(1L to samplePage()))
        val r = WacomSession(pad, PadKind.SPARK).wipe(hexToBytes("a1b2c3d4e5f6")) { error("no button press needed") }
        assertEquals(listOf("delete all pages: ok", "reset to defaults: ok"), r)
        assertEquals(listOf(0xe6, 0xe2, 0xe1), pad.sent)
    }

    @Test
    fun liveModeAndNameCommands() {
        val pad = FakePad(PadKind.SPARK, mutableListOf())
        val w = WacomSession(pad, PadKind.SPARK)
        w.startLive(hexToBytes("a1b2c3d4e5f6"))
        w.stopLive()
        w.setName(hexToBytes("a1b2c3d4e5f6"), "Mijn Folio")
        assertEquals(listOf(0xe6, 0xb1, 0xb1, 0xe6, 0xbb), pad.sent)
    }

    @Test
    fun parsesLivePenData() {
        val d = byteArrayOf(0xa1.toByte(), 12, 0x10, 0x27, 0x20, 0x4e, 0x64, 0, -1, -1, -1, -1, -1, -1)
        assertEquals(listOf(Pt(10000, 20000, 100), null), parseLive(d))
        assertEquals(emptyList(), parseLive(byteArrayOf(0xa2.toByte(), 4, 0, 0, 0, 0)))
    }

    @Test
    fun docxIsAZipWithEscapedText() {
        val bytes = Docx.build(listOf("28 okt" to "# Kop\n- punt\nA & <b> **vet**", "29 okt" to "tweede"))
        val entries = HashMap<String, String>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"), entries.keys)
        val doc = entries.getValue("word/document.xml")
        assertTrue(doc.contains("A &amp; &lt;b&gt; </w:t>"))
        assertTrue(doc.contains("<w:b/></w:rPr><w:t xml:space=\"preserve\">vet</w:t>"))
        assertTrue(doc.contains("•\tpunt"))
        assertTrue(doc.contains("w:type=\"page\""))
    }

    @Test
    fun recognisesTodoListsFromBoxesOrHeading() {
        val boxed = Todo.normalize("Boodschappen\n☐ melk\n[x] brood\n- kaas\nnoot")
        assertEquals("Boodschappen\n- [ ] melk\n- [x] brood\n- [ ] kaas\nnoot", boxed)
        val headed = Todo.normalize("# To do\nbellen\n\nmail sturen")
        assertEquals("# To do\n- [ ] bellen\n\n- [ ] mail sturen", headed)
        assertEquals("Gewoon een notitie\nmet tekst", Todo.normalize("Gewoon een notitie\nmet tekst"))
        assertEquals(listOf("melk", "brood"), Todo.items(boxed).take(2).map { it.text })
        assertEquals("- [x] a\n- [x] b", Todo.toggle("- [ ] a\n- [x] b", 0))
        assertEquals("werk, todo", Todo.withTag("werk", boxed))
        assertEquals("werk, Todo", Todo.withTag("werk, Todo", boxed))
        assertEquals("- [ ] a\n- [ ] b", Todo.add("- [ ] a", "b"))
        assertEquals("T\n- [ ] b\n- [x] a\nnoot", Todo.withItems("T\n- [ ] a\n- [x] b\nnoot", listOf("b" to false, "a" to true)))
        assertEquals("T\n- [ ] a\n- [ ] \n- [ ] c\nnoot", Todo.withItems("T\n- [ ] a\nnoot", listOf("a" to false, "" to false, "c" to false)))
        assertEquals("T\nnoot", Todo.withItems("T\n- [ ] a\nnoot", emptyList()))
        assertEquals("T\n- [ ] x", Todo.withItems("T\n", listOf("x" to false)))
        assertEquals(listOf("a", ""), Todo.items("- [ ] a\n- [ ] ").map { it.text })
    }

    @Test
    fun cleansRecognisedShapes() {
        val square = listOf(Pt(0, 0, 5), Pt(1000, 30, 5), Pt(1020, 990, 5), Pt(10, 1000, 5), Pt(20, 40, 5))
        assertEquals(listOf(Pt(0, 0, 5), Pt(1020, 0, 5), Pt(1020, 1000, 5), Pt(0, 1000, 5), Pt(0, 0, 5)), Shapes.clean("SHAPE_RECTANGLE", square))
        val line = listOf(Pt(0, 0, 1), Pt(500, 30, 1), Pt(1000, 0, 1), Pt(1500, 20, 1), Pt(2000, 0, 1))
        assertEquals(listOf(Pt(0, 0, 1), Pt(2000, 0, 1)), Shapes.clean("line", line))
        assertEquals(null, Shapes.clean("rectangle", line)) // not closed
        assertEquals(null, Shapes.clean("ellipse", listOf(Pt(0, 0, 1), Pt(10, 10, 1), Pt(20, 0, 1), Pt(10, 5, 1), Pt(0, 1, 1)))) // too small
        val circle = (0..20).map { i -> val a = 2 * Math.PI * i / 20; Pt((1000 + 600 * Math.cos(a)).toInt(), (1000 + 600 * Math.sin(a)).toInt(), 3) }
        assertEquals(49, Shapes.clean("circle", circle)!!.size)
        assertEquals(null, Shapes.clean("circle", square)) // corners too far from a round shape
        val word = listOf(Pt(0, 0, 1), Pt(300, 600, 1), Pt(600, 0, 1), Pt(900, 600, 1), Pt(1200, 0, 1))
        assertEquals(null, Shapes.clean("line", word)) // zigzag writing is not a line
    }

    @Test
    fun svgHasOnePolylinePerStroke() {
        val svg = Sketch(listOf(listOf(Pt(1, 2, 3), Pt(4, 5, 6)), listOf(Pt(7, 8, 9))), 21000, 14800).toSvg()
        assertTrue(svg.contains("width=\"210.0mm\""))
        assertEquals(2, Regex("<polyline").findAll(svg).count())
        assertTrue(svg.contains("points=\"7,8 7,8\""))
    }
}

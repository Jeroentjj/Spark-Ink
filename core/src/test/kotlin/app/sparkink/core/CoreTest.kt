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
}

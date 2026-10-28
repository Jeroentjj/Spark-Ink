package app.sparkink.core

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Builds pen-data files in the pad's compressed format. */
class PenFileBuilder {
    private val out = ByteArrayOutputStream().also { it.write(byteArrayOf(0x62, 0x38, 0x62, 0x74)) }

    private fun w(vararg v: Int) = v.forEach { out.write(it) }
    private fun le(v: Int) = w(v and 0xff, (v shr 8) and 0xff)

    fun strokeHeader() = w(0xff, 0xff, 0xee, 0xee, 0, 0, 0, 0, 0)
    fun point(x: Int, y: Int, p: Int) { w(0xff, 0xff, 0xff); le(x); le(y); le(p) }
    fun delta(dx: Int, dy: Int) { w(0x28, dx and 0xff, dy and 0xff) }
    fun endStroke() = w(0xfc, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)
    fun eof() = w(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)
    fun build(): ByteArray = out.toByteArray()
}

fun crc(b: ByteArray) = CRC32().also { it.update(b) }.value

/** A scripted pad that answers like the real thing, per protocol family. */
class FakePad(private val kind: PadKind, private val pages: MutableList<Pair<Long, ByteArray>>) : Transport {
    val sent = ArrayList<Int>()
    private val inbox = ArrayDeque<NordicMsg>()
    private var pen = ByteArray(0)
    private var penAt = 0L
    private val spark get() = kind == PadKind.SPARK
    var deleted = 0
    private var mustCount = false // the real Spark refuses the next page (b3 01) until it is asked the count again

    private fun reply(op: Int, vararg d: Int) = inbox.addLast(NordicMsg(op, ByteArray(d.size) { d[it].toByte() }))
    private fun replyB(op: Int, d: ByteArray) = inbox.addLast(NordicMsg(op, d))

    override fun send(opcode: Int, args: ByteArray) {
        sent.add(opcode)
        when (opcode) {
            0xe6, 0xe3, 0xb6, 0xb1, 0xec, 0xe1, 0xbb -> reply(0xb3, 0)
            0xe2 -> { pages.clear(); reply(0xb3, 0) }
            0xb9 -> reply(0xba, 87, 0)
            0xea -> {
                val which = args[0].toInt()
                val v = if (which == 3) 21600 else 14800
                replyB(0xeb, byteArrayOf(which.toByte(), 0, (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), 0, 0))
            }
            0xc1 -> if (spark.also { mustCount = false }) reply(0xc2, pages.size shr 8, pages.size and 0xff) else reply(0xc2, pages.size and 0xff, pages.size shr 8)
            0xc5 -> if (mustCount) reply(0xb3, 1) else { // spark: count (BE) then timestamp
                val (ts, data) = pages.first()
                reply(0xc7, 0, 0, (data.size shr 8) and 0xff, data.size and 0xff)
                replyB(0xcd, epochToBcd(ts))
            }
            0xcc -> {
                val (ts, data) = pages.first()
                replyB(0xcf, byteArrayOf((data.size and 0xff).toByte(), ((data.size shr 8) and 0xff).toByte(), 0, 0) + epochToBcd(ts))
            }
            0xc3 -> {
                val data = pages.first().second
                reply(0xc8, 0xbe)
                pen = data; penAt = System.currentTimeMillis()
                val c = crc(data)
                if (spark) {
                    reply(0xc8, 0xed)
                    reply(0xc9, (c shr 24).toInt() and 0xff, (c shr 16).toInt() and 0xff, (c shr 8).toInt() and 0xff, c.toInt() and 0xff)
                } else {
                    reply(0xc8, 0xed, c.toInt() and 0xff, (c shr 8).toInt() and 0xff, (c shr 16).toInt() and 0xff, (c shr 24).toInt() and 0xff)
                }
            }
            0xca -> { pages.removeAt(0); deleted++; mustCount = spark; if (!spark) reply(0xb3, 0) }
            else -> reply(0xb3, 4)
        }
    }

    override fun receive(timeoutMs: Long) = inbox.removeFirstOrNull()
    override fun takePenData() = pen
    override fun clearPenData() { pen = ByteArray(0) }
    override fun lastPenDataAt() = penAt
}

package app.sparkink.core

import java.time.LocalDateTime
import java.time.ZoneOffset

fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

fun hexToBytes(s: String): ByteArray {
    require(s.length % 2 == 0) { "odd hex length" }
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private fun ByteArray.u(i: Int) = this[i].toInt() and 0xff

fun ByteArray.u16le(o: Int) = u(o) or (u(o + 1) shl 8)
fun ByteArray.u16be(o: Int) = (u(o) shl 8) or u(o + 1)
fun ByteArray.u32le(o: Int): Long = u16le(o).toLong() or (u16le(o + 2).toLong() shl 16)
fun ByteArray.u32be(o: Int): Long = (u16be(o).toLong() shl 16) or u16be(o + 2).toLong()

/**
 * The pads send timestamps as six "BCD-ish" bytes yy mm dd hh mm ss where each byte,
 * printed as hex, reads as the decimal value (0x24 -> 24). Always UTC.
 */
fun bcdToEpoch(b: ByteArray, o: Int = 0): Long {
    fun v(i: Int) = (b.u(o + i) shr 4) * 10 + (b.u(o + i) and 0xf)
    return try {
        LocalDateTime.of(2000 + v(0), v(1), v(2), v(3), v(4), v(5)).toEpochSecond(ZoneOffset.UTC)
    } catch (e: Exception) {
        0L
    }
}

fun epochToBcd(epochSec: Long): ByteArray {
    val t = LocalDateTime.ofEpochSecond(epochSec, 0, ZoneOffset.UTC)
    fun enc(n: Int) = (((n / 10) shl 4) or (n % 10)).toByte()
    return byteArrayOf(enc(t.year % 100), enc(t.monthValue), enc(t.dayOfMonth),
        enc(t.hour), enc(t.minute), enc(t.second))
}

package app.sparkink.core

import java.util.zip.CRC32

/**
 * The command protocol of the Wacom Bamboo Spark / Folio ("SPARK") and Slate-style ("SLATE")
 * smartpads, as documented by the open-source Tuhi project. Every command is
 * [opcode, length, args...]; most are answered with `b3 <status>`, the rest with a
 * dedicated reply opcode.
 *
 * Not verified against every firmware: when a step fails, the log lines from [log] are what
 * is needed to adjust it.
 */
class WacomSession(
    private val t: Transport,
    val kind: PadKind,
    private val log: (String) -> Unit = {},
) {
    private val spark get() = kind == PadKind.SPARK

    // ---- low level -------------------------------------------------------------------

    private fun sendOnly(opcode: Int, args: ByteArray = byteArrayOf(0)) {
        log("> %02x %s".format(opcode, args.hex()))
        t.send(opcode, args)
    }

    private fun recv(timeoutMs: Long = 5000, what: String): NordicMsg {
        val m = t.receive(timeoutMs) ?: throw MissingReply(what)
        log("< $m")
        return m
    }

    private fun request(opcode: Int, args: ByteArray, expectReply: Int, what: String, timeoutMs: Long = 5000): NordicMsg {
        sendOnly(opcode, args)
        val m = recv(timeoutMs, what)
        if (m.opcode == 0xb3 && expectReply != 0xb3) checkAck(m)
        if (m.opcode != expectReply) throw UnexpectedReply("$what: expected %02x, got %02x".format(expectReply, m.opcode))
        return m
    }

    private fun checkAck(m: NordicMsg) {
        if (m.opcode != 0xb3) throw UnexpectedReply("expected b3 ack, got %02x".format(m.opcode))
        val code = m.data.firstOrNull()?.toInt()?.and(0xff) ?: 0
        if (code != 0) throw DeviceError(code)
    }

    private fun ack(opcode: Int, args: ByteArray = byteArrayOf(0), what: String) {
        sendOnly(opcode, args)
        checkAck(recv(what = what))
    }

    // ---- commands --------------------------------------------------------------------

    /** Log in with the id chosen when the pad was registered. */
    fun connect(uuid: ByteArray) {
        require(uuid.size == 6) { "uuid must be 6 bytes" }
        try {
            ack(0xe6, uuid, "connect")
        } catch (e: DeviceError) {
            val authCode = if (spark) DeviceError.GENERAL_ERROR else DeviceError.AUTHORIZATION_ERROR
            if (e.code == authCode) throw AuthorizationError()
            throw e
        }
    }

    fun setTime(nowSec: Long = System.currentTimeMillis() / 1000) = ack(0xb6, epochToBcd(nowSec), "set time")

    fun battery(): Pair<Int, Boolean> {
        val m = request(0xb9, byteArrayOf(0), 0xba, "battery")
        return (m.data[0].toInt() and 0xff) to (m.data[1].toInt() == 1)
    }

    fun padInfo(): PadInfo {
        val (pct, charging) = battery()
        if (spark) return PadInfo(kind, 21000, 14800, pct, charging)
        fun dim(which: Int): Int {
            val m = request(0xea, byteArrayOf(which.toByte(), 0), 0xeb, "dimension $which")
            if (m.data.size != 6 || m.data.u16le(0) != which) throw UnexpectedReply("bad dimension reply ${m.data.hex()}")
            return m.data.u32le(2).toInt()
        }
        return PadInfo(kind, dim(3), dim(4), pct, charging)
    }

    private fun setPaperMode() = ack(0xb1, byteArrayOf(1), "paper mode")

    /** Log in and switch to live mode: the pad then streams every pen move (see [parseLive]). */
    fun startLive(uuid: ByteArray) {
        connect(uuid)
        ack(0xb1, byteArrayOf(0), "live mode")
    }

    /** Back to normal recording on paper. */
    fun stopLive() = setPaperMode()

    /** The Spark firmware needs the trailing line break (Tuhi). */
    fun setName(uuid: ByteArray, name: String) {
        connect(uuid)
        ack(0xbb, (name.take(30) + "\n").toByteArray(Charsets.UTF_8), "set name")
    }

    fun batteryOf(uuid: ByteArray): Pair<Int, Boolean> {
        connect(uuid)
        return battery()
    }

    private fun fileCount(): Int {
        val m = request(0xc1, byteArrayOf(0), 0xc2, "file count")
        return if (spark) m.data.u16be(0) else m.data.u16le(0)
    }

    /** Returns (bytes in file, timestamp seconds). */
    private fun strokeInfo(): Pair<Long, Long> {
        if (spark) {
            sendOnly(0xc5)
            var count = 0L
            while (true) {
                val m = recv(what = "stroke info")
                when (m.opcode) {
                    0xc7 -> count = m.data.u32be(0) // sometimes missing, the timestamp follows
                    0xcd -> return count to bcdToEpoch(m.data)
                    0xb3 -> checkAck(m)
                    else -> throw UnexpectedReply("stroke info: unexpected %02x".format(m.opcode))
                }
            }
        }
        val m = request(0xcc, byteArrayOf(0), 0xcf, "stroke info")
        return m.data.u32le(0) to bcdToEpoch(m.data, 4)
    }

    /** Ask for the oldest file, wait for it to finish streaming and verify the checksum. */
    private fun downloadOldest(): ByteArray {
        t.clearPenData()
        val start = request(0xc3, byteArrayOf(0), 0xc8, "download")
        if (start.data.firstOrNull()?.toInt()?.and(0xff) != 0xbe) throw UnexpectedReply("download: ${start.data.hex()}")

        var crc: Long? = null
        var seenEnd = false
        while (crc == null) {
            val m = t.receive(5000)
            if (m == null) {
                // Data still flowing in? Keep waiting; the end message only comes after the last chunk.
                if (System.currentTimeMillis() - t.lastPenDataAt() < 5000) continue
                throw MissingReply("end of download")
            }
            log("< $m")
            when {
                m.opcode == 0xc8 && m.data.firstOrNull()?.toInt()?.and(0xff) == 0xed -> {
                    seenEnd = true
                    if (!spark) crc = m.data.copyOfRange(1, 5).reversedArray().u32be(0)
                }
                m.opcode == 0xc9 && spark -> crc = m.data.u32be(0)
                else -> throw UnexpectedReply("end of download: unexpected %02x".format(m.opcode))
            }
        }
        val pen = t.takePenData()
        val sum = CRC32().also { it.update(pen) }.value
        if (sum != crc) throw UnexpectedReply("checksum mismatch (got %08x, pad says %08x, ${pen.size} bytes, end=$seenEnd)".format(sum, crc))
        return pen
    }

    private fun deleteOldest() {
        if (spark) {
            sendOnly(0xca)
            t.receive(200)?.let { log("< $it (ignored)") }
        } else {
            ack(0xca, what = "delete")
        }
    }

    // ---- flows -----------------------------------------------------------------------

    /**
     * Pair a pad that is in pairing mode (button held until the LED blinks blue).
     * [onPressButton] is called when the user must press the pad's button to confirm.
     */
    fun register(uuid: ByteArray, onPressButton: () -> Unit): PadInfo {
        if (spark) {
            try { connect(uuid) } catch (_: AuthorizationError) { /* expected while pairing */ }
            sendOnly(0xe3, byteArrayOf(1))
        } else {
            sendOnly(0xe7, uuid)
        }
        onPressButton()

        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            val m = t.receive(10_000)
            if (m != null) {
                log("< $m")
                if (m.opcode == 0xe4) break
                if (m.opcode == 0x53) throw WacomException("This looks like an Intuos Pro Paper, which this app does not support")
                if (m.opcode == 0xb3) checkAck(m)
            }
            if (System.currentTimeMillis() > deadline) throw MissingReply("button press")
        }

        if (spark) ack(0xe5, what = "register complete")
        setTime()
        if (!spark) ack(0xec, byteArrayOf(6, 0, 0, 0, 0, 0), "transfer type")
        return padInfo()
    }

    /**
     * Erase all pages (e2) and reset the pad to factory defaults (e1), the commands behind
     * Inkspace's hidden "Device Memory Reset". Inkspace sends them right after the button
     * confirmation of a (re)registration, so call this after [register]. Both are tried even
     * if one fails; returns one result line per command. Not verified on hardware.
     */
    /**
     * Empty and reset the pad. First tries the commands straight after the (refused) login,
     * since a full pad drops the link on e3; only if both are refused does it register first.
     */
    fun wipe(uuid: ByteArray, onPressButton: () -> Unit): List<String> {
        try { connect(uuid) } catch (_: AuthorizationError) { /* not registered with us: expected */ }
        val direct = eraseAndReset()
        if (direct.any { it.endsWith(": ok") }) return direct
        log("direct wipe refused, registering first")
        register(uuid, onPressButton)
        return eraseAndReset()
    }

    fun eraseAndReset(): List<String> {
        return listOf(0xe2 to "delete all pages", 0xe1 to "reset to defaults").map { (op, what) ->
            try { ack(op, what = what); "$what: ok" } catch (e: Exception) { "$what: failed ($e)" }
        }
    }

    /**
     * Download every stored page. [onPage] must persist the page and return true; only then
     * is it deleted from the pad. Throws [DeviceError] with invalidState when the pad is not
     * in "ready to sync" state.
     */
    fun syncAll(uuid: ByteArray, onPage: (RawPage) -> Boolean): Int {
        connect(uuid)
        if (spark) ack(0xe3, what = "e3")
        setTime()
        val info = padInfo()
        if (!spark) ack(0xec, byteArrayOf(6, 0, 0, 0, 0, 0), "transfer type")
        setPaperMode()

        var saved = 0
        var lastTs = -1L
        // Ask the count again before every page: after a delete the Spark refuses the next page (b3 01) otherwise.
        while (true) {
            val remaining = fileCount()
            log("pages on pad: $remaining")
            if (remaining == 0) break
            val (bytes, ts) = strokeInfo()
            log("page of $bytes bytes, timestamp $ts")
            if (ts == lastTs) { log("same page again: delete did not work, stopping"); break }
            lastTs = ts
            val raw = downloadOldest()
            if (!onPage(RawPage(ts, raw, info))) break
            deleteOldest()
            saved++
        }
        return saved
    }
}

package app.sparkink.core

/** One framed message on the Nordic-UART style channel: opcode, length, payload. */
class NordicMsg(val opcode: Int, val data: ByteArray) {
    override fun toString() = "%02x/%02x %s".format(opcode, data.size, data.hex())
}

/**
 * What the protocol needs from Bluetooth. The Android implementation lives in the app;
 * tests use a fake pad.
 */
interface Transport {
    /** Send [opcode, args.size, args...] to the pad. */
    fun send(opcode: Int, args: ByteArray)

    /** Next complete message, or null after [timeoutMs]. */
    fun receive(timeoutMs: Long): NordicMsg?

    /** Pen data (out-of-band notifications) received since the last [clearPenData]. */
    fun takePenData(): ByteArray
    fun clearPenData()

    /** System.currentTimeMillis() of the latest pen data chunk, 0 if none yet. */
    fun lastPenDataAt(): Long
}

package app.sparkink.core

/** One sampled pen position in device units (10 µm) plus pressure. */
data class Pt(val x: Int, val y: Int, val p: Int)

enum class PadKind(val pressureMax: Int) {
    SPARK(1023),
    SLATE(2047),
}

class PadInfo(
    val kind: PadKind,
    val width: Int,
    val height: Int,
    val batteryPercent: Int,
    val charging: Boolean,
) {
    val pressureMax get() = kind.pressureMax
}

/** One page downloaded from the pad. */
class RawPage(
    val timestamp: Long,
    val raw: ByteArray,
    val info: PadInfo,
)

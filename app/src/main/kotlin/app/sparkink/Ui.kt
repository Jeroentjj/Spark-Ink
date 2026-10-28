package app.sparkink

/** Colours of the app: dark night-desk, light ink, one soft blue accent, and muted notebook covers. */
object Ui {
    val BG = 0xFF16171B.toInt()
    val SURFACE = 0xFF23252B.toInt()
    val INK = 0xFFECEAE4.toInt()
    val MUTED = 0xFF9A98A2.toInt()
    val LINE = 0xFF3A3C44.toInt()
    val ACCENT = 0xFF8AB0F0.toInt()
    val ACCENT_SOFT = 0xFF263452.toInt()
    val DANGER = 0xFFE88A78.toInt()
    val DANGER_SOFT = 0xFF4A2A26.toInt()
    /** Text on the paper-white label of a notebook cover. */
    val PAPER_INK = 0xFF1E1E24.toInt()

    val COVERS = intArrayOf(
        0xFF2E5AAC.toInt(), // blue
        0xFF2F7D6D.toInt(), // teal
        0xFFB5533C.toInt(), // terracotta
        0xFF6E55A0.toInt(), // purple
        0xFFC08A26.toInt(), // mustard
        0xFF3E4A59.toInt(), // slate
    )
}

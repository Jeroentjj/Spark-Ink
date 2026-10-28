package app.sparkink

/** Colours of the app: warm paper, ink, one blue accent, and muted notebook covers. */
object Ui {
    val BG = 0xFFF4F1EA.toInt()
    val SURFACE = 0xFFFFFFFF.toInt()
    val INK = 0xFF1E1E24.toInt()
    val MUTED = 0xFF6E6C75.toInt()
    val LINE = 0xFFDCD7CC.toInt()
    val ACCENT = 0xFF2E5AAC.toInt()
    val ACCENT_SOFT = 0xFFE3EAF6.toInt()
    val DANGER = 0xFFB3402F.toInt()

    val COVERS = intArrayOf(
        0xFF2E5AAC.toInt(), // blue
        0xFF2F7D6D.toInt(), // teal
        0xFFB5533C.toInt(), // terracotta
        0xFF6E55A0.toInt(), // purple
        0xFFC08A26.toInt(), // mustard
        0xFF3E4A59.toInt(), // slate
    )
}

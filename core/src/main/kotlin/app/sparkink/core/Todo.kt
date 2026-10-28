package app.sparkink.core

/**
 * To-do lists live in the note text as Markdown task lines (`- [ ] melk`, `- [x] brood`), so
 * Notion, Word and plain text exports carry them as they are.
 */
object Todo {
    const val TAG = "todo"

    // A hand-drawn box as OCR reads it: [ ] [x] ☐ □ ☑ ☒ ✓ ✔, optionally after a bullet.
    private val box = Regex("""^\s*(?:[-*•]\s*)?(\[\s?([xX✓✔]?)\s?]|[☐□]|[☑☒✓✔])\s*""")
    private val bullet = Regex("""^\s*[-*•]\s+""")
    private val heading = Regex("""\b(to[ -]?do'?s?|te doen|taken|boodschappen|checklist|tasks|shopping|groceries)\b""", RegexOption.IGNORE_CASE)
    private val task = Regex("""^- \[( |x)] (.*)$""")

    class Item(val line: Int, val text: String, val done: Boolean)

    private fun titleLine(lines: List<String>): Int {
        val i = lines.indexOfFirst { it.isNotBlank() }
        return if (i >= 0 && box.find(lines[i]) == null && heading.containsMatchIn(lines[i])) i else -1
    }

    /** At least two ticked/unticked boxes, or a first line like "To do" / "Boodschappen". */
    fun looksLikeTodo(text: String): Boolean {
        val lines = text.lines()
        return lines.count { box.find(it) != null } >= 2 || titleLine(lines) >= 0
    }

    /**
     * Rewrite the list lines as task lines. With boxes on the page only boxed and bulleted lines
     * become tasks; with just a heading every other non-empty line does.
     */
    fun normalize(text: String): String {
        if (!looksLikeTodo(text)) return text
        val lines = text.lines()
        val title = titleLine(lines)
        val boxed = lines.count { box.find(it) != null } >= 2
        return lines.mapIndexed { i, line ->
            val m = box.find(line)
            when {
                i == title || line.isBlank() || task.matches(line) -> line
                m != null -> {
                    val mark = m.groupValues[1]
                    val done = mark.any { it in "xX✓✔☑☒" }
                    "- [${if (done) "x" else " "}] " + line.substring(m.range.last + 1).trim()
                }
                bullet.containsMatchIn(line) -> "- [ ] " + line.replace(bullet, "").trim()
                !boxed && !line.trimStart().startsWith("#") -> "- [ ] " + line.trim()
                else -> line
            }
        }.joinToString("\n")
    }

    fun items(text: String): List<Item> = text.lines().mapIndexedNotNull { i, line ->
        task.find(line)?.let { Item(i, it.groupValues[2], it.groupValues[1] == "x") }
    }

    fun toggle(text: String, line: Int): String = text.lines().mapIndexed { i, l ->
        when {
            i != line -> l
            l.startsWith("- [ ] ") -> "- [x] " + l.drop(6)
            l.startsWith("- [x] ") -> "- [ ] " + l.drop(6)
            else -> l
        }
    }.joinToString("\n")

    fun add(text: String, item: String): String = text.trimEnd() + (if (text.isBlank()) "" else "\n") + "- [ ] " + item.trim()

    /** Tag list with [TAG] added when the text is a to-do list. */
    fun withTag(tags: String, text: String): String {
        val list = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (items(text).isEmpty() || list.any { it.equals(TAG, ignoreCase = true) }) tags
        else (list + TAG).joinToString(", ")
    }
}

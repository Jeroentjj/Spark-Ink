package app.sparkink.core

/** A Notion block reduced to what we need: its type (paragraph, heading_1, ...) and plain text. */
data class Block(val type: String, val text: String)

object NotionBlocks {
    const val MAX_TEXT = 2000     // Notion limit per rich_text item
    const val MAX_BLOCKS = 100    // Notion limit per create-page request

    /** Light Markdown (the format the Claude transcription uses) to Notion blocks. */
    fun fromMarkdown(text: String): List<Block> {
        val out = ArrayList<Block>()
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val (type, body) = when {
                line.startsWith("### ") -> "heading_3" to line.drop(4)
                line.startsWith("## ") -> "heading_2" to line.drop(3)
                line.startsWith("# ") -> "heading_1" to line.drop(2)
                line.startsWith("- ") || line.startsWith("* ") -> "bulleted_list_item" to line.drop(2)
                Regex("^\\d+[.)] ").containsMatchIn(line) -> "numbered_list_item" to line.replace(Regex("^\\d+[.)] "), "")
                else -> "paragraph" to line
            }
            body.chunked(MAX_TEXT).forEach { out.add(Block(type, it)) }
        }
        if (out.size > MAX_BLOCKS) {
            val keep = out.take(MAX_BLOCKS - 1)
            val rest = out.drop(MAX_BLOCKS - 1).joinToString("\n") { it.text }.take(MAX_TEXT)
            return keep + Block("paragraph", rest)
        }
        return out
    }

    /** First meaningful line as page title (max 80 chars), or [fallback]. */
    fun title(text: String, fallback: String): String {
        val first = text.lines().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotBlank() }
        return (first ?: fallback).take(80)
    }

    /** Accepts a Notion URL or a bare id (with or without dashes); returns the 32-hex id. */
    fun extractId(input: String): String? {
        // The id is the last thing in the path ("Page-Title-<id>"), so anchor at the end: words like
        // "DB" or "Bad" in the title are hex digits too and must not be swallowed.
        val cleaned = input.trim().substringBefore('?').substringBefore('#').trimEnd('/').replace("-", "")
        return Regex("[0-9a-fA-F]{32}$").find(cleaned)?.value?.lowercase()
    }
}

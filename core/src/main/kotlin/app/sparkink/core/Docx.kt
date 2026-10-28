package app.sparkink.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal Word (.docx) writer, no library: one section per page (a heading, then its text),
 * each on a new Word page. Understands the light Markdown of the transcription:
 * `#`/`##`/`###` headings, `-`/`*` bullets and `**bold**`.
 */
object Docx {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun esc(s: String) = s.filter { it == '\t' || it >= ' ' }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Runs for one line; text between ** pairs is bold. */
    private fun runs(text: String, size: Int? = null, bold: Boolean = false): String =
        text.split("**").mapIndexed { i, part ->
            if (part.isEmpty()) "" else {
                val props = (if (bold || i % 2 == 1) "<w:b/>" else "") + (size?.let { "<w:sz w:val=\"$it\"/>" } ?: "")
                (if (props.isEmpty()) "<w:r>" else "<w:r><w:rPr>$props</w:rPr>") +
                    "<w:t xml:space=\"preserve\">${esc(part)}</w:t></w:r>"
            }
        }.joinToString("")

    private fun heading(text: String, size: Int) =
        "<w:p><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/></w:pPr>${runs(text, size, bold = true)}</w:p>"

    private fun paragraph(line: String): String = when {
        line.startsWith("### ") -> heading(line.drop(4), 26)
        line.startsWith("## ") -> heading(line.drop(3), 30)
        line.startsWith("# ") -> heading(line.drop(2), 36)
        line.startsWith("- [ ] ") || line.startsWith("- [x] ") ->
            "<w:p><w:pPr><w:ind w:left=\"360\" w:hanging=\"360\"/></w:pPr>${runs((if (line[3] == 'x') "☑\t" else "☐\t") + line.drop(6))}</w:p>"
        line.startsWith("- ") || line.startsWith("* ") ->
            "<w:p><w:pPr><w:ind w:left=\"360\" w:hanging=\"360\"/></w:pPr>${runs("•\t" + line.drop(2))}</w:p>"
        else -> "<w:p>${runs(line)}</w:p>"
    }

    /** [sections]: (heading, text) per page. */
    fun build(sections: List<Pair<String, String>>): ByteArray {
        val body = StringBuilder()
        sections.forEachIndexed { i, (title, text) ->
            if (i > 0) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            body.append(heading(title, 28))
            for (line in text.lines().map { it.trimEnd() }) body.append(if (line.isBlank()) "<w:p/>" else paragraph(line))
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="$W"><w:body>$body""" +
            """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val files = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to document,
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, xml) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

package app.sparkink

import app.sparkink.core.Block
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Creates one Notion page per note. Blocking: call from a worker thread. */
object NotionClient {
    private const val VERSION = "2022-06-28"

    private fun call(method: String, path: String, token: String, body: JSONObject? = null): Pair<Int, String> {
        val conn = URL("https://api.notion.com/v1/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Notion-Version", VERSION)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun richText(s: String) = JSONArray().put(JSONObject().apply {
        put("type", "text"); put("text", JSONObject().put("content", s))
    })

    private fun error(code: Int, text: String): Exception {
        val msg = try { JSONObject(text).getString("message") } catch (_: Exception) { text.take(200) }
        return Exception("Notion fout $code: $msg")
    }

    /**
     * [targetId] may be a database (a row is added, using its title column) or a normal page
     * (a sub-page is added). Returns the URL of the new page.
     */
    fun createPage(token: String, targetId: String, title: String, blocks: List<Block>): String {
        if (token.isBlank()) throw Exception("Geen Notion-token ingesteld (Instellingen)")

        val (dbCode, dbText) = call("GET", "databases/$targetId", token)
        if (dbCode == 401) throw error(dbCode, dbText)

        val parent = JSONObject()
        val props = JSONObject()
        if (dbCode == 200) {
            val schema = JSONObject(dbText).getJSONObject("properties")
            val titleName = schema.keys().asSequence().firstOrNull { schema.getJSONObject(it).optString("type") == "title" } ?: "Name"
            parent.put("database_id", targetId)
            props.put(titleName, JSONObject().put("title", richText(title)))
        } else {
            parent.put("page_id", targetId)
            props.put("title", JSONObject().put("title", richText(title)))
        }

        val children = JSONArray()
        for (b in blocks) children.put(JSONObject().apply {
            put("object", "block"); put("type", b.type)
            put(b.type, JSONObject().put("rich_text", richText(b.text)))
        })
        val body = JSONObject().put("parent", parent).put("properties", props).put("children", children)

        val (code, text) = call("POST", "pages", token, body)
        if (code !in 200..299) throw error(code, text)
        return JSONObject(text).optString("url")
    }
}

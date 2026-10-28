package app.sparkink

import android.content.Context
import app.sparkink.core.Pt
import app.sparkink.core.Sketch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One page of handwriting downloaded from the pad. */
class Note(
    val id: String,
    val timestamp: Long,          // seconds since epoch, from the pad
    val width: Int,
    val height: Int,
    val strokes: List<List<Pt>>,
    var rotation: Int,
    var text: String,
    var source: String,           // where [text] came from: "", "mlkit", "claude", "hand"
    var notionUrl: String = "",   // set once exported to Notion
    var notebookId: String = "",  // "" = loose page, not in a notebook
    var driveIds: String = "",    // "folder png txt" ids in Google Drive, once uploaded
) {
    // Pages are always shown portrait: the pad reports a landscape page, so turn it a quarter.
    init { if (rotation % 180 == 0 && width > height) rotation = (rotation + 90) % 360 }

    fun sketch() = Sketch(strokes, width, height).rotated(rotation)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("timestamp", timestamp); put("width", width); put("height", height)
        put("rotation", rotation); put("text", text); put("source", source); put("notionUrl", notionUrl)
        put("notebook", notebookId); put("drive", driveIds)
        // [[x,y,p,x,y,p,...], ...] keeps the file small
        put("strokes", JSONArray().also { all ->
            for (s in strokes) all.put(JSONArray().also { a -> for (p in s) { a.put(p.x); a.put(p.y); a.put(p.p) } })
        })
    }

    companion object {
        fun fromJson(j: JSONObject): Note {
            val arr = j.getJSONArray("strokes")
            val strokes = List(arr.length()) { i ->
                val a = arr.getJSONArray(i)
                List(a.length() / 3) { k -> Pt(a.getInt(k * 3), a.getInt(k * 3 + 1), a.getInt(k * 3 + 2)) }
            }
            return Note(j.getString("id"), j.getLong("timestamp"), j.getInt("width"), j.getInt("height"),
                strokes, j.optInt("rotation", 0), j.optString("text", ""), j.optString("source", ""), j.optString("notionUrl", ""),
                j.optString("notebook", ""), j.optString("drive", ""))
        }
    }
}

class NoteStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "notes").also { it.mkdirs() }

    fun save(n: Note) {
        File(dir, "${n.id}.json.tmp").also { it.writeText(n.toJson().toString()) }
            .renameTo(File(dir, "${n.id}.json"))
    }

    /** Keep the exact bytes from the pad too, so a better parser can re-read them later. */
    fun saveRaw(id: String, raw: ByteArray) = File(dir, "$id.raw").writeBytes(raw)

    fun load(id: String): Note? = try {
        Note.fromJson(JSONObject(File(dir, "$id.json").readText()))
    } catch (e: Exception) { null }

    fun all(): List<Note> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { load(it.name.removeSuffix(".json")) }
            .sortedByDescending { it.timestamp }

    fun delete(id: String) {
        File(dir, "$id.json").delete(); File(dir, "$id.raw").delete()
    }
}

class Notebook(val id: String, var title: String, var color: Int)

/** The notebooks, kept in one small JSON file; pages point to them by [Note.notebookId]. */
class NotebookStore(ctx: Context) {
    private val file = File(ctx.filesDir, "notebooks.json")
    private val list = ArrayList<Notebook>()

    init {
        if (file.exists()) try {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                list.add(Notebook(it.getString("id"), it.optString("title", ""), it.optInt("color", Ui.COVERS[0])))
            }
        } catch (e: Exception) {
            // Keep the unreadable file instead of overwriting it on the next save.
            file.renameTo(File(file.path + ".bad"))
            list.clear()
        }
    }

    fun all(): List<Notebook> = list.toList()
    fun get(id: String) = list.find { it.id == id }

    fun create(title: String) = Notebook("nb${System.currentTimeMillis()}", title, Ui.COVERS[list.size % Ui.COVERS.size])
        .also { list.add(it); write() }

    fun save() = write()

    fun delete(id: String) { list.removeAll { it.id == id }; write() }

    private fun write() {
        val a = JSONArray()
        for (b in list) a.put(JSONObject().put("id", b.id).put("title", b.title).put("color", b.color))
        File(file.path + ".tmp").also { it.writeText(a.toString()) }.renameTo(file)
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sparkink", Context.MODE_PRIVATE)

    var padAddress: String? get() = sp.getString("padAddress", null); set(v) = sp.edit().putString("padAddress", v).apply()
    var padUuid: String? get() = sp.getString("padUuid", null); set(v) = sp.edit().putString("padUuid", v).apply()
    var padKind: String? get() = sp.getString("padKind", null); set(v) = sp.edit().putString("padKind", v).apply()
    var padName: String? get() = sp.getString("padName", null); set(v) = sp.edit().putString("padName", v).apply()

    var apiKey: String get() = sp.getString("apiKey", "") ?: ""; set(v) = sp.edit().putString("apiKey", v.trim()).apply()
    var model: String get() = sp.getString("model", "claude-sonnet-5-5") ?: "claude-sonnet-5-5"; set(v) = sp.edit().putString("model", v.trim()).apply()
    var language: String get() = sp.getString("language", "nl") ?: "nl"; set(v) = sp.edit().putString("language", v.trim()).apply()
    /** "off", "mlkit" or "claude" */
    var autoOcr: String get() = sp.getString("autoOcr", "mlkit") ?: "mlkit"; set(v) = sp.edit().putString("autoOcr", v).apply()
    var githubToken: String get() = sp.getString("githubToken", "") ?: ""; set(v) = sp.edit().putString("githubToken", v.trim()).apply()
    var notionToken: String get() = sp.getString("notionToken", "") ?: ""; set(v) = sp.edit().putString("notionToken", v.trim()).apply()
    var notionTarget: String get() = sp.getString("notionTarget", "") ?: ""; set(v) = sp.edit().putString("notionTarget", v.trim()).apply()
    var notionAuto: Boolean get() = sp.getBoolean("notionAuto", false); set(v) = sp.edit().putBoolean("notionAuto", v).apply()
    var driveAuto: Boolean get() = sp.getBoolean("driveAuto", false); set(v) = sp.edit().putBoolean("driveAuto", v).apply()
    val notionReady get() = notionToken.isNotBlank() && app.sparkink.core.NotionBlocks.extractId(notionTarget) != null

    var defaultRotation: Int get() = sp.getInt("defaultRotation", 0); set(v) = sp.edit().putInt("defaultRotation", v).apply()
}

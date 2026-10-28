package app.sparkink

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Puts pages in Google Drive as "Spark Ink/<notebook>/<date>.png + .txt". Only the drive.file scope:
 * the app sees nothing but the files it made itself. Blocking: call from a worker thread.
 */
object DriveClient {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private const val FOLDER = "application/vnd.google-apps.folder"

    private class HttpError(val code: Int, msg: String) : Exception("Drive fout $code: $msg")

    private fun call(method: String, url: String, token: String, contentType: String? = null, body: ByteArray? = null): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection has no PATCH; Google APIs accept it as an override.
            conn.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (_: Exception) { text.take(200) }
                throw HttpError(code, msg)
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun json(o: JSONObject) = o.toString().toByteArray()

    /** The folder tagged [key] ("root", "loose" or a notebook id): found, renamed if needed, or made. */
    fun folder(token: String, key: String, name: String, parent: String?): String {
        val q = "appProperties has { key='sparkink' and value='$key' } and mimeType='$FOLDER' and trashed=false"
        val found = call("GET", "$API?fields=files(id,name)&q=" + URLEncoder.encode(q, "UTF-8"), token).getJSONArray("files")
        if (found.length() > 0) {
            val f = found.getJSONObject(0)
            if (f.getString("name") != name) call("PATCH", "$API/${f.getString("id")}", token, "application/json", json(JSONObject().put("name", name)))
            return f.getString("id")
        }
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER)
            .put("appProperties", JSONObject().put("sparkink", key))
        if (parent != null) meta.put("parents", JSONArray().put(parent))
        return call("POST", API, token, "application/json", json(meta)).getString("id")
    }

    /** Create the file, or replace the content of [id] and move it to [folder]. Returns its id. */
    private fun put(token: String, id: String, oldFolder: String, folder: String, name: String, mime: String, data: ByteArray): String {
        val b = "sparkink" + System.nanoTime()
        fun body(meta: JSONObject) = ("--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$b\r\nContent-Type: $mime\r\n\r\n").toByteArray() + data + "\r\n--$b--".toByteArray()
        val type = "multipart/related; boundary=$b"
        if (id.isNotBlank()) try {
            val move = if (oldFolder.isNotBlank() && oldFolder != folder) "&addParents=$folder&removeParents=$oldFolder" else ""
            return call("PATCH", "$UPLOAD/$id?uploadType=multipart$move", token, type, body(JSONObject().put("name", name))).getString("id")
        } catch (e: HttpError) {
            if (e.code != 404) throw e // removed in Drive: make it again
        }
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folder))
        return call("POST", "$UPLOAD?uploadType=multipart", token, type, body(meta)).getString("id")
    }

    /** Upload page [n] into the folder of its notebook ([bookTitle]); returns the new [Note.driveIds]. */
    fun uploadNote(token: String, n: Note, bookTitle: String): String {
        val root = folder(token, "root", "Spark Ink", null)
        val folder = folder(token, n.notebookId.ifBlank { "loose" }, bookTitle, root)
        val (oldFolder, oldPng, oldTxt) = (n.driveIds.split(" ") + listOf("", "", "")).take(3)
        val name = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date(n.timestamp * 1000))
        val png = put(token, oldPng, oldFolder, folder, "$name.png", "image/png",
            Render.png(Render.bitmap(n.sketch(), 2000, cropToInk = false)))
        val txt = if (n.text.isBlank()) oldTxt
            else put(token, oldTxt, oldFolder, folder, "$name.txt", "text/plain; charset=UTF-8", n.text.toByteArray())
        return "$folder $png $txt"
    }
}

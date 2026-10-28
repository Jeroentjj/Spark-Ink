package app.sparkink

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Looks for a newer build on the GitHub release the CI publishes, and downloads it. Blocking. */
object Updater {
    private const val REPO = "Jeroentjj/Spark-Ink"
    private const val TAG = "sparkink-latest"

    class Release(val sha: String, val assetUrl: String, val size: Long)

    private fun open(url: String, token: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    fun latest(token: String): Release {
        val c = open("https://api.github.com/repos/$REPO/releases/tags/$TAG", token, "application/vnd.github+json")
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code == 404 || code == 401 || code == 403)
                throw Exception(tr("GitHub geeft geen toegang (fout $code). De repo is privé: vul bij Instellingen een GitHub-token met leesrechten in.", "GitHub refused access (error $code). The repo is private: enter a read-only GitHub token in Settings."))
            if (code !in 200..299) throw Exception(tr("GitHub fout $code", "GitHub error $code"))
            val json = JSONObject(text)
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk"))
                    return Release(json.optString("target_commitish").take(7), a.getString("url"), a.getLong("size"))
            }
            throw Exception(tr("Geen APK gevonden op de release (wordt misschien net vernieuwd, probeer over een minuut opnieuw)", "No APK found on the release (it may be updating right now, try again in a minute)"))
        } finally {
            c.disconnect()
        }
    }

    fun isNewer(r: Release) = r.sha.isNotBlank() && !BuildConfig.GIT_SHA.startsWith(r.sha) && !r.sha.startsWith(BuildConfig.GIT_SHA)

    /** Download to [dest] and verify it really is the APK (size and zip header) before handing it to Android. */
    fun download(token: String, r: Release, dest: File, progress: (Long) -> Unit) {
        dest.parentFile?.mkdirs()
        dest.delete()
        var c = open(r.assetUrl, token, "application/octet-stream")
        try {
            var code = c.responseCode
            if (code in 301..303 || code == 307 || code == 308) {
                // The file itself lives on a signed storage URL that must not receive our token.
                val next = c.getHeaderField("Location") ?: throw Exception(tr("Download-omleiding zonder adres", "Download redirect without an address"))
                c.disconnect()
                c = open(next, "", "application/octet-stream")
                code = c.responseCode
            }
            if (code !in 200..299) throw Exception(tr("Download mislukt (fout $code)", "Download failed (error $code)"))
            var total = 0L
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        progress(total)
                    }
                }
            }
            val head = dest.inputStream().use { ByteArray(2).also { b -> it.read(b) } }
            if (total != r.size || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
                dest.delete()
                throw Exception(tr("Gedownload bestand is onvolledig of geen APK ($total van ${r.size} bytes)", "Downloaded file is incomplete or not an APK ($total of ${r.size} bytes)"))
            }
        } finally {
            c.disconnect()
        }
    }
}

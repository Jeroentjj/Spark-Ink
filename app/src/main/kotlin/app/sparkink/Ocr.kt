package app.sparkink

import android.util.Base64
import app.sparkink.core.Pt
import app.sparkink.core.Shapes
import app.sparkink.core.Sketch
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** On-device recognition from the pen strokes themselves (no image, works offline after the first model download). */
object MlKitOcr {
    fun recognize(sketch: Sketch, languageTag: String, done: (Result<String>) -> Unit) {
        val model = try {
            val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
                ?: return done(Result.failure(Exception("ML Kit kent taal '$languageTag' niet")))
            DigitalInkRecognitionModel.builder(id).build()
        } catch (e: Exception) {
            return done(Result.failure(e))
        }
        val lines = sketch.lines()
        if (lines.isEmpty()) return done(Result.success(""))

        RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build())
            .addOnFailureListener { done(Result.failure(Exception("Taalmodel downloaden mislukt (internet nodig, eenmalig): ${it.message}"))) }
            .addOnSuccessListener {
                val rec = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
                val out = ArrayList<String>()
                fun finish(r: Result<String>) { rec.close(); done(r) }
                fun next(i: Int) {
                    if (i >= lines.size) return finish(Result.success(out.joinToString("\n")))
                    val ink = Ink.builder()
                    for (stroke in lines[i]) {
                        val sb = Ink.Stroke.builder()
                        // 25 device units (0.25 mm) per pseudo-pixel
                        for (p in stroke) sb.addPoint(Ink.Point.create(p.x / 25f, p.y / 25f))
                        ink.addStroke(sb.build())
                    }
                    rec.recognize(ink.build())
                        .addOnSuccessListener { r -> out.add(r.candidates.firstOrNull()?.text ?: ""); next(i + 1) }
                        .addOnFailureListener { finish(Result.failure(it)) }
                }
                next(0)
            }
    }
}

/** Drawn lines, rectangles, circles and triangles, with ML Kit's shape model (offline after one download). */
object MlKitShapes {
    /** Returns stroke index -> clean shape for every stroke that is a recognised shape. */
    fun recognize(strokes: List<List<Pt>>, done: (Result<Map<Int, List<Pt>>>) -> Unit) {
        val model = try {
            val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("zxx-Zsym-x-shapes")
                ?: return done(Result.failure(Exception("ML Kit heeft geen vormenmodel")))
            DigitalInkRecognitionModel.builder(id).build()
        } catch (e: Exception) {
            return done(Result.failure(e))
        }
        val todo = strokes.indices.filter { Shapes.isCandidate(strokes[it]) }
        if (todo.isEmpty()) return done(Result.success(emptyMap()))
        RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build())
            .addOnFailureListener { done(Result.failure(Exception("Vormenmodel downloaden mislukt (internet nodig, eenmalig): ${it.message}"))) }
            .addOnSuccessListener {
                val rec = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
                val out = HashMap<Int, List<Pt>>()
                fun finish(r: Result<Map<Int, List<Pt>>>) { rec.close(); done(r) }
                fun next(k: Int) {
                    if (k >= todo.size) return finish(Result.success(out))
                    val i = todo[k]
                    val sb = Ink.Stroke.builder()
                    for (p in strokes[i]) sb.addPoint(Ink.Point.create(p.x / 25f, p.y / 25f))
                    rec.recognize(Ink.builder().addStroke(sb.build()).build())
                        .addOnSuccessListener { r ->
                            val label = r.candidates.firstOrNull()?.text ?: ""
                            AppLog.add("shape stroke $i: $label")
                            Shapes.clean(label, strokes[i])?.let { out[i] = it }
                            next(k + 1)
                        }
                        .addOnFailureListener { finish(Result.failure(it)) }
                }
                next(0)
            }
    }
}

/** Handwriting to text with Claude's vision. Blocking: call from a worker thread. */
object ClaudeOcr {
    private const val PROMPT =
        "Dit is een handgeschreven notitie (zwarte inkt op wit, afkomstig van een digitale notitieblok). " +
        "Schrijf de tekst exact over, in de taal waarin hij geschreven is. Behoud regeleinden, kopjes en " +
        "opsommingen (gebruik Markdown voor lijstjes en kopjes). Corrigeer niets en verzin niets; wat je " +
        "echt niet kunt lezen schrijf je als [onleesbaar]. Getekende selectievakjes schrijf je als '- [ ] ' (leeg) of '- [x] ' (afgevinkt) voor de regel. Een getekende tabel zet je om naar een Markdown-tabel. Eventuele tekeningen beschrijf je kort tussen " +
        "[haakjes]. Geef alleen de transcriptie, zonder inleiding of uitleg."

    fun recognize(png: ByteArray, apiKey: String, model: String): String {
        if (apiKey.isBlank()) throw Exception("Geen Claude API-key ingesteld (Instellingen)")
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray()
                    .put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64"); put("media_type", "image/png")
                            put("data", Base64.encodeToString(png, Base64.NO_WRAP))
                        })
                    })
                    .put(JSONObject().apply { put("type", "text"); put("text", PROMPT) }))
            }))
        }
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (_: Exception) { text.take(200) }
                throw Exception("Claude API fout $code: $msg")
            }
            val content = JSONObject(text).getJSONArray("content")
            return buildString {
                for (i in 0 until content.length()) {
                    val part = content.getJSONObject(i)
                    if (part.optString("type") == "text") append(part.getString("text"))
                }
            }.trim()
        } finally {
            conn.disconnect()
        }
    }
}

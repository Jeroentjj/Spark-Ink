package app.sparkink

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.sparkink.core.NotionBlocks
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var store: NoteStore
    private lateinit var pad: Pad

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()

    private var status: TextView? = null
    private var statusText = ""
    private var current: Note? = null
    private var editor: EditText? = null
    private var pendingAfterPermission: (() -> Unit)? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = NoteStore(this)
        pad = Pad(this, prefs, store)
        showList()
    }

    override fun onPause() {
        saveEditor()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current != null) {
            saveEditor()
            current = null
            showList()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---- small view helpers ------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(8))
    }

    private fun setStatus(s: String) {
        statusText = s
        status?.text = s
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---- list screen -----------------------------------------------------------------

    private fun showList() {
        current = null
        editor = null
        val root = page()
        root.addView(TextView(this).apply { text = "Spark Ink"; textSize = 26f; setTypeface(typeface, Typeface.BOLD) })

        val padLine = if (pad.isPaired) "Gekoppeld: ${prefs.padName ?: "Folio"}" else "Nog geen Folio gekoppeld"
        root.addView(TextView(this).apply { text = "$padLine  ·  versie ${BuildConfig.GIT_SHA}"; setPadding(0, dp(4), 0, dp(8)) })

        root.addView(row(
            button("Folio koppelen") { startPairing() },
            button("Synchroniseer") { startSync() },
        ))
        root.addView(row(button("Folio wissen + resetten") {
            withBlePermissions {
                setStatus("Houd de knop van de Folio ingedrukt tot hij in de lijst verschijnt en kies hem. WAARSCHUWING: alle pagina's op de Folio worden gewist.")
                pickDevice(::finishWipe)
            }
        }))
        val statusView = TextView(this).apply { text = statusText; setPadding(0, dp(8), 0, dp(8)) }
        status = statusView
        root.addView(statusView)

        val notes = store.all()
        val list = ListView(this)
        list.adapter = object : ArrayAdapter<Note>(this, 0, notes) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val n = getItem(position)!!
                val v = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
                v.addView(TextView(context).apply {
                    text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(n.timestamp * 1000))
                    setTypeface(typeface, Typeface.BOLD)
                })
                v.addView(TextView(context).apply {
                    text = n.text.lineSequence().firstOrNull { it.isNotBlank() } ?: "(nog geen tekst, ${n.strokes.size} streken)"
                    maxLines = 2
                })
                return v
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> showNote(notes[pos]) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(row(
            button("Instellingen") { showSettings() },
            button("Update") { checkForUpdate() },
            button("Log") { showLog() },
        ))
        setContentView(root)
    }

    // ---- note screen -----------------------------------------------------------------

    private fun showNote(n: Note) {
        current = n
        val root = page()
        val scroll = ScrollView(this)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(inner)

        val image = ImageView(this).apply { adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt()) }
        inner.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fun redraw() {
            bg.execute {
                val bmp = Render.bitmap(n.sketch(), 1400, cropToInk = false)
                main.post { image.setImageBitmap(bmp) }
            }
        }
        redraw()

        val src = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        fun srcLabel() {
            src.text = when (n.source) {
                "mlkit" -> "Tekst: ML Kit (op het toestel)"
                "claude" -> "Tekst: Claude"
                "hand" -> "Tekst: handmatig bewerkt"
                else -> "Nog geen tekst: kies een herkenning hieronder"
            }
        }
        srcLabel()
        inner.addView(src)

        inner.addView(row(
            button("Draai ⟳") {
                n.rotation = (n.rotation + 90) % 360
                prefs.defaultRotation = n.rotation
                store.save(n)
                redraw()
            },
            button("ML Kit") { runOcr(n, "mlkit") { editor?.setText(n.text); srcLabel() } },
            button("Claude") { runOcr(n, "claude") { editor?.setText(n.text); srcLabel() } },
        ))

        editor = EditText(this).apply {
            setText(n.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = android.view.Gravity.TOP
        }
        inner.addView(editor)

        val statusView = TextView(this).apply { text = statusText }
        status = statusView
        root.addView(statusView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(row(
            button("‹ Terug") { onBackPressed() },
            button("Kopieer") {
                saveEditor()
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("notitie", n.text))
                toast("Gekopieerd")
            },
            button("Deel") {
                saveEditor()
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, n.text)
                }, null))
            },
            button("Notion") { onNotionClicked(n) },
            button("Wis") {
                AlertDialog.Builder(this).setMessage("Deze notitie definitief verwijderen?")
                    .setPositiveButton("Verwijderen") { _, _ -> store.delete(n.id); current = null; showList() }
                    .setNegativeButton("Annuleren", null).show()
            },
        ))
        setContentView(root)
    }

    private fun saveEditor() {
        val n = current ?: return
        val e = editor ?: return
        val t = e.text.toString()
        if (t != n.text) {
            n.text = t
            n.source = "hand"
            store.save(n)
        }
    }

    // ---- OCR -------------------------------------------------------------------------

    private fun runOcr(n: Note, method: String, onFail: () -> Unit = {}, done: () -> Unit) {
        if (busy) return toast("Even geduld, er loopt nog iets")
        busy = true
        setStatus(if (method == "claude") "Claude leest het handschrift…" else "ML Kit leest het handschrift…")
        fun finish(r: Result<String>) {
            busy = false
            r.onSuccess {
                n.text = it; n.source = method; store.save(n)
                setStatus("Klaar")
                done()
            }.onFailure {
                AppLog.add("ocr $method failed: $it")
                setStatus("Herkenning mislukt: ${it.message}")
                onFail()
            }
        }
        if (method == "mlkit") {
            MlKitOcr.recognize(n.sketch(), prefs.language) { finish(it) }
        } else {
            bg.execute {
                val r = try {
                    val png = Render.png(Render.bitmap(n.sketch(), 1568, cropToInk = true))
                    Result.success(ClaudeOcr.recognize(png, prefs.apiKey, prefs.model))
                } catch (e: Exception) {
                    Result.failure(e)
                }
                main.post { finish(r) }
            }
        }
    }

    /** After a sync, recognise every new note with the method chosen in the settings. */
    private fun autoOcr(notes: List<Note>, index: Int = 0) {
        val method = prefs.autoOcr
        if (method == "off" || index >= notes.size) { showList(); return }
        val n = notes[index]
        val next = {
            if (prefs.notionAuto && prefs.notionReady && n.text.isNotBlank()) exportToNotion(n) { autoOcr(notes, index + 1) }
            else autoOcr(notes, index + 1)
        }
        // Claude needs internet and a key; if it fails fall back to the on-device recogniser, and if that
        // fails too the note simply stays readable as ink.
        runOcr(n, method, onFail = {
            if (method == "claude") runOcr(n, "mlkit", onFail = { showList() }, done = next) else showList()
        }, done = next)
    }

    // ---- Bluetooth actions ---------------------------------------------------------

    private fun withBlePermissions(then: () -> Unit) {
        val needed = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            if (!pad.bluetoothOn) return toast("Zet Bluetooth aan")
            then()
        } else {
            pendingAfterPermission = then
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val then = pendingAfterPermission
        pendingAfterPermission = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (pad.bluetoothOn) then?.invoke() else toast("Zet Bluetooth aan")
        } else toast("Zonder Bluetooth-toestemming kan de app de Folio niet vinden")
    }

    private fun startPairing() = withBlePermissions {
        setStatus("Houd de knop op de Folio minstens 6 seconden ingedrukt tot het lampje blauw knippert (afwisselend blauw-oranje betekent: geheugen vol) en kies de Folio meteen in de lijst: de koppelmodus duurt maar kort.")
        pickDevice()
    }

    private fun pickDevice(onPick: (BluetoothDevice) -> Unit = ::finishPairing) {
        val found = ArrayList<BluetoothDevice>()
        val names = ArrayList<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Kies je Folio")
            .setAdapter(adapter) { _, which ->
                pad.stopScan()
                onPick(found[which])
            }
            .setNegativeButton("Annuleren") { _, _ -> pad.stopScan() }
            .setOnCancelListener { pad.stopScan() }
            .create()
        dialog.show()
        pad.startScan { dev, rssi, connectable ->
            if (found.none { it.address == dev.address }) {
                found.add(dev)
                names.add("${dev.name}  (${dev.address}, $rssi dBm)" + if (connectable) "" else "  - niet verbindbaar")
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun finishPairing(dev: BluetoothDevice) {
        if (busy) return
        busy = true
        pad.pair(dev, { setStatus(it) }, { setStatus("Druk nu kort op de knop van de Folio om het koppelen te bevestigen.") }) { r ->
            busy = false
            r.onSuccess { setStatus(it); showList() }.onFailure { setStatus("Koppelen mislukt: ${it.message}") }
        }
    }

    private fun finishWipe(dev: BluetoothDevice) {
        if (busy) return
        busy = true
        pad.wipe(dev, { setStatus(it) }, { setStatus("Druk nu kort op de knop van de Folio. Daarna wist en reset de app hem automatisch.") }) { r ->
            busy = false
            r.onSuccess { setStatus(it); showList() }.onFailure { setStatus("Wissen mislukt: ${it.message}") }
        }
    }

    private fun startSync() {
        if (!pad.isPaired) return toast("Koppel eerst je Folio")
        if (busy) return
        withBlePermissions {
            busy = true
            val fresh = ArrayList<Note>()
            pad.sync({ setStatus(it) }, { fresh.add(it) }) { r ->
                busy = false
                r.onSuccess { n ->
                    setStatus(if (n == 0) "Geen nieuwe pagina's op de Folio" else "$n pagina('s) opgehaald")
                    if (n > 0) autoOcr(fresh.reversed()) else showList()
                }.onFailure { setStatus("Synchroniseren mislukt: ${it.message}") }
            }
        }
    }

    // ---- Notion --------------------------------------------------------------------

    private fun onNotionClicked(n: Note) {
        saveEditor()
        if (!prefs.notionReady) return toast("Vul eerst je Notion-token en database/pagina in bij Instellingen")
        if (n.text.isBlank()) return toast("Er is nog geen tekst om te exporteren")
        if (n.notionUrl.isNotBlank()) {
            AlertDialog.Builder(this).setMessage("Deze notitie staat al in Notion.")
                .setPositiveButton("Openen") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(n.notionUrl))) }
                .setNeutralButton("Opnieuw exporteren") { _, _ -> exportToNotion(n) {} }
                .setNegativeButton("Annuleren", null).show()
        } else exportToNotion(n) {}
    }

    private fun exportToNotion(n: Note, done: () -> Unit) {
        val id = NotionBlocks.extractId(prefs.notionTarget) ?: return toast("Notion-database/pagina niet herkend")
        setStatus("Naar Notion sturen…")
        val fallbackTitle = "Notitie " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(n.timestamp * 1000))
        val text = n.text
        bg.execute {
            val r = try {
                Result.success(NotionClient.createPage(prefs.notionToken, id, NotionBlocks.title(text, fallbackTitle), NotionBlocks.fromMarkdown(text)))
            } catch (e: Exception) {
                Result.failure<String>(e)
            }
            main.post {
                r.onSuccess { url -> n.notionUrl = url; store.save(n); setStatus("In Notion gezet") }
                    .onFailure { AppLog.add("notion failed: $it"); setStatus("Notion mislukt: ${it.message}") }
                done()
            }
        }
    }

    // ---- app update ------------------------------------------------------------------

    private fun checkForUpdate() {
        if (busy) return toast("Even geduld, er loopt nog iets")
        busy = true
        setStatus("Zoeken naar een nieuwe versie…")
        val token = prefs.githubToken
        bg.execute {
            val r = try { Result.success(Updater.latest(token)) } catch (e: Exception) { Result.failure<Updater.Release>(e) }
            main.post {
                busy = false
                r.onSuccess { rel ->
                    if (!Updater.isNewer(rel)) {
                        setStatus("Je hebt de nieuwste versie (${BuildConfig.GIT_SHA})")
                    } else {
                        setStatus("Nieuwe versie beschikbaar")
                        AlertDialog.Builder(this)
                            .setTitle("Update beschikbaar")
                            .setMessage("Nieuwe versie ${rel.sha} (${rel.size / 1_000_000} MB). Jouw versie: ${BuildConfig.GIT_SHA}.\n\nDownloaden en installeren? Je notities en instellingen blijven behouden.")
                            .setPositiveButton("Installeren") { _, _ -> downloadAndInstall(rel, token) }
                            .setNegativeButton("Later", null).show()
                    }
                }.onFailure { AppLog.add("update check failed: $it"); setStatus("Update zoeken mislukt: ${it.message}") }
            }
        }
    }

    private fun downloadAndInstall(rel: Updater.Release, token: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Sta eerst toe dat Spark Ink apps mag installeren, en tik daarna opnieuw op Update")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        busy = true
        val file = File(cacheDir, "updates/SparkInk.apk")
        var lastShown = 0L
        bg.execute {
            val r = try {
                Updater.download(token, rel, file) { done ->
                    val now = System.currentTimeMillis()
                    if (now - lastShown > 500) { lastShown = now; main.post { setStatus("Downloaden… ${done / 1_000_000} van ${rel.size / 1_000_000} MB") } }
                }
                Result.success(Unit)
            } catch (e: Exception) { Result.failure<Unit>(e) }
            main.post {
                busy = false
                r.onSuccess {
                    setStatus("Gedownload, installatie starten…")
                    val uri = FileProvider.getUriForFile(this, "app.sparkink.files", file)
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.onFailure { AppLog.add("update download failed: $it"); setStatus("Update mislukt: ${it.message}") }
            }
        }
    }

    // ---- settings & log ----------------------------------------------------------------

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun field(label: String, value: String, secret: Boolean = false): EditText {
            box.addView(TextView(this).apply { text = label; setPadding(0, dp(12), 0, 0) })
            return EditText(this).apply {
                setText(value); setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
                box.addView(this)
            }
        }
        box.addView(TextView(this).apply { text = "Automatisch omzetten naar tekst na synchroniseren" })
        val group = RadioGroup(this)
        val opts = listOf("off" to "Uit", "mlkit" to "ML Kit (op het toestel, gratis)", "claude" to "Claude (beter, API-key nodig)")
        opts.forEachIndexed { i, (key, label) ->
            group.addView(RadioButton(this).apply { id = 100 + i; text = label; isChecked = prefs.autoOcr == key })
        }
        box.addView(group)
        val lang = field("Taal voor ML Kit (bijv. nl, en, de)", prefs.language)
        val key = field("Claude API-key (blijft alleen op dit toestel)", prefs.apiKey, secret = true)
        val model = field("Claude-model", prefs.model)
        box.addView(TextView(this).apply { text = "App-updates (nodig omdat de repo privé is: maak op github.com/settings/personal-access-tokens een token met alleen lezen-rechten (Contents: Read) voor Jeroentjj/Spark-Ink)"; setPadding(0, dp(20), 0, 0) })
        val ghToken = field("GitHub-token (alleen voor updates)", prefs.githubToken, secret = true)
        box.addView(TextView(this).apply { text = "Notion (maak een integratie op notion.so/my-integrations en deel je database of pagina ermee via ••• > Verbindingen)"; setPadding(0, dp(20), 0, 0) })
        val nToken = field("Notion-token (secret_… of ntn_…)", prefs.notionToken, secret = true)
        val nTarget = field("Notion database of pagina (link of ID)", prefs.notionTarget)
        val nAuto = CheckBox(this).apply { text = "Na tekstherkenning automatisch naar Notion"; isChecked = prefs.notionAuto }
        box.addView(nAuto)
        AlertDialog.Builder(this).setTitle("Instellingen").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Opslaan") { _, _ ->
                prefs.autoOcr = opts[(group.checkedRadioButtonId - 100).coerceIn(0, 2)].first
                prefs.language = lang.text.toString().ifBlank { "nl" }
                prefs.apiKey = key.text.toString()
                prefs.model = model.text.toString().ifBlank { "claude-sonnet-5-5" }
                prefs.githubToken = ghToken.text.toString()
                prefs.notionToken = nToken.text.toString()
                prefs.notionTarget = nTarget.text.toString()
                prefs.notionAuto = nAuto.isChecked
            }
            .setNegativeButton("Annuleren", null).show()
    }

    private fun showLog() {
        val tv = TextView(this).apply { text = AppLog.text().ifBlank { "(leeg)" }; textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("Log").setView(ScrollView(this).apply { addView(tv); setPadding(dp(12), 0, dp(12), 0) })
            .setPositiveButton("Delen") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, AppLog.text())
                }, null))
            }
            .setNegativeButton("Sluiten", null).show()
    }
}

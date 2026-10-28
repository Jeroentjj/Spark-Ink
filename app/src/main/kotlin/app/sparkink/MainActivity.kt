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
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.transition.TransitionManager
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import app.sparkink.core.NotionBlocks
import app.sparkink.core.Pt
import app.sparkink.core.toSvg
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import java.text.SimpleDateFormat
import java.util.Locale
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var store: NoteStore
    private lateinit var books: NotebookStore
    private lateinit var pad: Pad

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val thumbBg = Executors.newSingleThreadExecutor()
    private val thumbs = LruCache<String, Bitmap>(48)

    // Navigation: a page ([current]) sits inside a notebook ([openBook], "" = loose pages, null = home).
    private var current: Note? = null
    private var openBook: String? = null
    private var inSettings = false
    private var showText = false
    private var sheetOpen = false
    private var saveSettings: (() -> Unit)? = null
    private var inLive = false
    private var query = ""
    private var pageInk: InkView? = null

    private var status: TextView? = null
    private var statusText = ""
    private var editor: EditText? = null
    private var pendingAfterPermission: (() -> Unit)? = null
    private var pendingDrive: ((String) -> Unit)? = null
    private var busy = false

    private class Action(val label: String, val icon: Int, val run: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = NoteStore(this)
        books = NotebookStore(this)
        pad = Pad(this, prefs, store)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        showHome()
    }

    override fun onPause() {
        saveEditor()
        saveSettings?.invoke()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val book = openBook
        when {
            inLive -> { pad.stopLive(); inLive = false; if (book != null) showBook(book) else showHome() }
            current != null -> { saveEditor(); current = null; showBook(book ?: "") }
            inSettings -> { saveSettings?.invoke(); saveSettings = null; showHome() }
            book != null -> showHome()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    /** Redraw whatever screen is open, e.g. after a sync or pairing finished. */
    private fun refresh() {
        val n = current
        val book = openBook
        when {
            inLive -> {}
            n != null -> showNote(n)
            inSettings -> { saveSettings?.invoke(); showSettings() }
            book != null -> showBook(book)
            else -> showHome()
        }
    }

    // ---- small view helpers ------------------------------------------------------------

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null, dashed: Boolean = false) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) {
            if (dashed) setStroke(dp(2), stroke, dp(6).toFloat(), dp(4).toFloat()) else setStroke(dp(1), stroke)
        }
    }

    private fun label(s: String, size: Float = 15f, color: Int = Ui.INK, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun icon(res: Int, color: Int = Ui.INK, size: Int = 24) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun ripple(v: View) {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        v.foreground = getDrawable(tv.resourceId)
        v.clipToOutline = true
    }

    private fun rowOf(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
    }

    /** A rounded, tinted button with an icon, for use inside screens (settings). */
    private fun pill(text: String, iconRes: Int, color: Int = Ui.ACCENT, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = rounded(if (color == Ui.ACCENT) Ui.ACCENT_SOFT else Ui.DANGER_SOFT, 22)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        addView(icon(iconRes, color, 18))
        addView(label(text, 14f, color, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
        ripple(this)
        setOnClickListener { onClick() }
    }

    private fun grid(cells: List<View>, cols: Int = 2) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        cells.chunked(cols).forEach { chunk ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (i in 0 until cols) {
                    addView(chunk.getOrNull(i) ?: View(context),
                        LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
                }
            })
        }
    }

    private fun header(title: View, back: Boolean, trailing: View? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(8))
        if (back) {
            addView(icon(R.drawable.ic_arrow_back).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(Color.TRANSPARENT, 20)
                ripple(this)
                setOnClickListener { onBackPressed() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(4) })
        }
        addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (trailing != null) addView(trailing)
    }

    /**
     * Every screen: header, status line, body, and the action sheet hidden behind a small tab
     * at the bottom.
     */
    private fun scaffold(header: View, body: View, actions: List<Action>) {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), if (actions.isEmpty()) dp(16) else dp(40))
        }
        col.addView(header)
        val st = label(statusText, 13f, Ui.MUTED).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
            visibility = if (statusText.isBlank()) View.GONE else View.VISIBLE
        }
        status = st
        col.addView(st)
        col.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        if (actions.isNotEmpty()) root.addView(sheet(root, actions), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun sheet(root: ViewGroup, actions: List<Action>): View {
        val r = dp(22).toFloat()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Ui.SURFACE); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            elevation = dp(12).toFloat()
            setPadding(dp(8), dp(10), dp(8), dp(14))
            visibility = if (sheetOpen) View.VISIBLE else View.GONE
            actions.chunked(4).forEach { chunk ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (i in 0 until 4) {
                        val a = chunk.getOrNull(i)
                        addView(if (a != null) actionButton(a) else View(context), LinearLayout.LayoutParams(0, WRAP, 1f))
                    }
                })
            }
        }
        val chevron = icon(if (sheetOpen) R.drawable.ic_expand_more else R.drawable.ic_expand_less, Ui.MUTED, 22)
        val tab = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Ui.SURFACE); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            elevation = dp(12).toFloat()
            contentDescription = "Knoppen tonen"
            addView(chevron, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener {
                sheetOpen = !sheetOpen
                TransitionManager.beginDelayedTransition(root)
                panel.visibility = if (sheetOpen) View.VISIBLE else View.GONE
                chevron.setImageResource(if (sheetOpen) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(tab, LinearLayout.LayoutParams(dp(72), dp(28)))
            addView(panel, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun actionButton(a: Action) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(8), 0, dp(8))
        val danger = a.icon == R.drawable.ic_delete
        addView(FrameLayout(context).apply {
            background = rounded(if (danger) Ui.DANGER_SOFT else Ui.ACCENT_SOFT, 24)
            addView(icon(a.icon, if (danger) Ui.DANGER else Ui.ACCENT, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label(a.label, 12f, Ui.INK).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        ripple(this)
        setOnClickListener { sheetOpen = false; a.run() }
    }

    private fun setStatus(s: String) {
        statusText = s
        status?.text = s
        status?.visibility = if (s.isBlank()) View.GONE else View.VISIBLE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun pages(n: Int) = if (n == 1) "1 pagina" else "$n pagina's"

    private fun dateOf(n: Note) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(n.timestamp * 1000))

    private fun loadThumb(n: Note, into: ImageView) {
        val key = "${n.id}_${n.rotation}_${n.strokes.size}"
        thumbs.get(key)?.let { into.setImageBitmap(it); return }
        into.tag = key
        thumbBg.execute {
            val b = Render.bitmap(n.sketch(), 480, cropToInk = false, ink = prefs.inkColor, widthMm = prefs.inkWidth)
            main.post { thumbs.put(key, b); if (into.tag == key) into.setImageBitmap(b) }
        }
    }

    // ---- home: the notebooks -------------------------------------------------------------

    private fun showHome() {
        saveEditor()
        current = null; editor = null; openBook = null; inSettings = false; saveSettings = null
        val notes = store.all()
        val battery = if (prefs.padBattery >= 0) " · batterij ${prefs.padBattery}%" else ""
        val padLine = if (pad.isPaired) "Gekoppeld met ${prefs.padName ?: "Folio"}$battery" else "Nog geen Folio gekoppeld (zie Instellingen)"
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Spark Ink", 28f, Ui.INK, bold = true))
            addView(label(padLine, 13f, Ui.MUTED))
        }
        val content = FrameLayout(this)
        fun fill() {
            content.removeAllViews()
            val q = query.trim()
            content.addView(if (q.isEmpty()) {
                val cells = ArrayList<View>()
                cells.add(looseCard(notes.filter { it.notebookId.isEmpty() }))
                books.all().forEach { b -> cells.add(bookCard(b, notes.count { it.notebookId == b.id })) }
                cells.add(newBookCard())
                grid(cells)
            } else {
                val hits = notes.filter { it.text.contains(q, ignoreCase = true) || it.tags.contains(q, ignoreCase = true) }
                if (hits.isEmpty()) label("Niets gevonden voor “$q”", 15f, Ui.MUTED).apply { setPadding(dp(8), dp(24), dp(8), 0) }
                else grid(hits.map { pageCard(it) })
            })
        }
        val search = EditText(this).apply {
            setText(query); setSingleLine(); textSize = 15f
            hint = "Zoek in tekst en tags"
            setTextColor(Ui.INK); setHintTextColor(Ui.MUTED)
            background = rounded(Ui.SURFACE, 22)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString(); fill() }
            })
        }
        fill()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(6), 0, dp(6), dp(6)) })
            addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        scaffold(header(title, back = false), body, listOf(
            Action("Synchroniseer", R.drawable.ic_sync) { startSync("") },
            Action("Live", R.drawable.ic_edit) { showLive() },
            Action("Notitieblok", R.drawable.ic_add) { newBook() },
            Action("Instellingen", R.drawable.ic_settings) { showSettings() },
        ))
    }

    private fun bookCard(b: Notebook, count: Int) = FrameLayout(this).apply {
        minimumHeight = dp(176)
        background = rounded(b.color, 14)
        elevation = dp(3).toFloat()
        addView(View(context).apply { setBackgroundColor(0x33000000) }, FrameLayout.LayoutParams(dp(10), MATCH, Gravity.START))
        addView(LinearLayout(context).apply {
            background = rounded(0xF2FFFFFF.toInt(), 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(label(b.title.ifBlank { "Naamloos" }, 16f, Ui.PAPER_INK, bold = true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
        }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { setMargins(dp(22), dp(18), dp(12), 0) })
        addView(label(pages(count), 12f, 0xE6FFFFFF.toInt()),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(22), 0, 0, dp(12)) })
        ripple(this)
        setOnClickListener { showBook(b.id) }
        setOnLongClickListener { askName("Notitieblok hernoemen", b.title) { b.title = it; books.save(); showHome() }; true }
    }

    private fun looseCard(loose: List<Note>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = dp(176)
        background = rounded(Ui.SURFACE, 14, Ui.LINE)
        elevation = dp(1).toFloat()
        setPadding(dp(12), dp(12), dp(12), dp(12))
        addView(label("Losse pagina's", 16f, Ui.INK, bold = true))
        addView(label(pages(loose.size), 12f, Ui.MUTED))
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_START }
        addView(img, LinearLayout.LayoutParams(MATCH, dp(110)).apply { topMargin = dp(8) })
        loose.firstOrNull()?.let { loadThumb(it, img) }
        ripple(this)
        setOnClickListener { showBook("") }
    }

    private fun newBookCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = dp(176)
        background = rounded(Color.TRANSPARENT, 14, Ui.LINE, dashed = true)
        addView(icon(R.drawable.ic_add, Ui.MUTED, 32))
        addView(label("Nieuw notitieblok", 14f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
        ripple(this)
        setOnClickListener { newBook() }
    }

    private fun askName(title: String, initial: String, hintText: String = "Bijv. Werk, Ideeën, Project X", onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial); setSingleLine(); hint = hintText
            setSelection(initial.length)
        }
        AlertDialog.Builder(this).setTitle(title)
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("Opslaan") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Annuleren", null).show()
    }

    private fun newBook() = askName("Nieuw notitieblok", "") { showBook(books.create(it).id) }

    // ---- one notebook (or the loose pages) -------------------------------------------------

    private fun showBook(id: String) {
        saveEditor()
        current = null; editor = null; openBook = id; inSettings = false; saveSettings = null
        val book = books.get(id)
        val notes = store.all().filter { it.notebookId == (book?.id ?: "") }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (book == null) addView(label("Losse pagina's", 24f, Ui.INK, bold = true))
            else addView(EditText(context).apply {
                setText(book.title)
                hint = "Naam van dit notitieblok"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.INK)
                background = null
                setPadding(0, 0, 0, 0)
                setSingleLine()
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { book.title = s.toString(); books.save() }
                })
            })
            addView(label(pages(notes.size), 13f, Ui.MUTED))
        }
        val body: View = if (notes.isEmpty()) {
            label(if (book == null) "Geen losse pagina's. Nieuwe pagina's van de Folio komen hier terecht."
                  else "Nog geen pagina's. Synchroniseer vanuit dit notitieblok, of verplaats pagina's hierheen.", 15f, Ui.MUTED)
                .apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), 0) }
        } else ScrollView(this).apply { addView(grid(notes.map { pageCard(it) })) }

        val actions = arrayListOf(
            Action("Synchroniseer", R.drawable.ic_sync) { startSync(book?.id ?: "") },
            Action("PDF", R.drawable.ic_download) { exportBookPdf(book?.title?.ifBlank { null } ?: "Losse pagina's", notes) },
        )
        if (book != null) {
            actions.add(Action("Drive", R.drawable.ic_cloud) { uploadToDrive(notes) })
            actions.add(Action("Kleur", R.drawable.ic_palette) {
                book.color = Ui.COVERS[(Ui.COVERS.indexOf(book.color) + 1) % Ui.COVERS.size]
                books.save()
                toast("Kaftkleur gewijzigd")
            })
            actions.add(Action("Verwijder", R.drawable.ic_delete) {
                AlertDialog.Builder(this).setMessage("Notitieblok verwijderen? De pagina's blijven bewaard bij de losse pagina's.")
                    .setPositiveButton("Verwijderen") { _, _ ->
                        notes.forEach { it.notebookId = ""; store.save(it) }
                        books.delete(book.id)
                        showHome()
                    }
                    .setNegativeButton("Annuleren", null).show()
            })
        }
        scaffold(header(titleCol, back = true), body, actions)
    }

    private fun pageCard(n: Note) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Ui.SURFACE, 12)
        elevation = dp(2).toFloat()
        setPadding(dp(8), dp(8), dp(8), dp(10))
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        addView(img, LinearLayout.LayoutParams(MATCH, dp(210)))
        loadThumb(n, img)
        addView(label(dateOf(n), 12f, Ui.MUTED).apply { setPadding(dp(2), dp(6), 0, 0) })
        n.text.lineSequence().firstOrNull { it.isNotBlank() }?.let {
            addView(label(it, 13f, Ui.INK).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), 0, 0, 0) })
        }
        if (n.tags.isNotBlank()) addView(label(tagLine(n), 12f, Ui.ACCENT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), dp(2), 0, 0) })
        ripple(this)
        setOnClickListener { showText = false; openBook = n.notebookId; showNote(n) }
        setOnLongClickListener { moveDialog(n) { refresh() }; true }
    }

    private fun moveDialog(n: Note, done: () -> Unit) {
        val list = books.all()
        val labels = listOf("Losse pagina's") + list.map { it.title.ifBlank { "Naamloos" } } + "+ Nieuw notitieblok…"
        AlertDialog.Builder(this).setTitle("Verplaats naar").setItems(labels.toTypedArray()) { _, i ->
            when {
                i == 0 -> { n.notebookId = ""; store.save(n); done() }
                i <= list.size -> { n.notebookId = list[i - 1].id; store.save(n); done() }
                else -> askName("Nieuw notitieblok", "") { n.notebookId = books.create(it).id; store.save(n); done() }
            }
        }.show()
    }

    // ---- one page ------------------------------------------------------------------------

    private fun segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(Ui.ACCENT_SOFT, 18)
        setPadding(dp(3), dp(3), dp(3), dp(3))
        labels.forEachIndexed { i, l ->
            addView(label(l, 13f, if (i == selected) Ui.BG else Ui.ACCENT, bold = true).apply {
                setPadding(dp(14), dp(6), dp(14), dp(6))
                if (i == selected) background = rounded(Ui.ACCENT, 15)
                setOnClickListener { if (i != selected) onPick(i) }
            })
        }
    }

    private fun showNote(n: Note) {
        current = n; inSettings = false
        val sourceLine = when (n.source) {
            "mlkit" -> "Tekst door ML Kit"
            "claude" -> "Tekst door Claude"
            "hand" -> "Tekst handmatig bewerkt"
            else -> "Nog geen tekst"
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(dateOf(n), 17f, Ui.INK, bold = true))
            addView(label(sourceLine, 12f, Ui.MUTED))
            if (n.tags.isNotBlank()) addView(label(tagLine(n), 12f, Ui.ACCENT))
        }
        val toggle = segmented(listOf("Pagina", "Tekst"), if (showText) 1 else 0) { i -> saveEditor(); showText = i == 1; showNote(n) }

        val body: View
        if (!showText) {
            editor = null
            val ink = InkView(this).apply { sketch = n.sketch(); this.ink = prefs.inkColor; widthMm = prefs.inkWidth }
            pageInk = ink
            body = FrameLayout(this).apply {
                background = rounded(Ui.SURFACE, 12)
                elevation = dp(2).toFloat()
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(ink, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        } else {
            val e = EditText(this).apply {
                setText(n.text)
                hint = "Nog geen tekst. Kies ML Kit of Claude in de knoppen onderin, of typ zelf."
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                gravity = Gravity.TOP
                background = null
                textSize = 17f
                setTextColor(Ui.INK)
                setLineSpacing(0f, 1.2f)
            }
            editor = e
            body = ScrollView(this).apply {
                background = rounded(Ui.SURFACE, 12)
                elevation = dp(2).toFloat()
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isFillViewport = true
                addView(e)
            }
        }

        val afterOcr = { if (current?.id == n.id) { showText = true; showNote(n) } }
        scaffold(header(titleCol, back = true, trailing = toggle), body, listOf(
            Action("Draai", R.drawable.ic_rotate) {
                saveEditor()
                n.rotation = (n.rotation + 180) % 360 // stays portrait
                prefs.defaultRotation = n.rotation
                store.save(n)
                showNote(n)
            },
            Action("ML Kit", R.drawable.ic_scan) { saveEditor(); runOcr(n, "mlkit") { afterOcr() } },
            Action("Claude", R.drawable.ic_auto) { saveEditor(); runOcr(n, "claude") { afterOcr() } },
            Action("Verplaats", R.drawable.ic_book) { saveEditor(); moveDialog(n) { toast("Verplaatst"); openBook = n.notebookId; showNote(n) } },
            Action("Kopieer", R.drawable.ic_copy) {
                saveEditor()
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("notitie", n.text))
                toast("Tekst gekopieerd")
            },
            Action("Deel", R.drawable.ic_share) {
                saveEditor()
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, n.text)
                }, null))
            },
            Action("Notion", R.drawable.ic_open_in_new) { onNotionClicked(n) },
            Action("Drive", R.drawable.ic_cloud) { saveEditor(); uploadToDrive(listOf(n)) },
            Action("Afspelen", R.drawable.ic_play) { replay(n) },
            Action("Exporteer", R.drawable.ic_download) { saveEditor(); exportDialog(n) },
            Action("Tags", R.drawable.ic_label) {
                saveEditor()
                askName("Tags", n.tags, "Bijv. werk, idee, boodschappen") { n.tags = it; store.save(n); showNote(n) }
            },
            Action("Voeg samen", R.drawable.ic_merge) { saveEditor(); mergeDialog(n) },
            Action("Wis", R.drawable.ic_delete) {
                AlertDialog.Builder(this).setMessage("Deze pagina definitief verwijderen?")
                    .setPositiveButton("Verwijderen") { _, _ -> store.delete(n.id); current = null; editor = null; showBook(n.notebookId) }
                    .setNegativeButton("Annuleren", null).show()
            },
        ))
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

    private fun tagLine(n: Note) = n.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("  ") { "#$it" }

    /** Redraw the page stroke by stroke, at a steady pace. */
    private fun replay(n: Note) {
        if (showText) { saveEditor(); showText = false; showNote(n) }
        val v = pageInk ?: return
        val total = n.strokes.sumOf { it.size }
        ValueAnimator.ofInt(0, total).apply {
            duration = (total * 4L).coerceIn(2_000, 20_000)
            interpolator = LinearInterpolator()
            addUpdateListener { v.limit = it.animatedValue as Int }
            start()
        }
    }

    /** Append another page of the same notebook to this one; the other page is removed. */
    private fun mergeDialog(n: Note) {
        val others = store.all().filter { it.notebookId == n.notebookId && it.id != n.id }
        if (others.isEmpty()) return toast("Geen andere pagina's in dit notitieblok")
        val labels = others.map { o -> dateOf(o) + (o.text.lineSequence().firstOrNull { it.isNotBlank() }?.let { " · $it" } ?: "") }
        AlertDialog.Builder(this).setTitle("Welke pagina toevoegen aan deze?").setItems(labels.toTypedArray()) { _, i ->
            val o = others[i]
            val merged = Note(n.id, n.timestamp, n.width, n.height, n.strokes + o.strokes, n.rotation,
                listOf(n.text, o.text).filter { it.isNotBlank() }.joinToString("\n\n"), n.source, n.notionUrl, n.notebookId, n.driveIds,
                listOf(n.tags, o.tags).filter { it.isNotBlank() }.joinToString(", "))
            store.save(merged)
            store.delete(o.id)
            toast("Pagina's samengevoegd")
            showNote(merged)
        }.setNegativeButton("Annuleren", null).show()
    }

    // ---- export --------------------------------------------------------------------------

    private fun exportFile(name: String) = File(File(cacheDir, "exports").also { it.mkdirs() }, name)

    private fun share(f: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "app.sparkink.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, null))
    }

    /** Make the file on the worker thread, then offer it to the share sheet (Drive, Files, mail…). */
    private fun exportAndShare(make: () -> Pair<File, String>) {
        setStatus("Exporteren…")
        bg.execute {
            val r = try { Result.success(make()) } catch (e: Exception) { Result.failure(e) }
            main.post {
                r.onSuccess { (f, mime) -> setStatus(""); share(f, mime) }
                    .onFailure { AppLog.add("export failed: $it"); setStatus("Exporteren mislukt: ${it.message}") }
            }
        }
    }

    private fun exportDialog(n: Note) {
        val base = "Spark Ink " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date(n.timestamp * 1000))
        val ink = prefs.inkColor
        val width = prefs.inkWidth
        AlertDialog.Builder(this).setTitle("Exporteer pagina")
            .setItems(arrayOf("PDF (vector)", "SVG (vector)", "PNG (afbeelding)", "Tekst (.txt)")) { _, i ->
                exportAndShare {
                    when (i) {
                        0 -> exportFile("$base.pdf").also { Render.pdf(listOf(n.sketch()), ink, width, it) } to "application/pdf"
                        1 -> exportFile("$base.svg").also { it.writeText(n.sketch().toSvg("#%06x".format(ink and 0xffffff), width.toDouble())) } to "image/svg+xml"
                        2 -> exportFile("$base.png").also { it.writeBytes(Render.png(Render.bitmap(n.sketch(), 2400, false, ink, width))) } to "image/png"
                        else -> exportFile("$base.txt").also { it.writeText(n.text) } to "text/plain"
                    }
                }
            }.show()
    }

    private fun exportBookPdf(title: String, notes: List<Note>) {
        if (notes.isEmpty()) return toast("Geen pagina's om te exporteren")
        val name = title.filter { it.isLetterOrDigit() || it in " -_.'" }.ifBlank { "Spark Ink" }
        val sketches = notes.sortedBy { it.timestamp }.map { it.sketch() }
        val ink = prefs.inkColor
        val width = prefs.inkWidth
        exportAndShare { exportFile("$name.pdf").also { Render.pdf(sketches, ink, width, it) } to "application/pdf" }
    }

    // ---- live ----------------------------------------------------------------------------

    /** Show the pen as you write on the Folio. Experimental: built from Tuhi, not yet tried on a Spark. */
    private fun showLive() {
        if (!pad.isPaired) return toast("Koppel eerst je Folio (Instellingen)")
        if (busy) return toast("Even geduld, er loopt nog iets")
        withBlePermissions {
            saveEditor()
            current = null; editor = null; inSettings = false; inLive = true
            val target = openBook ?: ""
            val (w, h) = if (prefs.padKind == "SLATE") 21600 to 14800 else 21000 to 14800
            val strokes = ArrayList<ArrayList<Pt>>()
            fun sketch() = Note("live", 0, w, h, strokes.map { it.toList() }, prefs.defaultRotation, "", "").sketch()
            val view = InkView(this).apply { ink = prefs.inkColor; widthMm = prefs.inkWidth; sketch = sketch() }
            val title = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Live", 24f, Ui.INK, bold = true))
                addView(label("Experimenteel: wat je op de Folio schrijft verschijnt hier", 13f, Ui.MUTED))
            }
            val body = FrameLayout(this).apply {
                background = rounded(Ui.SURFACE, 12)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            scaffold(header(title, back = true), body, listOf(
                Action("Bewaar", R.drawable.ic_add) {
                    val done = strokes.filter { it.isNotEmpty() }.map { it.toList() }
                    if (done.isEmpty()) toast("Nog niets geschreven") else {
                        val ts = System.currentTimeMillis() / 1000
                        store.save(Note("live$ts", ts, w, h, done, prefs.defaultRotation, "", "", notebookId = target))
                        strokes.clear(); view.sketch = sketch()
                        toast("Pagina bewaard")
                    }
                },
                Action("Leeg", R.drawable.ic_delete) { strokes.clear(); view.sketch = sketch() },
            ))
            busy = true
            pad.live({ setStatus(it) }, { pts ->
                for (p in pts) {
                    if (p == null) { if (strokes.lastOrNull()?.isNotEmpty() == true) strokes.add(ArrayList()) }
                    else { if (strokes.isEmpty()) strokes.add(ArrayList()); strokes.last().add(p) }
                }
                view.sketch = sketch()
            }) { r ->
                busy = false
                r.onSuccess { setStatus("Live gestopt") }.onFailure { setStatus("Live mislukt: ${it.message}") }
            }
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
                setStatus("")
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

    /** After a sync, recognise every new note with the method chosen in the settings, then call [finished]. */
    private fun autoOcr(notes: List<Note>, finished: () -> Unit, index: Int = 0) {
        val method = prefs.autoOcr
        if (method == "off" || index >= notes.size) { finished(); return }
        val n = notes[index]
        val next = {
            if (prefs.notionAuto && prefs.notionReady && n.text.isNotBlank()) exportToNotion(n) { autoOcr(notes, finished, index + 1) }
            else autoOcr(notes, finished, index + 1)
        }
        // Claude needs internet and a key; if it fails fall back to the on-device recogniser, and if that
        // fails too the note simply stays readable as ink.
        runOcr(n, method, onFail = {
            if (method == "claude") runOcr(n, "mlkit", onFail = finished, done = next) else finished()
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

    /** Guard for a short command to the paired pad; [run] must reset [busy] when it is done. */
    private fun padCommand(run: () -> Unit) {
        if (busy) return toast("Even geduld, er loopt nog iets")
        withBlePermissions { busy = true; run() }
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
            r.onSuccess { setStatus(it); refresh() }.onFailure { setStatus("Koppelen mislukt: ${it.message}") }
        }
    }

    private fun finishWipe(dev: BluetoothDevice) {
        if (busy) return
        busy = true
        pad.wipe(dev, { setStatus(it) }, { setStatus("Druk nu kort op de knop van de Folio. Daarna wist en reset de app hem automatisch.") }) { r ->
            busy = false
            r.onSuccess { setStatus(it); refresh() }.onFailure { setStatus("Wissen mislukt: ${it.message}") }
        }
    }

    /** Download new pages into notebook [target] ("" = loose pages) and show them as they arrive. */
    private fun startSync(target: String) {
        if (!pad.isPaired) return toast("Koppel eerst je Folio (Instellingen)")
        if (busy) return
        withBlePermissions {
            busy = true
            val fresh = ArrayList<Note>()
            pad.sync({ setStatus(it) }, { n ->
                n.notebookId = target
                store.save(n)
                fresh.add(n)
                if (current == null && !inSettings) showBook(target)
            }) { r ->
                busy = false
                r.onSuccess { n ->
                    setStatus(if (n == 0) "Geen nieuwe pagina's op de Folio" else "${pages(n)} opgehaald")
                    if (n > 0) autoOcr(fresh.reversed(), finished = {
                        if (current == null && !inSettings) showBook(target)
                        if (prefs.driveAuto) uploadToDrive(fresh)
                    }) else refresh()
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
                .setPositiveButton("Openen") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.notionUrl))) }
                .setNeutralButton("Opnieuw exporteren") { _, _ -> exportToNotion(n) {} }
                .setNegativeButton("Annuleren", null).show()
        } else exportToNotion(n) {}
    }

    private fun exportToNotion(n: Note, done: () -> Unit) {
        val id = NotionBlocks.extractId(prefs.notionTarget) ?: return toast("Notion-database/pagina niet herkend")
        setStatus("Naar Notion sturen…")
        val fallbackTitle = "Notitie " + dateOf(n)
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

    // ---- Google Drive --------------------------------------------------------------

    /** Get a Drive access token; the first time Google shows its own account + permission screen. */
    private fun withDrive(then: (String) -> Unit) {
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveClient.SCOPE))).build()
        Identity.getAuthorizationClient(this).authorize(req)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    pendingDrive = then
                    try { startIntentSenderForResult(pi.intentSender, 2, null, 0, 0, 0) }
                    catch (e: Exception) { pendingDrive = null; setStatus("Google-inlogscherm niet te openen: ${e.message}") }
                } else r.accessToken?.let(then) ?: setStatus("Google Drive gaf geen toegang")
            }
            .addOnFailureListener { AppLog.add("drive auth failed: $it"); setStatus("Inloggen bij Google Drive mislukt: ${it.message}") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2) return
        val then = pendingDrive
        pendingDrive = null
        val token = try { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data).accessToken } catch (e: Exception) { null }
        if (token != null) then?.invoke(token) else setStatus("Geen toegang tot Google Drive gekregen")
    }

    private fun uploadToDrive(notes: List<Note>) {
        if (notes.isEmpty()) return
        withDrive { token ->
            setStatus("Naar Google Drive…")
            val titles = notes.associate { it.notebookId to (books.get(it.notebookId)?.title?.ifBlank { "Naamloos" } ?: "Losse pagina's") }
            bg.execute {
                var ok = 0
                var err: Exception? = null
                for (n in notes) try {
                    val ids = DriveClient.uploadNote(token, n, titles.getValue(n.notebookId))
                    main.post { n.driveIds = ids; store.save(n) }
                    ok++
                } catch (e: Exception) { AppLog.add("drive upload failed: $e"); err = e }
                main.post { setStatus(err?.let { "Google Drive: ${pages(ok)} gelukt, fout: ${it.message}" } ?: "${pages(ok)} in Google Drive (map Spark Ink)") }
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
        saveEditor()
        current = null; editor = null; openBook = null; inSettings = true
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        fun section(title: String): LinearLayout {
            col.addView(label(title.uppercase(), 12f, Ui.MUTED, bold = true).apply { setPadding(dp(4), dp(16), 0, dp(6)) })
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Ui.SURFACE, 14)
                setPadding(dp(16), dp(10), dp(16), dp(14))
                col.addView(this)
            }
        }
        fun field(box: LinearLayout, title: String, value: String, secret: Boolean = false): EditText {
            box.addView(label(title, 13f, Ui.MUTED).apply { setPadding(0, dp(10), 0, 0) })
            return EditText(this).apply {
                setText(value); setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
                box.addView(this)
            }
        }
        fun note(box: LinearLayout, s: String) = box.addView(label(s, 13f, Ui.MUTED).apply { setPadding(0, dp(6), 0, dp(4)) })

        val folio = section("Folio")
        folio.addView(label(if (pad.isPaired) "Gekoppeld met ${prefs.padName ?: "Folio"}" else "Nog geen Folio gekoppeld", 15f, Ui.INK, bold = true))
        note(folio, "Koppelen: houd de knop 6 seconden ingedrukt tot het lampje blauw knippert. Knippert het blauw-oranje, dan is het geheugen vol: kies dan Wissen + resetten (alle pagina's op de Folio gaan verloren).")
        if (prefs.padBattery >= 0) note(folio, "Batterij ${prefs.padBattery}% (bij de laatste verbinding)")
        folio.addView(rowOf(
            pill("Batterij", R.drawable.ic_bluetooth) { padCommand { pad.battery({ setStatus(it) }) { r ->
                busy = false
                r.onSuccess { (pct, charging) -> setStatus("Batterij $pct%" + if (charging) ", laadt op" else "") }
                    .onFailure { setStatus("Batterij uitlezen mislukt: ${it.message}") }
            } } },
            pill("Naam", R.drawable.ic_edit) {
                askName("Naam van de Folio", prefs.padName ?: "", "Bijv. Folio van Jeroen") { name ->
                    if (name.isNotBlank()) padCommand { pad.rename(name, { setStatus(it) }) { r ->
                        busy = false
                        r.onSuccess { setStatus("Folio heet nu $it (zichtbaar na herstart van de Folio)") }
                            .onFailure { setStatus("Naam wijzigen mislukt: ${it.message}") }
                    } }
                }
            },
        ))
        folio.addView(rowOf(
            pill("Koppelen", R.drawable.ic_bluetooth) { startPairing() },
            pill("Wissen + resetten", R.drawable.ic_delete, Ui.DANGER) {
                withBlePermissions {
                    setStatus("Houd de knop van de Folio ingedrukt tot hij in de lijst verschijnt en kies hem. Alle pagina's op de Folio worden gewist.")
                    pickDevice(::finishWipe)
                }
            },
        ))

        val inkBox = section("Inkt")
        note(inkBox, "Kleur en dikte van de lijnen op het scherm en in exports. De tekstherkenning gebruikt altijd zwart.")
        val inks = listOf(0xFF000000.toInt(), 0xFF1F4FB5.toInt(), 0xFFB3261E.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt())
        inkBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(8))
            for (c in inks) addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(c)
                    setStroke(dp(3), if (c == prefs.inkColor) Ui.ACCENT else Ui.LINE)
                }
                contentDescription = "Inktkleur"
                setOnClickListener { saveSettings?.invoke(); prefs.inkColor = c; thumbs.evictAll(); showSettings() }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        })
        val widths = listOf(0.25f, 0.35f, 0.6f)
        inkBox.addView(segmented(listOf("Dun", "Normaal", "Dik"), widths.indexOf(prefs.inkWidth).coerceAtLeast(0)) { i ->
            saveSettings?.invoke(); prefs.inkWidth = widths[i]; thumbs.evictAll(); showSettings()
        }, LinearLayout.LayoutParams(WRAP, WRAP))

        val ocr = section("Tekstherkenning")
        note(ocr, "Automatisch omzetten naar tekst na synchroniseren")
        val group = RadioGroup(this)
        val opts = listOf("off" to "Uit", "mlkit" to "ML Kit (op het toestel, gratis)", "claude" to "Claude (beter, API-key nodig)")
        opts.forEachIndexed { i, (key, text) ->
            group.addView(RadioButton(this).apply { id = 100 + i; this.text = text; isChecked = prefs.autoOcr == key })
        }
        ocr.addView(group)
        val lang = field(ocr, "Taal voor ML Kit (bijv. nl, en, de)", prefs.language)
        val key = field(ocr, "Claude API-key (blijft alleen op dit toestel)", prefs.apiKey, secret = true)
        val model = field(ocr, "Claude-model", prefs.model)

        val notion = section("Notion")
        note(notion, "Maak een integratie op notion.so/my-integrations en deel je database of pagina ermee via ••• > Verbindingen.")
        val nToken = field(notion, "Notion-token (secret_… of ntn_…)", prefs.notionToken, secret = true)
        val nTarget = field(notion, "Notion database of pagina (link of ID)", prefs.notionTarget)
        val nAuto = CheckBox(this).apply { text = "Na tekstherkenning automatisch naar Notion"; isChecked = prefs.notionAuto }
        notion.addView(nAuto)

        val drive = section("Google Drive")
        note(drive, "Pagina's komen als afbeelding en tekst in de map Spark Ink in je Drive, met een submap per notitieblok. De app ziet alleen de bestanden die hij zelf maakt.")
        val dAuto = CheckBox(this).apply { text = "Na synchroniseren automatisch naar Google Drive"; isChecked = prefs.driveAuto }
        drive.addView(dAuto)
        drive.addView(rowOf(pill("Verbinden", R.drawable.ic_cloud) {
            withDrive { token ->
                setStatus("Google Drive controleren…")
                bg.execute {
                    val r = try { DriveClient.folder(token, "root", "Spark Ink", null); "Verbonden met Google Drive" } catch (e: Exception) { "Google Drive mislukt: ${e.message}" }
                    main.post { setStatus(r) }
                }
            }
        }))

        val app = section("App")
        note(app, "Versie ${BuildConfig.GIT_SHA}. Updates hebben een GitHub-token met alleen leesrechten (Contents: Read) voor Jeroentjj/Spark-Ink nodig, omdat de repo privé is.")
        val ghToken = field(app, "GitHub-token (alleen voor updates)", prefs.githubToken, secret = true)
        app.addView(rowOf(
            pill("Update zoeken", R.drawable.ic_download) { saveSettings?.invoke(); checkForUpdate() },
            pill("Log", R.drawable.ic_list) { showLog() },
        ))

        saveSettings = {
            prefs.autoOcr = opts[(group.checkedRadioButtonId - 100).coerceIn(0, 2)].first
            prefs.language = lang.text.toString().ifBlank { "nl" }
            prefs.apiKey = key.text.toString()
            prefs.model = model.text.toString().ifBlank { "claude-sonnet-5-5" }
            prefs.githubToken = ghToken.text.toString()
            prefs.notionToken = nToken.text.toString()
            prefs.notionTarget = nTarget.text.toString()
            prefs.notionAuto = nAuto.isChecked
            prefs.driveAuto = dAuto.isChecked
        }
        scaffold(header(label("Instellingen", 24f, Ui.INK, bold = true), back = true), ScrollView(this).apply { addView(col) }, emptyList())
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

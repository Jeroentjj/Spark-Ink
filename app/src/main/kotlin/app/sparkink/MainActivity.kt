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
import app.sparkink.core.Docx
import app.sparkink.core.Todo
import android.view.DragEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import kotlin.math.abs
import android.graphics.Paint
import android.view.inputmethod.EditorInfo
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

private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

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
    private var inTodos = false
    private var showList = false
    private var orderPick: ArrayList<String>? = null // page ids tapped in "set order" mode
    private var resumed = false
    private var lastAutoSync = 0L
    private val autoTried = HashSet<String>() // pages already given one automatic text recognition
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
        // Keep the stack trace of a crash, and show it on the next start instead of crashing again unseen.
        val crashFile = File(filesDir, "crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { crashFile.writeText("Spark Ink ${BuildConfig.GIT_SHA}\n" + android.util.Log.getStackTraceString(e)) } catch (_: Exception) {}
            previous?.uncaughtException(t, e)
        }
        if (crashFile.exists()) return showCrash(crashFile)
        prefs = Prefs(this)
        store = NoteStore(this)
        books = NotebookStore(this)
        pad = Pad(this, prefs, store)
        Lang.en = prefs.language == "en"
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        showHome()
        immersive() // only after setContentView: before it the window has no decor view (crash on Android 11+)
    }

    /** Bare screen without any of the app's own UI code, so it cannot crash the same way. */
    private fun showCrash(f: File) {
        val trace = f.readText()
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 48, 32, 32)
                addView(TextView(context).apply {
                    text = "Spark Ink is de vorige keer gecrasht / crashed last time. Deel dit met de ontwikkelaar / share this with the developer:"
                    setTextColor(Color.WHITE); textSize = 16f
                })
                addView(android.widget.Button(context).apply {
                    text = "Delen / Share"
                    setOnClickListener {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, trace) }, null))
                    }
                })
                addView(android.widget.Button(context).apply {
                    text = "Toch openen / Open anyway"
                    setOnClickListener { f.delete(); recreate() }
                })
                addView(TextView(context).apply {
                    text = trace; setTextColor(Color.LTGRAY); textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
                })
            })
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::prefs.isInitialized) immersive()
    }

    /** Full screen: status and navigation bar hidden, a swipe from the edge shows them for a moment. */
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startWatching()
    }

    /**
     * Auto sync: while the app is open, a page comes in as soon as the Folio's button is pressed
     * (the pad then advertises). A short pause after each sync stops it from firing twice.
     */
    private fun startWatching() {
        if (!resumed || !::prefs.isInitialized || !prefs.autoSync || !pad.isPaired || !pad.bluetoothOn || missingBlePermissions().isNotEmpty()) return
        pad.watch {
            // not in Settings: pairing, erasing and pad commands there use the same advert
            if (busy || inLive || inSettings || orderPick != null || System.currentTimeMillis() - lastAutoSync < 20_000) return@watch
            lastAutoSync = System.currentTimeMillis()
            startSync(openBook ?: "")
        }
    }

    override fun onPause() {
        resumed = false
        if (::pad.isInitialized) pad.stopWatch()
        saveEditor()
        saveSettings?.invoke()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val book = openBook
        when {
            inLive -> { pad.stopLive(); inLive = false; if (book != null) showBook(book) else showHome() }
            inTodos && current == null -> { inTodos = false; showHome() }
            current == null && orderPick != null -> { orderPick = null; setStatus(""); showBook(book ?: "") }
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
            inTodos && n == null -> showTodos()
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

    /** One row of actions at the bottom, behind a small tab; tap the tab or swipe up/down to open or close. */
    private fun sheet(root: ViewGroup, actions: List<Action>): View {
        val r = dp(22).toFloat()
        // at most two rows that fit the width: never scrolls
        val cols = if (actions.size <= 5) actions.size else (actions.size + 1) / 2
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Ui.SURFACE); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            elevation = dp(12).toFloat()
            setPadding(dp(4), dp(8), dp(4), dp(10))
            visibility = if (sheetOpen) View.VISIBLE else View.GONE
            actions.chunked(cols).forEach { chunk ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (i in 0 until cols) {
                        val a = chunk.getOrNull(i)
                        addView(if (a != null) actionButton(a) else View(context), LinearLayout.LayoutParams(0, WRAP, 1f))
                    }
                })
            }
        }
        val chevron = icon(if (sheetOpen) R.drawable.ic_expand_more else R.drawable.ic_expand_less, Ui.MUTED, 22)
        fun setOpen(open: Boolean) {
            if (open == sheetOpen) return
            sheetOpen = open
            TransitionManager.beginDelayedTransition(root)
            panel.visibility = if (open) View.VISIBLE else View.GONE
            chevron.setImageResource(if (open) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
        }
        val tab = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Ui.SURFACE); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            elevation = dp(12).toFloat()
            contentDescription = tr("Knoppen tonen", "Show buttons")
            addView(chevron, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { setOpen(!sheetOpen) }
        }
        return SwipeBar(this) { up -> setOpen(up) }.apply {
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

    private fun pages(n: Int) = if (n == 1) tr("1 pagina", "1 page") else tr("$n pagina's", "$n pages")

    private fun dateOf(n: Note) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(n.timestamp * 1000))

    private fun loadThumb(n: Note, into: ImageView) {
        val key = "${n.id}_${n.rotation}_${n.strokes.size}_${n.tidyOn && n.tidy.isNotEmpty()}"
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
        current = null; editor = null; openBook = null; inSettings = false; inTodos = false; saveSettings = null
        val notes = store.all()
        val content = FrameLayout(this)
        fun fill() {
            content.removeAllViews()
            val q = query.trim()
            content.addView(if (q.isEmpty()) {
                val cells = ArrayList<View>()
                cells.add(looseCard(notes.filter { it.notebookId.isEmpty() }))
                todoCard(notes)?.let { cells.add(it) }
                books.all().forEach { b -> cells.add(bookCard(b, notes.count { it.notebookId == b.id })) }
                cells.add(newBookCard())
                grid(cells)
            } else {
                val hits = notes.filter { it.text.contains(q, ignoreCase = true) || it.tags.contains(q, ignoreCase = true) }
                if (hits.isEmpty()) label(tr("Niets gevonden voor “$q”", "Nothing found for “$q”"), 15f, Ui.MUTED).apply { setPadding(dp(8), dp(24), dp(8), 0) }
                else grid(hits.map { pageCard(it) })
            })
        }
        val search = EditText(this).apply {
            setText(query); setSingleLine(); textSize = 15f
            hint = tr("Zoek in tekst en tags", "Search text and tags")
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
        val body = ScrollView(this).apply { addView(content) }
        val top = FrameLayout(this).apply { setPadding(dp(6), 0, dp(6), 0); addView(search) }
        scaffold(header(top, back = false), body, listOf(
            Action(tr("Synchroniseer", "Sync"), R.drawable.ic_sync) { startSync("") },
            Action("Live", R.drawable.ic_edit) { showLive() },
            Action(tr("Notitieblok", "Notebook"), R.drawable.ic_add) { newBook() },
            Action(tr("Instellingen", "Settings"), R.drawable.ic_settings) { showSettings() },
        ))
    }

    private fun bookCard(b: Notebook, count: Int) = FrameLayout(this).apply {
        minimumHeight = dp(176)
        // straight spine on the left, rounded on the open side
        val r = dp(14).toFloat()
        background = GradientDrawable().apply { setColor(b.color); cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) }
        elevation = dp(3).toFloat()
        addView(View(context).apply { setBackgroundColor(0x33000000) }, FrameLayout.LayoutParams(dp(10), MATCH, Gravity.START))
        addView(LinearLayout(context).apply {
            background = rounded(0xF2FFFFFF.toInt(), 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            gravity = Gravity.CENTER
            addView(label(b.title.ifBlank { tr("Naamloos", "Untitled") }, 16f, Ui.PAPER_INK, bold = true).apply {
                maxLines = 3; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { setMargins(dp(22), dp(18), dp(12), 0) })
        addView(label(pages(count), 12f, 0xE6FFFFFF.toInt()),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(22), 0, 0, dp(12)) })
        ripple(this)
        setOnClickListener { showBook(b.id) }
        draggable(this, b.id, onDrop = { from, to -> books.move(from, to); showHome() }) {
            askName(tr("Notitieblok hernoemen", "Rename notebook"), b.title) { b.title = it; books.save(); showHome() }
        }
    }

    private fun looseCard(loose: List<Note>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = dp(176)
        background = rounded(Ui.SURFACE, 14, Ui.LINE)
        elevation = dp(1).toFloat()
        setPadding(dp(12), dp(12), dp(12), dp(12))
        addView(label(tr("Losse pagina's", "Loose pages"), 16f, Ui.INK, bold = true))
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
        addView(label(tr("Nieuw notitieblok", "New notebook"), 14f, Ui.MUTED).apply { setPadding(dp(8), dp(6), dp(8), 0); gravity = Gravity.CENTER })
        ripple(this)
        setOnClickListener { newBook() }
    }

    private fun askName(title: String, initial: String, hintText: String = tr("Bijv. Werk, Ideeën, Project X", "E.g. Work, Ideas, Project X"), onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial); setSingleLine(); hint = hintText
            setSelection(initial.length)
        }
        AlertDialog.Builder(this).setTitle(title)
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton(tr("Opslaan", "Save")) { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton(tr("Annuleren", "Cancel"), null).show()
    }

    private fun newBook() = askName(tr("Nieuw notitieblok", "New notebook"), "") { showBook(books.create(it).id) }

    // ---- one notebook (or the loose pages) -------------------------------------------------

    private fun showBook(id: String) {
        saveEditor()
        current = null; editor = null; openBook = id; inSettings = false; inTodos = false; saveSettings = null
        val book = books.get(id)
        val notes = store.all().filter { it.notebookId == (book?.id ?: "") }.sortedBy { it.sortKey }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (book == null) addView(label(tr("Losse pagina's", "Loose pages"), 24f, Ui.INK, bold = true))
            else addView(EditText(context).apply {
                setText(book.title)
                hint = tr("Naam van dit notitieblok", "Name of this notebook")
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
            label(if (book == null) tr("Geen losse pagina's. Nieuwe pagina's van de Folio komen hier terecht.", "No loose pages. New pages from the Folio land here.")
                  else tr("Nog geen pagina's. Synchroniseer vanuit dit notitieblok, of verplaats pagina's hierheen.", "No pages yet. Sync from this notebook, or move pages here."), 15f, Ui.MUTED)
                .apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), 0) }
        } else ScrollView(this).apply { addView(grid(notes.map { pageCard(it, inBook = notes) })) }

        val pick = orderPick
        if (pick != null) {
            setStatus(tr("Tik de pagina's aan in de volgorde die je wilt (${pick.size} gekozen), dan Klaar. Niet aangetikte pagina's komen erachter.",
                "Tap the pages in the order you want (${pick.size} picked), then Done. Pages you don't tap go after them."))
            return scaffold(header(titleCol, back = true), body, listOf(
                Action(tr("Klaar", "Done"), R.drawable.ic_list) {
                    val byId = notes.associateBy { it.id }
                    applyOrder(pick.mapNotNull { byId[it] } + notes.filter { it.id !in pick })
                    orderPick = null
                    setStatus(tr("Volgorde opgeslagen", "Order saved"))
                    showBook(id)
                },
                Action(tr("Annuleer", "Cancel"), R.drawable.ic_delete) { orderPick = null; setStatus(""); showBook(id) },
            ))
        }

        val actions = arrayListOf(
            Action(tr("Synchroniseer", "Sync"), R.drawable.ic_sync) { startSync(book?.id ?: "") },
            Action(tr("Deel", "Share"), R.drawable.ic_share) { exportBook(book?.title?.ifBlank { null } ?: tr("Losse pagina's", "Loose pages"), notes) },
        )
        if (book != null) {
            actions.add(Action(tr("Verwijder", "Delete"), R.drawable.ic_delete) {
                AlertDialog.Builder(this).setMessage(tr("Notitieblok verwijderen? De pagina's blijven bewaard bij de losse pagina's.", "Delete this notebook? Its pages are kept under loose pages."))
                    .setPositiveButton(tr("Verwijderen", "Delete")) { _, _ ->
                        notes.forEach { it.notebookId = ""; store.save(it) }
                        books.delete(book.id)
                        showHome()
                    }
                    .setNegativeButton(tr("Annuleren", "Cancel"), null).show()
            })
        }
        val swatch = if (book == null) null else View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(book.color); setStroke(dp(2), Ui.LINE) }
            contentDescription = tr("Kaftkleur", "Cover colour")
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(8) }
            setOnClickListener { pickColor(book.color) { c -> book.color = c; books.save(); showBook(id) } }
        }
        scaffold(header(titleCol, back = true, trailing = swatch), body, actions)
    }

    private fun pickColor(current: Int, onPick: (Int) -> Unit) {
        lateinit var dialog: AlertDialog
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
            Ui.COVERS.toList().chunked(4).forEach { row ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    for (c in row) addView(View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL; setColor(c)
                            setStroke(dp(3), if (c == current) Ui.ACCENT else Color.TRANSPARENT)
                        }
                        setOnClickListener { dialog.dismiss(); onPick(c) }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
                })
            }
        }
        dialog = AlertDialog.Builder(this).setTitle(tr("Kaftkleur", "Cover colour")).setView(grid).create()
        dialog.show()
    }

    /** [inBook]: the pages of the open notebook, in order, so this card can be dragged among them. */
    private fun pageCard(n: Note, inBook: List<Note>? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pick = orderPick
        val picked = pick?.indexOf(n.id) ?: -1
        background = rounded(Ui.SURFACE, 12, if (picked >= 0) Ui.ACCENT else null)
        if (picked >= 0) addView(label("${picked + 1}", 16f, Ui.ACCENT, bold = true).apply { setPadding(dp(2), 0, 0, dp(4)) })
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
        when {
            pick != null -> setOnClickListener {
                if (picked >= 0) pick.remove(n.id) else pick.add(n.id)
                showBook(openBook ?: "")
            }
            inBook != null -> {
                setOnClickListener { openNote(n) }
                // drag onto another page to move it there; let go in place to tap the order instead
                draggable(this, n.id, onDrop = { from, to ->
                    val list = inBook.toMutableList()
                    val moved = list.first { it.id == from }
                    list.remove(moved)
                    list.add(list.indexOfFirst { it.id == to }.let { if (it < 0) list.size else it }, moved)
                    applyOrder(list)
                    showBook(openBook ?: "")
                }) { pageMenu(n) }
            }
            else -> {
                setOnClickListener { openNote(n) }
                setOnLongClickListener { moveDialog(n) { refresh() }; true }
            }
        }
    }

    private fun pageMenu(n: Note) {
        AlertDialog.Builder(this).setItems(arrayOf(
            tr("Verplaats naar ander notitieblok", "Move to another notebook"),
            tr("Volgorde aanpassen", "Change the order"),
        )) { _, i ->
            if (i == 0) moveDialog(n) { toast(tr("Verplaatst", "Moved")); showBook(openBook ?: "") }
            else { orderPick = arrayListOf(); showBook(openBook ?: "") }
        }.show()
    }

    /** Store [notes] in this order; pages synced later still come first (see [Note.sortKey]). */
    private fun applyOrder(notes: List<Note>) {
        if (notes.isEmpty()) return
        val base = notes.minOf { it.sortKey }
        notes.forEachIndexed { i, n -> n.order = base + i; store.save(n) }
    }

    /**
     * Long press starts dragging [v]; dropping it on another card calls [onDrop] (dragged key, target
     * key), letting go on itself calls [onSelf].
     * shortcut: no auto-scroll while dragging, fine for a screenful of cards; add it if lists get long.
     */
    private fun draggable(v: View, key: String, onDrop: (String, String) -> Unit, onSelf: () -> Unit) {
        v.setOnLongClickListener {
            it.startDragAndDrop(ClipData.newPlainText("spark", key), View.DragShadowBuilder(it), key, 0)
            true
        }
        v.setOnDragListener { view, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> e.localState is String
                DragEvent.ACTION_DRAG_ENTERED -> { view.alpha = 0.5f; true }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { view.alpha = 1f; true }
                DragEvent.ACTION_DROP -> {
                    view.alpha = 1f
                    val from = e.localState as String
                    // post: the drag must finish before the screen is rebuilt
                    main.post { if (from == key) onSelf() else onDrop(from, key) }
                    true
                }
                else -> true
            }
        }
    }

    private fun moveDialog(n: Note, done: () -> Unit) {
        val list = books.all()
        val labels = listOf(tr("Losse pagina's", "Loose pages")) + list.map { it.title.ifBlank { tr("Naamloos", "Untitled") } } + tr("+ Nieuw notitieblok…", "+ New notebook…")
        AlertDialog.Builder(this).setTitle(tr("Verplaats naar", "Move to")).setItems(labels.toTypedArray()) { _, i ->
            when {
                i == 0 -> { n.notebookId = ""; store.save(n); done() }
                i <= list.size -> { n.notebookId = list[i - 1].id; store.save(n); done() }
                else -> askName(tr("Nieuw notitieblok", "New notebook"), "") { n.notebookId = books.create(it).id; store.save(n); done() }
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
        if (n.text.isBlank() && n.source.isEmpty() && prefs.autoOcr != "off" && !busy && autoTried.add(n.id))
            main.post { autoOcr(listOf(n), finished = { if (current?.id == n.id) showNote(n) }) }
        val sourceLine = when (n.source) {
            "mlkit" -> tr("Tekst door ML Kit", "Text by ML Kit")
            "claude" -> tr("Tekst door Claude", "Text by Claude")
            "hand" -> tr("Tekst handmatig bewerkt", "Text edited by hand")
            else -> tr("Nog geen tekst", "No text yet")
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(dateOf(n), 17f, Ui.INK, bold = true))
            addView(label(sourceLine, 12f, Ui.MUTED))
            if (n.tags.isNotBlank()) addView(label(tagLine(n), 12f, Ui.ACCENT))
            if (prefs.appendTo == n.id) addView(label(tr("Wordt aangevuld bij de volgende sync", "Will be extended at the next sync"), 12f, Ui.ACCENT, bold = true))
        }
        val todo = Todo.items(n.text).isNotEmpty()
        val tabs = if (todo) listOf(tr("Pagina", "Page"), tr("Lijst", "List"), tr("Tekst", "Text")) else listOf(tr("Pagina", "Page"), tr("Tekst", "Text"))
        val selected = if (!showText) 0 else if (todo && showList) 1 else tabs.size - 1
        val toggle = segmented(tabs, selected) { i -> saveEditor(); showText = i > 0; showList = todo && i == 1; showNote(n) }

        val body: View
        if (showText && showList && todo) {
            editor = null
            body = ScrollView(this).apply {
                background = rounded(Ui.SURFACE, 12)
                elevation = dp(2).toFloat()
                setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(checklist(n, withAdd = true))
            }
        } else if (!showText) {
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
                hint = tr("Nog geen tekst. Kies ML Kit of Claude in de knoppen onderin, of typ zelf.", "No text yet. Pick ML Kit or Claude in the buttons below, or type it yourself.")
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

        scaffold(header(titleCol, back = true, trailing = toggle), body, listOf(
            Action(tr("Draai", "Rotate"), R.drawable.ic_rotate) {
                saveEditor()
                n.rotation = (n.rotation + 180) % 360 // stays portrait
                prefs.defaultRotation = n.rotation
                store.save(n)
                showNote(n)
            },
            Action(tr("Deel", "Share"), R.drawable.ic_share) { saveEditor(); exportDialog(n) },
            Action(tr("Afspelen", "Replay"), R.drawable.ic_play) { replay(n) },
            Action(tr("Vormen", "Shapes"), R.drawable.ic_shapes) {
                saveEditor()
                if (n.tidy.isEmpty()) findShapes(n) { if (current?.id == n.id) { showText = false; showNote(n) } }
                else {
                    n.tidyOn = !n.tidyOn
                    store.save(n)
                    setStatus(if (n.tidyOn) tr("Vormen strak getekend", "Shapes drawn clean") else tr("Vormen zoals getekend", "Shapes as drawn"))
                    showText = false; showNote(n)
                }
            },
            Action("Tags", R.drawable.ic_label) {
                saveEditor()
                askName("Tags", n.tags, tr("Bijv. werk, idee, boodschappen", "E.g. work, idea, groceries")) { n.tags = it; store.save(n); showNote(n) }
            },
            Action(tr("Voeg samen", "Merge"), R.drawable.ic_merge) { saveEditor(); mergeDialog(n) },
            Action(if (prefs.appendTo == n.id) tr("Niet aanvullen", "Don't extend") else tr("Aanvullen", "Extend"), R.drawable.ic_add) {
                saveEditor()
                if (prefs.appendTo == n.id) {
                    prefs.appendTo = null
                    setStatus(tr("Volgende sync wordt weer een nieuwe pagina", "The next sync becomes a new page again"))
                } else {
                    prefs.appendTo = n.id
                    setStatus(tr("Schrijf verder op dit vel en synchroniseer: wat je toevoegt komt in deze pagina.", "Keep writing on this sheet and sync: what you add goes into this page."))
                }
                showNote(n)
            },
            Action(tr("Wis", "Delete"), R.drawable.ic_delete) {
                AlertDialog.Builder(this).setMessage(tr("Deze pagina definitief verwijderen?", "Delete this page for good?"))
                    .setPositiveButton(tr("Verwijderen", "Delete")) { _, _ -> store.delete(n.id); current = null; editor = null; showBook(n.notebookId) }
                    .setNegativeButton(tr("Annuleren", "Cancel"), null).show()
            },
        ))
    }

    private fun saveEditor() {
        val n = current ?: return
        val e = editor ?: return
        val t = e.text.toString()
        if (t != n.text) {
            n.text = Todo.normalize(t)
            n.tags = Todo.withTag(n.tags, n.text)
            n.source = "hand"
            store.save(n)
        }
    }

    /** Open a page; a to-do page opens on its checklist. */
    private fun openNote(n: Note) {
        inTodos = false
        openBook = n.notebookId
        showList = Todo.items(n.text).isNotEmpty()
        showText = showList
        showNote(n)
    }

    /** Tickable list of the tasks in [n]; ticking rewrites "- [ ]" / "- [x]" in its text. */
    private fun checklist(n: Note, withAdd: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        for (item in Todo.items(n.text)) addView(CheckBox(context).apply {
            text = item.text
            textSize = 16f
            setTextColor(Ui.INK)
            buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            setPadding(dp(4), dp(8), 0, dp(8))
            isChecked = item.done
            fun look() {
                paintFlags = if (isChecked) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                alpha = if (isChecked) 0.55f else 1f
            }
            look()
            setOnCheckedChangeListener { _, _ -> n.text = Todo.toggle(n.text, item.line); store.save(n); look() }
        })
        if (withAdd) addView(EditText(context).apply {
            hint = tr("+ Nieuw item", "+ New item")
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Ui.INK); setHintTextColor(Ui.MUTED)
            setOnEditorActionListener { v, _, _ ->
                val t = v.text.toString()
                if (t.isNotBlank()) {
                    n.text = Todo.add(n.text, t)
                    n.tags = Todo.withTag(n.tags, n.text)
                    store.save(n)
                    showNote(n)
                }
                true
            }
        })
    }

    private fun todoCard(notes: List<Note>): View? {
        val open = notes.flatMap { n -> Todo.items(n.text).filter { !it.done } }
        if (open.isEmpty()) return null
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(176)
            background = rounded(Ui.ACCENT_SOFT, 14)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(label(tr("Taken", "Tasks"), 16f, Ui.INK, bold = true))
            addView(label("${open.size} open", 12f, Ui.ACCENT))
            for (item in open.take(4)) addView(label("☐ " + item.text, 13f, Ui.INK).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
            })
            ripple(this)
            setOnClickListener { showTodos() }
        }
    }

    /** Every page with open tasks, tickable in one place. */
    private fun showTodos() {
        saveEditor()
        current = null; editor = null; openBook = null; inSettings = false; inTodos = true
        val pagesWithTasks = store.all().filter { n -> Todo.items(n.text).any { !it.done } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (n in pagesWithTasks) {
            val book = books.get(n.notebookId)?.title?.ifBlank { tr("Naamloos", "Untitled") } ?: tr("Losse pagina's", "Loose pages")
            col.addView(label("$book · ${dateOf(n)}", 12f, Ui.MUTED, bold = true).apply {
                setPadding(dp(4), dp(16), 0, dp(4))
                setOnClickListener { openNote(n) }
            })
            col.addView(checklist(n, withAdd = false).apply {
                background = rounded(Ui.SURFACE, 12)
                setPadding(dp(12), dp(4), dp(12), dp(4))
            })
        }
        val body = if (pagesWithTasks.isEmpty()) label(tr("Geen open taken. Alles is afgevinkt!", "No open tasks. Everything is ticked off!"), 15f, Ui.MUTED)
            .apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), 0) }
        else ScrollView(this).apply { addView(col) }
        scaffold(header(label(tr("Taken", "Tasks"), 24f, Ui.INK, bold = true), back = true), body, emptyList())
    }

    private fun findShapes(n: Note, done: () -> Unit) {
        setStatus(tr("Vormen zoeken…", "Looking for shapes…"))
        MlKitShapes.recognize(n.strokes) { r ->
            r.onSuccess { m ->
                n.tidy = m; n.tidyOn = true; store.save(n)
                setStatus(if (m.isEmpty()) tr("Geen vormen gevonden", "No shapes found") else tr("${m.size} vorm(en) strak getekend. Tik nogmaals op Vormen voor het origineel.", "${m.size} shape(s) drawn clean. Tap Shapes again for the original."))
            }.onFailure { AppLog.add("shapes failed: $it"); setStatus("Vormen herkennen mislukt: ${it.message}") }
            done()
        }
    }

    /** Find shapes in each page in turn, then [finished] (a failure only skips that page). */
    private fun shapesAll(notes: List<Note>, index: Int = 0, finished: () -> Unit) {
        if (index >= notes.size) return finished()
        findShapes(notes[index]) { shapesAll(notes, index + 1, finished) }
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

    /** [n] with the strokes, text and tags of [o] added; both come from the same sheet, so the ink lines up. */
    private fun mergeNotes(n: Note, o: Note) = Note(n.id, n.timestamp, n.width, n.height, n.strokes + o.strokes, n.rotation,
        listOf(n.text, o.text).filter { it.isNotBlank() }.joinToString("\n\n"), n.source, n.notionUrl, n.notebookId, n.driveIds,
        listOf(n.tags, o.tags).filter { it.isNotBlank() }.joinToString(", "), n.tidy, n.tidyOn, n.order)

    /** Append another page of the same notebook to this one; the other page is removed. */
    private fun mergeDialog(n: Note) {
        val others = store.all().filter { it.notebookId == n.notebookId && it.id != n.id }
        if (others.isEmpty()) return toast(tr("Geen andere pagina's in dit notitieblok", "No other pages in this notebook"))
        val labels = others.map { o -> dateOf(o) + (o.text.lineSequence().firstOrNull { it.isNotBlank() }?.let { " · $it" } ?: "") }
        AlertDialog.Builder(this).setTitle(tr("Welke pagina toevoegen aan deze?", "Which page should be added to this one?")).setItems(labels.toTypedArray()) { _, i ->
            val o = others[i]
            val merged = mergeNotes(n, o)
            store.save(merged)
            store.delete(o.id)
            toast(tr("Pagina's samengevoegd", "Pages merged"))
            showNote(merged)
        }.setNegativeButton(tr("Annuleren", "Cancel"), null).show()
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
        setStatus(tr("Exporteren…", "Exporting…"))
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
        val items = arrayListOf<Pair<String, () -> Unit>>(
            tr("Tekst", "Text") to {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, n.text) }, null))
            },
            "PDF" to { exportAndShare { exportFile("$base.pdf").also { Render.pdf(listOf(n.sketch()), ink, width, it) } to "application/pdf" } },
            "Word" to { exportAndShare { exportFile("$base.docx").also { it.writeBytes(Docx.build(listOf(dateOf(n) to n.text))) } to DOCX } },
            tr("Afbeelding (PNG)", "Image (PNG)") to {
                exportAndShare { exportFile("$base.png").also { it.writeBytes(Render.png(Render.bitmap(n.sketch(), 2400, false, ink, width))) } to "image/png" }
            },
            "SVG" to {
                exportAndShare { exportFile("$base.svg").also { it.writeText(n.sketch().toSvg("#%06x".format(ink and 0xffffff), width.toDouble())) } to "image/svg+xml" }
            },
            tr("Tekstbestand (.txt)", "Text file (.txt)") to { exportAndShare { exportFile("$base.txt").also { it.writeText(n.text) } to "text/plain" } },
        )
        if (prefs.notionReady) items.add("Notion" to { onNotionClicked(n) })
        AlertDialog.Builder(this).setTitle(tr("Deel pagina", "Share page"))
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    /** A whole notebook, oldest page first. */
    private fun exportBook(title: String, notes: List<Note>) {
        if (notes.isEmpty()) return toast(tr("Geen pagina's om te exporteren", "No pages to export"))
        val name = title.filter { it.isLetterOrDigit() || it in " -_.'" }.ifBlank { "Spark Ink" }
        val sorted = notes.sortedBy { it.timestamp }
        val ink = prefs.inkColor
        val width = prefs.inkWidth
        AlertDialog.Builder(this).setTitle(tr("Exporteer notitieblok", "Export notebook"))
            .setItems(arrayOf(tr("PDF (de pagina's)", "PDF (the pages)"), tr("Word (de tekst)", "Word (the text)"))) { _, i ->
                if (i == 0) exportAndShare { exportFile("$name.pdf").also { Render.pdf(sorted.map { it.sketch() }, ink, width, it) } to "application/pdf" }
                else {
                    val sections = sorted.map { dateOf(it) to it.text.ifBlank { tr("(nog geen tekst)", "(no text yet)") } }
                    exportAndShare { exportFile("$name.docx").also { it.writeBytes(Docx.build(sections)) } to DOCX }
                }
            }.show()
    }

    // ---- live ----------------------------------------------------------------------------

    /** Show the pen as you write on the Folio. Experimental: built from Tuhi, not yet tried on a Spark. */
    private fun showLive() {
        if (!pad.isPaired) return toast(tr("Koppel eerst je Folio (Instellingen)", "Pair your Folio first (Settings)"))
        if (busy) return toast(tr("Even geduld, er loopt nog iets", "One moment, something is still running"))
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
                addView(label(tr("Experimenteel: wat je op de Folio schrijft verschijnt hier", "Experimental: what you write on the Folio appears here"), 13f, Ui.MUTED))
            }
            val body = FrameLayout(this).apply {
                background = rounded(Ui.SURFACE, 12)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            scaffold(header(title, back = true), body, listOf(
                Action(tr("Bewaar", "Save"), R.drawable.ic_add) {
                    val done = strokes.filter { it.isNotEmpty() }.map { it.toList() }
                    if (done.isEmpty()) toast(tr("Nog niets geschreven", "Nothing written yet")) else {
                        val ts = System.currentTimeMillis() / 1000
                        store.save(Note("live$ts", ts, w, h, done, prefs.defaultRotation, "", "", notebookId = target))
                        strokes.clear(); view.sketch = sketch()
                        toast(tr("Pagina bewaard", "Page saved"))
                    }
                },
                Action(tr("Leeg", "Clear"), R.drawable.ic_delete) { strokes.clear(); view.sketch = sketch() },
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
                r.onSuccess { setStatus(tr("Live gestopt", "Live stopped")) }.onFailure { setStatus(tr("Live mislukt: ${it.message}", "Live failed: ${it.message}")) }
            }
        }
    }

    // ---- OCR -------------------------------------------------------------------------

    private fun runOcr(n: Note, method: String, onFail: () -> Unit = {}, lang: String = prefs.language, done: () -> Unit) {
        if (busy) return toast(tr("Even geduld, er loopt nog iets", "One moment, something is still running"))
        busy = true
        setStatus(if (method == "claude") tr("Claude leest het handschrift…", "Claude is reading the handwriting…") else tr("ML Kit leest het handschrift…", "ML Kit is reading the handwriting…"))
        fun finish(r: Result<String>) {
            busy = false
            r.onSuccess {
                n.text = Todo.normalize(it); n.tags = Todo.withTag(n.tags, n.text); n.source = method; store.save(n)
                setStatus("")
                done()
            }.onFailure {
                AppLog.add("ocr $method failed: $it")
                setStatus(tr("Herkenning mislukt: ${it.message}", "Recognition failed: ${it.message}"))
                onFail()
            }
        }
        if (method == "mlkit") {
            MlKitOcr.recognize(n.sketch(), lang) { finish(it) }
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

    private fun missingBlePermissions(): List<String> {
        val needed = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun withBlePermissions(then: () -> Unit) {
        val missing = missingBlePermissions()
        if (missing.isEmpty()) {
            if (!pad.bluetoothOn) return toast(tr("Zet Bluetooth aan", "Turn on Bluetooth"))
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
            if (pad.bluetoothOn) then?.invoke() else toast(tr("Zet Bluetooth aan", "Turn on Bluetooth"))
        } else toast(tr("Zonder Bluetooth-toestemming kan de app de Folio niet vinden", "Without Bluetooth permission the app cannot find the Folio"))
    }

    /** Guard for a short command to the paired pad; [run] must reset [busy] when it is done. */
    private fun padCommand(run: () -> Unit) {
        if (busy) return toast(tr("Even geduld, er loopt nog iets", "One moment, something is still running"))
        withBlePermissions { busy = true; run() }
    }

    private fun startPairing() = withBlePermissions {
        setStatus(tr("Houd de knop op de Folio minstens 6 seconden ingedrukt tot het lampje blauw knippert (afwisselend blauw-oranje betekent: geheugen vol) en kies de Folio meteen in de lijst: de koppelmodus duurt maar kort.", "Hold the Folio button for at least 6 seconds until the light blinks blue (blue and orange in turn means: memory full) and pick the Folio in the list right away: pairing mode is short."))
        pickDevice()
    }

    private fun pickDevice(onPick: (BluetoothDevice) -> Unit = ::finishPairing) {
        val found = ArrayList<BluetoothDevice>()
        val names = ArrayList<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Kies je Folio", "Pick your Folio"))
            .setAdapter(adapter) { _, which ->
                pad.stopScan()
                onPick(found[which])
            }
            .setNegativeButton(tr("Annuleren", "Cancel")) { _, _ -> pad.stopScan() }
            .setOnCancelListener { pad.stopScan() }
            .create()
        dialog.show()
        pad.startScan { dev, rssi, connectable ->
            if (found.none { it.address == dev.address }) {
                found.add(dev)
                names.add("${dev.name}  (${dev.address}, $rssi dBm)" + if (connectable) "" else tr("  - niet verbindbaar", "  - not connectable"))
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun finishPairing(dev: BluetoothDevice) {
        if (busy) return
        busy = true
        pad.pair(dev, { setStatus(it) }, { setStatus(tr("Druk nu kort op de knop van de Folio om het koppelen te bevestigen.", "Now briefly press the Folio button to confirm pairing.")) }) { r ->
            busy = false
            r.onSuccess { setStatus(it); refresh() }.onFailure { setStatus(tr("Koppelen mislukt: ${it.message}", "Pairing failed: ${it.message}")) }
        }
    }

    private fun finishWipe(dev: BluetoothDevice) {
        if (busy) return
        busy = true
        pad.wipe(dev, { setStatus(it) }, { setStatus(tr("Druk nu kort op de knop van de Folio. Daarna wist en reset de app hem automatisch.", "Now briefly press the Folio button. The app then erases and resets it automatically.")) }) { r ->
            busy = false
            r.onSuccess { setStatus(it); refresh() }.onFailure { setStatus(tr("Wissen mislukt: ${it.message}", "Erasing failed: ${it.message}")) }
        }
    }

    /** Download new pages into notebook [target] ("" = loose pages) and show them as they arrive. */
    private fun startSync(target: String) {
        if (!pad.isPaired) return toast(tr("Koppel eerst je Folio (Instellingen)", "Pair your Folio first (Settings)"))
        if (busy) return
        withBlePermissions {
            busy = true
            pad.stopWatch() // one scan at a time; restarted when the sync is done
            val fresh = ArrayList<Note>()
            var appended: Note? = null
            pad.sync({ setStatus(it) }, { n ->
                // tr("Aanvullen", "Extend"): the first page that comes in is what was added to that sheet
                val into = prefs.appendTo?.let { store.load(it) }
                if (prefs.appendTo != null) prefs.appendTo = null
                if (into != null) {
                    val merged = mergeNotes(into, n.also { it.text = "" })
                    store.save(merged)
                    store.delete(n.id, keepRaw = true)
                    appended = merged
                    fresh.add(merged)
                } else {
                    n.notebookId = target
                    store.save(n)
                    fresh.add(n)
                }
                if (current == null && !inSettings) showBook(target)
            }) { r ->
                busy = false
                lastAutoSync = System.currentTimeMillis()
                startWatching()
                r.onSuccess { n ->
                    val extra = appended?.let { tr(" (aangevuld: ${dateOf(it)})", " (extended: ${dateOf(it)})") } ?: ""
                    setStatus(if (n == 0) tr("Geen nieuwe pagina's op de Folio", "No new pages on the Folio") else tr("${pages(n)} opgehaald$extra", "${pages(n)} fetched$extra"))
                    // a page whose text was typed by hand keeps that text
                    if (n > 0) autoOcr(fresh.filter { it.source != "hand" }.reversed(), finished = {
                        shapesAll(if (prefs.autoShapes) fresh else emptyList()) {
                            if (current == null && !inSettings) showBook(target)
                            if (prefs.driveAuto) uploadToDrive(fresh, quiet = true)
                        }
                    }) else refresh()
                }.onFailure { setStatus(tr("Synchroniseren mislukt: ${it.message}", "Sync failed: ${it.message}")) }
            }
        }
    }

    // ---- Notion --------------------------------------------------------------------

    private fun onNotionClicked(n: Note) {
        saveEditor()
        if (!prefs.notionReady) return toast(tr("Vul eerst je Notion-token en database/pagina in bij Instellingen", "First enter your Notion token and database/page in Settings"))
        if (n.text.isBlank()) return toast(tr("Er is nog geen tekst om te exporteren", "There is no text to export yet"))
        if (n.notionUrl.isNotBlank()) {
            AlertDialog.Builder(this).setMessage(tr("Deze notitie staat al in Notion.", "This note is already in Notion."))
                .setPositiveButton(tr("Openen", "Open")) { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.notionUrl))) }
                .setNeutralButton(tr("Opnieuw exporteren", "Export again")) { _, _ -> exportToNotion(n) {} }
                .setNegativeButton(tr("Annuleren", "Cancel"), null).show()
        } else exportToNotion(n) {}
    }

    private fun exportToNotion(n: Note, done: () -> Unit) {
        val id = NotionBlocks.extractId(prefs.notionTarget) ?: return toast(tr("Notion-database/pagina niet herkend", "Notion database/page not recognised"))
        setStatus(tr("Naar Notion sturen…", "Sending to Notion…"))
        val fallbackTitle = tr("Notitie ", "Note ") + dateOf(n)
        val text = n.text
        bg.execute {
            val r = try {
                Result.success(NotionClient.createPage(prefs.notionToken, id, NotionBlocks.title(text, fallbackTitle), NotionBlocks.fromMarkdown(text)))
            } catch (e: Exception) {
                Result.failure<String>(e)
            }
            main.post {
                r.onSuccess { url -> n.notionUrl = url; store.save(n); setStatus(tr("In Notion gezet", "Added to Notion")) }
                    .onFailure { AppLog.add("notion failed: $it"); setStatus("Notion mislukt: ${it.message}") }
                done()
            }
        }
    }

    // ---- Google Drive --------------------------------------------------------------

    /** Get a Drive access token; the first time Google shows its own account + permission screen. */
    private fun withDrive(quiet: Boolean = false, then: (String) -> Unit) {
        val say: (String) -> Unit = { if (quiet) AppLog.add("drive: $it") else setStatus(it) }
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveClient.SCOPE))).build()
        Identity.getAuthorizationClient(this).authorize(req)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    pendingDrive = then
                    try { startIntentSenderForResult(pi.intentSender, 2, null, 0, 0, 0) }
                    catch (e: Exception) { pendingDrive = null; say(tr("Google-inlogscherm niet te openen: ${e.message}", "Could not open the Google sign-in screen: ${e.message}")) }
                } else r.accessToken?.let(then) ?: say(tr("Google Drive gaf geen toegang", "Google Drive gave no access"))
            }
            .addOnFailureListener { AppLog.add("drive auth failed: $it"); say("Inloggen bij Google Drive mislukt: ${it.message}") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2) return
        val then = pendingDrive
        pendingDrive = null
        val token = try { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data).accessToken } catch (e: Exception) { null }
        if (token != null) then?.invoke(token) else setStatus(tr("Geen toegang tot Google Drive gekregen", "No access to Google Drive granted"))
    }

    private fun uploadToDrive(notes: List<Note>, quiet: Boolean = false) {
        if (notes.isEmpty()) return
        withDrive(quiet) { token ->
            setStatus(tr("Naar Google Drive…", "Uploading to Google Drive…"))
            val titles = notes.associate { it.notebookId to (books.get(it.notebookId)?.title?.ifBlank { tr("Naamloos", "Untitled") } ?: tr("Losse pagina's", "Loose pages")) }
            bg.execute {
                var ok = 0
                var err: Exception? = null
                for (n in notes) try {
                    val ids = DriveClient.uploadNote(token, n, titles.getValue(n.notebookId))
                    main.post { n.driveIds = ids; store.save(n) }
                    ok++
                } catch (e: Exception) { AppLog.add("drive upload failed: $e"); err = e }
                main.post { setStatus(err?.let { tr("Google Drive: ${pages(ok)} gelukt, fout: ${it.message}", "Google Drive: ${pages(ok)} done, error: ${it.message}") } ?: tr("${pages(ok)} in Google Drive (map Spark Ink)", "${pages(ok)} in Google Drive (folder Spark Ink)")) }
            }
        }
    }

    // ---- app update ------------------------------------------------------------------

    private fun checkForUpdate() {
        if (busy) return toast(tr("Even geduld, er loopt nog iets", "One moment, something is still running"))
        busy = true
        setStatus(tr("Zoeken naar een nieuwe versie…", "Looking for a new version…"))
        val token = prefs.githubToken
        bg.execute {
            val r = try { Result.success(Updater.latest(token)) } catch (e: Exception) { Result.failure<Updater.Release>(e) }
            main.post {
                busy = false
                r.onSuccess { rel ->
                    if (!Updater.isNewer(rel)) {
                        setStatus(tr("Je hebt de nieuwste versie (${BuildConfig.GIT_SHA})", "You have the latest version (${BuildConfig.GIT_SHA})"))
                    } else {
                        setStatus(tr("Nieuwe versie beschikbaar", "New version available"))
                        AlertDialog.Builder(this)
                            .setTitle(tr("Update beschikbaar", "Update available"))
                            .setMessage(tr("Nieuwe versie ${rel.sha} (${rel.size / 1_000_000} MB). Jouw versie: ${BuildConfig.GIT_SHA}.\n\nDownloaden en installeren? Je notities en instellingen blijven behouden.", "New version ${rel.sha} (${rel.size / 1_000_000} MB). Your version: ${BuildConfig.GIT_SHA}.\n\nDownload and install? Your notes and settings are kept."))
                            .setPositiveButton(tr("Installeren", "Install")) { _, _ -> downloadAndInstall(rel, token) }
                            .setNegativeButton(tr("Later", "Later"), null).show()
                    }
                }.onFailure { AppLog.add("update check failed: $it"); setStatus("Update zoeken mislukt: ${it.message}") }
            }
        }
    }

    private fun downloadAndInstall(rel: Updater.Release, token: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            toast(tr("Sta eerst toe dat Spark Ink apps mag installeren, en tik daarna opnieuw op Update", "First allow Spark Ink to install apps, then tap Update again"))
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
                    if (now - lastShown > 500) { lastShown = now; main.post { setStatus(tr("Downloaden… ${done / 1_000_000} van ${rel.size / 1_000_000} MB", "Downloading… ${done / 1_000_000} of ${rel.size / 1_000_000} MB")) } }
                }
                Result.success(Unit)
            } catch (e: Exception) { Result.failure<Unit>(e) }
            main.post {
                busy = false
                r.onSuccess {
                    setStatus(tr("Gedownload, installatie starten…", "Downloaded, starting installation…"))
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

        val language = section(tr("Taal", "Language"))
        note(language, tr("Taal van de app en van je handschrift (ML Kit). Claude herkent de taal van je handschrift zelf.",
            "Language of the app and of your handwriting (ML Kit). Claude detects the handwriting language by itself."))
        val langs = listOf("nl" to "Nederlands", "en" to "English")
        language.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            langs.forEachIndexed { i, (code, name) ->
                addView(RadioButton(context).apply { id = 200 + i; text = name; isChecked = prefs.language == code })
            }
            setOnCheckedChangeListener { _, id ->
                saveSettings?.invoke()
                prefs.language = langs[(id - 200).coerceIn(0, 1)].first
                Lang.en = prefs.language == "en"
                showSettings()
            }
        })

        val folio = section("Folio")
        folio.addView(label(if (pad.isPaired) tr("Gekoppeld met ${prefs.padName ?: "Folio"}", "Paired with ${prefs.padName ?: "Folio"}") else tr("Nog geen Folio gekoppeld", "No Folio paired yet"), 15f, Ui.INK, bold = true))
        note(folio, tr("Koppelen: houd de knop 6 seconden ingedrukt tot het lampje blauw knippert. Knippert het blauw-oranje, dan is het geheugen vol: kies dan Wissen + resetten (alle pagina's op de Folio gaan verloren).", "Pairing: hold the button for 6 seconds until the light blinks blue. If it blinks blue and orange, the memory is full: choose Erase + reset (all pages on the Folio are lost)."))
        if (prefs.padBattery >= 0) note(folio, tr("Batterij ${prefs.padBattery}% (bij de laatste verbinding)", "Battery ${prefs.padBattery}% (at the last connection)"))
        folio.addView(rowOf(
            pill(tr("Batterij", "Battery"), R.drawable.ic_bluetooth) { padCommand { pad.battery({ setStatus(it) }) { r ->
                busy = false
                r.onSuccess { (pct, charging) -> setStatus(tr("Batterij $pct%", "Battery $pct%") + if (charging) tr(", laadt op", ", charging") else "") }
                    .onFailure { setStatus(tr("Batterij uitlezen mislukt: ${it.message}", "Reading the battery failed: ${it.message}")) }
            } } },
            pill(tr("Naam", "Name"), R.drawable.ic_edit) {
                askName(tr("Naam van de Folio", "Name of the Folio"), prefs.padName ?: "", tr("Bijv. Folio van Jeroen", "E.g. Jeroen's Folio")) { name ->
                    if (name.isNotBlank()) padCommand { pad.rename(name, { setStatus(it) }) { r ->
                        busy = false
                        r.onSuccess { setStatus(tr("Folio heet nu $it (zichtbaar na herstart van de Folio)", "The Folio is now called $it (visible after restarting the Folio)")) }
                            .onFailure { setStatus(tr("Naam wijzigen mislukt: ${it.message}", "Renaming failed: ${it.message}")) }
                    } }
                }
            },
        ))
        folio.addView(rowOf(
            pill(tr("Koppelen", "Pair"), R.drawable.ic_bluetooth) { startPairing() },
            pill(tr("Wissen + resetten", "Erase + reset"), R.drawable.ic_delete, Ui.DANGER) {
                withBlePermissions {
                    setStatus(tr("Houd de knop van de Folio ingedrukt tot hij in de lijst verschijnt en kies hem. Alle pagina's op de Folio worden gewist.", "Hold the Folio button until it appears in the list and pick it. All pages on the Folio will be erased."))
                    pickDevice(::finishWipe)
                }
            },
        ))

        val inkBox = section(tr("Inkt", "Ink"))
        note(inkBox, tr("Kleur en dikte van de lijnen op het scherm en in exports. De tekstherkenning gebruikt altijd zwart.", "Colour and width of the lines on screen and in exports. Text recognition always uses black."))
        val inks = listOf(0xFF000000.toInt(), 0xFF1F4FB5.toInt(), 0xFFB3261E.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt())
        inkBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(8))
            for (c in inks) addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(c)
                    setStroke(dp(3), if (c == prefs.inkColor) Ui.ACCENT else Ui.LINE)
                }
                contentDescription = tr("Inktkleur", "Ink colour")
                setOnClickListener { saveSettings?.invoke(); prefs.inkColor = c; thumbs.evictAll(); showSettings() }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        })
        val widths = listOf(0.25f, 0.35f, 0.6f)
        inkBox.addView(segmented(listOf(tr("Dun", "Thin"), tr("Normaal", "Normal"), tr("Dik", "Thick")), widths.indexOf(prefs.inkWidth).coerceAtLeast(0)) { i ->
            saveSettings?.invoke(); prefs.inkWidth = widths[i]; thumbs.evictAll(); showSettings()
        }, LinearLayout.LayoutParams(WRAP, WRAP))

        val ocr = section(tr("Tekstherkenning", "Text recognition"))
        note(ocr, tr("Automatisch omzetten naar tekst na synchroniseren", "Convert to text automatically after syncing"))
        val group = RadioGroup(this)
        val opts = listOf("off" to tr("Uit", "Off"), "mlkit" to tr("ML Kit (op het toestel, gratis)", "ML Kit (on the phone, free)"), "claude" to tr("Claude (beter, API-key nodig)", "Claude (better, needs an API key)"))
        opts.forEachIndexed { i, (key, text) ->
            group.addView(RadioButton(this).apply { id = 100 + i; this.text = text; isChecked = prefs.autoOcr == key })
        }
        ocr.addView(group)
        val syncAuto = CheckBox(this).apply {
            text = tr("Automatisch synchroniseren zodra je op de knop van de Folio drukt (terwijl de app open is)",
                "Sync automatically as soon as you press the Folio button (while the app is open)")
            isChecked = prefs.autoSync
        }
        ocr.addView(syncAuto)
        val sAuto = CheckBox(this).apply { text = tr("Na synchroniseren vormen strak maken (lijnen, rechthoeken, cirkels, driehoeken)", "Draw shapes clean after syncing (lines, rectangles, circles, triangles)"); isChecked = prefs.autoShapes }
        ocr.addView(sAuto)
        val key = field(ocr, tr("Claude API-key (blijft alleen op dit toestel)", "Claude API key (stays on this phone only)"), prefs.apiKey, secret = true)
        val model = field(ocr, "Claude-model", prefs.model)

        val notion = section("Notion")
        note(notion, tr("Maak een integratie op notion.so/my-integrations en deel je database of pagina ermee via ••• > Verbindingen.", "Create an integration at notion.so/my-integrations and share your database or page with it via ••• > Connections."))
        val nToken = field(notion, tr("Notion-token (secret_… of ntn_…)", "Notion token (secret_… or ntn_…)"), prefs.notionToken, secret = true)
        val nTarget = field(notion, tr("Notion database of pagina (link of ID)", "Notion database or page (link or ID)"), prefs.notionTarget)
        val nAuto = CheckBox(this).apply { text = tr("Na tekstherkenning automatisch naar Notion", "Send to Notion automatically after text recognition"); isChecked = prefs.notionAuto }
        notion.addView(nAuto)

        val drive = section("Google Drive")
        note(drive, tr("Pagina's komen als afbeelding en tekst in de map Spark Ink in je Drive, met een submap per notitieblok. De app ziet alleen de bestanden die hij zelf maakt.", "Pages go into the Spark Ink folder in your Drive as image and text, with a subfolder per notebook. The app only sees the files it creates itself."))
        val dAuto = CheckBox(this).apply { text = tr("Na synchroniseren automatisch naar Google Drive", "Upload to Google Drive automatically after syncing"); isChecked = prefs.driveAuto }
        drive.addView(dAuto)
        drive.addView(rowOf(pill(tr("Verbinden", "Connect"), R.drawable.ic_cloud) {
            withDrive { token ->
                setStatus(tr("Google Drive controleren…", "Checking Google Drive…"))
                bg.execute {
                    val r = try { DriveClient.folder(token, "root", "Spark Ink", null); tr("Verbonden met Google Drive", "Connected to Google Drive") } catch (e: Exception) { tr("Google Drive mislukt: ${e.message}", "Google Drive failed: ${e.message}") }
                    main.post { setStatus(r) }
                }
            }
        }))

        val app = section("App")
        note(app, tr("Versie ${BuildConfig.GIT_SHA}. Updates hebben een GitHub-token met alleen leesrechten (Contents: Read) voor Jeroentjj/Spark-Ink nodig, omdat de repo privé is.", "Version ${BuildConfig.GIT_SHA}. Updates need a read-only GitHub token (Contents: Read) for Jeroentjj/Spark-Ink, because the repo is private."))
        val ghToken = field(app, tr("GitHub-token (alleen voor updates)", "GitHub token (only for updates)"), prefs.githubToken, secret = true)
        app.addView(rowOf(
            pill(tr("Update zoeken", "Check for update"), R.drawable.ic_download) { saveSettings?.invoke(); checkForUpdate() },
            pill("Log", R.drawable.ic_list) { showLog() },
        ))

        saveSettings = {
            prefs.autoOcr = opts[(group.checkedRadioButtonId - 100).coerceIn(0, 2)].first
            prefs.apiKey = key.text.toString()
            prefs.model = model.text.toString().ifBlank { "claude-sonnet-5-5" }
            prefs.githubToken = ghToken.text.toString()
            prefs.notionToken = nToken.text.toString()
            prefs.notionTarget = nTarget.text.toString()
            prefs.notionAuto = nAuto.isChecked
            prefs.driveAuto = dAuto.isChecked
            prefs.autoShapes = sAuto.isChecked
            prefs.autoSync = syncAuto.isChecked
        }
        scaffold(header(label(tr("Instellingen", "Settings"), 24f, Ui.INK, bold = true), back = true), ScrollView(this).apply { addView(col) }, emptyList())
    }

    private fun showLog() {
        val tv = TextView(this).apply { text = AppLog.text().ifBlank { "(leeg)" }; textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("Log").setView(ScrollView(this).apply { addView(tv); setPadding(dp(12), 0, dp(12), 0) })
            .setPositiveButton(tr("Delen", "Share")) { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, AppLog.text())
                }, null))
            }
            .setNegativeButton(tr("Sluiten", "Close"), null).show()
    }
}

/** Vertical swipes over the action bar open (up) or close (down) it; taps and sideways scrolling pass through. */
private class SwipeBar(ctx: Context, private val onSwipe: (up: Boolean) -> Unit) : LinearLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2
    private var x0 = 0f
    private var y0 = 0f
    private var fired = false

    private fun track(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; fired = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - y0
                if (!fired && abs(dy) > slop && abs(dy) > abs(e.x - x0)) { fired = true; onSwipe(dy < 0); return true }
            }
        }
        return false
    }

    override fun onInterceptTouchEvent(e: MotionEvent) = track(e)

    override fun onTouchEvent(e: MotionEvent): Boolean { track(e); return true }
}

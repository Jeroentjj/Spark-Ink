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
import org.json.JSONArray
import android.view.DragEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.Spinner
import android.graphics.Paint
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import android.widget.PopupMenu
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
    private val thumbs = object : LruCache<String, Bitmap>(48 * 1024) { // KB
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    // Navigation: a page ([current]) sits inside a notebook ([openBook], "" = loose pages, null = home).
    private var current: Note? = null
    private var openBook: String? = null
    private var inSettings = false
    private var showText = false
    private var sheetOpen = false
    private var saveSettings: (() -> Unit)? = null
    private var inLive = false
    private var inTodos = false
    private var inArchive = false
    private var listSave: Runnable? = null // a list item typed but not saved yet
    private val doneLog by lazy { DoneLog(this) }
    private var showList = false
    private var selected: ArrayList<String>? = null // pages picked in the open notebook, in the order tapped
    private var dragMode = false // pages show a handle to drag them to another place or chapter
    private var bookScroll: ScrollView? = null // keeps the place in a notebook when it is redrawn
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
    private class ListTask(var text: String, var done: Boolean)
    private class ItemDrag(val list: String, val index: Int)

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

    /** Full screen: status bar hidden (a swipe from the top shows it for a moment); the navigation bar stays. */
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
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
            if (busy || inLive || inSettings || selected != null || dragMode || System.currentTimeMillis() - lastAutoSync < 20_000) return@watch
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
            inArchive -> showTodos()
            inTodos && current == null -> { inTodos = false; showHome() }
            current == null && (selected != null || dragMode) -> { selected = null; dragMode = false; showBook(book ?: "") }
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
            inArchive -> showListArchive()
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
        views.forEach { addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
    }

    /** A rounded, tinted button with an icon, for use inside screens (settings). */
    private fun pill(text: String, iconRes: Int, color: Int = Ui.ACCENT, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = rounded(if (color == Ui.ACCENT) Ui.ACCENT_SOFT else Ui.DANGER_SOFT, 22)
        setPadding(dp(12), 0, dp(12), 0)
        minimumHeight = dp(44)
        addView(icon(iconRes, color, 18))
        addView(label(text, 14f, color, bold = true).apply { setPadding(dp(8), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
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
     * Every screen: header, status line, body, and the action bar at the bottom. Its label row always
     * shows; the buttons slide up under it, below a divider.
     */
    private fun scaffold(header: View, body: View, actions: List<Action>, barLabel: String = tr("Acties", "Actions")) {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), if (actions.isEmpty()) dp(16) else dp(52))
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
        if (actions.isNotEmpty()) root.addView(sheet(actions, barLabel), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)
    }

    /** Google Photos style: tap the label row or swipe to slide the buttons up or down; the label stays. */
    private fun sheet(actions: List<Action>, barLabel: String): View {
        val r = dp(22).toFloat()
        val chevron = icon(R.drawable.ic_expand_less, Ui.MUTED, 22)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(16), 0)
            contentDescription = tr("Knoppen tonen", "Show buttons")
            addView(label(barLabel, 14f, Ui.INK, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(chevron)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(8), dp(2), dp(10))
            for (a in actions) addView(actionButton(a), LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        lateinit var bar: SwipeBar
        fun place(open: Boolean, animate: Boolean) {
            sheetOpen = open
            val y = if (open) 0f else (bar.height - head.height).toFloat()
            val ms = if (animate) 220L else 0L
            chevron.animate().rotation(if (open) 180f else 0f).setDuration(ms).start()
            if (animate) bar.animate().translationY(y).setDuration(ms).setInterpolator(DecelerateInterpolator()).start() else bar.translationY = y
        }
        bar = SwipeBar(this) { up -> if (up != sheetOpen) place(up, true) }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Ui.SURFACE); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            elevation = dp(12).toFloat()
            addView(head, LinearLayout.LayoutParams(MATCH, dp(48)))
            addView(View(context).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(MATCH, dp(1)).apply { setMargins(dp(16), 0, dp(16), 0) })
            addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        head.setOnClickListener { place(!sheetOpen, true) }
        // place the bar before its first frame, so a closed bar never flashes open
        bar.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                bar.viewTreeObserver.removeOnPreDrawListener(this)
                place(sheetOpen, false)
                return true
            }
        })
        return bar
    }

    private fun actionButton(a: Action) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
        val danger = a.icon == R.drawable.ic_delete
        addView(FrameLayout(context).apply {
            background = rounded(if (danger) Ui.DANGER_SOFT else Ui.ACCENT_SOFT, 20)
            addView(icon(a.icon, if (danger) Ui.DANGER else Ui.ACCENT, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(label(a.label, 11f, Ui.INK).apply {
            gravity = Gravity.CENTER; setPadding(dp(1), dp(4), dp(1), 0)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        ripple(this)
        setOnClickListener { a.run() }
    }

    private fun setStatus(s: String) {
        statusText = s
        status?.text = s
        status?.visibility = if (s.isBlank()) View.GONE else View.VISIBLE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** A message that goes away by itself (sync results and errors), instead of a status line that stays. */
    private fun popup(s: String) { setStatus(""); toast(s) }

    private fun pages(n: Int) = if (n == 1) tr("1 pagina", "1 page") else tr("$n pagina's", "$n pages")

    private fun dateOf(n: Note) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(n.timestamp * 1000))

    private fun loadThumb(n: Note, into: ImageView, edge: Int = 480) {
        val key = "${n.id}_${n.rotation}_${n.strokes.size}_${n.tidyOn && n.tidy.isNotEmpty()}_$edge"
        thumbs.get(key)?.let { into.setImageBitmap(it); return }
        into.tag = key
        thumbBg.execute {
            val b = Render.bitmap(n.sketch(), edge, cropToInk = false, ink = prefs.inkColor, widthMm = prefs.inkWidth)
            main.post { thumbs.put(key, b); if (into.tag == key) into.setImageBitmap(b) }
        }
    }

    // ---- home: the notebooks -------------------------------------------------------------

    private fun showHome() {
        saveEditor()
        current = null; editor = null; openBook = null; inSettings = false; inTodos = false; inArchive = false; saveSettings = null
        selected = null; dragMode = false
        val notes = store.all()
        val content = FrameLayout(this)
        fun fill() {
            content.removeAllViews()
            val q = query.trim()
            content.addView(if (q.isEmpty()) {
                val cells = ArrayList<View>()
                cells.add(looseCard(notes.filter { it.notebookId.isEmpty() }))
                cells.add(todoCard(notes))
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
        val badge = BatteryBadge(this, prefs.padBattery).apply {
            contentDescription = tr("Batterij van de Folio", "Folio battery")
            setOnClickListener {
                toast(if (prefs.padBattery >= 0) tr("Folio-batterij ${prefs.padBattery}% (bij de laatste verbinding)", "Folio battery ${prefs.padBattery}% (at the last connection)")
                      else tr("Batterij nog onbekend: synchroniseer een keer", "Battery not known yet: sync once"))
            }
        }
        val menu = icon(R.drawable.ic_menu, Ui.INK).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = tr("Instellingen", "Settings")
            ripple(this)
            setOnClickListener { showSettings() }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(search, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(10), 0, dp(6), 0) })
            addView(menu, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        scaffold(top, body, listOf(
            Action(tr("Synchroniseer", "Sync"), R.drawable.ic_sync) { startSync("") },
            Action("Live", R.drawable.ic_edit) { showLive() },
            Action(tr("Notitieblok", "Notebook"), R.drawable.ic_add) { newBook() },
        ), "Spark Ink")
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
        val keepY = if (openBook == id) bookScroll?.scrollY ?: 0 else 0
        current = null; editor = null; openBook = id; inSettings = false; inTodos = false; saveSettings = null
        val book = books.get(id)
        val notes = store.all().filter { it.notebookId == (book?.id ?: "") }.sortedBy { it.sortKey }
        val sel = selected
        val big = prefs.pageView == "list"
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
        } else {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            // pages without a chapter first, then the chapters in the order of their first page
            val chapters = notes.map { it.chapter }.distinct().sortedBy { it.isNotEmpty() }
            for (ch in chapters) {
                val inCh = notes.filter { it.chapter == ch }
                if (chapters.size > 1 || ch.isNotEmpty()) col.addView(chapterHeader(ch, inCh.size, notes))
                col.addView(grid(inCh.map { pageCard(it, notes, big) }, if (big) 1 else 2))
            }
            ScrollView(this).apply { addView(col); bookScroll = this; post { scrollTo(0, keepY) } }
        }

        if (sel != null) return scaffold(header(titleCol, back = true), body, selectionActions(sel, notes, book),
            tr("${sel.size} geselecteerd", "${sel.size} selected"))
        if (dragMode) return scaffold(header(titleCol, back = true), body, listOf(
            Action(tr("Klaar", "Done"), R.drawable.ic_list) { dragMode = false; showBook(id) },
        ), tr("Sleep een pagina aan ⠿ naar een plek of hoofdstuk", "Drag a page by ⠿ to a place or chapter"))

        val actions = arrayListOf(
            Action(tr("Synchroniseer", "Sync"), R.drawable.ic_sync) { startSync(book?.id ?: "") },
            Action(tr("Deel", "Share"), R.drawable.ic_share) { exportBook(book?.title?.ifBlank { null } ?: tr("Losse pagina's", "Loose pages"), notes) },
        )
        if (book != null) {
            actions.add(Action(tr("Verwijder", "Delete"), R.drawable.ic_delete) {
                AlertDialog.Builder(this).setMessage(tr("Notitieblok verwijderen? De pagina's blijven bewaard bij de losse pagina's.", "Delete this notebook? Its pages are kept under loose pages."))
                    .setPositiveButton(tr("Verwijderen", "Delete")) { _, _ ->
                        notes.forEach { it.notebookId = ""; it.chapter = ""; store.save(it) }
                        books.delete(book.id)
                        showHome()
                    }
                    .setNegativeButton(tr("Annuleren", "Cancel"), null).show()
            })
        }
        val trailing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(if (big) R.drawable.ic_grid else R.drawable.ic_list, Ui.INK).apply {
                setPadding(dp(6), dp(6), dp(6), dp(6))
                contentDescription = if (big) tr("Raster", "Grid") else tr("Lijst", "List")
                ripple(this)
                setOnClickListener { prefs.pageView = if (big) "grid" else "list"; showBook(id) }
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            if (book != null) addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(book.color); setStroke(dp(2), Ui.LINE) }
                contentDescription = tr("Kaftkleur", "Cover colour")
                setOnClickListener { pickColor(book.color) { c -> book.color = c; books.save(); showBook(id) } }
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(8) })
        }
        scaffold(header(titleCol, back = true, trailing = trailing), body, actions,
            book?.title?.ifBlank { null } ?: tr("Losse pagina's", "Loose pages"))
    }

    /** The buttons while pages are selected; [sel] is the order they were tapped in (the first is the base of a merge). */
    private fun selectionActions(sel: ArrayList<String>, notes: List<Note>, book: Notebook?): List<Action> {
        val chosen = sel.mapNotNull { id -> notes.find { it.id == id } }
        val id = book?.id ?: ""
        fun done(msg: String) { selected = null; if (msg.isNotEmpty()) popup(msg); showBook(id) }
        val list = arrayListOf(
            Action(tr("Voeg samen", "Merge"), R.drawable.ic_merge) {
                if (chosen.size < 2) return@Action toast(tr("Kies minstens twee pagina's", "Pick at least two pages"))
                // the others live on inside the first; keep their raw pad data
                val merged = chosen.drop(1).fold(chosen[0]) { acc, o -> mergeNotes(acc, o) }
                store.save(merged)
                chosen.drop(1).forEach { store.delete(it.id, keepRaw = true) }
                thumbs.evictAll()
                done(tr("${chosen.size} pagina's samengevoegd", "${chosen.size} pages merged"))
            },
            Action(tr("Hoofdstuk", "Chapter"), R.drawable.ic_folder) { chapterDialog(chosen, notes) { done("") } },
            Action(tr("Verplaats", "Move"), R.drawable.ic_book) { moveDialog(chosen) { done(tr("Verplaatst", "Moved")) } },
            Action(tr("Orden", "Order"), R.drawable.ic_list) {
                // the picked pages, in the order tapped, go where the first of them is now
                val rest = notes.filter { it.id !in sel }
                val at = notes.indexOfFirst { it.id in sel }.coerceIn(0, rest.size)
                applyOrder(rest.take(at) + chosen + rest.drop(at))
                done(tr("Volgorde opgeslagen", "Order saved"))
            },
            Action(tr("Slepen", "Drag"), R.drawable.ic_drag) { selected = null; dragMode = true; showBook(id) },
            Action(tr("Deel", "Share"), R.drawable.ic_share) { exportBook(book?.title?.ifBlank { null } ?: "Spark Ink", chosen) },
            Action(tr("Wis", "Delete"), R.drawable.ic_delete) {
                AlertDialog.Builder(this).setMessage(tr("${pages(chosen.size)} definitief verwijderen?", "Delete ${pages(chosen.size)} for good?"))
                    .setPositiveButton(tr("Verwijderen", "Delete")) { _, _ ->
                        chosen.forEach { store.delete(it.id); if (prefs.appendTo == it.id) prefs.appendTo = null }
                        done("")
                    }
                    .setNegativeButton(tr("Annuleren", "Cancel"), null).show()
            },
        )
        chosen.singleOrNull()?.takeIf { it.mergedFrom.isNotBlank() }?.let { m ->
            list.add(1, Action(tr("Splits", "Split"), R.drawable.ic_merge) { unmerge(m); selected = null; showBook(id) })
        }
        return list
    }

    private fun chapterHeader(ch: String, count: Int, all: List<Note>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(16), dp(8), dp(4))
        addView(icon(R.drawable.ic_folder, Ui.ACCENT, 18))
        addView(label(ch.ifEmpty { tr("Zonder hoofdstuk", "No chapter") }, 16f, Ui.INK, bold = true).apply { setPadding(dp(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(label(pages(count), 12f, Ui.MUTED))
        if (ch.isNotEmpty()) setOnClickListener {
            askName(tr("Hoofdstuk hernoemen", "Rename chapter"), ch, tr("Bijv. Vergaderingen", "E.g. Meetings")) { name ->
                all.filter { it.chapter == ch }.forEach { it.chapter = name; store.save(it) }
                showBook(openBook ?: "")
            }
        }
        if (dragMode) dropTarget(this) { from -> movePage(from, null, ch, all) }
    }

    private fun chapterDialog(chosen: List<Note>, all: List<Note>, done: () -> Unit) {
        val existing = all.map { it.chapter }.filter { it.isNotEmpty() }.distinct()
        val labels = existing + tr("+ Nieuw hoofdstuk…", "+ New chapter…") + tr("Geen hoofdstuk", "No chapter")
        fun put(ch: String) { chosen.forEach { it.chapter = ch; store.save(it) }; done() }
        AlertDialog.Builder(this).setTitle(tr("Hoofdstuk", "Chapter")).setItems(labels.toTypedArray()) { _, i ->
            when {
                i < existing.size -> put(existing[i])
                i == existing.size -> askName(tr("Nieuw hoofdstuk", "New chapter"), "", tr("Bijv. Vergaderingen, Ideeën", "E.g. Meetings, Ideas")) { put(it) }
                else -> put("")
            }
        }.show()
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

    /**
     * [inBook]: the pages of the open notebook, in order; [big]: one page per row, as large as fits.
     * In a notebook a long press selects the page; in drag mode the ⠿ handle drags it.
     */
    private fun pageCard(n: Note, inBook: List<Note>? = null, big: Boolean = false) = FrameLayout(this).apply {
        val card = this
        val sel = selected
        val picked = sel?.indexOf(n.id) ?: -1
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Ui.SURFACE, 12, if (picked >= 0) Ui.ACCENT else null)
            elevation = dp(2).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(10))
            val img = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = big }
            if (big) {
                img.minimumHeight = dp(300)
                addView(img, LinearLayout.LayoutParams(MATCH, WRAP))
                loadThumb(n, img, 1100)
            } else {
                addView(img, LinearLayout.LayoutParams(MATCH, dp(210)))
                loadThumb(n, img)
            }
            addView(label(dateOf(n), 12f, Ui.MUTED).apply { setPadding(dp(2), dp(6), 0, 0) })
            n.text.lineSequence().firstOrNull { it.isNotBlank() }?.let {
                addView(label(it, 13f, Ui.INK).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), 0, 0, 0) })
            }
            if (n.tags.isNotBlank()) addView(label(tagLine(n), 12f, Ui.ACCENT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), dp(2), 0, 0) })
            ripple(this)
        }
        addView(box, FrameLayout.LayoutParams(MATCH, WRAP))
        // selection order, top left
        if (sel != null) addView(label(if (picked >= 0) "${picked + 1}" else "", 13f, Ui.BG, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (picked >= 0) Ui.ACCENT else 0x66000000)
                setStroke(dp(2), if (picked >= 0) Ui.ACCENT else Color.WHITE)
            }
            elevation = dp(4).toFloat()
        }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), 0, 0) })
        when {
            sel != null -> box.setOnClickListener {
                if (picked >= 0) sel.remove(n.id) else sel.add(n.id)
                if (sel.isEmpty()) selected = null
                showBook(openBook ?: "")
            }
            inBook != null && dragMode -> {
                box.setOnClickListener { openNote(n) }
                addView(icon(R.drawable.ic_drag, Ui.INK, 22).apply {
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT_SOFT) }
                    elevation = dp(4).toFloat()
                    contentDescription = tr("Slepen", "Drag")
                    // the handle starts the drag at once, the rest of the card still scrolls
                    setOnTouchListener { _, e ->
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) startDragAndDrop(ClipData.newPlainText("spark", n.id), View.DragShadowBuilder(card), n.id, 0)
                        true
                    }
                }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
                dropTarget(this) { from -> movePage(from, n.id, n.chapter, inBook) }
            }
            inBook != null -> {
                box.setOnClickListener { openNote(n) }
                box.setOnLongClickListener { selected = arrayListOf(n.id); sheetOpen = true; showBook(openBook ?: ""); true }
            }
            else -> {
                box.setOnClickListener { openNote(n) }
                box.setOnLongClickListener { moveDialog(listOf(n)) { refresh() }; true }
            }
        }
    }

    /**
     * Put page [from] in [chapter]: next to page [target] (after it when moved down, before it when
     * moved up), or at the end of the chapter when dropped on its heading.
     * shortcut: no auto-scroll while dragging; drop on a chapter heading or scroll first if the list is long.
     */
    private fun movePage(from: String, target: String?, chapter: String, all: List<Note>) {
        if (from == target) return
        val list = all.toMutableList()
        val moved = list.firstOrNull { it.id == from } ?: return
        val down = target != null && list.indexOf(moved) < list.indexOfFirst { it.id == target }
        list.remove(moved)
        moved.chapter = chapter
        val at = if (target == null) list.indexOfLast { it.chapter == chapter } + 1
                 else list.indexOfFirst { it.id == target } + if (down) 1 else 0
        list.add(if (target == null && at <= 0) list.size else at, moved)
        applyOrder(list)
        showBook(openBook ?: "")
    }

    /** Store [notes] in this order; pages synced later still come first (see [Note.sortKey]). */
    private fun applyOrder(notes: List<Note>) {
        if (notes.isEmpty()) return
        val base = notes.minOf { it.sortKey }
        notes.forEachIndexed { i, n -> n.order = base + i; store.save(n) }
    }

    /** Long press starts dragging [v]; dropping it on another card calls [onDrop] (dragged key, target key), on itself [onSelf]. */
    private fun draggable(v: View, key: String, onDrop: (String, String) -> Unit, onSelf: () -> Unit) {
        v.setOnLongClickListener {
            it.startDragAndDrop(ClipData.newPlainText("spark", key), View.DragShadowBuilder(it), key, 0)
            true
        }
        dropTarget(v) { from -> if (from == key) onSelf() else onDrop(from, key) }
    }

    /** [v] accepts a dragged card; [onDrop] gets the dragged key once the drag has finished. */
    private fun dropTarget(v: View, onDrop: (String) -> Unit) = v.setOnDragListener { view, e ->
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> e.localState is String
            DragEvent.ACTION_DRAG_ENTERED -> { view.alpha = 0.5f; true }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { view.alpha = 1f; true }
            DragEvent.ACTION_DROP -> {
                view.alpha = 1f
                val from = e.localState as String
                // post: the drag must finish before the screen is rebuilt
                main.post { onDrop(from) }
                true
            }
            else -> true
        }
    }

    private fun moveDialog(ns: List<Note>, done: () -> Unit) {
        val list = books.all()
        val labels = listOf(tr("Losse pagina's", "Loose pages")) + list.map { it.title.ifBlank { tr("Naamloos", "Untitled") } } + tr("+ Nieuw notitieblok…", "+ New notebook…")
        // chapters belong to a notebook: a moved page starts without one
        fun put(bookId: String) { ns.forEach { it.notebookId = bookId; it.chapter = ""; store.save(it) }; done() }
        AlertDialog.Builder(this).setTitle(tr("Verplaats naar", "Move to")).setItems(labels.toTypedArray()) { _, i ->
            when {
                i == 0 -> put("")
                i <= list.size -> put(list[i - 1].id)
                else -> askName(tr("Nieuw notitieblok", "New notebook"), "") { put(books.create(it).id) }
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
        val todo = isList(n)
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
                addView(Checklist(n, withAdd = true).view)
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
        ).let { list ->
            if (n.mergedFrom.isBlank()) list else list + Action(tr("Splits", "Split"), R.drawable.ic_merge) {
                AlertDialog.Builder(this).setMessage(tr("Samenvoegen ongedaan maken? Wat je daarna aan deze pagina veranderde, gaat verloren.",
                    "Undo the merge? Changes made to this page since then are lost."))
                    .setPositiveButton(tr("Splits", "Split")) { _, _ -> unmerge(n); current = null; editor = null; showBook(n.notebookId) }
                    .setNegativeButton(tr("Annuleren", "Cancel"), null).show()
            }
        } + listOf(
            Action(tr("Wis", "Delete"), R.drawable.ic_delete) {
                AlertDialog.Builder(this).setMessage(tr("Deze pagina definitief verwijderen?", "Delete this page for good?"))
                    .setPositiveButton(tr("Verwijderen", "Delete")) { _, _ -> store.delete(n.id); current = null; editor = null; showBook(n.notebookId) }
                    .setNegativeButton(tr("Annuleren", "Cancel"), null).show()
            },
        ))
    }

    private fun saveEditor() {
        listSave?.let { main.removeCallbacks(it); it.run() }
        listSave = null
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
        showList = isList(n)
        showText = showList
        showNote(n)
    }

    private fun strike(v: TextView, on: Boolean) {
        v.paintFlags = if (on) v.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else v.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        v.alpha = if (on) 0.55f else 1f
    }

    /**
     * The tasks of [n], edited in place: Enter adds an item below (or splits at the cursor), Enter or
     * Backspace on an empty item removes it, swipe left deletes with undo, the handle drags. Ticked
     * items fold away under "Voltooid".
     */
    private inner class Checklist(val n: Note, val withAdd: Boolean) {
        private val rows = Todo.items(n.text).map { ListTask(it.text, it.done) }.toMutableList()
        private val edits = HashMap<Int, EditText>()
        private var addEdit: EditText? = null
        private var rendering = false
        private val imm = getSystemService(InputMethodManager::class.java)
        private val save = Runnable {
            n.text = Todo.withItems(n.text, rows.map { it.text to it.done })
            n.tags = Todo.withTag(n.tags, n.text)
            store.save(n)
        }
        val view = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }

        init { render(null) }

        private fun commit() { main.removeCallbacks(save); save.run() }

        private fun snapshot() = rows.map { ListTask(it.text, it.done) }

        private fun restore(before: List<ListTask>) {
            rows.clear(); rows.addAll(before); commit()
            if (view.isAttachedToWindow) render(null) else refresh()
        }

        private fun focused() = edits.entries.firstOrNull { it.value.hasFocus() }?.let { it.key to it.value.selectionStart }

        /** Rebuild the rows and put the cursor in row [focus] at [cursor] (-1 = end); [ADD] = the new-item field, null = none. */
        fun render(focus: Int? = focused()?.first, cursor: Int = focused()?.second ?: -1) {
            rendering = true
            view.removeAllViews(); edits.clear(); addEdit = null
            for (i in rows.indices) if (!rows[i].done) view.addView(row(i))
            if (withAdd) view.addView(addField())
            val done = rows.indices.filter { rows[it].done }
            if (done.isNotEmpty()) {
                view.addView(doneHeader(done.size))
                if (n.doneOpen) for (i in done) view.addView(row(i))
            }
            rendering = false
            val target = if (focus == ADD) addEdit else focus?.let { edits[it] }
            if (target == null) imm.hideSoftInputFromWindow(view.windowToken, 0)
            else target.post {
                target.requestFocus()
                target.setSelection(if (cursor < 0 || cursor > target.length()) target.length() else cursor)
                imm.showSoftInput(target, 0)
            }
        }

        private fun input(e: EditText) = e.apply {
            background = null
            textSize = 16f
            setTextColor(Ui.INK); setHintTextColor(Ui.MUTED)
            // one-line input so Enter is an action, but long items still wrap
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            maxLines = 20
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        private fun row(i: Int): View {
            val t = rows[i]
            val swipe = SwipeRow(this@MainActivity) { delete(i) }.apply { background = rounded(Ui.DANGER_SOFT, 8) }
            val edit = input(EditText(this@MainActivity)).apply {
                setText(t.text)
                strike(this, t.done)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        t.text = s.toString(); listSave = save
                        main.removeCallbacks(save); main.postDelayed(save, 600)
                    }
                })
                setOnEditorActionListener { _, _, _ -> enter(i, selectionStart); true }
                setOnKeyListener { _, code, e ->
                    if (code == KeyEvent.KEYCODE_DEL && e.action == KeyEvent.ACTION_DOWN && length() == 0) { removeEmpty(i); true } else false
                }
                setOnFocusChangeListener { _, has ->
                    // an item left empty disappears
                    if (!has && !rendering && t.text.isBlank() && rows.getOrNull(i) === t) {
                        rows.removeAt(i); commit()
                        view.post { val f = focused(); render(f?.first?.let { if (it > i) it - 1 else it }, f?.second ?: -1) }
                    }
                }
            }
            edits[i] = edit
            val content = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Ui.SURFACE)
                addView(CheckBox(context).apply {
                    buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
                    isChecked = t.done
                    setOnCheckedChangeListener { _, on ->
                        t.done = on; commit()
                        if (on) doneLog.add(n.id, t.text)
                        strike(edit, on)
                        // stays struck through for a moment, then folds away
                        main.postDelayed({ if (view.isAttachedToWindow) render() }, 1200)
                    }
                })
                addView(edit, LinearLayout.LayoutParams(0, WRAP, 1f))
                if (!t.done) addView(icon(R.drawable.ic_drag, Ui.MUTED, 22).apply {
                    contentDescription = tr("Verslepen", "Drag")
                    setPadding(dp(8), dp(8), dp(4), dp(8))
                    setOnTouchListener { _, e ->
                        if (e.actionMasked == MotionEvent.ACTION_DOWN)
                            swipe.startDragAndDrop(ClipData.newPlainText("spark", t.text), View.DragShadowBuilder(swipe), ItemDrag(n.id, i), 0)
                        true
                    }
                }, LinearLayout.LayoutParams(dp(34), dp(38)))
            }
            swipe.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
            swipe.setOnDragListener { v, e ->
                val d = e.localState as? ItemDrag
                when {
                    d == null || d.list != n.id -> false
                    e.action == DragEvent.ACTION_DRAG_ENTERED -> { v.alpha = 0.5f; true }
                    e.action == DragEvent.ACTION_DRAG_EXITED || e.action == DragEvent.ACTION_DRAG_ENDED -> { v.alpha = 1f; true }
                    e.action == DragEvent.ACTION_DROP -> { v.alpha = 1f; main.post { move(d.index, i) }; true }
                    else -> true
                }
            }
            return swipe
        }

        private fun addField() = input(EditText(this@MainActivity)).apply {
            hint = tr("+ Nieuw item", "+ New item")
            addEdit = this
            setOnEditorActionListener { v, _, _ ->
                val s = v.text.toString()
                if (s.isBlank()) render(null) else { rows.add(ListTask(s.trim(), false)); commit(); render(ADD) }
                true
            }
        }

        private fun doneHeader(count: Int) = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), 0, dp(4))
            addView(icon(if (n.doneOpen) R.drawable.ic_expand_less else R.drawable.ic_expand_more, Ui.MUTED, 20))
            addView(label(tr("Voltooid ($count)", "Done ($count)"), 14f, Ui.MUTED, bold = true).apply { setPadding(dp(6), 0, 0, 0) },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(label(tr("Wissen", "Clear"), 13f, Ui.ACCENT, bold = true).apply {
                setPadding(dp(12), dp(6), dp(8), dp(6))
                setOnClickListener {
                    val before = snapshot()
                    rows.removeAll { it.done }; commit(); render(null)
                    undoBar(tr("Voltooide items gewist", "Done items cleared")) { restore(before) }
                }
            })
            setOnClickListener { n.doneOpen = !n.doneOpen; commit(); render(null) }
        }

        private fun enter(i: Int, cur: Int) {
            val t = rows[i]
            if (t.text.isBlank()) { rows.removeAt(i); commit(); render(null); return }
            val c = cur.coerceIn(0, t.text.length)
            rows.add(i + 1, ListTask(t.text.substring(c).trim(), false))
            t.text = t.text.substring(0, c).trimEnd()
            commit(); render(i + 1, 0)
        }

        private fun removeEmpty(i: Int) {
            rows.removeAt(i); commit()
            render(rows.indices.lastOrNull { it < i && !rows[it].done }, -1)
        }

        private fun delete(i: Int) {
            val before = snapshot()
            rows.removeAt(i); commit(); render(null)
            undoBar(tr("Item verwijderd", "Item deleted")) { restore(before) }
        }

        // dropped on a row lower down it goes below it, higher up above it
        private fun move(from: Int, to: Int) {
            if (from == to || from !in rows.indices || to !in rows.indices) return
            rows.add(to, rows.removeAt(from)); commit(); render(null)
        }
    }

    /** "Item verwijderd — Ongedaan maken" for 5 s above the action bar. */
    private fun undoBar(msg: String, undo: () -> Unit) {
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? FrameLayout ?: return
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Ui.INK, 10)
            elevation = dp(16).toFloat()
            setPadding(dp(16), 0, dp(4), 0)
            addView(label(msg, 14f, Ui.BG), LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        bar.addView(label(tr("Ongedaan maken", "Undo"), 14f, Ui.COVERS[0], bold = true).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { root.removeView(bar); undo() }
        })
        root.addView(bar, FrameLayout.LayoutParams(MATCH, dp(48), Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(64)) })
        main.postDelayed({ root.removeView(bar) }, 5000)
    }

    private fun isList(n: Note) = Todo.items(n.text).isNotEmpty() || n.tags.split(",").any { it.trim().equals(Todo.TAG, ignoreCase = true) }

    /** Name of a list: its first line that is not a task, else the date. */
    private fun listTitle(n: Note) = n.text.lines().map { it.trim().removePrefix("#").trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("- [") } ?: dateOf(n)

    private fun todoCard(notes: List<Note>): View {
        val lists = notes.filter { isList(it) && it.archived == 0L }
        val open = lists.sumOf { n -> Todo.items(n.text).count { !it.done } }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(176)
            background = rounded(Ui.ACCENT_SOFT, 14)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(label(tr("Lijsten", "Lists"), 16f, Ui.INK, bold = true))
            addView(label(if (lists.isEmpty()) tr("Nog geen lijsten", "No lists yet") else tr("${lists.size} · $open open", "${lists.size} · $open open"), 12f, Ui.ACCENT))
            for (l in lists.take(4)) addView(label("☐ " + listTitle(l), 13f, Ui.INK).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
            })
            ripple(this)
            setOnClickListener { showTodos() }
        }
    }

    /** Every to-do list, each in its own card, tickable and extendable in one place. */
    private fun showTodos() {
        saveEditor()
        current = null; editor = null; openBook = null; inSettings = false; inTodos = true; inArchive = false
        val now = System.currentTimeMillis()
        store.all(bin = true).filter { now - it.trashed > BIN_MS }.forEach { store.delete(it.id) }
        val lists = store.all().filter { isList(it) && it.archived == 0L }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (n in lists) {
            val book = books.get(n.notebookId)?.title?.ifBlank { tr("Naamloos", "Untitled") } ?: tr("Losse pagina's", "Loose pages")
            val open = Todo.items(n.text).count { !it.done }
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Ui.SURFACE, 12)
                setPadding(dp(14), dp(10), dp(14), dp(6))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(listTitle(n), 17f, Ui.INK, bold = true))
                        addView(label("$book · $open open", 12f, Ui.MUTED))
                        ripple(this)
                        setOnClickListener { openNote(n) }
                        setOnLongClickListener { listMenu(n, it); true }
                    }, LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(label("⋯", 24f, Ui.MUTED).apply {
                        contentDescription = tr("Lijstmenu", "List menu")
                        setPadding(dp(14), 0, dp(4), 0)
                        setOnClickListener { listMenu(n, it) }
                    })
                })
                addView(Checklist(n, withAdd = true).view)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
        val body = if (lists.isEmpty()) label(tr("Nog geen lijsten. Maak er een, of schrijf ‘To do’ of vakjes op de Folio.", "No lists yet. Make one, or write ‘To do’ or boxes on the Folio."), 15f, Ui.MUTED)
            .apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), 0) }
        else ScrollView(this).apply { addView(col) }
        scaffold(header(label(tr("Lijsten", "Lists"), 24f, Ui.INK, bold = true), back = true), body, listOf(
            Action(tr("Nieuwe lijst", "New list"), R.drawable.ic_add) {
                askName(tr("Nieuwe lijst", "New list"), "", tr("Bijv. Boodschappen, Klussen", "E.g. Groceries, Chores")) { t ->
                    val now = System.currentTimeMillis()
                    store.save(Note("list$now", now / 1000, 21000, 14800, emptyList(), 0, t.ifBlank { tr("Lijst", "List") }, "hand", tags = Todo.TAG))
                    showTodos()
                }
            },
            Action(tr("Archief", "Archive"), R.drawable.ic_folder) { showListArchive() },
        ), tr("Lijsten", "Lists"))
    }

    private fun listMenu(n: Note, anchor: View) = PopupMenu(this, anchor).apply {
        menu.add(0, 1, 0, tr("Archiveren", "Archive"))
        menu.add(0, 2, 1, tr("Verwijderen", "Delete"))
        setOnMenuItemClickListener { item ->
            saveEditor()
            val archive = item.itemId == 1
            if (archive) n.archived = System.currentTimeMillis() else n.trashed = System.currentTimeMillis()
            store.save(n)
            showTodos()
            undoBar(if (archive) tr("Lijst gearchiveerd", "List archived") else tr("Lijst in de prullenbak", "List moved to the bin")) {
                n.archived = 0; n.trashed = 0; store.save(n); refresh()
            }
            true
        }
        show()
    }

    /** Archived lists and the bin (emptied after 30 days): put back, or delete. */
    private fun showListArchive() {
        saveEditor()
        inArchive = true
        val now = System.currentTimeMillis()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun section(title: String, ns: List<Note>, bin: Boolean) {
            col.addView(label(title, 15f, Ui.MUTED, bold = true).apply { setPadding(dp(4), dp(16), 0, dp(4)) })
            if (ns.isEmpty()) col.addView(label(tr("Leeg", "Empty"), 14f, Ui.MUTED).apply { setPadding(dp(4), 0, 0, 0) })
            for (n in ns) col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Ui.SURFACE, 12)
                setPadding(dp(14), dp(10), dp(14), dp(6))
                addView(label(listTitle(n), 16f, Ui.INK, bold = true))
                if (bin) {
                    val days = ((BIN_MS - (now - n.trashed)) / 86_400_000L).coerceAtLeast(0)
                    addView(label(tr("Nog $days dagen, daarna weg", "$days days left, then gone"), 12f, Ui.MUTED))
                }
                addView(rowOf(
                    pill(tr("Terugzetten", "Restore"), R.drawable.ic_arrow_back) { n.archived = 0; n.trashed = 0; store.save(n); showListArchive() },
                    pill(if (bin) tr("Definitief wissen", "Delete for good") else tr("Verwijderen", "Delete"), R.drawable.ic_delete, Ui.DANGER) {
                        if (!bin) { n.trashed = System.currentTimeMillis(); store.save(n); showListArchive() }
                        else AlertDialog.Builder(this@MainActivity)
                            .setMessage(tr("‘${listTitle(n)}’ definitief wissen? Dit kan niet ongedaan worden.", "Delete ‘${listTitle(n)}’ for good? This cannot be undone."))
                            .setPositiveButton(tr("Wissen", "Delete")) { _, _ -> store.delete(n.id); showListArchive() }
                            .setNegativeButton(tr("Annuleren", "Cancel"), null).show()
                    },
                ))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        section(tr("Archief", "Archive"), store.all().filter { it.archived > 0 }, false)
        section(tr("Prullenbak", "Bin"), store.all(bin = true), true)
        scaffold(header(label(tr("Archief", "Archive"), 24f, Ui.INK, bold = true), back = true), ScrollView(this).apply { addView(col) }, emptyList())
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
    private fun mergeNotes(n: Note, o: Note): Note {
        // keep every original page inside the merged one, so the merge can be undone
        val originals = JSONArray()
        for (x in listOf(n, o)) {
            if (x.mergedFrom.isBlank()) originals.put(x.toJson())
            else JSONArray(x.mergedFrom).let { a -> for (i in 0 until a.length()) originals.put(a.getJSONObject(i)) }
        }
        return Note(n.id, n.timestamp, n.width, n.height, n.strokes + o.strokes, n.rotation,
            listOf(n.text, o.text).filter { it.isNotBlank() }.joinToString("\n\n"), n.source, n.notionUrl, n.notebookId, n.driveIds,
            listOf(n.tags, o.tags).filter { it.isNotBlank() }.joinToString(", "), n.tidy, n.tidyOn, n.order, originals.toString(), n.chapter)
    }

    /** Put the pages of a merge back as they were before it (later edits to the merged page are lost). */
    private fun unmerge(n: Note) {
        val a = JSONArray(n.mergedFrom)
        val originals = (0 until a.length()).map { Note.fromJson(a.getJSONObject(it)) }
        originals.forEach { store.save(it) } // the first has the merged page's id and replaces it
        if (originals.none { it.id == n.id }) store.delete(n.id)
        if (prefs.appendTo == n.id) prefs.appendTo = null
        thumbs.evictAll()
        setStatus(tr("Samenvoegen ongedaan gemaakt: ${pages(originals.size)} terug", "Merge undone: ${pages(originals.size)} restored"))
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
                r.onSuccess { popup(tr("Live gestopt", "Live stopped")) }.onFailure { popup(tr("Live mislukt: ${it.message}", "Live failed: ${it.message}")) }
            }
        }
    }

    // ---- OCR -------------------------------------------------------------------------

    private fun runOcr(n: Note, method: String, onFail: () -> Unit = {}, lang: String = prefs.ocrLanguage, done: () -> Unit) {
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
                    popup(if (n == 0) tr("Geen nieuwe pagina's op de Folio", "No new pages on the Folio") else tr("${pages(n)} opgehaald$extra", "${pages(n)} fetched$extra"))
                    // a page whose text was typed by hand keeps that text
                    if (n > 0) autoOcr(fresh.filter { it.source != "hand" }.reversed(), finished = {
                        shapesAll(if (prefs.autoShapes) fresh else emptyList()) {
                            if (current == null && !inSettings) showBook(target)
                            if (prefs.driveAuto) uploadToDrive(fresh, quiet = true)
                        }
                    }) else refresh()
                }.onFailure { popup(tr("Synchroniseren mislukt: ${it.message}", "Sync failed: ${it.message}")) }
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
        // the title scrolls away with the settings
        col.addView(header(label(tr("Instellingen", "Settings"), 24f, Ui.INK, bold = true), back = true))
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
        fun dropdown(box: LinearLayout, title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
            box.addView(label(title, 13f, Ui.MUTED).apply { setPadding(0, dp(10), 0, 0) })
            box.addView(Spinner(this).apply {
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options.map { it.second })
                setSelection(options.indexOfFirst { it.first == current }.coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(options[pos].first)
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            })
        }

        val app = section("App")
        app.addView(label(tr("Versie ${BuildConfig.GIT_SHA}", "Version ${BuildConfig.GIT_SHA}"), 20f, Ui.INK, bold = true).apply { setPadding(0, dp(4), 0, dp(2)) })
        val ghToken = field(app, tr("GitHub-token (alleen voor updates)", "GitHub token (only for updates)"), prefs.githubToken, secret = true)
        app.addView(rowOf(
            pill(tr("Update zoeken", "Check for update"), R.drawable.ic_download) { saveSettings?.invoke(); checkForUpdate() },
            pill("Log", R.drawable.ic_list) { showLog() },
        ))

        val folio = section("Folio")
        folio.addView(label(if (pad.isPaired) tr("Gekoppeld met ${prefs.padName ?: "Folio"}", "Paired with ${prefs.padName ?: "Folio"}") else tr("Nog geen Folio gekoppeld", "No Folio paired yet"), 15f, Ui.INK, bold = true))
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

        val language = section(tr("Taal", "Language"))
        dropdown(language, tr("Taal van de app", "App language"), listOf("nl" to "Nederlands", "en" to "English"), prefs.language) { code ->
            if (code != prefs.language) {
                saveSettings?.invoke()
                prefs.language = code
                Lang.en = code == "en"
                showSettings()
            }
        }
        dropdown(language, tr("Taal van je handschrift (ML Kit)", "Handwriting language (ML Kit)"), listOf(
            "nl" to "Nederlands", "en" to "English", "de" to "Deutsch", "fr" to "Français", "es" to "Español", "it" to "Italiano",
        ), prefs.ocrLanguage) { prefs.ocrLanguage = it }
        note(language, tr("Claude herkent de taal van je handschrift zelf.", "Claude detects the handwriting language by itself."))

        val inkBox = section(tr("Inkt", "Ink"))
        note(inkBox, tr("Tik op een kleur om hem te kiezen; nog eens tikken wisselt dun, normaal en dik.", "Tap a colour to pick it; tap again to switch thin, normal and thick."))
        val widths = listOf(0.25f, 0.35f, 0.6f)
        val wi = widths.indexOf(prefs.inkWidth).let { if (it < 0) 1 else it }
        val inks = listOf(0xFF000000.toInt(), 0xFF1F4FB5.toInt(), 0xFFB3261E.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt())
            .let { if (prefs.inkColor in it) it else it + prefs.inkColor }
        fun setInk(c: Int, w: Float) { saveSettings?.invoke(); prefs.inkColor = c; prefs.inkWidth = w; thumbs.evictAll(); showSettings() }
        inkBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(10))
            for (c in inks) {
                val on = c == prefs.inkColor
                addView(FrameLayout(context).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(if (on) 3 else 1), if (on) Ui.ACCENT else Ui.LINE) }
                    contentDescription = tr("Inktkleur", "Ink colour")
                    // the white dot shows the line width: thin, normal or thick
                    if (on) addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } },
                        dp(listOf(6, 10, 16)[wi]).let { FrameLayout.LayoutParams(it, it, Gravity.CENTER) })
                    setOnClickListener { setInk(c, if (on) widths[(wi + 1) % widths.size] else prefs.inkWidth) }
                }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
            }
            addView(ColorWheel(context, null).apply {
                contentDescription = tr("Eigen kleur", "Custom colour")
                setOnClickListener { pickInk(prefs.inkColor) { c -> setInk(c, prefs.inkWidth) } }
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
        })
        inkBox.addView(FrameLayout(this).apply {
            background = rounded(Color.WHITE, 14)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(TextView(context).apply {
                text = tr("Voorbeeld", "Preview")
                textSize = 30f
                typeface = Typeface.create("casual", Typeface.NORMAL)
                setTextColor(prefs.inkColor)
                gravity = Gravity.CENTER
                // thicker ink: the letters get an outline in the same colour
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = listOf(0f, 1f, 2.5f)[wi] * resources.displayMetrics.density
            }, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        val ocr = section(tr("Tekstherkenning", "Text recognition"))
        note(ocr, tr("Automatisch omzetten naar tekst na synchroniseren", "Convert to text automatically after syncing"))
        val group = RadioGroup(this)
        val opts = listOf("off" to tr("Uit", "Off"), "mlkit" to tr("ML Kit (op het toestel, gratis)", "ML Kit (on the phone, free)"), "claude" to tr("Claude (beter, API-key nodig)", "Claude (better, needs an API key)"))
        opts.forEachIndexed { i, (key, text) ->
            group.addView(RadioButton(this).apply { id = 100 + i; this.text = text; isChecked = prefs.autoOcr == key })
        }
        ocr.addView(group)
        // Claude's key and model, right under its option and only while it is chosen
        val claudeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), 0, 0, dp(6))
            visibility = if (prefs.autoOcr == "claude") View.VISIBLE else View.GONE
        }
        ocr.addView(claudeBox)
        val key = field(claudeBox, tr("Claude API-key (blijft alleen op dit toestel)", "Claude API key (stays on this phone only)"), prefs.apiKey, secret = true)
        val model = field(claudeBox, "Claude-model", prefs.model)
        group.setOnCheckedChangeListener { _, id -> claudeBox.visibility = if (id == 102) View.VISIBLE else View.GONE }
        val syncAuto = CheckBox(this).apply {
            text = tr("Automatisch synchroniseren zodra je op de knop van de Folio drukt (terwijl de app open is)",
                "Sync automatically as soon as you press the Folio button (while the app is open)")
            isChecked = prefs.autoSync
        }
        ocr.addView(syncAuto)
        val sAuto = CheckBox(this).apply { text = tr("Na synchroniseren vormen strak maken (lijnen, rechthoeken, cirkels, driehoeken)", "Draw shapes clean after syncing (lines, rectangles, circles, triangles)"); isChecked = prefs.autoShapes }
        ocr.addView(sAuto)

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
        scaffold(View(this), ScrollView(this).apply { addView(col) }, emptyList())
    }

    /** Colour wheel: hue around the circle, white towards the middle, and a slider for how dark. */
    private fun pickInk(start: Int, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3).also { Color.colorToHSV(start, it) }
        val swatch = View(this)
        fun show() { swatch.background = rounded(Color.HSVToColor(hsv), 10) }
        val wheel = ColorWheel(this) { h, s -> hsv[0] = h; hsv[1] = s; show() }.apply { value = hsv[2] }
        val dark = SeekBar(this).apply {
            max = 100
            progress = (hsv[2] * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, user: Boolean) { hsv[2] = p / 100f; wheel.value = hsv[2]; show() }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        }
        show()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(wheel, LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(label(tr("Helderheid", "Brightness"), 13f, Ui.MUTED).apply { setPadding(0, dp(12), 0, 0) })
            addView(dark)
            addView(swatch, LinearLayout.LayoutParams(MATCH, dp(36)).apply { topMargin = dp(8) })
        }
        AlertDialog.Builder(this).setTitle(tr("Eigen inktkleur", "Custom ink colour")).setView(col)
            .setPositiveButton(tr("Kiezen", "Pick")) { _, _ -> onPick(Color.HSVToColor(hsv)) }
            .setNegativeButton(tr("Annuleren", "Cancel"), null).show()
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
private const val ADD = -2 // Checklist: focus the new-item field
private const val BIN_MS = 30L * 86_400_000 // lists stay in the bin this long

/** Swipe the row left past a third of its width to delete it; the row slides with the finger. */
private class SwipeRow(ctx: Context, private val onSwiped: () -> Unit) : FrameLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2
    private var x0 = 0f
    private var y0 = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> if (x0 - e.x > slop && abs(e.x - x0) > abs(e.y - y0) * 2) {
                dragging = true; parent.requestDisallowInterceptTouchEvent(true)
            }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = getChildAt(0) ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y }
            MotionEvent.ACTION_MOVE -> if (dragging || x0 - e.x > slop) { dragging = true; c.translationX = min(0f, e.x - x0) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_UP && -c.translationX > width / 3f)
                    c.animate().translationX(-width.toFloat()).setDuration(150).withEndAction { onSwiped() }.start()
                else c.animate().translationX(0f).setDuration(150).start()
                dragging = false
            }
        }
        return true
    }
}

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

/** The app's logo in a circle whose ring shows the Folio's battery (grey while not known yet). */
private class BatteryBadge(ctx: Context, private val pct: Int) : View(ctx) {
    private val logo = ctx.getDrawable(R.drawable.ic_launcher_foreground)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val w = s * 0.09f
        paint.style = Paint.Style.FILL
        paint.color = Ui.SURFACE
        c.drawCircle(s / 2, s / 2, s / 2, paint)
        // adaptive-icon foreground: the logo sits in the middle 66 of 108 units
        val half = (s * 0.67f).toInt()
        val mid = (s / 2).toInt()
        logo?.setBounds(mid - half, mid - half, mid + half, mid + half)
        c.save()
        c.clipPath(Path().apply { addCircle(s / 2, s / 2, s / 2 - w, Path.Direction.CW) })
        logo?.draw(c)
        c.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w
        paint.strokeCap = Paint.Cap.ROUND
        val ring = RectF(w / 2, w / 2, s - w / 2, s - w / 2)
        paint.color = Ui.LINE
        c.drawArc(ring, 0f, 360f, false, paint)
        if (pct >= 0) {
            paint.color = when { pct <= 15 -> Ui.DANGER; pct <= 40 -> 0xFFE0B050.toInt(); else -> 0xFF7CC48A.toInt() }
            c.drawArc(ring, -90f, 3.6f * pct, false, paint)
        }
    }
}

/** Hue around the circle, white towards the middle; with [onPick] it reports (hue, saturation) where touched. */
private class ColorWheel(ctx: Context, private val onPick: ((Float, Float) -> Unit)?) : View(ctx) {
    var value = 1f
        set(v) { field = v; invalidate() }
    private val hue = Paint(Paint.ANTI_ALIAS_FLAG)
    private val white = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        // clockwise on screen: red, magenta, blue, cyan, green, yellow, so hue = 360 - angle
        hue.shader = SweepGradient(cx, cy, intArrayOf(Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED), null)
        white.shader = RadialGradient(cx, cy, min(cx, cy).coerceAtLeast(1f), Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy)
        c.drawCircle(cx, cy, r, hue)
        c.drawCircle(cx, cy, r, white)
        shade.color = Color.argb(((1 - value) * 255).toInt(), 0, 0, 0)
        c.drawCircle(cx, cy, r, shade)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pick = onPick ?: return super.onTouchEvent(e)
        parent?.requestDisallowInterceptTouchEvent(true)
        val dx = e.x - width / 2f
        val dy = e.y - height / 2f
        val angle = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
        pick((360f - angle) % 360f, (hypot(dx, dy) / (min(width, height) / 2f)).coerceAtMost(1f))
        return true
    }
}

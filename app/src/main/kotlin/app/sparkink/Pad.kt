package app.sparkink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.sparkink.core.AuthorizationError
import app.sparkink.core.DeviceError
import app.sparkink.core.StrokeParser
import app.sparkink.core.WacomSession
import app.sparkink.core.Pt
import app.sparkink.core.hex
import app.sparkink.core.hexToBytes
import app.sparkink.core.parseLive
import java.security.SecureRandom
import java.util.concurrent.Executors

// LED meanings from Wacom support ("What do the LEDs on Bamboo Spark mean?").
private val LED_HINT get() = tr(
    "Lampje: blauw knipperend = koppelmodus, oranje knipperend = batterij bijna leeg, " +
        "afwisselend blauw-oranje = geheugen vol (gebruik dan Folio wissen + resetten).",
    "Light: blinking blue = pairing mode, blinking orange = battery low, " +
        "blue and orange in turn = memory full (then use Erase + reset).")

/** In-memory debug log, shown and shareable from the app: it is the way to debug against the real pad. */
object AppLog {
    private val sb = StringBuilder()
    @Synchronized fun add(line: String) {
        sb.append(java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())).append(' ').append(line).append('\n')
        if (sb.length > 80_000) sb.delete(0, sb.length - 60_000)
    }
    @Synchronized fun text(): String = sb.toString()
}

@SuppressLint("MissingPermission") // requested by the activity before calling in here
class Pad(private val ctx: Context, private val prefs: Prefs, private val store: NoteStore) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private fun ui(f: () -> Unit) = main.post(f)

    val bluetoothOn get() = adapter?.isEnabled == true
    val isPaired get() = prefs.padAddress != null && prefs.padUuid != null

    // ---- scanning --------------------------------------------------------------------

    private var scanCb: ScanCallback? = null

    private fun describe(r: ScanResult) =
        "advert ${r.device.address} name=${r.scanRecord?.deviceName ?: r.device.name} rssi=${r.rssi} " +
            "connectable=${r.isConnectable} flags=${r.scanRecord?.advertiseFlags} uuids=${r.scanRecord?.serviceUuids}"

    fun startScan(onDevice: (BluetoothDevice, Int, Boolean) -> Unit) {
        stopScan()
        val scanner = adapter?.bluetoothLeScanner ?: return
        val logged = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name
                if (name != null) {
                    if (logged.add(r.device.address)) AppLog.add(describe(r))
                    ui { onDevice(r.device, r.rssi, r.isConnectable) }
                }
            }
            override fun onScanFailed(errorCode: Int) { AppLog.add("scan failed $errorCode") }
        }
        scanCb = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
    }

    fun stopScan() {
        scanCb?.let { try { adapter?.bluetoothLeScanner?.stopScan(it) } catch (_: Exception) {} }
        scanCb = null
    }

    private var watchCb: ScanCallback? = null

    /** Calls [onSeen] whenever the paired pad advertises (it does after its button is pressed), until [stopWatch]. */
    fun watch(onSeen: () -> Unit) {
        stopWatch()
        val address = prefs.padAddress ?: return
        val scanner = adapter?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) { ui(onSeen) }
            override fun onScanFailed(errorCode: Int) { AppLog.add("watch scan failed $errorCode") }
        }
        try {
            scanner.startScan(listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), cb)
            watchCb = cb
        } catch (e: Exception) {
            AppLog.add("watch: $e")
        }
    }

    fun stopWatch() {
        watchCb?.let { try { adapter?.bluetoothLeScanner?.stopScan(it) } catch (_: Exception) {} }
        watchCb = null
    }

    /**
     * Wait until the pad at [address] is seen advertising, so we connect to a device we know is
     * on the air right now (and with the right address type). Blocking.
     */
    private fun scanFor(address: String, timeoutMs: Long): ScanResult? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val found = java.util.concurrent.atomic.AtomicReference<ScanResult?>()
        val latch = java.util.concurrent.CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                if (r.device.address.equals(address, ignoreCase = true)) { found.set(r); latch.countDown() }
            }
            override fun onScanFailed(errorCode: Int) { AppLog.add("scan failed $errorCode"); latch.countDown() }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        try { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } finally { scanner.stopScan(cb) }
        return found.get()?.also { AppLog.add(describe(it)) }
    }

    // ---- pairing ---------------------------------------------------------------------

    fun pair(device: BluetoothDevice, status: (String) -> Unit, pressButton: () -> Unit, done: (Result<String>) -> Unit) {
        worker.execute {
            var t: BleTransport? = null
            try {
                ui { status(tr("Koppelen met ${device.name}… bevestig een Bluetooth-koppelverzoek op je telefoon als dat verschijnt", "Pairing with ${device.name}… confirm the Bluetooth pairing request on your phone if it appears")) }
                t = BleTransport(ctx, device) { AppLog.add(it) }
                val kind = t.open()
                AppLog.add("pad family: $kind")
                val uuid = ByteArray(6).also { SecureRandom().nextBytes(it) }
                val info = WacomSession(t, kind) { AppLog.add(it) }.register(uuid) { ui(pressButton) }
                prefs.padAddress = device.address
                prefs.padUuid = uuid.joinToString("") { "%02x".format(it) }
                prefs.padKind = kind.name
                prefs.padName = device.name
                ui { done(Result.success(tr("Gekoppeld met ${device.name} (batterij ${info.batteryPercent}%)", "Paired with ${device.name} (battery ${info.batteryPercent}%)"))) }
            } catch (e: Exception) {
                AppLog.add("pair failed: $e")
                ui { done(Result.failure(Exception(tr("${explain(e)} $LED_HINT Druk na het koppelverzoek binnen 5 seconden op de knop.", "${explain(e)} $LED_HINT After the pairing request, press the button within 5 seconds.")))) }
            } finally {
                t?.close()
            }
        }
    }

    /**
     * Register (user presses the pad button), then erase + factory-reset the pad, like Inkspace's
     * hidden "Device Memory Reset" (see [WacomSession.eraseAndReset]). Forgets our registration.
     */
    fun wipe(device: BluetoothDevice, status: (String) -> Unit, pressButton: () -> Unit, done: (Result<String>) -> Unit) {
        worker.execute {
            var t: BleTransport? = null
            try {
                ui { status(tr("Verbinden met ${device.name} om te wissen…", "Connecting to ${device.name} to erase it…")) }
                t = BleTransport(ctx, device) { AppLog.add(it) }
                val kind = t.open()
                AppLog.add("pad family: $kind")
                ui { status(tr("Wissen en resetten…", "Erasing and resetting…")) }
                val results = WacomSession(t, kind) { AppLog.add(it) }
                    .wipe(ByteArray(6).also { SecureRandom().nextBytes(it) }) { ui(pressButton) }
                results.forEach { AppLog.add("wipe: $it") }
                prefs.padAddress = null
                prefs.padUuid = null
                val ok = results.any { it.endsWith(": ok") }
                ui {
                    if (ok) done(Result.success(tr("Folio gewist. Houd nu de knop 6 seconden ingedrukt tot het lampje blauw knippert en tik op Folio koppelen.", "Folio erased. Now hold the button for 6 seconds until the light blinks blue, and tap Pair.")))
                    else done(Result.failure(Exception(tr("de Folio weigerde beide commando's. Zie de log.", "the Folio refused both commands. See the log."))))
                }
            } catch (e: Exception) {
                AppLog.add("wipe failed: $e")
                ui { done(Result.failure(Exception(explain(e)))) }
            } finally {
                t?.close()
            }
        }
    }

    // ---- sync ------------------------------------------------------------------------

    fun sync(status: (String) -> Unit, onNote: (Note) -> Unit, done: (Result<Int>) -> Unit) {
        val address = prefs.padAddress
        val uuid = prefs.padUuid
        if (adapter == null || address == null || uuid == null) return done(Result.failure(Exception(tr("Nog geen Folio gekoppeld", "No Folio paired yet"))))
        worker.execute {
            var t: BleTransport? = null
            try {
                ui { status(tr("Zoeken naar de Folio… druk nu kort op de knop van de Folio", "Looking for the Folio… briefly press the Folio button now")) }
                val seen = scanFor(address, 40_000)
                if (seen == null) AppLog.add("Folio niet in scan gezien, probeer toch direct te verbinden")
                ui { status(tr("Verbinden…", "Connecting…")) }
                t = BleTransport(ctx, seen?.device ?: adapter.getRemoteDevice(address)) { AppLog.add(it) }
                val kind = t.open()
                ui { status(tr("Notities ophalen…", "Fetching notes…")) }
                val session = WacomSession(t, kind) { AppLog.add(it) }
                val n = session.syncAll(hexToBytes(uuid)) { page ->
                    val parsed = StrokeParser.parse(page.raw)
                    parsed.warnings.forEach { AppLog.add("parse: $it") }
                    var id = page.timestamp.toString()
                    var k = 1
                    while (store.load(id) != null) id = "${page.timestamp}_${k++}"
                    // Save the raw bytes first: the page is deleted from the Folio after this returns true.
                    store.saveRaw(id, page.raw)
                    val note = Note(id, page.timestamp, page.info.width, page.info.height, parsed.strokes,
                        prefs.defaultRotation, "", "")
                    store.save(note)
                    ui { onNote(note) }
                    true
                }
                try { session.battery().let { prefs.padBattery = it.first } } catch (e: Exception) { AppLog.add("battery: $e") }
                ui { done(Result.success(n)) }
            } catch (e: Exception) {
                AppLog.add("sync failed: $e")
                ui { done(Result.failure(Exception(explain(e)))) }
            } finally {
                t?.close()
            }
        }
    }

    // ---- other pad commands -----------------------------------------------------------

    /** Find and connect to the paired pad, run [block] on the worker thread, then disconnect. */
    private fun <T> withPad(status: (String) -> Unit, block: (BleTransport, WacomSession, ByteArray) -> T, done: (Result<T>) -> Unit) {
        val address = prefs.padAddress
        val uuid = prefs.padUuid
        if (adapter == null || address == null || uuid == null) return done(Result.failure(Exception(tr("Nog geen Folio gekoppeld", "No Folio paired yet"))))
        worker.execute {
            var t: BleTransport? = null
            try {
                ui { status(tr("Zoeken naar de Folio… druk nu kort op de knop van de Folio", "Looking for the Folio… briefly press the Folio button now")) }
                val seen = scanFor(address, 40_000)
                ui { status(tr("Verbinden…", "Connecting…")) }
                t = BleTransport(ctx, seen?.device ?: adapter.getRemoteDevice(address)) { AppLog.add(it) }
                val kind = t.open()
                val r = block(t, WacomSession(t, kind) { AppLog.add(it) }, hexToBytes(uuid))
                ui { done(Result.success(r)) }
            } catch (e: Exception) {
                AppLog.add("pad command failed: $e")
                ui { done(Result.failure(Exception(explain(e)))) }
            } finally {
                t?.close()
            }
        }
    }

    fun battery(status: (String) -> Unit, done: (Result<Pair<Int, Boolean>>) -> Unit) =
        withPad(status, { _, s, uuid -> s.batteryOf(uuid).also { prefs.padBattery = it.first } }, done)

    fun rename(name: String, status: (String) -> Unit, done: (Result<String>) -> Unit) =
        withPad(status, { _, s, uuid -> s.setName(uuid, name); prefs.padName = name; name }, done)

    private val liveStop = java.util.concurrent.atomic.AtomicBoolean()

    /** Stream pen moves to [onPoints] (null = pen lifted) until [stopLive]. Untested on a real Spark. */
    fun live(status: (String) -> Unit, onPoints: (List<Pt?>) -> Unit, done: (Result<Unit>) -> Unit) {
        liveStop.set(false)
        withPad(status, { t, s, uuid ->
            t.enableLive()
            var logged = 0
            t.onLive = { d ->
                val pts = parseLive(d)
                if (pts.isNotEmpty()) ui { onPoints(pts) } else if (logged++ < 20) AppLog.add("live ${d.hex()}")
            }
            s.startLive(uuid)
            ui { status(tr("Live: schrijf op de Folio, het verschijnt hier.", "Live: write on the Folio and it appears here.")) }
            while (!liveStop.get() && t.isOpen) Thread.sleep(200)
            t.onLive = null
            if (t.isOpen) try { s.stopLive() } catch (e: Exception) { AppLog.add("stop live: $e") }
            if (!liveStop.get()) throw Exception(tr("De Folio verbrak de verbinding", "The Folio dropped the connection"))
        }, done)
    }

    fun stopLive() = liveStop.set(true)

    private fun explain(e: Exception): String = when (e) {
        is AuthorizationError -> tr("De Folio herkent deze app niet meer. Koppel opnieuw (knop 6 sec ingedrukt houden).", "The Folio no longer knows this app. Pair again (hold the button for 6 s).")
        is DeviceError -> if (e.invalidState)
            tr("De Folio is niet klaar om te synchroniseren. $LED_HINT", "The Folio is not ready to sync. $LED_HINT")
        else tr("De Folio gaf foutcode ${e.code}. Zie de log.", "The Folio returned error code ${e.code}. See the log.")
        else -> e.message ?: e.toString()
    }
}

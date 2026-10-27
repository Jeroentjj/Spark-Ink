package app.sparkink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.sparkink.core.AuthorizationError
import app.sparkink.core.DeviceError
import app.sparkink.core.StrokeParser
import app.sparkink.core.WacomSession
import app.sparkink.core.hexToBytes
import java.security.SecureRandom
import java.util.concurrent.Executors

// LED meanings from Wacom support ("What do the LEDs on Bamboo Spark mean?").
private const val LED_HINT = "Lampje: blauw knipperend = koppelmodus, oranje knipperend = batterij bijna leeg, " +
    "afwisselend blauw-oranje = geheugen vol (de Folio moet eerst gekoppeld en gesynchroniseerd worden om het te legen)."

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
                ui { status("Koppelen met ${device.name}… bevestig een Bluetooth-koppelverzoek op je telefoon als dat verschijnt") }
                t = BleTransport(ctx, device) { AppLog.add(it) }
                val kind = t.open()
                AppLog.add("pad family: $kind")
                val uuid = ByteArray(6).also { SecureRandom().nextBytes(it) }
                val info = WacomSession(t, kind) { AppLog.add(it) }.register(uuid) { ui(pressButton) }
                prefs.padAddress = device.address
                prefs.padUuid = uuid.joinToString("") { "%02x".format(it) }
                prefs.padKind = kind.name
                prefs.padName = device.name
                ui { done(Result.success("Gekoppeld met ${device.name} (batterij ${info.batteryPercent}%)")) }
            } catch (e: Exception) {
                AppLog.add("pair failed: $e")
                ui { done(Result.failure(Exception("${explain(e)} $LED_HINT Druk na het koppelverzoek binnen 5 seconden op de knop."))) }
            } finally {
                t?.close()
            }
        }
    }

    // ---- sync ------------------------------------------------------------------------

    fun sync(status: (String) -> Unit, onNote: (Note) -> Unit, done: (Result<Int>) -> Unit) {
        val address = prefs.padAddress
        val uuid = prefs.padUuid
        if (adapter == null || address == null || uuid == null) return done(Result.failure(Exception("Nog geen Folio gekoppeld")))
        worker.execute {
            var t: BleTransport? = null
            try {
                ui { status("Zoeken naar de Folio… druk nu kort op de knop van de Folio") }
                val seen = scanFor(address, 40_000)
                if (seen == null) AppLog.add("Folio niet in scan gezien, probeer toch direct te verbinden")
                ui { status("Verbinden…") }
                t = BleTransport(ctx, seen?.device ?: adapter.getRemoteDevice(address)) { AppLog.add(it) }
                val kind = t.open()
                ui { status("Notities ophalen…") }
                val n = WacomSession(t, kind) { AppLog.add(it) }.syncAll(hexToBytes(uuid)) { page ->
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
                ui { done(Result.success(n)) }
            } catch (e: Exception) {
                AppLog.add("sync failed: $e")
                ui { done(Result.failure(Exception(explain(e)))) }
            } finally {
                t?.close()
            }
        }
    }

    private fun explain(e: Exception): String = when (e) {
        is AuthorizationError -> "De Folio herkent deze app niet meer. Koppel opnieuw (knop 6 sec ingedrukt houden)."
        is DeviceError -> if (e.invalidState)
            "De Folio is niet klaar om te synchroniseren. $LED_HINT"
        else "De Folio gaf foutcode ${e.code}. Zie de log."
        else -> e.message ?: e.toString()
    }
}

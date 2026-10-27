package app.sparkink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import app.sparkink.core.NordicMsg
import app.sparkink.core.Transport
import app.sparkink.core.WacomException
import app.sparkink.core.PadKind
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private fun uuid(s: String): UUID = UUID.fromString(s)

object Gatt {
    val NORDIC_TX = uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // we write commands here
    val NORDIC_RX = uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // replies come in here
    val PEN_DATA = uuid("ffee0003-bbaa-9988-7766-554433221100")  // offline pen data stream
    val SYSEVENT = uuid("3a340721-c572-11e5-86c5-0002a5d5c51b")  // only on Slate-style pads
    val CCCD = uuid("00002902-0000-1000-8000-00805f9b34fb")
}

/**
 * Blocking GATT client: every method must be called from a worker thread, never the UI thread.
 */
@SuppressLint("MissingPermission") // permissions are requested before any Bluetooth work starts
@Suppress("DEPRECATION")
class BleTransport(
    private val ctx: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) : Transport {

    private var gatt: BluetoothGatt? = null
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val nordic = ByteArrayOutputStream()
    private val pen = ByteArrayOutputStream()
    @Volatile private var penAt = 0L

    private val connected = Semaphore(0)
    private val discovered = Semaphore(0)
    private val written = Semaphore(0)
    private val descriptorDone = Semaphore(0)
    @Volatile private var connectStatus = -1
    @Volatile private var lastWriteStatus = BluetoothGatt.GATT_SUCCESS
    @Volatile private var lastDescStatus = BluetoothGatt.GATT_SUCCESS
    @Volatile private var disconnected = false

    @Volatile private var attempt = 0

    private fun newCallback(id: Int) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (id != attempt) return
            log("gatt state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectStatus = status
                connected.release()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnected = true
                connectStatus = status
                // wake up everybody who might be waiting
                connected.release(); discovered.release(); written.release(); descriptorDone.release()
                lock.withLock { cond.signalAll() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (id != attempt) return
            discovered.release()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (id != attempt) return
            lastWriteStatus = status
            written.release()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (id != attempt) return
            lastDescStatus = status
            descriptorDone.release()
        }

        @Deprecated("used below API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (id == attempt && Build.VERSION.SDK_INT < 33) handle(c.uuid, c.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (id == attempt) handle(c.uuid, value)
        }
    }

    private fun handle(id: UUID, value: ByteArray) {
        when (id) {
            Gatt.NORDIC_RX -> lock.withLock { nordic.write(value); cond.signalAll() }
            Gatt.PEN_DATA -> lock.withLock { pen.write(value); penAt = System.currentTimeMillis() }
            else -> log("notify $id ${value.size} bytes")
        }
    }

    /**
     * Connect, discover services and switch on all notifications. Returns the pad family.
     *
     * The pad often drops the very first connection (right after service discovery, or with GATT
     * status 133 when it is not advertising at that moment), so a few attempts are made; for a pad
     * that only wakes up on its button this also keeps a connection request pending for a while.
     */
    fun open(attempts: Int = 3): PadKind {
        var last: WacomException? = null
        for (i in 1..attempts) {
            try {
                // last attempt: leave a background connection request pending (pad may only advertise briefly)
                return openOnce(i, autoConnect = attempts > 1 && i == attempts)
            } catch (e: WacomException) {
                log("attempt $i/$attempts failed: ${e.message}")
                last = e
                close()
                if (i < attempts) Thread.sleep(3000) // let the Bluetooth stack settle before retrying
            }
        }
        throw last!!
    }

    private fun openOnce(n: Int, autoConnect: Boolean): PadKind {
        attempt++
        disconnected = false
        connectStatus = -1
        listOf(connected, discovered, written, descriptorDone).forEach { it.drainPermits() }
        lock.withLock { nordic.reset(); pen.reset() }
        log("connecting to ${device.address} (attempt $n, autoConnect=$autoConnect, bond=${device.bondState}, type=${device.type})")
        gatt = if (Build.VERSION.SDK_INT >= 23)
            device.connectGatt(ctx, autoConnect, newCallback(attempt), BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(ctx, autoConnect, newCallback(attempt))
        val g = gatt ?: throw WacomException("Could not start Bluetooth connection")

        if (!connected.tryAcquire(if (autoConnect) 45L else 35L, TimeUnit.SECONDS) || disconnected)
            throw WacomException(
                if (connectStatus == -1) "Geen reactie van de Folio (time-out): hij accepteert nu geen verbinding"
                else "Geen verbinding met de Folio (Bluetooth-status $connectStatus)")
        if (!g.discoverServices()) throw WacomException("Service discovery failed to start")
        if (!discovered.tryAcquire(20, TimeUnit.SECONDS) || disconnected)
            throw WacomException("De Folio verbrak de verbinding tijdens het opzoeken van services")

        val all = g.services.flatMap { s -> s.characteristics.map { it.uuid } }
        log("characteristics: " + all.joinToString(" ") { it.toString().substring(0, 8) })
        val rx = find(Gatt.NORDIC_RX) ?: throw WacomException("This does not look like a Wacom smartpad (no UART service)")
        find(Gatt.NORDIC_TX) ?: throw WacomException("Missing UART write characteristic")

        enableNotify(rx)
        find(Gatt.PEN_DATA)?.let { enableNotify(it) }
        val sys = find(Gatt.SYSEVENT)
        sys?.let { enableNotify(it) }
        return if (sys == null) PadKind.SPARK else PadKind.SLATE
    }

    private fun find(id: UUID): BluetoothGattCharacteristic? {
        for (s in gatt!!.services) s.getCharacteristic(id)?.let { return it }
        return null
    }

    /**
     * Switching notifications on is the first operation that needs an encrypted link, so this is
     * where Android starts bonding and shows its pairing request. That needs the user's
     * confirmation, hence the long waits; when the write is rejected for lack of
     * authentication we wait until the bond exists and write again.
     */
    private fun enableNotify(c: BluetoothGattCharacteristic) {
        val g = gatt!!
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Gatt.CCCD) ?: return
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

        for (round in 1..3) {
            descriptorDone.drainPermits()
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                run { d.value = value; g.writeDescriptor(d) }
            }
            if (!started) throw WacomException("Could not enable notifications on ${c.uuid}")
            log("enable notifications ${c.uuid.toString().substring(0, 8)} (round $round, bond=${device.bondState})")
            if (!descriptorDone.tryAcquire(60, TimeUnit.SECONDS) || disconnected)
                throw WacomException("Enabling notifications timed out: is the Bluetooth pairing request on the phone confirmed?")
            val status = lastDescStatus
            if (status == BluetoothGatt.GATT_SUCCESS) return
            // 5 = insufficient authentication, 15 = insufficient encryption: bonding is under way
            if (status != 5 && status != 15) throw WacomException("Enabling notifications failed (status $status)")
            log("link needs pairing (status $status), waiting for the bond")
            val deadline = System.currentTimeMillis() + 45_000
            while (device.bondState != BluetoothDevice.BOND_BONDED && System.currentTimeMillis() < deadline && !disconnected)
                Thread.sleep(500)
            if (device.bondState != BluetoothDevice.BOND_BONDED)
                throw WacomException("Bluetooth-koppeling met de telefoon niet bevestigd")
        }
        throw WacomException("Enabling notifications kept failing")
    }

    fun close() {
        attempt++ // late callbacks of this connection must not disturb a following attempt
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    // ---- Transport -----------------------------------------------------------------

    override fun send(opcode: Int, args: ByteArray) {
        val c = find(Gatt.NORDIC_TX) ?: throw WacomException("Not connected")
        val g = gatt ?: throw WacomException("Not connected")
        val data = byteArrayOf(opcode.toByte(), args.size.toByte()) + args
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            run { c.writeType = type; c.value = data; g.writeCharacteristic(c) }
        }
        if (!started) throw WacomException("Write failed to start")
        if (!written.tryAcquire(10, TimeUnit.SECONDS) || disconnected) throw WacomException("Write timed out or connection lost")
        if (lastWriteStatus != BluetoothGatt.GATT_SUCCESS) throw WacomException("Write failed (status $lastWriteStatus)")
    }

    override fun receive(timeoutMs: Long): NordicMsg? {
        val deadline = System.currentTimeMillis() + timeoutMs
        lock.lock()
        try {
            while (true) {
                popMessage()?.let { return it }
                if (disconnected) throw WacomException("Connection lost")
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                cond.await(left, TimeUnit.MILLISECONDS)
            }
        } finally {
            lock.unlock()
        }
    }

    private fun popMessage(): NordicMsg? {
        val b = nordic.toByteArray()
        if (b.size < 2) return null
        val len = b[1].toInt() and 0xff
        if (b.size < 2 + len) return null
        val rest = b.copyOfRange(2 + len, b.size)
        nordic.reset()
        nordic.write(rest)
        return NordicMsg(b[0].toInt() and 0xff, b.copyOfRange(2, 2 + len))
    }

    override fun takePenData(): ByteArray = lock.withLock { pen.toByteArray().also { pen.reset() } }
    override fun clearPenData() = lock.withLock { pen.reset(); nordic.reset(); penAt = 0L }
    override fun lastPenDataAt() = penAt
}

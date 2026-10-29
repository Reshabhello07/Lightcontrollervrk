package com.ledstudio.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.*
import java.util.UUID

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
object Led {
    private val SVC = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val CHR = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private var gatt: BluetoothGatt? = null
    private var chr: BluetoothGattCharacteristic? = null
    val status = mutableStateOf("Not connected")
    val bright = mutableStateOf(1f)

    // --- smooth renderer: everything goes through fadeTo(); one loop sends frames ---
    private var from = Color.BLACK; private var to = Color.BLACK
    private var t0 = 0L; private var dur = 0L; private var ease = false
    @Volatile var cur = Color.BLACK
    @Volatile var paused = false   // true while a built-in effect runs on the strip
    @Volatile private var busy = false
    private var lastSent = -1
    private var job: Job? = null

    fun hsv(h: Float) = Color.HSVToColor(floatArrayOf(h, 1f, 1f))

    @Volatile private var holdUntil = 0L

    fun pulse(c: Int) {
        if (paused || System.currentTimeMillis() < holdUntil) return
        val prev = cur
        holdUntil = System.currentTimeMillis() + 1400
        fadeTo(c, 150, false, true)
        CoroutineScope(Dispatchers.Default).launch { delay(900); holdUntil = 0; fadeTo(prev, 500, true, true) }
    }

    @Synchronized fun fadeTo(c: Int, ms: Long, smooth: Boolean = false, force: Boolean = false) {
        if (!force && System.currentTimeMillis() < holdUntil) return
        if (paused) { paused = false; lastSent = -1 }
        from = cur; to = c; t0 = System.nanoTime(); dur = ms * 1_000_000; ease = smooth
    }

    @Synchronized private fun compute(): Int {
        if (dur <= 0) return to
        var t = ((System.nanoTime() - t0).toFloat() / dur).coerceIn(0f, 1f)
        if (ease) t = t * t * (3 - 2 * t)
        return ColorUtils.blendARGB(from, to, t)
    }

    private fun startRender() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.Default).launch {
            var lastWrite = 0L
            while (isActive) {
                val c = compute(); cur = c
                val k = bright.value
                val key = Color.rgb((Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())
                val now = System.currentTimeMillis()
                if (!paused && key != lastSent && (!busy || now - lastWrite > 80)) {
                    if (write(key)) { lastSent = key; lastWrite = now }
                }
                delay(25)
            }
        }
    }

    fun connect(ctx: Context) {
        val sc = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner
        if (sc == null) { status.value = "Turn on Bluetooth"; return }
        status.value = "Scanning..."
        sc.startScan(object : ScanCallback() {
            override fun onScanResult(t: Int, r: ScanResult) {
                if (r.device.name?.startsWith("LEDDMX") == true) {
                    sc.stopScan(this)
                    gatt = r.device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                }
            }
        })
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, s: Int, n: Int) {
            if (n == BluetoothProfile.STATE_CONNECTED) { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); g.discoverServices() }
            else { chr = null; status.value = "Disconnected" }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            chr = g.getService(SVC)?.getCharacteristic(CHR)
            status.value = if (chr != null) "Connected" else "Service not found"
            lastSent = -1; busy = false
            if (chr != null) startRender()
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) { busy = false }
    }

    // Protocol (tested): 7B FF 07 R G B 00 FF BF on 0xFFE1
    private fun write(c: Int) = writeBytes(byteArrayOf(0x7B, 0xFF.toByte(), 0x07,
        Color.red(c).toByte(), Color.green(c).toByte(), Color.blue(c).toByte(), 0, 0xFF.toByte(), 0xBF.toByte()))

    private fun writeBytes(b: ByteArray): Boolean {
        val ch = chr ?: return false
        val g = gatt ?: return false
        busy = true
        val ok = if (Build.VERSION.SDK_INT >= 33)
            g.writeCharacteristic(ch, b, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
        else { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; ch.value = b; g.writeCharacteristic(ch) }
        if (!ok) busy = false
        return ok
    }

    // From the snoop log: 03 = built-in effect number, 05 04 00 <n> 03 = pixel count
    private fun raw(vararg v: Int) {
        val b = ByteArray(9) { v[it].toByte() }
        CoroutineScope(Dispatchers.Default).launch { repeat(10) { if (writeBytes(b)) return@launch; delay(30) } }
    }
    fun effect(id: Int) { paused = true; raw(0x7B, 0xFF, 0x03, id, 0xFF, 0xFF, 0xFF, 0xFF, 0xBF) }
    fun setPixels(n: Int) = raw(0x7B, 0xFF, 0x05, 0x04, 0x00, n.coerceIn(1, 255), 0x03, 0xFF, 0xBF)
}

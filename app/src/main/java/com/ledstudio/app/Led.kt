package com.ledstudio.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.compose.runtime.mutableStateOf
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

    fun hsv(h: Float) = Color.HSVToColor(floatArrayOf(h, 1f, 1f))

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
            if (n == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else { chr = null; status.value = "Disconnected" }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            chr = g.getService(SVC)?.getCharacteristic(CHR)
            status.value = if (chr != null) "Connected" else "Service not found"
        }
    }

    // Protocol (tested): 7B FF 07 R G B 00 FF BF on 0xFFE1
    fun color(c: Int) {
        val ch = chr ?: return
        val g = gatt ?: return
        val k = bright.value
        val b = byteArrayOf(0x7B, 0xFF.toByte(), 0x07,
            (Color.red(c) * k).toInt().toByte(), (Color.green(c) * k).toInt().toByte(), (Color.blue(c) * k).toInt().toByte(),
            0, 0xFF.toByte(), 0xBF.toByte())
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, b, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        else { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; ch.value = b; g.writeCharacteristic(ch) }
    }
}

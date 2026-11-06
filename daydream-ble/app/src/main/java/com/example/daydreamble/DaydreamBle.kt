package com.example.daydreamble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import kotlin.math.PI

/* ------------------------------------------------------------------ */
/*  UUID của tay cầm Daydream                                          */
/* ------------------------------------------------------------------ */
object DaydreamUuid {
    /** Service riêng của tay cầm Daydream (16-bit alias 0xFE55). */
    val SERVICE: UUID = UUID.fromString("0000fe55-0000-1000-8000-00805f9b34fb")

    /** Characteristic phát notify 20 byte, ~60 Hz. */
    val CHAR_DATA: UUID = UUID.fromString("00000001-1000-1000-8000-00805f9b34fb")

    /** Client Characteristic Configuration Descriptor (bật notify). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Battery Service / Battery Level (tuỳ chọn). */
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
}

/* ------------------------------------------------------------------ */
/*  Trạng thái đã giải mã của tay cầm                                  */
/* ------------------------------------------------------------------ */
data class DaydreamState(
    val time: Int,
    val seq: Int,
    // Vector xoay (orientation), đơn vị radian
    val oriX: Double, val oriY: Double, val oriZ: Double,
    // Gia tốc kế, đơn vị m/s^2
    val accX: Double, val accY: Double, val accZ: Double,
    // Con quay hồi chuyển, đơn vị rad/s
    val gyrX: Double, val gyrY: Double, val gyrZ: Double,
    // Touchpad: 0.0 .. 1.0 (gốc toạ độ ở góc trên-trái)
    val touchX: Double, val touchY: Double,
    val touching: Boolean,
    // Nút bấm
    val clickBtn: Boolean,   // nhấn lún touchpad
    val homeBtn: Boolean,    // nút tròn dưới (Home)
    val appBtn: Boolean,     // nút App / Menu (phía trên)
    val volDownBtn: Boolean,
    val volUpBtn: Boolean
) {
    val anyButton: Boolean
        get() = clickBtn || homeBtn || appBtn || volDownBtn || volUpBtn
}

/* ------------------------------------------------------------------ */
/*  Bộ giải mã gói 20 byte                                             */
/* ------------------------------------------------------------------ */
/**
 * Gói tin là một chuỗi bit liên tục, KHÔNG căn theo byte:
 *
 *   bit   0..8    time       (9 bit)
 *   bit   9..13   sequence   (5 bit)
 *   bit  14..26   ori.x      (13 bit, bù 2)
 *   bit  27..39   ori.y
 *   bit  40..52   ori.z
 *   bit  53..65   acc.x
 *   bit  66..78   acc.y
 *   bit  79..91   acc.z
 *   bit  92..104  gyr.x
 *   bit 105..117  gyr.y
 *   bit 118..130  gyr.z
 *   bit 131..138  touch.x    (8 bit, 0..255)
 *   bit 139..146  touch.y    (8 bit, 0..255)
 *   bit 147..151  5 nút bấm
 */
object DaydreamDecoder {

    private const val ORI_SCALE = 2.0 * PI / 4095.0
    private const val ACC_SCALE = 8.0 * 9.8 / 4095.0
    private const val GYR_SCALE = 2048.0 / 180.0 * PI / 4095.0

    /** Mở rộng dấu cho số bù 2 độ rộng [bits]. */
    private fun signed(v: Int, bits: Int): Int =
        if (v and (1 shl (bits - 1)) != 0) v - (1 shl bits) else v

    fun decode(b: ByteArray): DaydreamState? {
        if (b.size < 20) return null
        val u = IntArray(20) { b[it].toInt() and 0xFF }

        val time = (u[0] shl 1) or ((u[1] and 0x80) shr 7)
        val seq = (u[1] and 0x7C) shr 2

        val oriXr = signed(((u[1] and 0x03) shl 11) or (u[2] shl 3) or ((u[3] and 0xE0) shr 5), 13)
        val oriYr = signed(((u[3] and 0x1F) shl 8) or u[4], 13)
        val oriZr = signed((u[5] shl 5) or ((u[6] and 0xF8) shr 3), 13)

        val accXr = signed(((u[6] and 0x07) shl 10) or (u[7] shl 2) or ((u[8] and 0xC0) shr 6), 13)
        val accYr = signed(((u[8] and 0x3F) shl 7) or ((u[9] and 0xFE) shr 1), 13)
        val accZr = signed(((u[9] and 0x01) shl 12) or (u[10] shl 4) or ((u[11] and 0xF0) shr 4), 13)

        val gyrXr = signed(((u[11] and 0x0F) shl 9) or (u[12] shl 1) or ((u[13] and 0x80) shr 7), 13)
        val gyrYr = signed(((u[13] and 0x7F) shl 6) or ((u[14] and 0xFC) shr 2), 13)
        val gyrZr = signed(((u[14] and 0x03) shl 11) or (u[15] shl 3) or ((u[16] and 0xE0) shr 5), 13)

        val txRaw = ((u[16] and 0x1F) shl 3) or ((u[17] and 0xE0) shr 5)
        val tyRaw = ((u[17] and 0x1F) shl 3) or ((u[18] and 0xE0) shr 5)

        // 5 bit thấp nhất của byte 18 là các nút.
        // Nếu máy bạn báo sai nút, hãy xem log HEX rồi hoán đổi các mask dưới đây.
        val clickBtn = (u[18] and 0x01) != 0
        val homeBtn = (u[18] and 0x02) != 0
        val appBtn = (u[18] and 0x04) != 0
        val volDown = (u[18] and 0x08) != 0
        val volUp = (u[18] and 0x10) != 0

        return DaydreamState(
            time = time,
            seq = seq,
            oriX = oriXr * ORI_SCALE, oriY = oriYr * ORI_SCALE, oriZ = oriZr * ORI_SCALE,
            accX = accXr * ACC_SCALE, accY = accYr * ACC_SCALE, accZ = accZr * ACC_SCALE,
            gyrX = gyrXr * GYR_SCALE, gyrY = gyrYr * GYR_SCALE, gyrZ = gyrZr * GYR_SCALE,
            touchX = txRaw / 255.0, touchY = tyRaw / 255.0,
            touching = txRaw != 0 || tyRaw != 0,
            clickBtn = clickBtn, homeBtn = homeBtn, appBtn = appBtn,
            volDownBtn = volDown, volUpBtn = volUp
        )
    }

    fun toHex(b: ByteArray): String =
        b.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
}

/* ------------------------------------------------------------------ */
/*  Client GATT                                                        */
/* ------------------------------------------------------------------ */
@SuppressLint("MissingPermission")
class DaydreamBleClient(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onLog(msg: String)
        fun onConnectionState(connected: Boolean, deviceName: String?)
        fun onFrame(state: DaydreamState, raw: ByteArray)
        fun onBattery(percent: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null

    private fun log(m: String) = main.post { listener.onLog(m) }

    fun connect(device: BluetoothDevice) {
        close()
        log("Đang kết nối tới ${device.address} …")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Đã kết nối (status=$status). Đang dò GATT service…")
                    main.post { listener.onConnectionState(true, g.device.name) }
                    // Chờ một nhịp rồi mới discover: ổn định hơn trên nhiều máy.
                    main.postDelayed({ runCatching { g.discoverServices() } }, 400)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Ngắt kết nối (status=$status)")
                    main.post { listener.onConnectionState(false, null) }
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("discoverServices thất bại: $status")
                return
            }
            log("Tìm thấy ${g.services.size} service:")
            g.services.forEach { s -> log("  • ${s.uuid}") }

            val svc = g.getService(DaydreamUuid.SERVICE)
            if (svc == null) {
                log("KHÔNG thấy service 0xFE55 → thiết bị này không phải tay cầm Daydream.")
                return
            }
            val ch = svc.getCharacteristic(DaydreamUuid.CHAR_DATA)
            if (ch == null) {
                log("Không thấy characteristic dữ liệu 0x0001.")
                return
            }
            if (!g.setCharacteristicNotification(ch, true)) {
                log("setCharacteristicNotification trả về false.")
                return
            }
            val cccd = ch.getDescriptor(DaydreamUuid.CCCD)
            if (cccd == null) {
                log("Không thấy CCCD 0x2902.")
                return
            }
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            log("Đang bật notify…")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == DaydreamUuid.CCCD) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("Notify đã bật. Bắt đầu nhận dữ liệu (~60 gói/giây).")
                } else {
                    log("Bật notify thất bại: $status")
                }
                // GATT phải nối tiếp từng thao tác: đọc pin sau khi ghi xong.
                main.postDelayed({ readBattery(g) }, 250)
            }
        }

        private fun readBattery(g: BluetoothGatt) {
            val ch = g.getService(DaydreamUuid.BATTERY_SERVICE)
                ?.getCharacteristic(DaydreamUuid.BATTERY_LEVEL) ?: return
            runCatching { g.readCharacteristic(ch) }
        }

        // --- Android 13 trở lên ---
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) = handleFrame(c, value)

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) = handleRead(c, value, status)

        // --- Android 12 trở xuống ---
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleFrame(c, c.value ?: return)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            handleRead(c, c.value ?: return, status)
        }

        private fun handleFrame(c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != DaydreamUuid.CHAR_DATA) return
            val state = DaydreamDecoder.decode(value) ?: return
            main.post { listener.onFrame(state, value) }
        }

        private fun handleRead(c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                c.uuid == DaydreamUuid.BATTERY_LEVEL && value.isNotEmpty()
            ) {
                val pct = value[0].toInt() and 0xFF
                main.post { listener.onBattery(pct) }
            }
        }
    }
}

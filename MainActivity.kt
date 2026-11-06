package com.example.daydreamble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), DaydreamBleClient.Listener {

    private companion object {
        const val SCAN_MILLIS = 20_000L
        const val UI_REFRESH_MS = 60L
        const val MAX_LOG_CHARS = 20_000
    }

    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnPerm: Button
    private lateinit var btnLocation: Button
    private lateinit var btnClearLog: Button
    private lateinit var btnAccess: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnVr: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvState: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var listDevices: ListView

    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val found = mutableListOf<BluetoothDevice>()
    private val labels = mutableListOf<String>()
    private lateinit var listAdapter: ArrayAdapter<String>

    private val ui = Handler(Looper.getMainLooper())
    private var scanning = false

    private lateinit var ble: DaydreamBleClient
    private var lastState: DaydreamState? = null
    private var lastRaw: ByteArray? = null
    private var prevButtons = 0
    private var frameCount = 0
    private var lastRateStamp = 0L
    private var fps = 0

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /* ============================================================ */
    /*  DANH SÁCH QUYỀN                                              */
    /* ============================================================ */

    /**
     * Vị trí luôn nằm trong danh sách, kể cả Android 12+.
     * Google nói API 31+ không cần, nhưng rất nhiều ROM vẫn trả về
     * danh sách quét rỗng nếu app chưa có quyền vị trí.
     */
    private fun neededPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun isGranted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissions() = neededPermissions().filterNot { isGranted(it) }

    private fun shortName(p: String) = p.substringAfterLast('.')

    /* ============================================================ */
    /*  VÒNG ĐỜI                                                     */
    /* ============================================================ */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnPerm = findViewById(R.id.btnPerm)
        btnLocation = findViewById(R.id.btnLocation)
        btnClearLog = findViewById(R.id.btnClearLog)
        btnAccess = findViewById(R.id.btnAccess)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnVr = findViewById(R.id.btnVr)
        tvStatus = findViewById(R.id.tvStatus)
        tvState = findViewById(R.id.tvState)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        listDevices = findViewById(R.id.listDevices)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listDevices.adapter = listAdapter
        listDevices.setOnItemClickListener { _, _, pos, _ ->
            stopScan()
            val dev = found[pos]
            // Ghi nhớ địa chỉ để dịch vụ điều khiển tự kết nối lại sau này.
            getSharedPreferences(ControlService.PREFS, Context.MODE_PRIVATE)
                .edit().putString(ControlService.KEY_MAC, dev.address).apply()

            val svc = ControlService.instance
            if (svc != null) {
                // Dịch vụ điều khiển đang bật: để nó giữ kết nối, tránh tranh chấp GATT.
                ble.close()
                svc.reconnect()
                onLog("Đã giao tay cầm cho dịch vụ điều khiển (${dev.address}).")
            } else {
                ble.connect(dev)
            }
        }

        ble = DaydreamBleClient(this, this)

        btnScan.setOnClickListener { if (scanning) stopScan() else ensureReadyThenScan() }
        btnDisconnect.setOnClickListener { ble.close(); onLog("Đã yêu cầu ngắt kết nối.") }
        btnPerm.setOnClickListener { showPermissionDialog() }
        btnLocation.setOnClickListener { openLocationSettings() }
        btnClearLog.setOnClickListener { tvLog.text = "" }
        btnAccess.setOnClickListener { openAccessibilitySettings() }
        btnOverlay.setOnClickListener { requestOverlayPermission() }
        btnVr.setOnClickListener {
            val saved = getSharedPreferences(ControlService.PREFS, Context.MODE_PRIVATE)
                .getString(ControlService.KEY_MAC, null)
            if (saved == null) {
                onLog("Hãy quét và chọn tay cầm một lần trước khi vào VR.")
            } else {
                ble.close()
                startActivity(Intent(this, VrActivity::class.java))
            }
        }

        tvStatus.text = "Chưa kết nối"
        onLog("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        logPermissionState()
        onLog(
            "Dịch vụ điều khiển: " +
                    if (ControlService.instance != null) "ĐANG BẬT" else "chưa bật (nút Bật điều khiển)"
        )
        onLog("Giữ nút Home của tay cầm ~3 giây tới khi LED nháy, rồi bấm Quét thiết bị.")

        // Hỏi quyền ngay khi mở app để bạn không phải đi tìm.
        if (missingPermissions().isNotEmpty()) {
            onLog("Đang xin quyền…")
            permissionLauncher.launch(missingPermissions().toTypedArray())
        }
        startUiLoop()
    }

    override fun onDestroy() {
        stopScan()
        ble.close()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun logPermissionState() {
        val s = neededPermissions().joinToString("  ") {
            "${shortName(it)}=${if (isGranted(it)) "OK" else "THIẾU"}"
        }
        onLog("Quyền: $s")
        onLog("Dịch vụ vị trí: ${if (isLocationOn()) "đang bật" else "ĐANG TẮT"}")
    }

    /* ============================================================ */
    /*  XIN QUYỀN                                                    */
    /* ============================================================ */

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isEmpty()) {
                onLog("Đã cấp đủ quyền.")
                return@registerForActivityResult
            }
            // Nếu hệ thống không cho hiện lại hộp thoại nghĩa là người dùng đã
            // chọn "Không hỏi lại" -> phải vào Cài đặt bật tay.
            val permanentlyDenied = denied.any {
                !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
            }
            onLog("Chưa cấp: ${denied.joinToString { shortName(it) }}")
            if (permanentlyDenied) showGoToSettingsDialog(denied.map { shortName(it) })
        }

    private fun showPermissionDialog() {
        val lines = neededPermissions().joinToString("\n") {
            "${if (isGranted(it)) "✔" else "✘"}  ${shortName(it)}"
        }
        val locLine = "\n\nDịch vụ vị trí: ${if (isLocationOn()) "đang bật" else "ĐANG TẮT"}"
        AlertDialog.Builder(this)
            .setTitle("Trạng thái quyền")
            .setMessage(lines + locLine)
            .setPositiveButton("Xin quyền còn thiếu") { _, _ ->
                val missing = missingPermissions()
                if (missing.isEmpty()) {
                    onLog("Đã có đủ quyền rồi.")
                } else {
                    permissionLauncher.launch(missing.toTypedArray())
                }
            }
            .setNeutralButton("Mở cài đặt app") { _, _ -> openAppSettings() }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showGoToSettingsDialog(names: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("Cần bật quyền thủ công")
            .setMessage(
                "Các quyền sau đã bị từ chối vĩnh viễn:\n\n${names.joinToString("\n")}\n\n" +
                        "Mở Cài đặt > Quyền, chọn Vị trí = \"Cho phép khi dùng ứng dụng\" " +
                        "và Thiết bị ở gần = Cho phép."
            )
            .setPositiveButton("Mở cài đặt") { _, _ -> openAppSettings() }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }.onFailure { onLog("Không mở được trang cài đặt app.") }
    }

    private fun openLocationSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            .onFailure { onLog("Không mở được cài đặt vị trí.") }
    }

    private fun openAccessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle("Bật điều khiển điện thoại")
            .setMessage(
                "Trong màn hình Trợ năng sắp mở, tìm mục \"Daydream Controller\" " +
                        "(thường nằm trong nhóm Ứng dụng đã cài đặt) và bật nó lên.\n\n" +
                        "Android sẽ cảnh báo dịch vụ có thể quan sát thao tác. Đó là cảnh báo " +
                        "chung cho mọi dịch vụ trợ năng; app này chỉ gửi thao tác chạm, " +
                        "không đọc nội dung màn hình.\n\n" +
                        "Nhớ bấm \"Quyền con trỏ\" trước thì mới thấy chấm đỏ."
            )
            .setPositiveButton("Mở Trợ năng") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure { onLog("Không mở được cài đặt Trợ năng.") }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            onLog("Phiên bản Android này không cần xin quyền hiển thị."); return
        }
        if (Settings.canDrawOverlays(this)) {
            onLog("Quyền hiển thị trên ứng dụng khác: đã có."); return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure { onLog("Không mở được trang quyền hiển thị.") }
    }

    private fun isLocationOn(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    /* ============================================================ */
    /*  CHUẨN BỊ RỒI QUÉT                                            */
    /* ============================================================ */

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (adapter?.isEnabled == true) ensureReadyThenScan() else onLog("Bluetooth vẫn đang tắt.")
        }

    private fun ensureReadyThenScan() {
        val ad = adapter
        if (ad == null) { onLog("Máy này không có Bluetooth."); return }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            onLog("Máy này không hỗ trợ Bluetooth Low Energy."); return
        }

        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            onLog("Thiếu quyền: ${missing.joinToString { shortName(it) }} → đang xin…")
            val permanentlyDenied = missing.any {
                !ActivityCompat.shouldShowRequestPermissionRationale(this, it) &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            }
            if (permanentlyDenied) {
                showGoToSettingsDialog(missing.map { shortName(it) })
            } else {
                permissionLauncher.launch(missing.toTypedArray())
            }
            return
        }

        if (!ad.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        if (!isLocationOn()) {
            AlertDialog.Builder(this)
                .setTitle("Dịch vụ vị trí đang tắt")
                .setMessage(
                    "Android dùng chung hạ tầng vị trí cho việc quét Bluetooth. " +
                            "Vị trí tắt thì danh sách quét thường rỗng.\n\nBật vị trí rồi quay lại?"
                )
                .setPositiveButton("Mở cài đặt vị trí") { _, _ -> openLocationSettings() }
                .setNegativeButton("Cứ quét thử") { _, _ -> startScan() }
                .show()
            return
        }

        startScan()
    }

    /* ============================================================ */
    /*  QUÉT BLE                                                     */
    /* ============================================================ */

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = add(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { add(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            btnScan.text = "Quét thiết bị"
            val reason = when (errorCode) {
                1 -> "đã quét rồi"
                2 -> "không đăng ký được app (thử tắt/bật Bluetooth)"
                3 -> "lỗi nội bộ"
                4 -> "chip không hỗ trợ"
                else -> "mã $errorCode"
            }
            onLog("Quét thất bại: $reason")
        }

        private fun add(r: ScanResult) {
            val dev = r.device
            if (found.any { it.address == dev.address }) return

            val name = runCatching { dev.name }.getOrNull() ?: r.scanRecord?.deviceName
            val isDaydream = name?.contains("daydream", true) == true ||
                    r.scanRecord?.serviceUuids?.any { it.uuid == DaydreamUuid.SERVICE } == true

            if (name == null && !isDaydream) return

            found.add(dev)
            val mark = if (isDaydream) "★ " else ""
            labels.add("$mark${name ?: "(không tên)"}\n${dev.address}   ${r.rssi} dBm")
            listAdapter.notifyDataSetChanged()
            if (isDaydream) onLog("Thấy tay cầm Daydream: ${dev.address}")
        }
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            onLog("Không lấy được bluetoothLeScanner. Thử tắt/bật Bluetooth."); return
        }
        found.clear(); labels.clear(); listAdapter.notifyDataSetChanged()

        // Hiện luôn các thiết bị đã ghép nối, phòng khi quét không ra.
        runCatching {
            adapter?.bondedDevices?.forEach { dev ->
                found.add(dev)
                labels.add("(đã ghép nối) ${dev.name ?: "?"}\n${dev.address}")
            }
        }
        if (found.isNotEmpty()) listAdapter.notifyDataSetChanged()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        scanning = true
        btnScan.text = "Dừng quét"
        onLog("Bắt đầu quét (${SCAN_MILLIS / 1000} giây)…")
        ui.postDelayed({ if (scanning) stopScan() }, SCAN_MILLIS)
    }

    private fun stopScan() {
        if (!scanning) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        btnScan.text = "Quét thiết bị"
        onLog("Đã dừng quét. Danh sách có ${found.size} thiết bị.")
        if (found.isEmpty()) {
            onLog("Không thấy gì. Kiểm tra: LED tay cầm có nháy không, quyền Vị trí, dịch vụ vị trí.")
        }
    }

    /* ============================================================ */
    /*  CALLBACK TỪ BLE                                              */
    /* ============================================================ */

    override fun onLog(msg: String) {
        tvLog.append("${timeFmt.format(Date())}  $msg\n")
        if (tvLog.length() > MAX_LOG_CHARS) {
            tvLog.text = tvLog.text.substring(tvLog.length() - MAX_LOG_CHARS / 2)
        }
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onConnectionState(connected: Boolean, deviceName: String?) {
        tvStatus.text = if (connected) "Đã kết nối: ${deviceName ?: "thiết bị"}" else "Chưa kết nối"
        if (!connected) { lastState = null; tvState.text = "—" }
    }

    override fun onBattery(percent: Int) = onLog("Pin tay cầm: $percent%")

    override fun onFrame(state: DaydreamState, raw: ByteArray) {
        lastState = state
        lastRaw = raw
        frameCount++

        val now = System.currentTimeMillis()
        if (now - lastRateStamp >= 1000) { fps = frameCount; frameCount = 0; lastRateStamp = now }

        val mask = (if (state.clickBtn) 1 else 0) or
                (if (state.homeBtn) 2 else 0) or
                (if (state.appBtn) 4 else 0) or
                (if (state.volDownBtn) 8 else 0) or
                (if (state.volUpBtn) 16 else 0)
        if (mask != prevButtons) {
            val names = buildList {
                if (state.clickBtn) add("CLICK")
                if (state.homeBtn) add("HOME")
                if (state.appBtn) add("APP")
                if (state.volDownBtn) add("VOL-")
                if (state.volUpBtn) add("VOL+")
            }
            onLog(
                "Nút: ${if (names.isEmpty()) "(nhả hết)" else names.joinToString("+")}" +
                        "   raw=${DaydreamDecoder.toHex(raw)}"
            )
            prevButtons = mask
        }
    }

    /* ============================================================ */
    /*  VẼ UI                                                        */
    /* ============================================================ */

    private fun startUiLoop() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                lastState?.let { render(it) }
                ui.postDelayed(this, UI_REFRESH_MS)
            }
        }, UI_REFRESH_MS)
    }

    private fun render(s: DaydreamState) {
        fun f(v: Double) = String.format(Locale.US, "%+7.3f", v)
        fun on(b: Boolean) = if (b) "[X]" else "[ ]"

        tvState.text = buildString {
            append("seq ${s.seq}   $fps gói/giây\n\n")
            append("${on(s.clickBtn)} Click   ${on(s.homeBtn)} Home   ${on(s.appBtn)} App\n")
            append("${on(s.volDownBtn)} Vol-    ${on(s.volUpBtn)} Vol+\n\n")
            append(
                if (s.touching)
                    "Touchpad  x=${String.format(Locale.US, "%.3f", s.touchX)}" +
                            "  y=${String.format(Locale.US, "%.3f", s.touchY)}\n\n"
                else "Touchpad  (không chạm)\n\n"
            )
            append("Ori (rad)  ${f(s.oriX)} ${f(s.oriY)} ${f(s.oriZ)}\n")
            append("Acc (m/s²) ${f(s.accX)} ${f(s.accY)} ${f(s.accZ)}\n")
            append("Gyr (rad/s)${f(s.gyrX)} ${f(s.gyrY)} ${f(s.gyrZ)}\n\n")
            append("RAW ${lastRaw?.let { DaydreamDecoder.toHex(it) } ?: ""}")
        }
    }
}

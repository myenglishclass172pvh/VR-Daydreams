package com.example.daydreamble

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Biến tay cầm Daydream thành thiết bị điều khiển thật cho toàn bộ điện thoại.
 *
 * Android không cho app thường bơm sự kiện chạm vào app khác. Cách duy nhất
 * hợp lệ (không cần root) là AccessibilityService + dispatchGesture().
 *
 * Bản đồ phím:
 *   Lướt touchpad            -> di chuyển con trỏ
 *   Nhấn lún touchpad        -> chạm (tap) tại con trỏ
 *   Nhấn lún + lướt          -> vuốt / cuộn trang
 *   Nút App (trên)           -> Quay lại (Back)
 *   Giữ App + lướt dọc       -> phóng to / thu nhỏ (pinch zoom)
 *   Nút Home (dưới)          -> về Màn hình chính
 *   Nhấn Home 2 lần nhanh    -> Đa nhiệm (Recents)
 *   Vol+ / Vol-              -> tăng / giảm âm lượng
 */
@SuppressLint("MissingPermission")
class ControlService : AccessibilityService(), DaydreamBleClient.Listener {

    companion object {
        @Volatile
        var instance: ControlService? = null

        const val PREFS = "daydream_prefs"
        const val KEY_MAC = "last_mac"

        /** Độ nhạy con trỏ. Tăng số này nếu thấy con trỏ chạy chậm. */
        const val SENSITIVITY = 2.5f

        const val TAP_MAX_DRIFT = 40f      // px, dưới mức này coi là tap
        const val DOUBLE_TAP_MS = 400L
        const val ZOOM_THRESHOLD = 60f     // px, vượt mức này mới phát pinch
    }

    private val main = Handler(Looper.getMainLooper())
    private var ble: DaydreamBleClient? = null

    private var wm: WindowManager? = null
    private var cursor: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var cursorSize = 0

    private var sw = 0
    private var sh = 0
    private var cx = 0f
    private var cy = 0f

    // trạng thái touchpad
    private var prevTouching = false
    private var prevTx = 0.0
    private var prevTy = 0.0

    // trạng thái nút
    private var clickDown = false
    private var pressX = 0f
    private var pressY = 0f
    private var prevApp = false
    private var prevHome = false
    private var prevVolUp = false
    private var prevVolDown = false
    private var lastHomeAt = 0L

    // tích luỹ zoom khi đang giữ nút App
    private var zoomAccum = 0f
    private var zoomUsed = false

    private var wantReconnect = true

    /**
     * Khi màn hình VR đang mở, nó gán hàm này để mượn luồng dữ liệu.
     * Lúc đó dịch vụ ngừng phát thao tác chạm, nếu không tay cầm sẽ
     * vừa điều khiển trong VR vừa bấm lung tung vào app phía dưới.
     */
    @Volatile
    var frameSink: ((DaydreamState) -> Unit)? = null

    /* ============================================================ */

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        sw = dm.widthPixels
        sh = dm.heightPixels
        cx = sw / 2f
        cy = sh / 2f
        cursorSize = (dm.density * 26).toInt()

        showCursor()
        wantReconnect = true
        connectSaved()
        toast("Daydream: dịch vụ điều khiển đã bật")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        wantReconnect = false
        ble?.close()
        ble = null
        hideCursor()
        instance = null
    }

    private fun toast(m: String) = main.post {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    /* ============================================================ */
    /*  KẾT NỐI BLE                                                  */
    /* ============================================================ */

    /** MainActivity gọi hàm này sau khi người dùng chọn tay cầm. */
    fun reconnect() {
        wantReconnect = true
        ble?.close()
        main.postDelayed({ connectSaved() }, 300)
    }

    private fun connectSaved() {
        if (!wantReconnect) return
        val mac = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MAC, null)
        if (mac == null) {
            toast("Chưa chọn tay cầm. Mở app Daydream BLE và quét trước.")
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            main.postDelayed({ connectSaved() }, 5000)
            return
        }
        val dev = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: return
        if (ble == null) ble = DaydreamBleClient(this, this)
        ble?.connect(dev)
    }

    override fun onLog(msg: String) = Unit

    override fun onBattery(percent: Int) = Unit

    override fun onConnectionState(connected: Boolean, deviceName: String?) {
        if (connected) {
            toast("Tay cầm đã kết nối")
        } else if (wantReconnect) {
            // tự thử lại, tay cầm hay tự ngủ
            main.postDelayed({ connectSaved() }, 5000)
        }
    }

    /* ============================================================ */
    /*  CON TRỎ NỔI                                                  */
    /* ============================================================ */

    private class CursorView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 64, 64)
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        override fun onDraw(c: Canvas) {
            val r = width / 2f
            c.drawCircle(r, r, r - 5f, fill)
            c.drawCircle(r, r, r - 5f, ring)
        }
    }

    private fun showCursor() {
        if (cursor != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            toast("Thiếu quyền Hiển thị trên ứng dụng khác, con trỏ sẽ không hiện")
            return
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            cursorSize, cursorSize, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (cx - cursorSize / 2).toInt()
            y = (cy - cursorSize / 2).toInt()
        }
        val v = CursorView(this)
        runCatching { wm?.addView(v, params) }
            .onSuccess { cursor = v; lp = params }
    }

    private fun hideCursor() {
        cursor?.let { v -> runCatching { wm?.removeView(v) } }
        cursor = null
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cx = (cx + dx).coerceIn(1f, sw - 2f)
        cy = (cy + dy).coerceIn(1f, sh - 2f)
        val v = cursor ?: return
        val p = lp ?: return
        p.x = (cx - cursorSize / 2).toInt()
        p.y = (cy - cursorSize / 2).toInt()
        runCatching { wm?.updateViewLayout(v, p) }
    }

    /* ============================================================ */
    /*  XỬ LÝ DỮ LIỆU TAY CẦM                                        */
    /* ============================================================ */

    override fun onFrame(s: DaydreamState, raw: ByteArray) {
        frameSink?.let { sink -> sink(s); return }

        val now = System.currentTimeMillis()

        /* ---- touchpad ---- */
        if (s.touching) {
            if (prevTouching) {
                val dx = ((s.touchX - prevTx) * sw * SENSITIVITY).toFloat()
                val dy = ((s.touchY - prevTy) * sh * SENSITIVITY).toFloat()
                if (s.appBtn) {
                    zoomAccum -= dy          // lướt lên = phóng to
                } else {
                    moveCursor(dx, dy)
                }
            }
            prevTx = s.touchX
            prevTy = s.touchY
        } else if (prevTouching) {
            // vừa nhấc ngón tay: nếu đang giữ App thì phát lệnh zoom
            if (s.appBtn && abs(zoomAccum) > ZOOM_THRESHOLD) {
                pinch(zoomAccum.coerceIn(-400f, 400f))
                zoomUsed = true
            }
            zoomAccum = 0f
        }
        prevTouching = s.touching

        /* ---- nhấn lún touchpad ---- */
        if (s.clickBtn && !clickDown) {
            clickDown = true
            pressX = cx
            pressY = cy
        } else if (!s.clickBtn && clickDown) {
            clickDown = false
            val drift = hypot(cx - pressX, cy - pressY)
            if (drift > TAP_MAX_DRIFT) swipe(pressX, pressY, cx, cy, 260) else tap(cx, cy)
        }

        /* ---- nút App = Back, hoặc zoom ---- */
        if (s.appBtn && !prevApp) {
            zoomAccum = 0f
            zoomUsed = false
        }
        if (!s.appBtn && prevApp) {
            if (!zoomUsed) performGlobalAction(GLOBAL_ACTION_BACK)
            zoomUsed = false
        }
        prevApp = s.appBtn

        /* ---- nút Home ---- */
        if (!s.homeBtn && prevHome) {
            if (now - lastHomeAt < DOUBLE_TAP_MS) {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                lastHomeAt = 0
            } else {
                lastHomeAt = now
                main.postDelayed({
                    if (lastHomeAt != 0L && System.currentTimeMillis() - lastHomeAt >= DOUBLE_TAP_MS) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        lastHomeAt = 0
                    }
                }, DOUBLE_TAP_MS + 20)
            }
        }
        prevHome = s.homeBtn

        /* ---- âm lượng ---- */
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (s.volUpBtn && !prevVolUp) audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
        )
        if (s.volDownBtn && !prevVolDown) audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
        )
        prevVolUp = s.volUpBtn
        prevVolDown = s.volDownBtn
    }

    /* ============================================================ */
    /*  PHÁT THAO TÁC CHẠM                                           */
    /* ============================================================ */

    private fun clampX(v: Float) = v.coerceIn(1f, sw - 2f)
    private fun clampY(v: Float) = v.coerceIn(1f, sh - 2f)

    private fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(clampX(x), clampY(y)) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        runCatching { dispatchGesture(g, null, null) }
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val p = Path().apply {
            moveTo(clampX(x1), clampY(y1))
            lineTo(clampX(x2), clampY(y2))
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        runCatching { dispatchGesture(g, null, null) }
    }

    /** amount > 0 = phóng to, < 0 = thu nhỏ. */
    private fun pinch(amount: Float) {
        val start = 140f
        val end = (start + amount / 2f).coerceAtLeast(30f)
        val left = Path().apply {
            moveTo(clampX(cx - start), clampY(cy))
            lineTo(clampX(cx - end), clampY(cy))
        }
        val right = Path().apply {
            moveTo(clampX(cx + start), clampY(cy))
            lineTo(clampX(cx + end), clampY(cy))
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(left, 0, 320))
            .addStroke(GestureDescription.StrokeDescription(right, 0, 320))
            .build()
        runCatching { dispatchGesture(g, null, null) }
    }
}

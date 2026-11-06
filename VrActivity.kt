package com.example.daydreamble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Màn hình VR thay cho Daydream Home.
 *
 * Điều khiển:
 *   Lướt ngang touchpad  -> xoay bảng chọn
 *   Nhấn lún touchpad    -> mở mục đang ngắm
 *   Nút App              -> căn giữa lại (recenter)
 *   Nút Home             -> thoát VR
 *   Vol+                 -> bật/tắt bù méo thấu kính
 *   Vol-                 -> đổi khoảng cách hai mắt
 */
@SuppressLint("MissingPermission")
class VrActivity : AppCompatActivity(), SensorEventListener, DaydreamBleClient.Listener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private lateinit var sensors: SensorManager
    private var rotSensor: Sensor? = null

    private val main = Handler(Looper.getMainLooper())
    private var ownBle: DaydreamBleClient? = null

    private val rotM = FloatArray(16)
    private val remapped = FloatArray(16)
    private val headView = FloatArray(16)
    private val ctrlView = FloatArray(16)

    private var prevTouching = false
    private var prevTx = 0.0
    private var prevClick = false
    private var prevApp = false
    private var prevHome = false
    private var prevVolUp = false
    private var prevVolDown = false
    private var currentYaw = 0f

    /* ============================================================ */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        renderer = VrRenderer(buildTiles())
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        immersive()

        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotSensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        Matrix.setIdentityM(headView, 0)
        Matrix.setIdentityM(ctrlView, 0)
    }

    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onResume() {
        super.onResume()
        immersive()
        glView.onResume()
        rotSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        // Nếu dịch vụ điều khiển đang chạy thì mượn luồng dữ liệu của nó,
        // tránh hai bên cùng mở kết nối GATT tới một tay cầm.
        val svc = ControlService.instance
        if (svc != null) {
            svc.frameSink = { s -> main.post { handle(s) } }
            toast("Dùng kết nối sẵn có từ dịch vụ điều khiển")
        } else {
            connectOwn()
        }
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        sensors.unregisterListener(this)
        ControlService.instance?.frameSink = null
        ownBle?.close()
        ownBle = null
    }

    private fun connectOwn() {
        val mac = getSharedPreferences(ControlService.PREFS, Context.MODE_PRIVATE)
            .getString(ControlService.KEY_MAC, null)
        if (mac == null) {
            toast("Chưa chọn tay cầm. Quay lại màn hình chính và quét trước.")
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val dev = runCatching { adapter?.getRemoteDevice(mac) }.getOrNull() ?: return
        ownBle = DaydreamBleClient(this, this).also { it.connect(dev) }
    }

    private fun toast(m: String) = main.post {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    /* ============================================================ */
    /*  CẢM BIẾN ĐẦU                                                 */
    /* ============================================================ */

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            e.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) return

        SensorManager.getRotationMatrixFromVector(rotM, e.values)
        // Máy nằm ngang, cạnh trên quay sang trái.
        SensorManager.remapCoordinateSystem(
            rotM, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped
        )
        // Ma trận view = nghịch đảo của phép xoay (ma trận xoay nên nghịch đảo = chuyển vị)
        Matrix.transposeM(headView, 0, remapped, 0)
        // Hệ cảm biến có Z hướng lên, OpenGL có Y hướng lên.
        Matrix.rotateM(headView, 0, -90f, 1f, 0f, 0f)

        renderer.headMatrix = headView.copyOf()
        currentYaw = Math.toDegrees(
            kotlin.math.atan2(headView[8].toDouble(), headView[10].toDouble())
        ).toFloat()

        if (!renderer.hasController) renderer.ctrlMatrix = headView.copyOf()
    }

    /* ============================================================ */
    /*  DỮ LIỆU TAY CẦM                                              */
    /* ============================================================ */

    override fun onLog(msg: String) = Unit
    override fun onBattery(percent: Int) = Unit

    override fun onConnectionState(connected: Boolean, deviceName: String?) {
        renderer.hasController = connected
        if (connected) toast("Tay cầm đã kết nối")
    }

    override fun onFrame(state: DaydreamState, raw: ByteArray) = handle(state)

    private fun handle(s: DaydreamState) {
        renderer.hasController = true
        renderer.ctrlMatrix = controllerView(s)

        /* lướt ngang -> xoay bảng chọn */
        if (s.touching) {
            if (prevTouching) {
                val dx = (s.touchX - prevTx).toFloat()
                renderer.scrollAngleDeg -= dx * 90f
            }
            prevTx = s.touchX
        }
        prevTouching = s.touching

        /* nhấn lún -> chọn */
        if (s.clickBtn && !prevClick) selectTile(renderer.hovered())
        prevClick = s.clickBtn

        /* App -> recenter */
        if (s.appBtn && !prevApp) {
            renderer.yawOffsetDeg = -currentYaw
            renderer.scrollAngleDeg = 0f
        }
        prevApp = s.appBtn

        /* Home -> thoát */
        if (s.homeBtn && !prevHome) finish()
        prevHome = s.homeBtn

        /* Vol+ bật tắt bù méo, Vol- đổi IPD */
        if (s.volUpBtn && !prevVolUp) {
            renderer.distortionOn = !renderer.distortionOn
            toast("Bù méo: ${if (renderer.distortionOn) "bật" else "tắt"}")
        }
        prevVolUp = s.volUpBtn

        if (s.volDownBtn && !prevVolDown) {
            renderer.ipd = if (renderer.ipd > 0.068f) 0.058f else renderer.ipd + 0.004f
            toast("IPD: ${(renderer.ipd * 1000).toInt()} mm")
        }
        prevVolDown = s.volDownBtn
    }

    private var tileList: List<VrTile> = emptyList()

    private fun selectTile(index: Int) {
        if (index < 0 || index >= tileList.size) return
        tileList[index].onSelect()
    }

    /**
     * Tay cầm gửi hướng xoay dạng vector trục-góc: hướng của vector là trục quay,
     * độ dài là góc quay tính bằng radian. Đổi sang ma trận view.
     */
    private fun controllerView(s: DaydreamState): FloatArray {
        val x = s.oriX.toFloat()
        val y = s.oriY.toFloat()
        val z = s.oriZ.toFloat()
        val angle = sqrt(x * x + y * y + z * z)
        Matrix.setIdentityM(ctrlView, 0)
        if (angle > 1e-4f) {
            // Trục của tay cầm: X sang phải, Y lên, Z ra sau. Khớp với OpenGL.
            Matrix.rotateM(
                ctrlView, 0, Math.toDegrees(angle.toDouble()).toFloat(),
                x / angle, y / angle, z / angle
            )
        }
        // Ma trận view cần phép xoay ngược
        val out = FloatArray(16)
        Matrix.transposeM(out, 0, ctrlView, 0)
        return out
    }

    /* ============================================================ */
    /*  DỰNG CÁC Ô                                                   */
    /* ============================================================ */

    private fun buildTiles(): List<VrTile> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .filter { it.activityInfo.packageName != packageName }
            .take(10)

        val list = ArrayList<VrTile>()
        list.add(
            VrTile("Căn giữa lại", tileBitmap("Căn giữa lại", null, 0xFF2A3550.toInt())) {
                renderer.yawOffsetDeg = -currentYaw
                renderer.scrollAngleDeg = 0f
            }
        )
        apps.forEach { ri ->
            val label = ri.loadLabel(pm).toString()
            val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
            val pkg = ri.activityInfo.packageName
            list.add(VrTile(label, tileBitmap(label, icon, 0xFF1A2032.toInt())) {
                runCatching {
                    pm.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                }
            })
        }
        list.add(
            VrTile("Thoát VR", tileBitmap("Thoát VR", null, 0xFF4A2030.toInt())) { finish() }
        )
        tileList = list
        return list
    }

    private fun tileBitmap(label: String, icon: Drawable?, bg: Int): Bitmap {
        val w = 256
        val h = 160
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        c.drawRoundRect(RectF(3f, 3f, w - 3f, h - 3f), 18f, 18f, fill)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(180, 120, 160, 230)
        }
        c.drawRoundRect(RectF(3f, 3f, w - 3f, h - 3f), 18f, 18f, border)

        icon?.let {
            it.setBounds(w / 2 - 34, 18, w / 2 + 34, 86)
            it.draw(c)
        }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            textAlign = Paint.Align.CENTER
        }
        val text = if (label.length > 16) label.take(15) + "…" else label
        c.drawText(text, w / 2f, if (icon != null) 122f else 92f, tp)
        return bmp
    }
}

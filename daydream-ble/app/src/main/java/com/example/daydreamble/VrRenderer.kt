package com.example.daydreamble

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Một ô trong bảng chọn VR. */
class VrTile(
    val label: String,
    val bitmap: Bitmap,
    val onSelect: () -> Unit
) {
    var texId = 0
    val center = FloatArray(3)
    val right = FloatArray(3)
    val up = floatArrayOf(0f, 1f, 0f)
    val halfW = 0.42f
    val halfH = 0.26f
}

class VrRenderer(private val tiles: List<VrTile>) : GLSurfaceView.Renderer {

    /* ---- tham số chỉnh tay ---- */
    @Volatile var ipd = 0.062f          // khoảng cách hai mắt, mét
    @Volatile var k1 = 0.34f            // hệ số méo thấu kính
    @Volatile var k2 = 0.20f
    @Volatile var distortionOn = true
    @Volatile var fovY = 90f

    /* ---- trạng thái đầu vào, ghi từ luồng UI ---- */
    @Volatile var headMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    @Volatile var ctrlMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    @Volatile var yawOffsetDeg = 0f
    @Volatile var hasController = false
    @Volatile var scrollAngleDeg = 0f

    @Volatile private var hoverIndex = -1
    fun hovered() = hoverIndex

    private var w = 0
    private var h = 0

    /* ---- GL handles ---- */
    private var progColor = 0
    private var progTex = 0
    private var progDist = 0
    private var fbo = 0
    private var fboTex = 0
    private var fboDepth = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val recenter = FloatArray(16)
    private val tmp = FloatArray(16)

    private lateinit var gridBuf: FloatBuffer
    private var gridVerts = 0
    private lateinit var quadBuf: FloatBuffer     // 4 đỉnh x (3 pos + 2 uv), dựng lại mỗi khung
    private lateinit var lineBuf: FloatBuffer
    private lateinit var distBuf: FloatBuffer
    private var distVerts = 0

    private val rayOrigin = FloatArray(3)
    private val rayDir = FloatArray(3)
    private var hitDist = 8f

    /* ============================================================ */

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.04f, 0.05f, 0.09f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        progColor = program(VS_COLOR, FS_COLOR)
        progTex = program(VS_TEX, FS_TEX)
        progDist = program(VS_DIST, FS_DIST)

        buildGrid()
        quadBuf = alloc(4 * 5)
        lineBuf = alloc(2 * 7)

        tiles.forEach { it.texId = uploadTexture(it.bitmap) }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        w = width; h = height
        setupFbo()
        buildDistortionMesh()
    }

    override fun onDrawFrame(unused: GL10?) {
        updateTilePositions()
        computeRay()

        val target = if (distortionOn && fbo != 0) fbo else 0
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val halfW = w / 2
        for (eye in 0..1) {
            GLES20.glViewport(eye * halfW, 0, halfW, h)
            buildEyeMatrices(eye)
            drawGrid()
            drawTiles()
            drawPointer()
        }

        if (target != 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            drawDistortion()
        }
    }

    /* ============================================================ */
    /*  MA TRẬN                                                      */
    /* ============================================================ */

    private fun buildEyeMatrices(eye: Int) {
        val aspect = (w / 2f) / h
        Matrix.perspectiveM(proj, 0, fovY, aspect, 0.1f, 100f)

        Matrix.setIdentityM(recenter, 0)
        Matrix.rotateM(recenter, 0, yawOffsetDeg, 0f, 1f, 0f)
        Matrix.multiplyMM(view, 0, headMatrix, 0, recenter, 0)

        // dịch sang trái / phải nửa IPD
        val dx = if (eye == 0) ipd / 2f else -ipd / 2f
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, dx, 0f, 0f)
        Matrix.multiplyMM(view, 0, tmp, 0, view, 0)

        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
    }

    /* ============================================================ */
    /*  SÀN LƯỚI                                                     */
    /* ============================================================ */

    private fun buildGrid() {
        val half = 12
        val y = -1.6f
        val data = ArrayList<Float>()
        fun push(x: Float, z: Float, a: Float) {
            data.add(x); data.add(y); data.add(z)
            data.add(0.35f); data.add(0.55f); data.add(0.95f); data.add(a)
        }
        for (i in -half..half) {
            val f = i.toFloat()
            val a = 0.5f * (1f - kotlin.math.abs(f) / half)
            push(f, -half.toFloat(), a); push(f, half.toFloat(), a)
            push(-half.toFloat(), f, a); push(half.toFloat(), f, a)
        }
        gridVerts = data.size / 7
        gridBuf = alloc(data.size)
        data.forEach { gridBuf.put(it) }
        gridBuf.position(0)
    }

    private fun drawGrid() {
        GLES20.glUseProgram(progColor)
        val aPos = GLES20.glGetAttribLocation(progColor, "aPos")
        val aCol = GLES20.glGetAttribLocation(progColor, "aColor")
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progColor, "uMvp"), 1, false, vp, 0)

        gridBuf.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 28, gridBuf)
        GLES20.glEnableVertexAttribArray(aPos)
        gridBuf.position(3)
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, 28, gridBuf)
        GLES20.glEnableVertexAttribArray(aCol)

        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridVerts)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aCol)
    }

    /* ============================================================ */
    /*  BẢNG CHỌN                                                    */
    /* ============================================================ */

    private fun updateTilePositions() {
        val radius = 2.6f
        val stepDeg = 16f
        val n = tiles.size
        tiles.forEachIndexed { i, t ->
            val ang = Math.toRadians(((i - (n - 1) / 2f) * stepDeg + scrollAngleDeg).toDouble())
            val s = sin(ang).toFloat()
            val c = cos(ang).toFloat()
            t.center[0] = radius * s
            t.center[1] = 0f
            t.center[2] = -radius * c
            // hướng phải của ô: tiếp tuyến cung tròn
            t.right[0] = c; t.right[1] = 0f; t.right[2] = s
        }
    }

    private fun drawTiles() {
        GLES20.glUseProgram(progTex)
        val aPos = GLES20.glGetAttribLocation(progTex, "aPos")
        val aUv = GLES20.glGetAttribLocation(progTex, "aUv")
        val uMvp = GLES20.glGetUniformLocation(progTex, "uMvp")
        val uTex = GLES20.glGetUniformLocation(progTex, "uTex")
        val uTint = GLES20.glGetUniformLocation(progTex, "uTint")

        GLES20.glUniformMatrix4fv(uMvp, 1, false, vp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTex, 0)

        tiles.forEachIndexed { i, t ->
            GLES20.glUniform1f(uTint, if (i == hoverIndex) 1.6f else 1.0f)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.texId)

            quadBuf.position(0)
            fun corner(sx: Float, sy: Float, u: Float, v: Float) {
                quadBuf.put(t.center[0] + t.right[0] * t.halfW * sx + t.up[0] * t.halfH * sy)
                quadBuf.put(t.center[1] + t.right[1] * t.halfW * sx + t.up[1] * t.halfH * sy)
                quadBuf.put(t.center[2] + t.right[2] * t.halfW * sx + t.up[2] * t.halfH * sy)
                quadBuf.put(u); quadBuf.put(v)
            }
            corner(-1f, 1f, 0f, 0f)
            corner(-1f, -1f, 0f, 1f)
            corner(1f, 1f, 1f, 0f)
            corner(1f, -1f, 1f, 1f)
            quadBuf.position(0)

            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, quadBuf)
            GLES20.glEnableVertexAttribArray(aPos)
            quadBuf.position(3)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, quadBuf)
            GLES20.glEnableVertexAttribArray(aUv)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUv)
        }
    }

    /* ============================================================ */
    /*  TIA LASER + GIAO ĐIỂM                                        */
    /* ============================================================ */

    private fun computeRay() {
        // gốc tia: lệch phải và xuống dưới so với đầu, mô phỏng cánh tay
        val m = if (hasController) ctrlMatrix else headMatrix
        // ctrlMatrix / headMatrix là ma trận view (world -> eye), nên nghịch đảo
        // của phần xoay cho ta hướng nhìn trong world.
        rayOrigin[0] = 0.18f; rayOrigin[1] = -0.35f; rayOrigin[2] = 0f
        rayDir[0] = -m[2]; rayDir[1] = -m[6]; rayDir[2] = -m[10]
        val len = sqrt(rayDir[0] * rayDir[0] + rayDir[1] * rayDir[1] + rayDir[2] * rayDir[2])
        if (len > 1e-5f) { rayDir[0] /= len; rayDir[1] /= len; rayDir[2] /= len }

        var best = -1
        var bestT = Float.MAX_VALUE
        tiles.forEachIndexed { i, t ->
            val t0 = intersect(t)
            if (t0 > 0.2f && t0 < bestT) { bestT = t0; best = i }
        }
        hoverIndex = best
        hitDist = if (best >= 0) bestT else 8f
    }

    private fun intersect(t: VrTile): Float {
        // pháp tuyến ô = right x up
        val nx = t.right[1] * t.up[2] - t.right[2] * t.up[1]
        val ny = t.right[2] * t.up[0] - t.right[0] * t.up[2]
        val nz = t.right[0] * t.up[1] - t.right[1] * t.up[0]
        val denom = rayDir[0] * nx + rayDir[1] * ny + rayDir[2] * nz
        if (kotlin.math.abs(denom) < 1e-5f) return -1f
        val ox = t.center[0] - rayOrigin[0]
        val oy = t.center[1] - rayOrigin[1]
        val oz = t.center[2] - rayOrigin[2]
        val tt = (ox * nx + oy * ny + oz * nz) / denom
        if (tt <= 0f) return -1f
        val px = rayOrigin[0] + rayDir[0] * tt - t.center[0]
        val py = rayOrigin[1] + rayDir[1] * tt - t.center[1]
        val pz = rayOrigin[2] + rayDir[2] * tt - t.center[2]
        val u = px * t.right[0] + py * t.right[1] + pz * t.right[2]
        val v = px * t.up[0] + py * t.up[1] + pz * t.up[2]
        return if (kotlin.math.abs(u) <= t.halfW && kotlin.math.abs(v) <= t.halfH) tt else -1f
    }

    private fun drawPointer() {
        GLES20.glUseProgram(progColor)
        val aPos = GLES20.glGetAttribLocation(progColor, "aPos")
        val aCol = GLES20.glGetAttribLocation(progColor, "aColor")
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progColor, "uMvp"), 1, false, vp, 0)

        val r = if (hoverIndex >= 0) 1f else 0.5f
        lineBuf.position(0)
        lineBuf.put(rayOrigin[0]); lineBuf.put(rayOrigin[1]); lineBuf.put(rayOrigin[2])
        lineBuf.put(r); lineBuf.put(0.9f); lineBuf.put(1f); lineBuf.put(0.1f)
        lineBuf.put(rayOrigin[0] + rayDir[0] * hitDist)
        lineBuf.put(rayOrigin[1] + rayDir[1] * hitDist)
        lineBuf.put(rayOrigin[2] + rayDir[2] * hitDist)
        lineBuf.put(r); lineBuf.put(0.9f); lineBuf.put(1f); lineBuf.put(0.95f)
        lineBuf.position(0)

        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 28, lineBuf)
        GLES20.glEnableVertexAttribArray(aPos)
        lineBuf.position(3)
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, 28, lineBuf)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glLineWidth(4f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aCol)
    }

    /* ============================================================ */
    /*  KHUNG ĐỆM + BÙ MÉO THẤU KÍNH                                 */
    /* ============================================================ */

    private fun setupFbo() {
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            GLES20.glDeleteRenderbuffers(1, intArrayOf(fboDepth), 0)
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0); fboTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glGenRenderbuffers(1, ids, 0); fboDepth = ids[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, fboDepth)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h)

        GLES20.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTex, 0
        )
        GLES20.glFramebufferRenderbuffer(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
            GLES20.GL_RENDERBUFFER, fboDepth
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            fbo = 0   // thất bại thì vẽ thẳng, không bù méo
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * Lưới bù méo. Với mỗi điểm trên màn hình, toạ độ texture được đẩy ra xa tâm
     * theo công thức r' = r(1 + k1 r² + k2 r⁴), chuẩn hoá để mép không bị đen.
     * Ảnh hiện ra bị nén vào trong (barrel), đúng bằng lượng mà thấu kính kéo ra.
     */
    private fun buildDistortionMesh() {
        val n = 24
        val data = ArrayList<Float>()
        val norm = 1f + k1 + k2

        fun vert(eye: Int, gx: Int, gy: Int) {
            val u = gx / n.toFloat()
            val v = gy / n.toFloat()
            val px = u * 2f - 1f
            val py = v * 2f - 1f
            val r2 = px * px + py * py
            val f = (1f + k1 * r2 + k2 * r2 * r2) / norm
            val tu = (0.5f + px * f * 0.5f) * 0.5f + eye * 0.5f
            val tv = 0.5f + py * f * 0.5f
            val sx = (u * 0.5f + eye * 0.5f) * 2f - 1f
            val sy = py
            data.add(sx); data.add(sy); data.add(tu); data.add(tv)
        }

        for (eye in 0..1) {
            for (gy in 0 until n) {
                for (gx in 0 until n) {
                    vert(eye, gx, gy); vert(eye, gx, gy + 1); vert(eye, gx + 1, gy)
                    vert(eye, gx + 1, gy); vert(eye, gx, gy + 1); vert(eye, gx + 1, gy + 1)
                }
            }
        }
        distVerts = data.size / 4
        distBuf = alloc(data.size)
        data.forEach { distBuf.put(it) }
        distBuf.position(0)
    }

    private fun drawDistortion() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(progDist)
        val aPos = GLES20.glGetAttribLocation(progDist, "aPos")
        val aUv = GLES20.glGetAttribLocation(progDist, "aUv")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progDist, "uTex"), 0)

        distBuf.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, distBuf)
        GLES20.glEnableVertexAttribArray(aPos)
        distBuf.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, distBuf)
        GLES20.glEnableVertexAttribArray(aUv)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, distVerts)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    fun rebuildDistortion() = buildDistortionMesh()

    /* ============================================================ */
    /*  TIỆN ÍCH GL                                                  */
    /* ============================================================ */

    private fun alloc(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun uploadTexture(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return ids[0]
    }

    private fun program(vs: String, fs: String): Int {
        val v = shader(GLES20.GL_VERTEX_SHADER, vs)
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private companion object {
        const val VS_COLOR = """
            uniform mat4 uMvp;
            attribute vec4 aPos;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() { vColor = aColor; gl_Position = uMvp * aPos; }
        """

        const val FS_COLOR = """
            precision mediump float;
            varying vec4 vColor;
            void main() { gl_FragColor = vColor; }
        """

        const val VS_TEX = """
            uniform mat4 uMvp;
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = uMvp * aPos; }
        """

        const val FS_TEX = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform float uTint;
            void main() {
                vec4 c = texture2D(uTex, vUv);
                gl_FragColor = vec4(c.rgb * uTint, c.a);
            }
        """

        const val VS_DIST = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        const val FS_DIST = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """
    }
}

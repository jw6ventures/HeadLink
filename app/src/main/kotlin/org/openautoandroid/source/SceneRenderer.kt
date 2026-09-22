package org.openautoandroid.source

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws the owned test scene into the encoder's input Surface with EGL and OpenGL ES 2
 * (spec §8.2: no ordinary Android views are drawn onto an encoder Surface). The scene is
 * painted into a Bitmap with Canvas, uploaded as a texture, and drawn as one quad;
 * presentation times are the monotonic times the caller passes.
 *
 * Every method must run on the renderer thread that called [start].
 */
class SceneRenderer(private val width: Int, private val height: Int) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var vertices: ByteBuffer? = null

    /** Sets up EGL on the current thread. Returns null on success or the failure reason. */
    fun start(target: Surface): String? {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return "no EGL display"
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return "eglInitialize failed"
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            return "no recordable EGL configuration"
        }
        context = EGL14.eglCreateContext(
            display,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) return "eglCreateContext failed"
        surface = EGL14.eglCreateWindowSurface(
            display,
            configs[0],
            target,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        if (surface == EGL14.EGL_NO_SURFACE) return "eglCreateWindowSurface failed"
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return "eglMakeCurrent failed"
        return setUpGl()
    }

    private fun setUpGl(): String? {
        val vertex = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vertex, VERTEX_SHADER)
        GLES20.glCompileShader(vertex)
        val fragment = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fragment, FRAGMENT_SHADER)
        GLES20.glCompileShader(fragment)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] != GLES20.GL_TRUE) {
            return "shader link failed: ${GLES20.glGetProgramInfoLog(program)}"
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        texture = names[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // A full-surface quad: position x, y and texture u, v.
        val quad = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )
        vertices = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).apply {
            asFloatBuffer().put(quad).position(0)
        }
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap!!)
        paint.typeface = Typeface.MONOSPACE
        GLES20.glViewport(0, 0, width, height)
        return null
    }

    /** Paints one frame and presents it at `presentationNs` (monotonic). */
    fun draw(scene: DemoScene, snapshot: DemoScene.Snapshot, presentationNs: Long) {
        val canvas = canvas ?: return
        val bitmap = bitmap ?: return
        SceneDrawing.draw(canvas, paint, scene, snapshot)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        val position = GLES20.glGetAttribLocation(program, "position")
        val coordinate = GLES20.glGetAttribLocation(program, "coordinate")
        val buffer = vertices ?: return
        buffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(position)
        buffer.position(8)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "scene"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(coordinate)
        EGLExt.eglPresentationTimeANDROID(display, surface, presentationNs)
        EGL14.eglSwapBuffers(display, surface)
    }

    /** Releases GL and EGL resources on the renderer thread. */
    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
        bitmap?.recycle()
        bitmap = null
        canvas = null
    }

    private companion object {
        /** `EGL_RECORDABLE_ANDROID`, so the surface can feed a video encoder. */
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX_SHADER =
            """
            attribute vec4 position;
            attribute vec2 coordinate;
            varying vec2 uv;
            void main() {
                gl_Position = position;
                uv = coordinate;
            }
            """

        const val FRAGMENT_SHADER =
            """
            precision mediump float;
            varying vec2 uv;
            uniform sampler2D scene;
            void main() {
                gl_FragColor = texture2D(scene, uv);
            }
            """
    }
}

/** Paints a [DemoScene] snapshot; separated from EGL so the drawing is easy to follow. */
object SceneDrawing {
    fun draw(canvas: Canvas, paint: Paint, scene: DemoScene, snapshot: DemoScene.Snapshot) {
        val l = scene.layout
        canvas.drawColor(0xFF000000.toInt())
        paint.style = Paint.Style.FILL
        paint.color = DemoScene.COLOR_BACKGROUND
        canvas.drawRect(l.x.toFloat(), l.y.toFloat(), (l.x + l.width).toFloat(), (l.y + l.height).toFloat(), paint)
        paint.color = DemoScene.COLOR_HEADER
        canvas.drawRect(l.x.toFloat(), l.y.toFloat(), (l.x + l.width).toFloat(), (l.y + l.header).toFloat(), paint)

        val headerText = l.header / 3f
        paint.color = DemoScene.COLOR_TEXT
        paint.textSize = headerText
        val baseline = l.y + l.header * 0.45f
        canvas.drawText("OpenAutoAndroid ${scene.token}", (l.x + l.margin).toFloat(), baseline, paint)
        paint.textSize = headerText * 0.8f
        canvas.drawText(
            "frame ${snapshot.frame}  ${snapshot.elapsedMs / 1000}s  ${snapshot.restriction}",
            (l.x + l.margin).toFloat(),
            baseline + headerText * 0.9f,
            paint,
        )
        // The last transport button, drawn right-aligned in the header, and only once one
        // has arrived. Drawing "media -" unconditionally read as an idle media feature
        // when in fact the integration was switched off entirely, which is exactly how a
        // Ford run was misread (report b03bffe4).
        if (snapshot.transport != null) {
            val transport = "media ${snapshot.transport}"
            canvas.drawText(
                transport,
                (l.x + l.width - l.margin - paint.measureText(transport)),
                baseline + headerText * 0.9f,
                paint,
            )
        }
        // What the head unit is being told is playing, in the band the button grid leaves
        // free below the header. This is the same text that goes on the media_info
        // channel, so a head unit that ignores that channel — or that shows its own
        // now-playing screen only when the projection is not in front — still makes the
        // publication visible, which is what G6 has to observe.
        // Null when the media integration is off for this attempt, so the line's presence
        // is itself the signal that it is running.
        snapshot.nowPlaying?.let { playing ->
            paint.textSize = maxOf(MIN_TEXT_PX, l.margin * 0.8f)
            canvas.drawText(
                fit(paint, playing, (l.width - 2 * l.margin).toFloat()),
                (l.x + l.margin).toFloat(),
                l.y + l.header + l.margin * 0.8f,
                paint,
            )
        }
        // A bar that moves every frame: a still image cannot pass for live video.
        val bar = l.width / 16
        val position = ((snapshot.frame * bar / 8) % maxOf(1, l.width - bar)).toInt()
        canvas.drawRect(
            (l.x + position).toFloat(),
            (l.y + l.header - l.header / 8).toFloat(),
            (l.x + position + bar).toFloat(),
            (l.y + l.header).toFloat(),
            paint,
        )

        for (index in 0 until SceneLayout.BUTTONS) {
            val bx = l.buttonX(index)
            val by = l.buttonY(index)
            val taps = snapshot.taps[index]
            paint.color = if (taps > 0) DemoScene.BUTTON_COLORS[index] else DemoScene.COLOR_IDLE_BUTTON
            canvas.drawRect(
                bx.toFloat(),
                by.toFloat(),
                (bx + l.buttonWidth).toFloat(),
                (by + l.buttonHeight).toFloat(),
                paint,
            )
            paint.color = DemoScene.COLOR_TEXT
            if (snapshot.pressed == index) {
                canvas.drawRect(
                    bx.toFloat(),
                    by.toFloat(),
                    (bx + l.buttonWidth).toFloat(),
                    (by + l.buttonHeight / 16f),
                    paint,
                )
            }
            // Labels stay in the top part: the rest of the button is a clean color patch
            // that a receiver samples (SceneLayout.samplePoint).
            paint.textSize = l.buttonHeight / 6f
            canvas.drawText(
                "${index + 1}",
                (bx + l.buttonWidth / 16).toFloat(),
                (by + l.buttonHeight / 4).toFloat(),
                paint,
            )
            canvas.drawText(
                "$taps",
                (bx + l.buttonWidth / 2).toFloat(),
                (by + l.buttonHeight / 4).toFloat(),
                paint,
            )
            // Calibration target at a frame pixel the scene knows exactly: pressing it
            // gives the report a (target, raw) pair per button. Kept clear of
            // SceneLayout.samplePoint so the receiver still samples a clean color patch.
            val tx = l.centerX(index).toFloat()
            val ty = l.centerY(index).toFloat()
            val targetArm = l.buttonHeight / 8f
            val targetThickness = maxOf(1f, l.height / 480f)
            canvas.drawRect(tx - targetArm, ty - targetThickness / 2, tx + targetArm, ty + targetThickness / 2, paint)
            canvas.drawRect(tx - targetThickness / 2, ty - targetArm, tx + targetThickness / 2, ty + targetArm, paint)
        }

        if (snapshot.lastTouchX >= 0) {
            val arm = l.height / 12f
            val thickness = maxOf(2f, l.height / 240f)
            val x = snapshot.lastTouchX.toFloat()
            val y = snapshot.lastTouchY.toFloat()
            canvas.drawRect(x - arm, y - thickness / 2, x + arm, y + thickness / 2, paint)
            canvas.drawRect(x - thickness / 2, y - arm, x + thickness / 2, y + arm, paint)
            paint.textSize = l.height / 24f
            canvas.drawText(
                "${snapshot.lastTouchX},${snapshot.lastTouchY} raw ${snapshot.lastRawX},${snapshot.lastRawY}" +
                    if (snapshot.lastKey >= 0) "  key ${snapshot.lastKey}" else "",
                (l.x + l.margin).toFloat(),
                (l.y + l.height - l.margin).toFloat(),
                paint,
            )
        }
    }

    /**
     * [text] truncated with an ellipsis to [width] pixels under [paint]. A track title is
     * arbitrary length and the band it is drawn in is not, so an untruncated line would
     * run under the frame edge — and on a head unit that crops the picture, silently.
     */
    private fun fit(paint: Paint, text: String, width: Float): String {
        if (width <= 0f) return ""
        if (paint.measureText(text) <= width) return text
        val room = width - paint.measureText(ELLIPSIS)
        if (room <= 0f) return ELLIPSIS
        return text.take(paint.breakText(text, true, room, null)) + ELLIPSIS
    }

    private const val ELLIPSIS = "…"

    /** Below this the now-playing line stops being readable at any screen size. */
    private const val MIN_TEXT_PX = 8f
}

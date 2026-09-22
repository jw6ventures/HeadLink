package org.openautoandroid.source

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws the dashboard into the encoder's input Surface: an owned VTM map composited
 * under chrome painted with Canvas (spec §8.2 — no Android views touch an encoder
 * Surface).
 *
 * Two textures, one pass each. The map is rasterised by [MapEngine] into its own
 * framebuffer and sampled into the map pane; the chrome is an ARGB bitmap with the pane
 * punched transparent, drawn over the whole frame with premultiplied blending.
 *
 * The chrome is only repainted and re-uploaded when it actually changes. The old test
 * scene moved a bar every frame and so uploaded 8 MB per frame at 1080p; a dashboard
 * changes about once a second, which leaves the map as the only per-frame cost.
 *
 * Every method must run on the renderer thread that called [start].
 */
class DashboardRenderer(
    private val width: Int,
    private val height: Int,
    private val mapFiles: List<File>,
) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var chromeTexture = 0
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var chromeQuad: ByteBuffer? = null
    private var mapQuad: ByteBuffer? = null

    /** The last chrome actually painted, so an unchanged frame skips the upload. */
    private var lastChrome: Dashboard.Snapshot? = null

    /**
     * Cover art to draw in the media pane, or null. Written from the media thread and
     * read on the renderer thread; only ever replaced, never mutated or recycled here,
     * so a frame in flight always has a whole bitmap to draw.
     */
    @Volatile
    private var albumArt: Bitmap? = null
    var chromeUploads = 0L
        private set

    var engine: MapEngine? = null
        private set

    /** Sets up EGL, the map engine and GL objects. Returns null or the failure reason. */
    fun start(target: Surface, layout: DashboardLayout, night: Boolean): String? {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return "no EGL display"
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return "eglInitialize failed"
        // The map keeps its depth and stencil on its own framebuffer, so the window
        // surface needs neither and this configuration stays the one the video path
        // has already been proven with.
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
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) return "eglCreateContext failed"
        surface = EGL14.eglCreateWindowSurface(display, configs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) return "eglCreateWindowSurface failed"
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return "eglMakeCurrent failed"
        return setUpGl(layout, night)
    }

    private fun setUpGl(layout: DashboardLayout, night: Boolean): String? {
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
        chromeTexture = names[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, chromeTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Full frame, texture origin top-left to match a Canvas bitmap.
        chromeQuad = quad(-1f, -1f, 1f, 1f, flipV = false)
        // The map pane in clip space. A framebuffer texture has its origin at the
        // bottom, the opposite of the bitmap, so this one samples flipped.
        val left = layout.map.x * 2f / width - 1f
        val right = layout.map.right * 2f / width - 1f
        val top = 1f - layout.map.y * 2f / height
        val bottom = 1f - layout.map.bottom * 2f / height
        mapQuad = quad(left, bottom, right, top, flipV = true)

        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap!!)

        val map = MapEngine(layout.map.width, layout.map.height)
        // Deliberately not propagated: a map that will not start leaves the rest of the
        // dashboard working and the pane draws the reason instead, which the caller
        // reads back from MapEngine.failure. Failing the whole scene here would take
        // the clock, the sensors and the media controls down with it, and there is no
        // download to retry with (APP10).
        map.start(mapFiles, night)
        engine = map
        GLES20.glViewport(0, 0, width, height)
        return null
    }

    /** Media thread: the cover art the next repaint should draw. */
    fun setAlbumArt(art: Bitmap?) {
        albumArt = art
    }

    /** Position, zoom, rotation and theme for the next map frame. */
    fun updateMap(camera: MapCamera, night: Boolean) {
        engine?.setNight(night)
        engine?.setPosition(camera.latitude, camera.longitude, camera.scale, camera.rotation)
    }

    /** The route line for the next map frame, or null for none. */
    fun setRoute(route: Route?) {
        engine?.setRoute(route)
    }

    /** Paints one frame and presents it at `presentationNs` (monotonic). */
    fun draw(layout: DashboardLayout, snapshot: Dashboard.Snapshot, presentationNs: Long) {
        val canvas = canvas ?: return
        val bitmap = bitmap ?: return

        engine?.drawFrame()

        // VTM owns global GL state and leaves it wherever its last layer put it: the
        // building and label layers change the blend function, the depth test and the
        // cull face between frames. Compositing on top of that means re-establishing
        // every piece this pass depends on, every frame.
        //
        // Setting the blend function once at start-up was not enough, and the way it
        // failed is worth recording: the chrome vanished completely and the map showed
        // through, but only once the map had real data to draw, because a VTM with no
        // tiles never reaches the layer that changes it.
        resetCompositorState()

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val position = GLES20.glGetAttribLocation(program, "position")
        val coordinate = GLES20.glGetAttribLocation(program, "coordinate")
        // VTM caches which vertex attribute arrays it has enabled and skips the GL call
        // when its cache already agrees. Leaving them in a state its cache does not
        // expect makes the next map frame draw nothing, so they are put back exactly.
        val wasEnabled = intArrayOf(enabled(position), enabled(coordinate))

        GLES20.glDisable(GLES20.GL_BLEND)
        engine?.texture()?.takeIf { it != 0 }?.let { texture ->
            drawQuad(mapQuad, texture, position, coordinate)
        }

        if (needsChrome(snapshot)) {
            DashboardDrawing.draw(canvas, paint, layout, snapshot, albumArt)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, chromeTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            chromeUploads++
            lastChrome = snapshot.copy(frame = 0)
        }
        // Premultiplied: Canvas bitmaps are, and the punched pane is transparent black.
        GLES20.glEnable(GLES20.GL_BLEND)
        drawQuad(chromeQuad, chromeTexture, position, coordinate)
        GLES20.glDisable(GLES20.GL_BLEND)

        restore(position, wasEnabled[0])
        restore(coordinate, wasEnabled[1])

        EGLExt.eglPresentationTimeANDROID(display, surface, presentationNs)
        EGL14.eglSwapBuffers(display, surface)
    }

    /**
     * Puts the GL state this pass assumes back the way it needs it. The window surface
     * has neither depth nor stencil, so those tests are disabled rather than configured.
     */
    private fun resetCompositorState() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDepthMask(false)
        GLES20.glColorMask(true, true, true, true)
        // Premultiplied alpha: Canvas bitmaps are premultiplied and the map pane is
        // punched to transparent black.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // Both quads below hand glVertexAttribPointer a direct ByteBuffer, and a client
        // pointer is only a pointer while no array buffer is bound — with one bound GL
        // reads the same number as a byte offset into it. VTM leaves whichever VBO its
        // last layer drew from bound, so this has to be cleared *before* the quads and
        // not after them.
        //
        // Unbinding afterwards instead is what the flicker was: on a frame the map
        // actually rasterised, both quads fetched their vertices from somewhere inside
        // a tile VBO and the whole 1920x1080 frame came out as the clear colour —
        // chrome, header and side panels included, not just the pane. Frames where the
        // map reused its texture never bound anything, so they composited correctly,
        // and the two alternated. Following at the default zoom is the worst case
        // because every fix redraws the map while the panel keeps running at 30fps.
        //
        // Safe against VTM's own cache: MapRenderer.draw() opens every frame with
        // GLState.bindVertexBuffer(DISABLED), which invalidates the cached id without
        // issuing a call, so the next bucket rebinds for real.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** Whether the chrome differs from what is already in the texture. */
    private fun needsChrome(snapshot: Dashboard.Snapshot): Boolean =
        lastChrome != snapshot.copy(frame = 0)

    private fun drawQuad(vertices: ByteBuffer?, texture: Int, position: Int, coordinate: Int) {
        val buffer = vertices ?: return
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "scene"), 0)
        buffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(position)
        buffer.position(8)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun enabled(attribute: Int): Int {
        if (attribute < 0) return 0
        val out = IntArray(1)
        GLES20.glGetVertexAttribiv(attribute, GLES20.GL_VERTEX_ATTRIB_ARRAY_ENABLED, out, 0)
        return out[0]
    }

    private fun restore(attribute: Int, was: Int) {
        if (attribute < 0) return
        if (was != 0) GLES20.glEnableVertexAttribArray(attribute)
        else GLES20.glDisableVertexAttribArray(attribute)
    }

    /** A triangle-strip quad: position x, y then texture u, v. */
    private fun quad(left: Float, bottom: Float, right: Float, top: Float, flipV: Boolean): ByteBuffer {
        val vTop = if (flipV) 1f else 0f
        val vBottom = if (flipV) 0f else 1f
        val values = floatArrayOf(
            left, bottom, 0f, vBottom,
            right, bottom, 1f, vBottom,
            left, top, 0f, vTop,
            right, top, 1f, vTop,
        )
        return ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).apply {
            asFloatBuffer().put(values).position(0)
        }
    }

    fun release() {
        engine?.release()
        engine = null
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
        lastChrome = null
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

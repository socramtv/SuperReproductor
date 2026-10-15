package com.example.superplayer.ui

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengl.GL10

/**
 * Pinta fotogramas I420 (Y, U, V por separado) con la GPU: sin convertir a mano en la CPU
 * ni crear imágenes por fotograma, así va más fluido y gasta menos batería.
 */
class YuvView(context: Context) : GLSurfaceView(context) {
    private val lock = Any()
    private var buf: ByteBuffer? = null
    private var frameW = 0
    private var frameH = 0
    private var dirty = false

    private val renderer = object : Renderer {
        private var program = 0
        private val tex = IntArray(3)
        private var viewW = 1
        private var viewH = 1
        private var texW = 0
        private var texH = 0
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // x, y, u, v (la v va invertida: la imagen viene de arriba abajo)
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
            position(0)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val vs = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main(){ gl_Position = p; v = t; }"
            val fs = "precision mediump float; varying vec2 v; uniform sampler2D ty; uniform sampler2D tu; uniform sampler2D tv;" +
                "void main(){ float y = 1.1643 * (texture2D(ty, v).r - 0.0625); float u = texture2D(tu, v).r - 0.5; float w = texture2D(tv, v).r - 0.5;" +
                "gl_FragColor = vec4(y + 1.5958 * w, y - 0.39173 * u - 0.81290 * w, y + 2.017 * u, 1.0); }"
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(program)
            GLES20.glGenTextures(3, tex, 0)
            for (t in tex) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            texW = 0
            texH = 0
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = maxOf(width, 1)
            viewH = maxOf(height, 1)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            var w: Int
            var h: Int
            synchronized(lock) {
                w = frameW
                h = frameH
                val b = buf
                if (b == null || w <= 0 || h <= 0) return
                if (dirty) {
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                    val cw = w / 2
                    val ch = h / 2
                    upload(0, w, h, 0, b, texW != w || texH != h)
                    upload(1, cw, ch, w * h, b, texW != w || texH != h)
                    upload(2, cw, ch, w * h + cw * ch, b, texW != w || texH != h)
                    texW = w
                    texH = h
                    dirty = false
                }
            }
            if (texW == 0) return
            // Ajuste con barras negras manteniendo la proporción.
            val scale = minOf(viewW.toFloat() / w, viewH.toFloat() / h)
            val vw = (w * scale).toInt()
            val vh = (h * scale).toInt()
            GLES20.glViewport((viewW - vw) / 2, (viewH - vh) / 2, vw, vh)
            GLES20.glUseProgram(program)
            val pLoc = GLES20.glGetAttribLocation(program, "p")
            val tLoc = GLES20.glGetAttribLocation(program, "t")
            quad.position(0)
            GLES20.glVertexAttribPointer(pLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(pLoc)
            quad.position(2)
            GLES20.glVertexAttribPointer(tLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(tLoc)
            val names = arrayOf("ty", "tu", "tv")
            for (i in 0..2) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, names[i]), i)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun upload(i: Int, w: Int, h: Int, offset: Int, b: ByteBuffer, realloc: Boolean) {
            b.position(offset)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
            if (realloc) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, b)
            } else {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, b)
            }
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }
    }

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Entrega un fotograma I420 completo (Y + U + V seguidos). Se puede llamar desde cualquier hilo. */
    fun setFrame(data: ByteArray, w: Int, h: Int) {
        if (w <= 0 || h <= 0 || data.size < w * h * 3 / 2) return
        synchronized(lock) {
            var b = buf
            if (b == null || b.capacity() < data.size) {
                b = ByteBuffer.allocateDirect(data.size)
                buf = b
            }
            b!!.clear()
            b.put(data, 0, w * h * 3 / 2)
            b.position(0)
            frameW = w
            frameH = h
            dirty = true
        }
        requestRender()
    }
}

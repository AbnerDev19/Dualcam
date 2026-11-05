package br.dualcam.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Coração do app: a câmera escreve num SurfaceTexture; uma thread OpenGL desenha cada quadro
 *  1) no preview (quadro inteiro, horizontal),
 *  2) no encoder do arquivo horizontal (quadro inteiro),
 *  3) no encoder do arquivo vertical (recorte central 9:16 do MESMO quadro).
 * Tudo vem do mesmo sensor, no mesmo instante, então os dois arquivos ficam perfeitamente sincronizados.
 */
class VideoPipeline {

    private val thread = HandlerThread("gl-pipeline").also { it.start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var previewSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var texId = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uSt = 0
    private var uTex = 0
    private var uZebra = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val stMatrix = FloatArray(16)
    private var realtimeTs = true

    @Volatile
    var rotationDegrees = 0

    @Volatile
    var zebra = false

    private val vertexBuf = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texFull = floatBuf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private val texFlip = floatBuf(floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f))
    private var texCrop: FloatBuffer = texFull

    private class Target(val enc: VideoEncoder, val surface: EGLSurface, val cropped: Boolean)

    private var targets: List<Target> = emptyList()
    private var recorder: Recorder? = null
    private var released = false
    private var errorReported = false
    private var reportError: (String) -> Unit = {}

    // ---------------------------------------------------------------- ciclo de vida

    /** Cria o contexto GL e devolve (via [onReady]) a Surface para onde a câmera deve escrever. */
    fun start(
        previewSurf: Surface,
        camW: Int,
        camH: Int,
        cropW: Int,
        realtimeTimestamps: Boolean,
        onError: (String) -> Unit,
        onReady: (Surface) -> Unit
    ) {
        handler.post {
            try {
                reportError = onError
                realtimeTs = realtimeTimestamps
                initEgl()
                previewSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, previewSurf, intArrayOf(EGL14.EGL_NONE), 0
                )
                check(previewSurface != EGL14.EGL_NO_SURFACE) { "Falha ao criar a prévia" }
                makeCurrent(previewSurface)
                EGL14.eglSwapInterval(eglDisplay, 0)
                initGl()

                val f = cropW.toFloat() / camW.toFloat()
                val x0 = (1f - f) / 2f
                val x1 = 1f - x0
                texCrop = floatBuf(floatArrayOf(x0, 0f, x1, 0f, x0, 1f, x1, 1f))

                val st = SurfaceTexture(texId)
                st.setDefaultBufferSize(camW, camH)
                st.setOnFrameAvailableListener(SurfaceTexture.OnFrameAvailableListener { onFrame() }, handler)
                surfaceTexture = st
                val s = Surface(st)
                cameraSurface = s
                onReady(s)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao iniciar pipeline", e)
                onError("Falha na imagem da câmera: ${e.message}")
            }
        }
    }

    /** Libera tudo e espera terminar (até ~4 s). Se estiver gravando, finaliza os arquivos antes. */
    fun releaseAndWait() {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                released = true
                val rec = recorder
                val old = targets
                recorder = null
                targets = emptyList()
                try {
                    makeCurrent(previewSurface)
                } catch (e: Exception) {
                }
                for (t in old) {
                    try {
                        EGL14.eglDestroySurface(eglDisplay, t.surface)
                    } catch (e: Exception) {
                    }
                }
                try {
                    rec?.finish()
                } catch (e: Exception) {
                    Log.e(TAG, "finish na liberação", e)
                }
                surfaceTexture?.setOnFrameAvailableListener(null)
                surfaceTexture?.release()
                cameraSurface?.release()
                surfaceTexture = null
                cameraSurface = null
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                    )
                    if (previewSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, previewSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                    EGL14.eglReleaseThread()
                }
                previewSurface = EGL14.EGL_NO_SURFACE
                eglContext = EGL14.EGL_NO_CONTEXT
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao liberar pipeline", e)
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(4, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
        }
        thread.quitSafely()
    }

    // ---------------------------------------------------------------- gravação

    /** [factory] roda na thread GL (cria arquivos e encoders). [onResult] também roda na thread GL. */
    fun startRecording(factory: () -> Recorder, onResult: (Boolean, String?) -> Unit) {
        handler.post {
            var rec: Recorder? = null
            try {
                if (released) throw IllegalStateException("Câmera não está ativa")
                if (recorder != null) throw IllegalStateException("Já está gravando")
                rec = factory()
                val sh = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, rec.landscape.inputSurface, intArrayOf(EGL14.EGL_NONE), 0
                )
                val sv = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, rec.portrait.inputSurface, intArrayOf(EGL14.EGL_NONE), 0
                )
                check(sh != EGL14.EGL_NO_SURFACE && sv != EGL14.EGL_NO_SURFACE) { "Encoders simultâneos indisponíveis nesta configuração" }
                targets = listOf(Target(rec.landscape, sh, false), Target(rec.portrait, sv, true))
                recorder = rec
                onResult(true, null)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao iniciar gravação", e)
                try {
                    rec?.finish()
                } catch (e2: Exception) {
                }
                onResult(false, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun stopRecording(onDone: (List<String>) -> Unit) {
        handler.post {
            val rec = recorder
            val old = targets
            recorder = null
            targets = emptyList()
            if (rec == null) {
                onDone(emptyList())
                return@post
            }
            try {
                makeCurrent(previewSurface)
            } catch (e: Exception) {
            }
            for (t in old) {
                try {
                    EGL14.eglDestroySurface(eglDisplay, t.surface)
                } catch (e: Exception) {
                }
            }
            val names = try {
                rec.finish()
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao finalizar gravação", e)
                emptyList()
            }
            onDone(names)
        }
    }

    // ---------------------------------------------------------------- render

    private fun onFrame() {
        val st = surfaceTexture ?: return
        if (released) return
        try {
            makeCurrent(previewSurface)
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            val ts = if (realtimeTs) st.timestamp else Clock.nowNs()

            // 1) preview
            val dim = IntArray(1)
            EGL14.eglQuerySurface(eglDisplay, previewSurface, EGL14.EGL_WIDTH, dim, 0)
            val w = dim[0]
            EGL14.eglQuerySurface(eglDisplay, previewSurface, EGL14.EGL_HEIGHT, dim, 0)
            val h = dim[0]
            if (w > 0 && h > 0) {
                GLES20.glViewport(0, 0, w, h)
                draw(oriented(texFull), zebra)
                EGL14.eglSwapBuffers(eglDisplay, previewSurface)
            }

            // 2) e 3) encoders
            val rec = recorder
            if (rec != null && ts >= rec.startNs) {
                val pts = ts - rec.startNs
                for (t in targets) {
                    makeCurrent(t.surface)
                    GLES20.glViewport(0, 0, t.enc.width, t.enc.height)
                    draw(oriented(if (t.cropped) texCrop else texFull), false)
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, t.surface, pts)
                    EGL14.eglSwapBuffers(eglDisplay, t.surface)
                    t.enc.drain()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desenhar quadro", e)
            if (!errorReported) { errorReported = true; reportError("Falha ao renderizar a câmera: ${e.message}") }
        }
    }

    // Rotate in normalized texture space before SurfaceTexture applies its device transform.
    private fun oriented(source: FloatBuffer): FloatBuffer {
        val out = FloatArray(8)
        for (i in 0..3) {
            val x = source.get(i * 2); val y = source.get(i * 2 + 1)
            val pair = when (rotationDegrees) {
                90 -> y to 1f - x
                180 -> 1f - x to 1f - y
                270 -> 1f - y to x
                else -> x to y
            }
            out[i * 2] = pair.first; out[i * 2 + 1] = pair.second
        }
        rotated.position(0); rotated.put(out); rotated.position(0)
        return rotated
    }
    private val rotated = floatBuf(FloatArray(8))

    private fun draw(texCoords: FloatBuffer, zebraOn: Boolean) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        GLES20.glUniform1f(uZebra, if (zebraOn) 1f else 0f)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // ---------------------------------------------------------------- EGL / GL

    private fun makeCurrent(s: EGLSurface) {
        check(EGL14.eglMakeCurrent(eglDisplay, s, s, eglContext)) { "Contexto de imagem indisponível (EGL ${EGL14.eglGetError()})" }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize falhou")
        }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
        if (num[0] <= 0 || configs[0] == null) throw RuntimeException("Nenhuma EGLConfig compatível")
        eglConfig = configs[0]
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext falhou")
    }

    private fun initGl() {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        uSt = GLES20.glGetUniformLocation(program, "uStMatrix")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uZebra = GLES20.glGetUniformLocation(program, "uZebra")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("Link do shader falhou: $log")
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Compilação do shader falhou: $log")
        }
        return s
    }

    companion object {
        private const val TAG = "VideoPipeline"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private fun floatBuf(a: FloatArray): FloatBuffer {
            val fb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.put(a)
            fb.position(0)
            return fb
        }

        private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uStMatrix;
varying vec2 vTex;
void main() {
    gl_Position = aPosition;
    vTex = (uStMatrix * aTexCoord).xy;
}
"""

        private const val FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
uniform samplerExternalOES uTex;
uniform float uZebra;
void main() {
    vec4 c = texture2D(uTex, vTex);
    if (uZebra > 0.5) {
        float y = dot(c.rgb, vec3(0.299, 0.587, 0.114));
        if (y > 0.94 && mod(floor(gl_FragCoord.x + gl_FragCoord.y), 16.0) < 6.0) {
            c = vec4(1.0, 0.0, 0.0, 1.0);
        }
    }
    gl_FragColor = c;
}
"""
    }
}

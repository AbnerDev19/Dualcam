package br.dualcam.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Valores escolhidos pelo usuário. */
data class CamState(
    var aeManual: Boolean = false,
    var iso: Int = 100,
    var exposureNs: Long = 16_666_666L,
    var evIndex: Int = 0,
    var awbManual: Boolean = false,
    var kelvin: Int = 5500,
    var afManual: Boolean = false,
    var focusDiopter: Float = 0f,
    var fps: Int = 30
)

/** O que cada lente/câmera do aparelho sabe fazer. */
class CamInfo(
    val id: String,
    val sensorOrientation: Int,
    val sizes: List<Size>,
    val isoRange: Range<Int>,
    val expRange: Range<Long>,
    val minFocusDiopter: Float,
    val manualSensor: Boolean,
    val manualPost: Boolean,
    val aeFpsRanges: List<Range<Int>>,
    val realtimeTs: Boolean,
    val evRange: Range<Int>,
    val evStep: Float,
    val equivMm: Int,
    val hasOis: Boolean,
    val map: StreamConfigurationMap
)

class CameraEngine(ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onInfo()
        fun onError(msg: String)
    }

    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera2").also { it.start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var info: CamInfo? = null

    @Volatile
    private var snapshot = CamState()

    @Volatile
    var lastIso = 100
        private set

    @Volatile
    var lastExposureNs = 0L
        private set

    @Volatile
    var lastFocus = 0f
        private set

    @Volatile
    private var baseGains: RggbChannelVector? = null

    @Volatile
    private var baseTransform: ColorSpaceTransform? = null
    private var lastNotify = 0L

    // ---------------------------------------------------------------- descoberta

    fun backCameras(): List<String> {
        val out = ArrayList<String>()
        for (id in manager.cameraIdList) {
            try {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val sizes = map.getOutputSizes(SurfaceTexture::class.java)
                if (sizes == null || sizes.isEmpty()) continue
                out.add(id)
            } catch (e: Exception) {
                Log.w(TAG, "Ignorando câmera $id", e)
            }
        }
        return out
    }

    fun loadInfo(id: String): CamInfo {
        val c = manager.getCameraCharacteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("Câmera sem lista de resoluções")
        val sizes = (map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()).toList()

        val iso: Range<Int> = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 800)
        val exp: Range<Long> = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: Range(100_000L, 33_000_000L)
        val ev: Range<Int> = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val evStep: Float = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        val fpsRanges: List<Range<Int>> =
            (c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()).toList()

        val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val equiv = if (focals != null && focals.isNotEmpty() && phys != null) {
            (focals[0] * 43.27f / hypot(phys.width, phys.height)).roundToInt()
        } else 0

        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

        return CamInfo(
            id = id,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            sizes = sizes,
            isoRange = iso,
            expRange = exp,
            minFocusDiopter = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            manualPost = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
            aeFpsRanges = fpsRanges,
            realtimeTs = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            evRange = ev,
            evStep = evStep,
            equivMm = equiv,
            hasOis = ois,
            map = map
        )
    }

    /** Frame rates que essa resolução aceita neste aparelho. */
    fun fpsOptions(inf: CamInfo, size: Size): List<Int> {
        val minDur = inf.map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
        val maxFps = if (minDur > 0L) ceil(1e9 / minDur.toDouble()).toInt() else 30
        return listOf(24, 25, 30, 60).filter { f ->
            f <= maxFps && inf.aeFpsRanges.any { r -> r.lower <= f && r.upper >= f }
        }
    }

    private fun pickFpsRange(inf: CamInfo, fps: Int): Range<Int> {
        inf.aeFpsRanges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
        inf.aeFpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower }?.let { return it }
        return inf.aeFpsRanges.filter { it.upper >= fps }.minByOrNull { it.upper } ?: Range(fps, fps)
    }

    /** O balanço de branco manual só funciona depois que o AWB automático já mediu a cena. */
    fun wbReady(): Boolean = baseGains != null && info?.manualPost == true

    // ---------------------------------------------------------------- abrir / fechar

    fun open(inf: CamInfo, target: Surface, state: CamState) {
        info = inf
        surface = target
        snapshot = state.copy()
        baseGains = null
        baseTransform = null
        handler.post {
            try {
                manager.openCamera(inf.id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        createSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        device = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        device = null
                        listener.onError("Erro ao abrir a câmera (código $error)")
                    }
                }, handler)
            } catch (e: SecurityException) {
                listener.onError("Sem permissão de câmera")
            } catch (e: Exception) {
                Log.e(TAG, "openCamera", e)
                listener.onError("Não foi possível abrir a câmera: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val dev = device ?: return
        val s = surface ?: return
        try {
            dev.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    applyNow()
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    listener.onError("Falha ao configurar a sessão da câmera (resolução não aceita?)")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession", e)
            listener.onError("Falha ao criar sessão: ${e.message}")
        }
    }

    fun close() {
        handler.post {
            try {
                session?.close()
            } catch (e: Exception) {
            }
            try {
                device?.close()
            } catch (e: Exception) {
            }
            session = null
            device = null
        }
    }

    fun destroy() {
        close()
        thread.quitSafely()
    }

    // ---------------------------------------------------------------- parâmetros

    fun apply(state: CamState) {
        snapshot = state.copy()
        handler.post { applyNow() }
    }

    private fun applyNow() {
        val dev = device ?: return
        val sess = session ?: return
        val s = surface ?: return
        val inf = info ?: return
        val st = snapshot
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(s)
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            if (inf.hasOis) {
                b.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
            }

            // exposição
            if (st.aeManual && inf.manualSensor) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, st.iso.coerceIn(inf.isoRange.lower, inf.isoRange.upper))
                b.set(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    st.exposureNs.coerceIn(inf.expRange.lower, inf.expRange.upper)
                )
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / st.fps)
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pickFpsRange(inf, st.fps))
                b.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    st.evIndex.coerceIn(inf.evRange.lower, inf.evRange.upper)
                )
            }

            // foco
            if (inf.minFocusDiopter > 0f) {
                if (st.afManual && inf.manualSensor) {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, st.focusDiopter.coerceIn(0f, inf.minFocusDiopter))
                } else {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
            }

            // balanço de branco
            val base = baseGains
            if (st.awbManual && inf.manualPost && base != null) {
                val f = WhiteBalance.relativeGains(st.kelvin.toFloat())
                var r = base.red * f[0]
                var ge = base.greenEven * f[1]
                var go = base.greenOdd * f[1]
                var bl = base.blue * f[2]
                val mn = minOf(minOf(r, ge), minOf(go, bl))
                if (mn < 1f) {
                    r /= mn
                    ge /= mn
                    go /= mn
                    bl /= mn
                }
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                b.set(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                )
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(r, ge, go, bl))
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, baseTransform ?: IDENTITY)
            } else {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }

            sess.setRepeatingRequest(b.build(), captureCb, handler)
        } catch (e: Exception) {
            Log.e(TAG, "applyNow", e)
            listener.onError("Falha ao aplicar configurações: ${e.message}")
        }
    }

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureNs = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocus = it }
            if (!snapshot.awbManual) {
                result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { baseGains = it }
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { baseTransform = it }
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastNotify > 250) {
                lastNotify = now
                listener.onInfo()
            }
        }
    }

    companion object {
        private const val TAG = "CameraEngine"
        private val IDENTITY = ColorSpaceTransform(
            intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1)
        )
    }
}

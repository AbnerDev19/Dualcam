package br.dualcam.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraEngine.Listener {

    private enum class Param { EV, ISO, SHUTTER, WB, FOCUS }

    // ---- views
    private lateinit var surfaceView: SurfaceView
    private lateinit var previewBox: AspectFrameLayout
    private lateinit var overlay: CropOverlayView
    private lateinit var btnRecord: RecordButton
    private lateinit var txtTimer: TextView
    private lateinit var btnLens: TextView
    private lateinit var btnRes: TextView
    private lateinit var btnFps: TextView
    private lateinit var btnCodec: TextView
    private lateinit var btnZebra: TextView
    private lateinit var btnGrid: TextView
    private lateinit var chipEv: TextView
    private lateinit var chipIso: TextView
    private lateinit var chipShutter: TextView
    private lateinit var chipWb: TextView
    private lateinit var chipFocus: TextView
    private lateinit var sliderRow: View
    private lateinit var txtParam: TextView
    private lateinit var txtValue: TextView
    private lateinit var btnAuto: TextView
    private lateinit var seek: SeekBar

    // ---- estado
    private lateinit var engine: CameraEngine
    private var pipeline: VideoPipeline? = null
    private val state = CamState()
    private var cams: List<String> = emptyList()
    private var camIndex = 0
    private var info: CamInfo? = null
    private var sizes: List<Size> = emptyList()
    private var sizeIndex = 0
    private var fpsOptions: List<Int> = listOf(30)
    private var isoList: List<Int> = listOf(100)
    private var shutterList: List<Long> = listOf(16_666_666L)
    private var useHevc = false
    private var zebra = false
    private var grid = false
    private var selectedParam: Param? = null

    private var surfaceReady = false
    private var resumed = false
    private var started = false
    private var permissionsAsked = false
    private var recording = false
    private var recordingTransition = false
    private var recStartMs = 0L

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = (SystemClock.elapsedRealtime() - recStartMs) / 1000
            txtTimer.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (!recording && !recordingTransition) info?.let { pipeline?.rotationDegrees = relativeRotation(it) }
        }
    }

    // ================================================================ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        engine = CameraEngine(this, this)
        cams = try {
            engine.backCameras()
        } catch (e: Exception) {
            emptyList()
        }
        if (cams.isEmpty()) toast("Nenhuma câmera traseira encontrada")

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                tryStart()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                stopAll()
            }
        })

        setupClicks()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemUi()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, null)
        ensurePermissions()
        tryStart()
    }

    override fun onPause() {
        resumed = false
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        stopAll()
        super.onPause()
    }

    override fun onDestroy() {
        engine.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    // ================================================================ permissões

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        if (permissionsAsked) return
        val need = ArrayList<String>()
        if (!hasCameraPermission()) need.add(Manifest.permission.CAMERA)
        if (!hasAudioPermission()) need.add(Manifest.permission.RECORD_AUDIO)
        if (need.isNotEmpty()) {
            permissionsAsked = true
            requestPermissions(need.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasCameraPermission()) {
            tryStart()
        } else {
            toast("O app precisa da permissão de câmera para funcionar")
        }
    }

    // ================================================================ iniciar / parar câmera

    private fun tryStart() {
        if (started || !surfaceReady || !resumed || !hasCameraPermission()) return
        val id = cams.getOrNull(camIndex) ?: return
        val inf = try {
            engine.loadInfo(id)
        } catch (e: Exception) {
            toast("Falha ao ler a câmera: ${e.message}")
            return
        }
        info = inf
        if (!configureForCamera(inf)) return
        started = true
        startPipelineAndCamera(inf)
    }

    private fun currentMime(): String = if (useHevc) CodecUtil.HEVC else CodecUtil.AVC

    private fun candidateSizes(inf: CamInfo, mime: String): List<Size> {
        return inf.sizes.filter { s ->
            val ar = s.width.toFloat() / s.height.toFloat()
            ar in 1.30f..1.80f &&
                s.width in 640..3840 && engine.fpsOptions(inf, s).any { fps ->
                CodecUtil.supports(mime, s.width, s.height, fps) &&
                CodecUtil.supports(mime, CodecUtil.cropWidth(s.height, mime), s.height, fps)
            }
        }.sortedByDescending { it.width * it.height }
    }

    /** Recalcula resoluções, fps e listas de ISO/obturador para a câmera atual. */
    private fun configureForCamera(inf: CamInfo): Boolean {
        val previous = sizes.getOrNull(sizeIndex)
        if (useHevc && !CodecUtil.hasEncoder(CodecUtil.HEVC)) useHevc = false
        var list = candidateSizes(inf, currentMime())
        if (list.isEmpty() && useHevc) {
            useHevc = false
            list = candidateSizes(inf, CodecUtil.AVC)
        }
        if (list.isEmpty()) {
            toast("Nenhuma resolução de vídeo compatível com o encoder deste aparelho")
            return false
        }
        sizes = list

        var idx = -1
        if (previous != null) idx = list.indexOfFirst { it.width == previous.width && it.height == previous.height }
        if (idx < 0) idx = list.indexOfFirst { it.width == 1920 && it.height == 1080 }
        if (idx < 0) idx = 0
        sizeIndex = idx

        isoList = Stops.isoList(inf.isoRange.lower, inf.isoRange.upper)
        updateFpsOptions(inf)
        return true
    }

    private fun updateFpsOptions(inf: CamInfo) {
        val size = sizes[sizeIndex]
        val mime = currentMime()
        val cropW = CodecUtil.cropWidth(size.height, mime)
        var opts = engine.fpsOptions(inf, size).filter {
            CodecUtil.supports(mime, size.width, size.height, it) &&
                CodecUtil.supports(mime, cropW, size.height, it)
        }
        check(opts.isNotEmpty()) { "Sem FPS compatível para esta resolução" }
        fpsOptions = opts
        if (state.fps !in opts) state.fps = if (30 in opts) 30 else opts.first()
        shutterList = Stops.shutterList(inf.expRange.lower, inf.expRange.upper, state.fps)
        if (state.aeManual) {
            state.exposureNs = shutterList[Stops.nearest(shutterList, state.exposureNs.toDouble())]
        }
    }

    private fun startPipelineAndCamera(inf: CamInfo) {
        val size = sizes[sizeIndex]
        val cropW = CodecUtil.cropWidth(size.height, currentMime())
        previewBox.setAspect(size.width.toFloat() / size.height.toFloat())
        overlay.update(cropW.toFloat() / size.width.toFloat(), grid, recording)

        val p = VideoPipeline()
        pipeline = p
        p.zebra = zebra
        p.rotationDegrees = relativeRotation(inf)
        p.start(surfaceView.holder.surface, size.width, size.height, cropW, inf.realtimeTs, { message ->
            runOnUiThread { if (pipeline === p) { stopAll(); toast(message) } }
        }) { camSurface ->
            runOnUiThread { if (pipeline === p && started && resumed) engine.open(inf, camSurface, state) }
        }
        bindSeek()
        refreshUi()
    }

    private fun stopAll() {
        if (!started) return
        started = false
        ui.removeCallbacks(tick)
        recordingTransition = false
        btnRecord.isEnabled = false
        if (recording) {
            recording = false
            btnRecord.recording = false
            txtTimer.visibility = View.INVISIBLE
            overlay.update(overlayFraction(), grid, false)
            toast("Gravação encerrada (o app saiu de cena). Arquivos em Movies/DualCam")
        }
        engine.close()
        pipeline?.releaseAndWait()
        pipeline = null
    }

    private fun overlayFraction(): Float {
        val size = sizes.getOrNull(sizeIndex) ?: return 0.3164f
        return CodecUtil.cropWidth(size.height, currentMime()).toFloat() / size.width.toFloat()
    }

    private fun restartCamera() {
        stopAll()
        tryStart()
    }

    /** Rotação (em graus, horário) que o quadro do sensor precisa para ficar em pé na tela. */
    @Suppress("DEPRECATION")
    private fun relativeRotation(inf: CamInfo): Int {
        val deg = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (inf.sensorOrientation - deg + 360) % 360
    }

    // ================================================================ CameraEngine.Listener

    override fun onInfo() {
        runOnUiThread { if (started) btnRecord.isEnabled = !recordingTransition; updateChipLabels(); updateValueText() }
    }

    override fun onError(msg: String) {
        runOnUiThread { stopAll(); toast(msg + ". Toque RESET para tentar novamente.") }
    }

    // ================================================================ interface

    private fun bindViews() {
        surfaceView = findViewById(R.id.surfaceView)
        previewBox = findViewById(R.id.previewBox)
        overlay = findViewById(R.id.overlay)
        btnRecord = findViewById(R.id.btnRecord)
        btnRecord.isEnabled = false
        txtTimer = findViewById(R.id.txtTimer)
        btnLens = findViewById(R.id.btnLens)
        btnRes = findViewById(R.id.btnRes)
        btnFps = findViewById(R.id.btnFps)
        btnCodec = findViewById(R.id.btnCodec)
        btnZebra = findViewById(R.id.btnZebra)
        btnGrid = findViewById(R.id.btnGrid)
        chipEv = findViewById(R.id.chipEv)
        chipIso = findViewById(R.id.chipIso)
        chipShutter = findViewById(R.id.chipShutter)
        chipWb = findViewById(R.id.chipWb)
        chipFocus = findViewById(R.id.chipFocus)
        sliderRow = findViewById(R.id.sliderRow)
        txtParam = findViewById(R.id.txtParam)
        txtValue = findViewById(R.id.txtValue)
        btnAuto = findViewById(R.id.btnAuto)
        seek = findViewById(R.id.seek)
    }

    private fun setupClicks() {
        btnRecord.setOnClickListener { toggleRecording() }
        findViewById<TextView>(R.id.btnReset).setOnClickListener {
            if (blockedWhileRecording()) return@setOnClickListener
            state.aeManual = false; state.afManual = false; state.awbManual = false
            state.evIndex = 0; state.kelvin = 5500
            selectedParam = null
            if (started) engine.apply(state) else tryStart()
            bindSeek(); refreshUi()
            toast("Exposição, foco e cores em automático")
        }

        btnLens.setOnClickListener {
            if (blockedWhileRecording()) return@setOnClickListener
            if (cams.size < 2) {
                toast("Este aparelho só expõe uma câmera traseira ao app")
                return@setOnClickListener
            }
            choose("Lente traseira", cams.map { id ->
                val mm = runCatching { engine.loadInfo(id).equivMm }.getOrDefault(0)
                "Câmera $id" + if (mm > 0) " · ${mm}mm" else ""
            }, camIndex) { index ->
                camIndex = index
                state.aeManual = false; state.awbManual = false; state.afManual = false
                state.evIndex = 0; selectedParam = null
                restartCamera()
            }
        }

        btnRes.setOnClickListener {
            if (blockedWhileRecording() || sizes.size < 2) return@setOnClickListener
            choose("Resolução", sizes.map { "${it.width} × ${it.height}" }, sizeIndex) { index ->
                sizeIndex = index
                info?.let { updateFpsOptions(it) }
                restartCamera()
            }
        }

        btnFps.setOnClickListener {
            if (blockedWhileRecording() || fpsOptions.size < 2) return@setOnClickListener
            val inf = info ?: return@setOnClickListener
            choose("Quadros por segundo", fpsOptions.map { "$it fps" }, fpsOptions.indexOf(state.fps)) { index ->
                state.fps = fpsOptions[index]
                shutterList = Stops.shutterList(inf.expRange.lower, inf.expRange.upper, state.fps)
                if (state.aeManual) state.exposureNs = shutterList[Stops.nearest(shutterList, state.exposureNs.toDouble())]
                engine.apply(state); bindSeek(); refreshUi()
            }
        }

        btnCodec.setOnClickListener {
            if (blockedWhileRecording()) return@setOnClickListener
            if (!CodecUtil.hasEncoder(CodecUtil.HEVC) || !CodecUtil.hasEncoder(CodecUtil.AVC)) {
                toast("Este aparelho só tem um codec de vídeo disponível")
                return@setOnClickListener
            }
            useHevc = !useHevc
            restartCamera()
        }

        btnZebra.setOnClickListener {
            zebra = !zebra
            pipeline?.zebra = zebra
            refreshUi()
        }

        btnGrid.setOnClickListener {
            grid = !grid
            overlay.update(overlayFraction(), grid, recording)
            refreshUi()
        }

        chipEv.setOnClickListener { selectParam(Param.EV) }
        chipIso.setOnClickListener { selectParam(Param.ISO) }
        chipShutter.setOnClickListener { selectParam(Param.SHUTTER) }
        chipWb.setOnClickListener { selectParam(Param.WB) }
        chipFocus.setOnClickListener { selectParam(Param.FOCUS) }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onSeek(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnAuto.setOnClickListener {
            when (selectedParam) {
                Param.ISO, Param.SHUTTER -> {
                    if (state.aeManual) state.aeManual = false else enterManualExposure()
                }
                Param.WB -> {
                    if (state.awbManual) state.awbManual = false else enterManualWb()
                }
                Param.FOCUS -> {
                    if (state.afManual) state.afManual = false else enterManualFocus()
                }
                else -> {}
            }
            engine.apply(state)
            bindSeek()
            refreshUi()
        }
    }

    private fun choose(title: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, index ->
                dialog.dismiss()
                if (!blockedWhileRecording()) onSelect(index)
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun blockedWhileRecording(): Boolean {
        val blocked = recording || recordingTransition
        if (blocked) toast("Aguarde o fim da gravação para mudar isto")
        return blocked
    }

    private fun selectParam(p: Param) {
        selectedParam = if (selectedParam == p) null else p
        bindSeek()
        refreshUi()
    }

    private fun bindSeek() {
        val p = selectedParam
        val inf = info
        if (p == null || inf == null) {
            sliderRow.visibility = View.GONE
            return
        }
        sliderRow.visibility = View.VISIBLE
        when (p) {
            Param.EV -> {
                txtParam.text = "EV"
                seek.max = (inf.evRange.upper - inf.evRange.lower).coerceAtLeast(1)
                seek.progress = state.evIndex - inf.evRange.lower
            }
            Param.ISO -> {
                txtParam.text = "ISO"
                seek.max = (isoList.size - 1).coerceAtLeast(1)
                seek.progress = Stops.nearest(isoList, currentIso().toDouble())
            }
            Param.SHUTTER -> {
                txtParam.text = "OBTURADOR"
                seek.max = (shutterList.size - 1).coerceAtLeast(1)
                seek.progress = Stops.nearest(shutterList, currentExposure().toDouble())
            }
            Param.WB -> {
                txtParam.text = "TEMP"
                seek.max = (10000 - 2500) / 100
                seek.progress = (state.kelvin - 2500) / 100
            }
            Param.FOCUS -> {
                txtParam.text = "FOCO"
                seek.max = 100
                seek.progress = if (inf.minFocusDiopter > 0f) {
                    (state.focusDiopter / inf.minFocusDiopter * 100f).roundToInt().coerceIn(0, 100)
                } else 0
            }
        }
        val available = when (p) {
            Param.ISO, Param.SHUTTER -> inf.manualSensor
            Param.WB -> inf.manualPost
            Param.FOCUS -> inf.manualFocus && inf.minFocusDiopter > 0f
            Param.EV -> inf.evRange.lower != inf.evRange.upper
        }
        seek.isEnabled = available
        btnAuto.isEnabled = available
        txtParam.text = txtParam.text.toString() + if (available) "" else " · indisponível"
        updateValueText()
    }

    private fun onSeek(progress: Int) {
        val inf = info ?: return
        when (selectedParam) {
            Param.EV -> { state.aeManual = false; state.evIndex = inf.evRange.lower + progress }
            Param.ISO -> {
                if (!enterManualExposure()) return
                state.iso = isoList[progress.coerceIn(0, isoList.size - 1)]
            }
            Param.SHUTTER -> {
                if (!enterManualExposure()) return
                state.exposureNs = shutterList[progress.coerceIn(0, shutterList.size - 1)]
            }
            Param.WB -> {
                if (!enterManualWb()) return
                state.kelvin = 2500 + progress * 100
            }
            Param.FOCUS -> {
                if (!enterManualFocus()) return
                state.focusDiopter = progress / 100f * inf.minFocusDiopter
            }
            null -> return
        }
        engine.apply(state)
        updateValueText()
        updateChipLabels()
    }

    private fun currentIso(): Int = if (state.aeManual) state.iso else engine.lastIso

    private fun currentExposure(): Long =
        if (state.aeManual) state.exposureNs else engine.lastExposureNs.coerceAtLeast(1L)

    private fun enterManualExposure(): Boolean {
        val inf = info ?: return false
        if (!inf.manualSensor) {
            toast("Este aparelho não libera ISO/obturador manual para apps")
            return false
        }
        if (!state.aeManual) {
            state.aeManual = true
            state.iso = engine.lastIso.coerceIn(inf.isoRange.lower, inf.isoRange.upper)
            val seed = engine.lastExposureNs.coerceIn(shutterList.first(), shutterList.last())
            state.exposureNs = seed
        }
        return true
    }

    private fun enterManualWb(): Boolean {
        if (!engine.wbReady()) {
            toast("Balanço de branco manual indisponível (aguarde a câmera medir a cena ou o aparelho não libera)")
            return false
        }
        state.awbManual = true
        return true
    }

    private fun enterManualFocus(): Boolean {
        val inf = info ?: return false
        if (!inf.manualFocus || inf.minFocusDiopter <= 0f) {
            toast("Esta lente tem foco fixo ou não libera foco manual")
            return false
        }
        if (!state.afManual) {
            state.afManual = true
            state.focusDiopter = engine.lastFocus.coerceIn(0f, inf.minFocusDiopter)
        }
        return true
    }

    // ---- textos

    private fun style(chip: TextView, manual: Boolean, selected: Boolean) {
        chip.isSelected = selected
        chip.setTextColor(
            if (selected) Color.BLACK else if (manual) 0xFFFFB300.toInt() else Color.WHITE
        )
    }

    private fun refreshUi() {
        val inf = info
        btnLens.text = if (inf != null && inf.equivMm > 0) "LENTE ${inf.equivMm}mm" else "LENTE"
        val size = sizes.getOrNull(sizeIndex)
        btnRes.text = if (size == null) "—" else sizeLabel(size)
        btnFps.text = "${state.fps}fps"
        btnCodec.text = if (useHevc) "H.265" else "H.264"
        style(btnLens, false, false)
        style(btnRes, false, false)
        style(btnFps, false, false)
        style(btnCodec, false, false)
        style(btnZebra, false, zebra)
        style(btnGrid, false, grid)
        updateChipLabels()
        updateValueText()
    }

    private fun sizeLabel(s: Size): String = when {
        s.width == 3840 && s.height == 2160 -> "4K"
        s.width == 1920 && s.height == 1080 -> "1080p"
        s.width == 1280 && s.height == 720 -> "720p"
        else -> "${s.width}x${s.height}"
    }

    private fun updateChipLabels() {
        val inf = info ?: return
        chipEv.text = if (state.aeManual) "EV —" else "EV " + String.format(Locale.US, "%+.1f", state.evIndex * inf.evStep)
        chipIso.text = "ISO ${currentIso()}"
        chipShutter.text = Stops.shutterLabel(
            if (state.aeManual) state.exposureNs else engine.lastExposureNs
        )
        chipWb.text = if (state.awbManual) "WB ${state.kelvin}K" else "WB AUTO"
        chipFocus.text = when {
            inf.minFocusDiopter <= 0f -> "FOCO FIXO"
            state.afManual -> "MF " + distanceText(state.focusDiopter)
            else -> "AF"
        }
        style(chipEv, false, selectedParam == Param.EV)
        style(chipIso, state.aeManual, selectedParam == Param.ISO)
        style(chipShutter, state.aeManual, selectedParam == Param.SHUTTER)
        style(chipWb, state.awbManual, selectedParam == Param.WB)
        style(chipFocus, state.afManual, selectedParam == Param.FOCUS)
        updateAutoButton()
    }

    private fun updateAutoButton() {
        val manual = when (selectedParam) {
            Param.ISO, Param.SHUTTER -> state.aeManual
            Param.WB -> state.awbManual
            Param.FOCUS -> state.afManual
            else -> false
        }
        btnAuto.visibility = if (selectedParam == Param.EV) View.INVISIBLE else View.VISIBLE
        btnAuto.text = if (manual) "MANUAL · AUTO?" else "AUTO · MANUAL?"
        style(btnAuto, false, !manual)
    }

    private fun updateValueText() {
        val inf = info ?: return
        txtValue.text = when (selectedParam) {
            Param.EV -> String.format(Locale.US, "%+.1f", state.evIndex * inf.evStep)
            Param.ISO -> currentIso().toString()
            Param.SHUTTER -> Stops.shutterLabel(currentExposure())
            Param.WB -> if (state.awbManual) "${state.kelvin}K" else "AUTO"
            Param.FOCUS -> if (state.afManual) distanceText(state.focusDiopter) else "AF"
            null -> ""
        }
        updateAutoButton()
    }

    private fun distanceText(diopter: Float): String =
        if (diopter <= 0.01f) "∞" else String.format(Locale.US, "%.2fm", 1f / diopter)

    // ================================================================ gravação

    private fun toggleRecording() {
        if (!btnRecord.isEnabled) return
        val p = pipeline ?: return
        val inf = info ?: return
        val size = sizes.getOrNull(sizeIndex) ?: return

        if (!recording) {
            val mime = currentMime()
            val cropW = CodecUtil.cropWidth(size.height, mime)
            val fps = state.fps
            val rotation = 0
            val audio = hasAudioPermission()
            val app = applicationContext
            recordingTransition = true
            btnRecord.isEnabled = false
            p.startRecording(
                { Recorder.create(app, size.width, size.height, cropW, fps, mime, rotation, audio) }
            ) { ok, err ->
                runOnUiThread {
                    if (pipeline !== p || !resumed) return@runOnUiThread
                    recordingTransition = false
                    btnRecord.isEnabled = true
                    if (ok) {
                        recording = true
                        recStartMs = SystemClock.elapsedRealtime()
                        btnRecord.recording = true
                        txtTimer.text = "● 00:00"
                        txtTimer.visibility = View.VISIBLE
                        overlay.update(overlayFraction(), grid, true)
                        ui.post(tick)
                        if (!audio) toast("Gravando sem áudio (permissão de microfone negada)")
                    } else {
                        toast("Não foi possível gravar: $err")
                    }
                }
            }
        } else {
            recordingTransition = true
            btnRecord.isEnabled = false
            p.stopRecording { names ->
                runOnUiThread {
                    if (pipeline !== p || !resumed) return@runOnUiThread
                    recordingTransition = false
                    ui.removeCallbacks(tick)
                    recording = false
                    btnRecord.isEnabled = true
                    btnRecord.recording = false
                    txtTimer.visibility = View.INVISIBLE
                    overlay.update(overlayFraction(), grid, false)
                    if (names.isEmpty()) {
                        toast("A gravação foi muito curta ou falhou — nada foi salvo")
                    } else {
                        toast("Salvo em Movies/DualCam:\n" + names.joinToString("\n"))
                    }
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REQ_PERMS = 10
    }
}

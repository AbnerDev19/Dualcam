package br.dualcam.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uma gravação: dois arquivos (horizontal "H" e vertical "V") + um áudio compartilhado.
 * Criar com [create]; encerrar com [finish].
 */
class Recorder private constructor(
    val startNs: Long,
    private val sinkH: MuxerSink,
    private val sinkV: MuxerSink,
    val landscape: VideoEncoder,
    val portrait: VideoEncoder,
    private val audio: AudioPump?
) {

    /** Encerra tudo e devolve os nomes dos arquivos salvos. */
    fun finish(): List<String> {
        audio?.stop()
        landscape.finish()
        portrait.finish()
        val saved = ArrayList<String>()
        if (sinkH.finish()) saved.add(sinkH.displayName)
        if (sinkV.finish()) saved.add(sinkV.displayName)
        return saved
    }

    companion object {
        fun create(
            ctx: Context,
            camW: Int,
            camH: Int,
            cropW: Int,
            fps: Int,
            mime: String,
            rotation: Int,
            withAudio: Boolean
        ): Recorder {
            val resolver = ctx.contentResolver
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val tracks = if (withAudio) 2 else 1

            val sinkH = MuxerSink(resolver, "DualCam_${stamp}_H.mp4", rotation, tracks)
            var sinkV: MuxerSink? = null
            var encH: VideoEncoder? = null
            var encV: VideoEncoder? = null
            try {
                sinkV = MuxerSink(resolver, "DualCam_${stamp}_V.mp4", rotation, tracks)
                encH = VideoEncoder(mime, camW, camH, CodecUtil.bitrate(mime, camW, camH, fps), fps, sinkH)
                encV = VideoEncoder(mime, cropW, camH, CodecUtil.bitrate(mime, cropW, camH, fps), fps, sinkV)
            } catch (e: Exception) {
                encH?.release()
                encV?.release()
                sinkH.finish()
                sinkV?.finish()
                throw e
            }

            val sv: MuxerSink = sinkV!!
            val eh: VideoEncoder = encH!!
            val ev: VideoEncoder = encV!!

            val startNs = Clock.nowNs()
            var audio: AudioPump? = null
            if (withAudio) {
                val a = AudioPump(listOf(sinkH, sv))
                if (a.start(startNs)) {
                    audio = a
                } else {
                    sinkH.audioUnavailable()
                    sv.audioUnavailable()
                }
            }
            return Recorder(startNs, sinkH, sv, eh, ev, audio)
        }
    }
}

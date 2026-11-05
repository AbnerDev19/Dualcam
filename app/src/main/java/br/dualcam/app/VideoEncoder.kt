package br.dualcam.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

/** Encoder de vídeo alimentado por Surface (o OpenGL desenha nela). */
class VideoEncoder(
    mime: String,
    val width: Int,
    val height: Int,
    bitrate: Int,
    fps: Int,
    private val sink: MuxerSink
) {
    private val codec: MediaCodec
    val inputSurface: Surface
    private var track = -1
    private val info = MediaCodec.BufferInfo()
    private var released = false

    init {
        val ci = CodecUtil.pickEncoder(mime)
            ?: throw IllegalStateException("Sem encoder de vídeo para $mime")
        codec = MediaCodec.createByCodecName(ci.name)
        val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    /** Escoa o que o encoder já terminou, sem bloquear. */
    fun drain() = drainInternal(false)

    /** Fim do vídeo: avisa o encoder, escoa tudo e libera. */
    fun finish() {
        try {
            codec.signalEndOfInputStream()
        } catch (e: Exception) {
        }
        drainInternal(true)
        release()
    }

    private fun drainInternal(eos: Boolean) {
        val timeoutUs = if (eos) 10_000L else 0L
        var idle = 0
        while (true) {
            val idx = try {
                codec.dequeueOutputBuffer(info, timeoutUs)
            } catch (e: Exception) {
                return
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos) return
                    idle++
                    if (idle > 300) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = sink.addTrack(codec.outputFormat)
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) sink.write(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        try {
            codec.stop()
        } catch (e: Exception) {
        }
        try {
            codec.release()
        } catch (e: Exception) {
        }
        try {
            inputSurface.release()
        } catch (e: Exception) {
        }
    }
}

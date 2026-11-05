package br.dualcam.app

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlin.math.max

object CodecUtil {
    const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
    const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC

    /** Prefere encoder de hardware. */
    fun pickEncoder(mime: String): MediaCodecInfo? {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        return all.firstOrNull { !it.isSoftwareOnly } ?: all.firstOrNull()
    }

    fun hasEncoder(mime: String): Boolean = pickEncoder(mime) != null

    private fun caps(mime: String): MediaCodecInfo.VideoCapabilities? = try {
        pickEncoder(mime)?.getCapabilitiesForType(mime)?.videoCapabilities
    } catch (e: Exception) {
        null
    }

    /** Largura do recorte vertical (9:16) para uma altura de vídeo, respeitando o alinhamento do encoder. */
    fun cropWidth(height: Int, mime: String): Int {
        val align = max(2, caps(mime)?.widthAlignment ?: 2)
        val raw = height * 9 / 16
        return max(align, raw / align * align)
    }

    fun supports(mime: String, w: Int, h: Int, fps: Int): Boolean {
        val c = caps(mime) ?: return false
        return try {
            c.areSizeAndRateSupported(w, h, fps.toDouble())
        } catch (e: Exception) {
            false
        }
    }

    fun bitrate(mime: String, w: Int, h: Int, fps: Int): Int {
        val factor = if (mime == HEVC) 0.15 else 0.22
        var br = (w.toDouble() * h * fps * factor).toInt().coerceIn(4_000_000, 120_000_000)
        caps(mime)?.bitrateRange?.let { br = br.coerceIn(it.lower, it.upper) }
        return br
    }
}

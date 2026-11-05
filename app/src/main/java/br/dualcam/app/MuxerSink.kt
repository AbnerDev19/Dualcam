package br.dualcam.app

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer

/**
 * Um arquivo .mp4 na galeria (Movies/DualCam). Recebe amostras de vídeo e áudio de threads
 * diferentes; guarda numa fila até todas as trilhas esperadas existirem e então inicia o muxer.
 */
class MuxerSink(
    private val resolver: ContentResolver,
    val displayName: String,
    rotation: Int,
    private var expectedTracks: Int
) {
    private val uri: Uri
    private val pfd: ParcelFileDescriptor
    private val muxer: MediaMuxer
    private var tracks = 0
    private var started = false
    private var finished = false
    private val pending = ArrayList<Pending>()

    private class Pending(val track: Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    init {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DualCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Não foi possível criar o arquivo $displayName")
        pfd = resolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Não foi possível abrir o arquivo $displayName")
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
    }

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val idx = muxer.addTrack(format)
        tracks++
        maybeStart()
        return idx
    }

    /** O áudio não vai existir (microfone falhou): começa só com o vídeo. */
    @Synchronized
    fun audioUnavailable() {
        expectedTracks = 1
        maybeStart()
    }

    private fun maybeStart() {
        if (!started && !finished && tracks >= expectedTracks) {
            muxer.start()
            started = true
            for (p in pending) {
                try {
                    muxer.writeSampleData(p.track, p.data, p.info)
                } catch (e: Exception) {
                    Log.w(TAG, "write pendente falhou", e)
                }
            }
            pending.clear()
        }
    }

    @Synchronized
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (finished || info.size <= 0) return
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        if (started) {
            try {
                muxer.writeSampleData(track, buf, info)
            } catch (e: Exception) {
                Log.w(TAG, "writeSampleData falhou", e)
            }
        } else {
            val copy = ByteBuffer.allocate(info.size)
            copy.put(buf)
            copy.flip()
            val i = MediaCodec.BufferInfo()
            i.set(0, info.size, info.presentationTimeUs, info.flags)
            pending.add(Pending(track, copy, i))
        }
    }

    /** Fecha o arquivo. Retorna true se o vídeo ficou válido e foi publicado na galeria. */
    @Synchronized
    fun finish(): Boolean {
        if (finished) return false
        finished = true
        var ok = started
        try {
            if (started) muxer.stop()
        } catch (e: Exception) {
            ok = false
        }
        try {
            muxer.release()
        } catch (e: Exception) {
        }
        try {
            pfd.close()
        } catch (e: Exception) {
        }
        if (ok) {
            val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, v, null, null)
        } else {
            resolver.delete(uri, null, null)
        }
        return ok
    }

    companion object {
        private const val TAG = "MuxerSink"
    }
}

package br.dualcam.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log

/** Captura o microfone, codifica em AAC e entrega o MESMO áudio para os dois arquivos. */
class AudioPump(private val sinks: List<MuxerSink>) {

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val chunkBytes = 2048

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var startNs = 0L
    private val tracks = IntArray(sinks.size) { -1 }
    private val info = MediaCodec.BufferInfo()

    /** @return false se não foi possível abrir o microfone. */
    fun start(startNs: Long): Boolean {
        this.startNs = startNs
        try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return false
            val bufSize = maxOf(minBuf * 4, 16384)

            var rec = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, sampleRate, channelConfig,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate, channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize
                )
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }

            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()

            record = rec
            codec = c
            running = true
            rec.startRecording()
            thread = Thread({ loop() }, "audio-pump").also { it.start() }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar áudio", e)
            running = false
            cleanup()
            return false
        }
    }

    fun stop() {
        running = false
        try {
            thread?.join(4000)
        } catch (e: InterruptedException) {
        }
    }

    private fun loop() {
        val rec = record ?: return
        val c = codec ?: return
        val buf = ByteArray(chunkBytes)
        var totalSamples = 0L
        var anchorUs = -1L

        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n < 0) break
            if (n == 0) continue
            if (anchorUs < 0) {
                val chunkUs = (n / 2) * 1_000_000L / sampleRate
                anchorUs = maxOf(0L, (Clock.nowNs() - startNs) / 1000L - chunkUs)
            }
            val ptsUs = anchorUs + totalSamples * 1_000_000L / sampleRate
            feed(c, buf, n, ptsUs, 0)
            totalSamples += (n / 2).toLong()
            drain(c, false)
        }

        val endPts = maxOf(0L, anchorUs) + totalSamples * 1_000_000L / sampleRate
        feed(c, ByteArray(0), 0, endPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(c, true)
        cleanup()
    }

    private fun feed(c: MediaCodec, data: ByteArray, n: Int, ptsUs: Long, flags: Int) {
        var tries = 0
        while (tries < 100) {
            tries++
            val idx = try {
                c.dequeueInputBuffer(10_000)
            } catch (e: Exception) {
                return
            }
            if (idx >= 0) {
                val inBuf = c.getInputBuffer(idx) ?: return
                inBuf.clear()
                inBuf.put(data, 0, n)
                c.queueInputBuffer(idx, 0, n, ptsUs, flags)
                return
            }
            drain(c, false)
        }
    }

    private fun drain(c: MediaCodec, eos: Boolean) {
        var idle = 0
        while (true) {
            val idx = try {
                c.dequeueOutputBuffer(info, if (eos) 10_000L else 0L)
            } catch (e: Exception) {
                return
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos) return
                    idle++
                    if (idle > 200) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    for (i in sinks.indices) tracks[i] = sinks[i].addTrack(f)
                }
                idx >= 0 -> {
                    val b = c.getOutputBuffer(idx)
                    if (b != null) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            for (i in sinks.indices) {
                                if (tracks[i] >= 0) sinks[i].write(tracks[i], b, info)
                            }
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun cleanup() {
        try {
            record?.stop()
        } catch (e: Exception) {
        }
        try {
            record?.release()
        } catch (e: Exception) {
        }
        try {
            codec?.stop()
        } catch (e: Exception) {
        }
        try {
            codec?.release()
        } catch (e: Exception) {
        }
        record = null
        codec = null
    }

    companion object {
        private const val TAG = "AudioPump"
    }
}

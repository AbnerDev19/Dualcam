package br.dualcam.app

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Relógio único usado para sincronizar vídeo e áudio. */
object Clock {
    fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
}

/** Listas de valores "de fotógrafo" para ISO e obturador. */
object Stops {
    private val ISO = intArrayOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
        1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800
    )
    private val SHUTTER_DEN = intArrayOf(
        8000, 4000, 2000, 1000, 500, 250, 200, 160, 125, 100, 80, 60,
        50, 48, 40, 30, 25, 24, 20, 15, 12, 10, 8
    )

    fun isoList(min: Int, max: Int): List<Int> {
        val l = ISO.filter { it in min..max }
        return if (l.isNotEmpty()) l else listOf(min, max).distinct()
    }

    /** Tempos de obturador em ns, do mais rápido ao mais lento, limitados ao frame rate. */
    fun shutterList(minNs: Long, maxNs: Long, fps: Int): List<Long> {
        val limit = minOf(maxNs, 1_000_000_000L / fps + 1000L)
        val l = SHUTTER_DEN.map { 1_000_000_000L / it }.filter { it in minNs..limit }
        return if (l.isNotEmpty()) l else listOf(minNs)
    }

    fun nearest(values: List<Number>, v: Double): Int {
        val target = if (v < 1.0) 1.0 else v
        var best = 0
        var bestDist = Double.MAX_VALUE
        for (i in values.indices) {
            val d = abs(ln(values[i].toDouble().coerceAtLeast(1.0) / target))
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    fun shutterLabel(ns: Long): String {
        if (ns <= 0L) return "—"
        val den = (1e9 / ns).roundToInt()
        return "1/$den"
    }
}

/**
 * Balanço de branco manual por temperatura (aproximado).
 *
 * O ganho final = ganho do AWB (congelado ao entrar no manual) x fator relativo a 5500K.
 * Valor baixo (luz quente) deixa a imagem mais azulada; valor alto deixa mais amarelada.
 */
object WhiteBalance {
    private fun lightRgb(kelvin: Float): FloatArray {
        val t = kelvin.toDouble() / 100.0
        val r = if (t <= 66.0) 255.0 else 329.698727446 * (t - 60.0).pow(-0.1332047592)
        val g = if (t <= 66.0) 99.4708025861 * ln(t) - 161.1195681661
        else 288.1221695283 * (t - 60.0).pow(-0.0755148492)
        val b = if (t >= 66.0) 255.0 else if (t <= 19.0) 0.0 else 138.5177312231 * ln(t - 10.0) - 305.0447927307
        return floatArrayOf(
            (r.coerceIn(1.0, 255.0) / 255.0).toFloat(),
            (g.coerceIn(1.0, 255.0) / 255.0).toFloat(),
            (b.coerceIn(1.0, 255.0) / 255.0).toFloat()
        )
    }

    /** Multiplicadores [R, G, B] a aplicar sobre os ganhos do AWB. */
    fun relativeGains(kelvin: Float): FloatArray {
        val ref = lightRgb(5500f)
        val cur = lightRgb(kelvin)
        return floatArrayOf(ref[0] / cur[0], ref[1] / cur[1], ref[2] / cur[2])
    }
}

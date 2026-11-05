package br.dualcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Desenha por cima do preview a moldura do vídeo vertical (9:16),
 * escurecendo o que fica de fora, e opcionalmente uma grade de terços.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var cropFraction = 0.3164f
    private var showGrid = false
    private var recording = false

    private val density = resources.displayMetrics.density

    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x55FFFFFF
    }
    private val gridPortraitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x66FFB300
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 12f * density
        isFakeBoldText = true
    }

    fun update(cropFraction: Float, grid: Boolean, recording: Boolean) {
        this.cropFraction = cropFraction
        this.showGrid = grid
        this.recording = recording
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cw = w * cropFraction
        val left = (w - cw) / 2f
        val right = left + cw

        // escurece o que NÃO entra no vídeo vertical
        canvas.drawRect(0f, 0f, left, h, dimPaint)
        canvas.drawRect(right, 0f, w, h, dimPaint)

        if (showGrid) {
            for (i in 1..2) {
                val x = w * i / 3f
                val y = h * i / 3f
                canvas.drawLine(x, 0f, x, h, gridPaint)
                canvas.drawLine(0f, y, w, y, gridPaint)
                val px = left + cw * i / 3f
                canvas.drawLine(px, 0f, px, h, gridPortraitPaint)
            }
        }

        borderPaint.color = if (recording) 0xFFFF3B30.toInt() else 0xFFFFFFFF.toInt()
        val inset = borderPaint.strokeWidth / 2f
        canvas.drawRect(left + inset, inset, right - inset, h - inset, borderPaint)
        canvas.drawText("9:16", left + 8f * density, 56f * density, textPaint)
    }
}

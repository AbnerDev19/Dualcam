package br.dualcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Botão de gravar: círculo vermelho; vira quadrado arredondado enquanto grava. */
class RecordButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var recording: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = 0xFFFFFFFF.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFF3B30.toInt()
    }
    private val rect = RectF()

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 3f * density
        canvas.drawCircle(cx, cy, r, ring)
        if (recording) {
            val s = r * 0.5f
            rect.set(cx - s, cy - s, cx + s, cy + s)
            canvas.drawRoundRect(rect, 6f * density, 6f * density, fill)
        } else {
            canvas.drawCircle(cx, cy, r - 8f * density, fill)
        }
    }
}

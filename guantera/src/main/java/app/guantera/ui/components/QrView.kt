package app.guantera.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.guantera.R
import app.guantera.presentation.parked.QrEncoder
import app.guantera.presentation.parked.QrMatrix

/**
 * Draws a QR symbol dark on light with its quiet zone. Phone scanners often reject inverted codes,
 * so this stays light even on a black screen, and modules are whole pixels to keep their edges sharp.
 */
class QrView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val lightColor = ContextCompat.getColor(context, R.color.ink)
    private val darkPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.background) }
    private var content: String? = null
    private var matrix: QrMatrix? = null

    fun setContent(text: String) {
        if (text == content) return
        content = text
        matrix = QrEncoder.encode(text)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(lightColor)
        val qr = matrix ?: return
        val modulePx = minOf(width, height) / (qr.size + QUIET_ZONE_MODULES * 2)
        val left = (width - qr.size * modulePx) / 2f
        val top = (height - qr.size * modulePx) / 2f
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr.isDark(x, y)) {
                    val px = left + x * modulePx
                    val py = top + y * modulePx
                    canvas.drawRect(px, py, px + modulePx, py + modulePx, darkPaint)
                }
            }
        }
    }

    private companion object {
        const val QUIET_ZONE_MODULES = 4
    }
}

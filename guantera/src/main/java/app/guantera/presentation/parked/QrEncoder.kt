package app.guantera.presentation.parked

import io.nayuki.qrcodegen.QrCode

/** The dark and light modules of a QR symbol, without any quiet zone. */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    fun isDark(x: Int, y: Int): Boolean = dark[y * size + x]
}

object QrEncoder {
    /** Medium correction keeps the symbol small while tolerating a dirty or reflective screen. */
    fun encode(text: String): QrMatrix {
        val code = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val modules = BooleanArray(code.size * code.size) { index ->
            code.getModule(index % code.size, index / code.size)
        }
        return QrMatrix(code.size, modules)
    }
}

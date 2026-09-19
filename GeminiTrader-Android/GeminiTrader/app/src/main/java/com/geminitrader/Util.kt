package com.geminitrader

import android.graphics.Bitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/** Log em memória + prévia da última captura, lidos pela tela principal. */
object AppLog {
    private val linhas = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Volatile var preview: Bitmap? = null
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile var rodando: Boolean = false

    @Synchronized
    fun add(msg: String) {
        linhas.addFirst("[" + fmt.format(Date()) + "] " + msg)
        while (linhas.size > 200) linhas.removeLast()
        onChange?.invoke()
    }

    @Synchronized
    fun texto(): String = linhas.joinToString("\n")

    fun setPreview(b: Bitmap) {
        preview = b
        onChange?.invoke()
    }
}

/** Converte '67,432.10', '67.432,10' ou '67432.1' em Double. Null se não der. */
fun paraFloat(txt: Any?): Double? {
    if (txt == null) return null
    if (txt is Number) return txt.toDouble()
    var s = txt.toString().replace(Regex("[^\\d,.\\-]"), "")
    if (s.isEmpty()) return null
    val temVirgula = s.contains(',')
    val temPonto = s.contains('.')
    if (temVirgula && temPonto) {
        s = if (s.lastIndexOf(',') > s.lastIndexOf('.')) {
            s.replace(".", "").replace(",", ".")   // 67.432,10
        } else {
            s.replace(",", "")                       // 67,432.10
        }
    } else if (temVirgula) {
        val depois = s.substringAfterLast(',')
        s = if (depois.length == 3) s.replace(",", "") else s.replace(",", ".")
    } else if (s.count { it == '.' } > 1) {
        s = s.replace(".", "")
    }
    return s.toDoubleOrNull()
}

/** Intervalo de confiança de 95% (Wilson) para a taxa de acerto. */
fun wilson(k: Int, n: Int, z: Double = 1.96): Pair<Double, Double> {
    if (n == 0) return Pair(0.0, 0.0)
    val p = k.toDouble() / n
    val den = 1 + z * z / n
    val centro = (p + z * z / (2 * n)) / den
    val marg = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / den
    return Pair(centro - marg, centro + marg)
}

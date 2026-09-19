package com.geminitrader

import android.content.Context

data class Config(
    val apiKey: String,
    val modelo: String,
    val periodoMin: Int,
    val confMin: Int,
    val payout: Double,
    val cropEsq: Int,
    val cropTopo: Int,
    val cropDir: Int,
    val cropBase: Int,
    val atrasoSeg: Int = 3,
    val atrasoMaxAlerta: Int = 60,
    val maxVariacao: Double = 0.05
) {
    companion object {
        const val PREFS = "config"

        fun carregar(ctx: Context): Config {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            fun inteiro(k: String, padrao: Int, min: Int, max: Int): Int =
                (p.getString(k, null)?.trim()?.toIntOrNull() ?: padrao).coerceIn(min, max)

            var esq = inteiro("cropEsq", 0, 0, 80)
            var dir = inteiro("cropDir", 0, 0, 80)
            if (esq + dir > 80) { esq = 0; dir = 0 }
            var topo = inteiro("cropTopo", 8, 0, 80)
            var base = inteiro("cropBase", 35, 0, 80)
            if (topo + base > 80) { topo = 8; base = 35 }

            val payout = (p.getString("payout", null)?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 0.85)
                .coerceIn(0.1, 2.0)
            val modelo = p.getString("modelo", null)?.trim().orEmpty().ifEmpty { "gemini-3.6-flash" }

            return Config(
                apiKey = p.getString("apiKey", "").orEmpty().trim(),
                modelo = modelo,
                periodoMin = inteiro("periodoMin", 5, 1, 60),
                confMin = inteiro("confMin", 70, 0, 100),
                payout = payout,
                cropEsq = esq, cropTopo = topo, cropDir = dir, cropBase = base
            )
        }
    }
}

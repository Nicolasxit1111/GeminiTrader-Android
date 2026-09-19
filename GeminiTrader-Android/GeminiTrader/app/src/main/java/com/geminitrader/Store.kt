package com.geminitrader

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.util.Locale
import kotlin.math.abs

class Pendente(
    val ts: Long,
    val hora: String,
    val dia: Int,
    val sinal: String,
    val conf: Int,
    val preco: Double
)

object Store {
    private class Linha(val dia: Int, val conf: Int, val res: String)

    private fun celula(v: Any?): String {
        val s = v?.toString() ?: ""
        val precisaAspas = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (precisaAspas) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun anexar(ctx: Context, nome: String, cabecalho: String, linha: List<Any?>) {
        val f = File(ctx.filesDir, nome)
        val novo = !f.exists()
        FileWriter(f, true).use { w ->
            if (novo) w.write(cabecalho + "\n")
            w.write(linha.joinToString(",") { celula(it) } + "\n")
        }
    }

    /** Compara o preço da entrada com o de uma vela depois e grava em resultados.csv. */
    fun resolver(ctx: Context, cfg: Config, p: Pendente, saida: Double): String {
        val v = (saida - p.preco) / p.preco
        val res = when {
            abs(v) > cfg.maxVariacao -> "INVALIDO"
            saida == p.preco -> "EMPATE"
            (p.sinal == "CIMA") == (saida > p.preco) -> "ACERTO"
            else -> "ERRO"
        }
        anexar(
            ctx, "resultados.csv",
            "hora_sinal,dia_semana,sinal,confianca,preco_entrada,preco_saida,resultado",
            listOf(p.hora, p.dia, p.sinal, p.conf, p.preco, saida, res)
        )
        return res
    }

    private fun pct(x: Double): String = String.format(Locale.US, "%.1f%%", x * 100)

    fun placar(ctx: Context, cfg: Config): String {
        val f = File(ctx.filesDir, "resultados.csv")
        if (!f.exists()) return "Ainda não há resultados medidos. Deixe o monitoramento rodando por algumas velas."
        val linhas = f.readLines().drop(1).mapNotNull { l ->
            val c = l.split(",")
            if (c.size < 7) null else Linha(c[1].toIntOrNull() ?: 0, c[3].toIntOrNull() ?: 0, c[6].trim())
        }
        val equilibrio = 1.0 / (1.0 + cfg.payout)
        val sb = StringBuilder()
        sb.append("Payout ").append(pct(cfg.payout)).append(" -> equilíbrio: ").append(pct(equilibrio)).append(" de acerto\n\n")

        fun bloco(titulo: String, sel: List<Linha>, veredito: Boolean) {
            val a = sel.count { it.res == "ACERTO" }
            val e = sel.count { it.res == "ERRO" }
            val emp = sel.count { it.res == "EMPATE" }
            val n = a + e
            if (n == 0) {
                sb.append(titulo).append(": sem dados\n\n")
                return
            }
            val taxa = a.toDouble() / n
            val ic = wilson(a, n)
            val lucro = a * cfg.payout - e
            sb.append(titulo).append(": ").append(n).append(" operações | ")
                .append(a).append(" acertos, ").append(e).append(" erros, ").append(emp).append(" empates\n")
            sb.append("  acerto ").append(pct(taxa)).append(" (intervalo 95%: ")
                .append(pct(ic.first)).append(" a ").append(pct(ic.second)).append(")\n")
            sb.append("  resultado simulado: ").append(String.format(Locale.US, "%+.2f", lucro)).append(" unidades de aposta\n")
            if (veredito) {
                if (n < 100) sb.append("  -> amostra pequena demais para concluir qualquer coisa.\n")
                else if (ic.first > equilibrio) sb.append("  -> há indício de vantagem. Confirme em outros dias antes de confiar.\n")
                else sb.append("  -> sem evidência de vantagem sobre o ponto de equilíbrio.\n")
            }
            sb.append("\n")
        }

        bloco("Todos os sinais", linhas, true)
        bloco("Confiança >= " + cfg.confMin, linhas.filter { it.conf >= cfg.confMin }, true)
        bloco("Dias úteis", linhas.filter { it.dia < 5 }, false)
        bloco("Fim de semana", linhas.filter { it.dia >= 5 }, false)
        return sb.toString()
    }
}

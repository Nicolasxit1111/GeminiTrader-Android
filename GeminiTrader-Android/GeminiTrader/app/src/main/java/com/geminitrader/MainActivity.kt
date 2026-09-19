package com.geminitrader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {

    private lateinit var etChave: EditText
    private lateinit var etModelo: EditText
    private lateinit var etPeriodo: EditText
    private lateinit var etConf: EditText
    private lateinit var etPayout: EditText
    private lateinit var etEsq: EditText
    private lateinit var etTopo: EditText
    private lateinit var etDir: EditText
    private lateinit var etBase: EditText
    private lateinit var tvEstado: TextView
    private lateinit var tvLog: TextView
    private lateinit var ivPrev: ImageView

    private val reqCaptura = 100

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Config.carregar(this)

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(dp(16), dp(16), dp(16), dp(24))

        val titulo = TextView(this)
        titulo.text = "Gemini Trader"
        titulo.textSize = 22f
        titulo.setTypeface(titulo.typeface, Typeface.BOLD)
        raiz.addView(titulo)

        val aviso = TextView(this)
        aviso.text = "Só avisa. Nunca envia ordens. Use em conta demo."
        aviso.textSize = 13f
        raiz.addView(aviso)

        etChave = campo(raiz, "Chave da API Gemini", cfg.apiKey,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        etModelo = campo(raiz, "Modelo", cfg.modelo, InputType.TYPE_CLASS_TEXT)
        etPeriodo = campo(raiz, "Vela e expiração (minutos)", cfg.periodoMin.toString(), InputType.TYPE_CLASS_NUMBER)
        etConf = campo(raiz, "Confiança mínima para alertar (0-100)", cfg.confMin.toString(), InputType.TYPE_CLASS_NUMBER)
        etPayout = campo(raiz, "Payout (0.85 = 85%)", cfg.payout.toString(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)

        val rotuloCrop = TextView(this)
        rotuloCrop.text = "Recorte do gráfico (% da tela a cortar)"
        rotuloCrop.textSize = 12f
        rotuloCrop.setPadding(0, dp(10), 0, 0)
        raiz.addView(rotuloCrop)

        val linhaCrop = LinearLayout(this)
        linhaCrop.orientation = LinearLayout.HORIZONTAL
        etEsq = colunaPeq(linhaCrop, "Esquerda", cfg.cropEsq.toString())
        etTopo = colunaPeq(linhaCrop, "Topo", cfg.cropTopo.toString())
        etDir = colunaPeq(linhaCrop, "Direita", cfg.cropDir.toString())
        etBase = colunaPeq(linhaCrop, "Base", cfg.cropBase.toString())
        raiz.addView(linhaCrop)

        val linha1 = LinearLayout(this)
        linha1.orientation = LinearLayout.HORIZONTAL
        linha1.addView(botao("Iniciar") { iniciar() })
        linha1.addView(botao("Parar") { parar() })
        raiz.addView(linha1)

        val linha2 = LinearLayout(this)
        linha2.orientation = LinearLayout.HORIZONTAL
        linha2.addView(botao("Placar") { mostrarPlacar() })
        linha2.addView(botao("Compartilhar CSVs") { compartilhar() })
        raiz.addView(linha2)

        tvEstado = TextView(this)
        tvEstado.textSize = 14f
        tvEstado.setTypeface(tvEstado.typeface, Typeface.BOLD)
        tvEstado.setPadding(0, dp(8), 0, dp(4))
        raiz.addView(tvEstado)

        ivPrev = ImageView(this)
        ivPrev.adjustViewBounds = true
        ivPrev.maxHeight = dp(320)
        ivPrev.scaleType = ImageView.ScaleType.FIT_CENTER
        raiz.addView(ivPrev, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        tvLog = TextView(this)
        tvLog.typeface = Typeface.MONOSPACE
        tvLog.textSize = 11f
        tvLog.setTextIsSelectable(true)
        tvLog.setPadding(0, dp(8), 0, 0)
        raiz.addView(tvLog)

        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.onChange = { runOnUiThread { atualizar() } }
        atualizar()
    }

    override fun onPause() {
        AppLog.onChange = null
        super.onPause()
    }

    private fun atualizar() {
        tvEstado.text = if (AppLog.rodando) "Status: ATIVO" else "Status: parado"
        tvLog.text = AppLog.texto()
        val b = AppLog.preview
        if (b != null) ivPrev.setImageBitmap(b)
    }

    // ---------- ações ----------

    private fun salvar() {
        getSharedPreferences(Config.PREFS, MODE_PRIVATE).edit()
            .putString("apiKey", etChave.text.toString().trim())
            .putString("modelo", etModelo.text.toString().trim())
            .putString("periodoMin", etPeriodo.text.toString().trim())
            .putString("confMin", etConf.text.toString().trim())
            .putString("payout", etPayout.text.toString().trim())
            .putString("cropEsq", etEsq.text.toString().trim())
            .putString("cropTopo", etTopo.text.toString().trim())
            .putString("cropDir", etDir.text.toString().trim())
            .putString("cropBase", etBase.text.toString().trim())
            .apply()
    }

    private fun iniciar() {
        salvar()
        if (Config.carregar(this).apiKey.isBlank()) {
            Toast.makeText(this, "Informe a chave da API.", Toast.LENGTH_LONG).show()
            return
        }
        if (AppLog.rodando) {
            Toast.makeText(this, "Já está rodando. Toque em Parar antes de reiniciar.", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCaptura)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCaptura) return
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
            i.putExtra(CaptureService.EXTRA_CODE, resultCode)
            i.putExtra(CaptureService.EXTRA_DATA, data)
            startForegroundService(i)
            AppLog.add("Iniciando... Vá para o app da corretora e deixe o gráfico à vista.")
        } else {
            AppLog.add("Permissão de captura de tela negada.")
        }
    }

    private fun parar() {
        val i = Intent(this, CaptureService::class.java)
        i.action = CaptureService.ACTION_STOP
        startService(i)
    }

    private fun mostrarPlacar() {
        AlertDialog.Builder(this)
            .setTitle("Placar")
            .setMessage(Store.placar(this, Config.carregar(this)))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun compartilhar() {
        val uris = ArrayList<Uri>()
        for (nome in listOf("resultados.csv", "sinais.csv")) {
            val f = File(filesDir, nome)
            if (f.exists()) uris.add(FileProvider.getUriForFile(this, packageName + ".fileprovider", f))
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Ainda não há arquivos.", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_SEND_MULTIPLE)
        i.type = "text/csv"
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Compartilhar CSVs"))
    }

    // ---------- helpers de interface ----------

    private fun campo(pai: LinearLayout, rotulo: String, valor: String, tipo: Int): EditText {
        val tv = TextView(this)
        tv.text = rotulo
        tv.textSize = 12f
        tv.setPadding(0, dp(10), 0, 0)
        pai.addView(tv)
        val et = EditText(this)
        et.setText(valor)
        et.inputType = tipo
        et.setSingleLine(true)
        pai.addView(et)
        return et
    }

    private fun colunaPeq(pai: LinearLayout, rotulo: String, valor: String): EditText {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val tv = TextView(this)
        tv.text = rotulo
        tv.textSize = 11f
        col.addView(tv)
        val et = EditText(this)
        et.setText(valor)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setSingleLine(true)
        col.addView(et)
        pai.addView(col)
        return et
    }

    private fun botao(texto: String, acao: () -> Unit): Button {
        val b = Button(this)
        b.text = texto
        b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        b.setOnClickListener { acao() }
        return b
    }
}

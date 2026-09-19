package com.geminitrader

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaptureService : Service() {

    companion object {
        const val ACTION_STOP = "com.geminitrader.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val CANAL_SERVICO = "servico"
        const val CANAL_SINAIS = "sinais"
        const val ID_SERVICO = 1
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var imgThread: HandlerThread? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var running = false
    @Volatile private var parando = false
    @Volatile private var querFrame = false
    @Volatile private var frameBmp: Bitmap? = null
    @Volatile private var latch: CountDownLatch? = null

    private var capW = 0
    private var capH = 0
    private lateinit var cfg: Config
    private var pendente: Pendente? = null
    private var ultimoSinal = "AGUARDAR"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLog.add("Monitoramento parado.")
            pararTudo()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        parando = false

        criarCanais()
        val notif = notificacaoServico()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_SERVICO, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID_SERVICO, notif)
        }

        val resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = lerData(intent)
        if (resultCode != Activity.RESULT_OK || data == null) {
            AppLog.add("Permissão de captura de tela negada.")
            pararTudo()
            return START_NOT_STICKY
        }

        cfg = Config.carregar(this)
        if (cfg.apiKey.isBlank()) {
            AppLog.add("Chave da API vazia.")
            pararTudo()
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
            if (proj == null) {
                AppLog.add("Não foi possível iniciar a captura.")
                pararTudo()
                return START_NOT_STICKY
            }
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    AppLog.add("Captura encerrada pelo sistema.")
                    pararTudo()
                }
            }, Handler(Looper.getMainLooper()))
            prepararCaptura(proj)
        } catch (e: Exception) {
            AppLog.add("Erro ao preparar a captura: " + e.message)
            pararTudo()
            return START_NOT_STICKY
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "geminitrader:loop")
        wakeLock?.acquire(8 * 60 * 60 * 1000L)

        running = true
        AppLog.rodando = true
        worker = Thread { loop() }
        worker?.start()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun lerData(intent: Intent?): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        }
    }

    // ---------- captura ----------

    @Suppress("DEPRECATION")
    private fun tamanhoTela(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun prepararCaptura(proj: MediaProjection) {
        val tam = tamanhoTela()
        capW = tam.first
        capH = tam.second
        val dpi = resources.displayMetrics.densityDpi

        val th = HandlerThread("frames")
        th.start()
        imgThread = th
        val handler = Handler(th.looper)

        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ leitor -> onFrame(leitor) }, handler)
        reader = r

        virtualDisplay = proj.createVirtualDisplay(
            "gemini-trader", capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
    }

    /** Esvazia a fila de quadros o tempo todo (para nunca ficar com um quadro velho)
     *  e só converte em bitmap quando o ciclo pede. */
    private fun onFrame(leitor: ImageReader) {
        val img: Image? = try {
            leitor.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img == null) return
        try {
            if (querFrame) {
                frameBmp = paraBitmap(img)
                querFrame = false
                latch?.countDown()
            }
        } catch (e: Exception) {
            AppLog.add("Erro ao converter quadro: " + e.message)
        } finally {
            img.close()
        }
    }

    private fun paraBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val padding = rowStride - pixelStride * img.width
        val largura = img.width + padding / pixelStride
        val bruto = Bitmap.createBitmap(largura, img.height, Bitmap.Config.ARGB_8888)
        bruto.copyPixelsFromBuffer(plane.buffer)
        return if (padding == 0) bruto else Bitmap.createBitmap(bruto, 0, 0, img.width, img.height)
    }

    private fun capturar(): Bitmap? {
        val tam = tamanhoTela()
        if (tam.first != capW || tam.second != capH) {
            AppLog.add("A orientação/tamanho da tela mudou. Pare e inicie de novo.")
            return null
        }
        frameBmp = null
        val l = CountDownLatch(1)
        latch = l
        querFrame = true
        val ok = l.await(8, TimeUnit.SECONDS)
        querFrame = false
        val bruto = frameBmp
        if (!ok || bruto == null) {
            AppLog.add("Nenhum quadro novo da tela em 8 s (tela parada ou bloqueada?).")
            return null
        }
        return recortar(bruto)
    }

    private fun recortar(b: Bitmap): Bitmap {
        val x = b.width * cfg.cropEsq / 100
        val y = b.height * cfg.cropTopo / 100
        val cw = (b.width * (100 - cfg.cropEsq - cfg.cropDir) / 100).coerceAtLeast(50).coerceAtMost(b.width - x)
        val ch = (b.height * (100 - cfg.cropTopo - cfg.cropBase) / 100).coerceAtLeast(50).coerceAtMost(b.height - y)
        var out = Bitmap.createBitmap(b, x, y, cw, ch)
        if (out.width > 1280) {
            val nh = out.height * 1280 / out.width
            out = Bitmap.createScaledBitmap(out, 1280, nh, true)
        }
        return out
    }

    // ---------- laço principal ----------

    private fun dormir(ms: Long) {
        if (ms > 0) Thread.sleep(ms)
    }

    private fun loop() {
        try {
            val periodo = cfg.periodoMin * 60_000L
            AppLog.add("Captura de TESTE em 10 s. Abra o app da corretora no gráfico AGORA.")
            dormir(10_000)
            if (running) ciclo(true)
            while (running) {
                val agora = System.currentTimeMillis()
                val alvo = (agora / periodo + 1) * periodo + cfg.atrasoSeg * 1000L
                val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(alvo))
                AppLog.add("Aguardando fechamento da vela ($hora)...")
                dormir(alvo - agora)
                if (!running) break
                ciclo(false)
            }
        } catch (e: InterruptedException) {
            // parada solicitada
        }
    }

    private fun texto(j: JSONObject, chave: String): String =
        if (j.isNull(chave)) "" else j.optString(chave, "")

    private fun ciclo(teste: Boolean) {
        try {
            val t0 = System.currentTimeMillis()
            val bmp = capturar() ?: return
            AppLog.setPreview(bmp)

            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            val res = Gemini.analisar(cfg, bos.toByteArray(), ultimoSinal)
            val lat = (System.currentTimeMillis() - t0) / 1000

            val r = res.json
            var sinal = texto(r, "sinal").uppercase(Locale.ROOT).trim()
            if (sinal != "CIMA" && sinal != "BAIXO" && sinal != "AGUARDAR") sinal = "AGUARDAR"
            val conf = (paraFloat(if (r.isNull("confianca")) null else r.opt("confianca")) ?: 0.0).toInt()
            val preco = paraFloat(if (r.isNull("preco_atual")) null else r.opt("preco_atual"))
            val motivo = texto(r, "motivo")
            val leitura = texto(r, "leitura")

            if (teste) {
                AppLog.add(
                    "TESTE: $sinal ($conf%) preço $preco - $motivo | ${lat}s. " +
                        "Confira a prévia: deve mostrar só o gráfico, com a etiqueta de preço. " +
                        "O preço lido bate com a tela?"
                )
                return
            }

            val agora = Date()
            val horaIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(agora)
            val dia = LocalDate.now().dayOfWeek.value - 1
            val limite = cfg.periodoMin * 60_000L * 3 / 2

            // 1) mede o sinal da vela anterior com o preço de agora
            val p = pendente
            if (p != null && preco != null && System.currentTimeMillis() - p.ts <= limite) {
                val resultado = Store.resolver(this, cfg, p, preco)
                AppLog.add("Resultado do sinal anterior (" + p.sinal + "): " + resultado)
            }
            pendente = null

            // 2) guarda o sinal atual para medir na próxima vela
            val operavel = sinal == "CIMA" || sinal == "BAIXO"
            if (operavel && preco != null) {
                pendente = Pendente(System.currentTimeMillis(), horaIso, dia, sinal, conf, preco)
                salvarPrint(bmp, sinal)
            }

            // 3) alerta
            val forte = operavel && conf >= cfg.confMin
            val alertou = forte && lat <= cfg.atrasoMaxAlerta
            val extra = " | " + lat + "s" + (if (res.modelo != cfg.modelo) " | modelo " + res.modelo else "")
            AppLog.add("$sinal ($conf%) preço $preco - $motivo$extra")
            if (forte && !alertou) AppLog.add("(análise demorou demais, sinal velho: sem alerta)")

            Store.anexar(
                this, "sinais.csv", "hora,sinal,confianca,preco_atual,leitura,motivo,alertou",
                listOf(horaIso, sinal, conf, preco, leitura, motivo, if (alertou) "sim" else "nao")
            )
            ultimoSinal = sinal
            if (alertou) notificarSinal(sinal, conf, preco, motivo)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            AppLog.add("erro: " + e.message)
        }
    }

    private fun salvarPrint(bmp: Bitmap, sinal: String) {
        try {
            val dir = File(getExternalFilesDir(null) ?: filesDir, "prints")
            dir.mkdirs()
            val nome = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + sinal + ".jpg"
            FileOutputStream(File(dir, nome)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (e: Exception) {
            // auditoria é opcional
        }
    }

    // ---------- notificações ----------

    private fun criarCanais() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL_SERVICO, "Monitoramento", NotificationManager.IMPORTANCE_LOW)
        )
        val c = NotificationChannel(CANAL_SINAIS, "Sinais", NotificationManager.IMPORTANCE_HIGH)
        c.enableVibration(true)
        c.vibrationPattern = longArrayOf(0, 400, 200, 400)
        nm.createNotificationChannel(c)
    }

    private fun abrirApp(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    @Suppress("DEPRECATION")
    private fun notificacaoServico(): Notification {
        val parar = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CANAL_SERVICO)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Gemini Trader ativo")
            .setContentText("Analisando a cada fechamento de vela")
            .setContentIntent(abrirApp())
            .addAction(R.drawable.ic_stat, "Parar", parar)
            .setOngoing(true)
            .build()
    }

    private fun notificarSinal(sinal: String, conf: Int, preco: Double?, motivo: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val texto = "Bitcoin | preço $preco | $motivo\nExpiração: ${cfg.periodoMin} min. A vela acabou de abrir."
        val n = Notification.Builder(this, CANAL_SINAIS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Sinal: $sinal ($conf%)")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setContentIntent(abrirApp())
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt() + 10, n)
    }

    // ---------- encerramento ----------

    private fun pararTudo() {
        if (parando) return
        parando = true
        running = false
        AppLog.rodando = false
        worker?.interrupt()
        try { virtualDisplay?.release() } catch (e: Exception) { }
        virtualDisplay = null
        try { reader?.close() } catch (e: Exception) { }
        reader = null
        try { projection?.stop() } catch (e: Exception) { }
        projection = null
        try { imgThread?.quitSafely() } catch (e: Exception) { }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pararTudo()
        super.onDestroy()
    }
}

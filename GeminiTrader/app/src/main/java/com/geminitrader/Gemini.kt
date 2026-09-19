package com.geminitrader

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Gemini {
    private val RESERVA = listOf("gemini-3.5-flash", "gemini-3.5-flash-lite")
    private val semThinking = mutableSetOf<String>()

    class Resultado(val json: JSONObject, val modelo: String)

    /** Tenta o modelo principal (2x) e depois os de reserva; refaz sem thinking se o modelo recusar. */
    fun analisar(cfg: Config, jpeg: ByteArray, ultimoSinal: String): Resultado {
        val inicio = System.currentTimeMillis()
        val tentativas = listOf(Pair(cfg.modelo, 0L), Pair(cfg.modelo, 3000L)) + RESERVA.map { Pair(it, 0L) }
        var ultimoErro: Exception? = null
        for (t in tentativas) {
            val modelo = t.first
            val espera = t.second
            if (System.currentTimeMillis() - inicio > 90_000) break
            if (espera > 0) Thread.sleep(espera)
            val opcoes = if (modelo in semThinking) listOf(false) else listOf(true, false)
            for (comThinking in opcoes) {
                try {
                    val json = chamar(cfg, modelo, jpeg, ultimoSinal, comThinking)
                    return Resultado(json, modelo)
                } catch (e: Exception) {
                    ultimoErro = e
                    if (comThinking && (e.message ?: "").contains("thinking", ignoreCase = true)) {
                        semThinking.add(modelo)
                        continue
                    }
                    break
                }
            }
        }
        throw ultimoErro ?: Exception("falha desconhecida ao chamar o Gemini")
    }

    private fun chamar(cfg: Config, modelo: String, jpeg: ByteArray, ultimoSinal: String, comThinking: Boolean): JSONObject {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

        val gen = JSONObject()
        gen.put("temperature", 0.2)
        gen.put("responseMimeType", "application/json")
        if (comThinking) {
            gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        }

        val sistema = JSONObject().put(
            "parts", JSONArray().put(JSONObject().put("text", Prompt.system(cfg.periodoMin)))
        )
        val partes = JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64)))
            .put(JSONObject().put("text", "Analise o gráfico agora. Último sinal dado: $ultimoSinal. Responda só o JSON."))
        val conteudo = JSONObject().put("role", "user").put("parts", partes)

        val body = JSONObject()
        body.put("systemInstruction", sistema)
        body.put("contents", JSONArray().put(conteudo))
        body.put("generationConfig", gen)

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/" + modelo + ":generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", cfg.apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val texto = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw Exception("HTTP " + code + ": " + extrairErro(texto))
            }
            return extrairJson(texto)
        } finally {
            conn.disconnect()
        }
    }

    private fun extrairErro(texto: String): String {
        return try {
            JSONObject(texto).getJSONObject("error").getString("message")
        } catch (e: Exception) {
            texto.take(200)
        }
    }

    private fun extrairJson(resposta: String): JSONObject {
        val raiz = JSONObject(resposta)
        val partes = raiz.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw Exception("resposta sem conteúdo (possivelmente bloqueada)")
        val sb = StringBuilder()
        for (i in 0 until partes.length()) {
            val p = partes.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        val texto = sb.toString()
        val ini = texto.indexOf('{')
        val fim = texto.lastIndexOf('}')
        if (ini < 0 || fim < ini) throw Exception("resposta sem JSON")
        return JSONObject(texto.substring(ini, fim + 1))
    }
}

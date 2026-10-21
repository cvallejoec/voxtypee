package com.deibee.voxtypee

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sesión de transcripción en vivo por WebSocket (Realtime API, gpt-live-transcribe).
 * Los callbacks llegan en el hilo de OkHttp: quien los use debe pasarlos al hilo principal.
 */
class EnVivo(
    ajustes: Ajustes,
    private val alTexto: (String) -> Unit,
    private val alTerminar: (String?) -> Unit,
) {
    private val texto = StringBuilder()
    @Volatile private var terminado = false
    private val socket: WebSocket

    init {
        val base = ajustes.endpoint.trimEnd('/').removeSuffix("/v1")
            .replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val pedido = Request.Builder()
            .url("$base/v1/realtime?intent=transcription")
            .header("Authorization", "Bearer ${ajustes.clave}")
            .build()

        val transcripcion = JSONObject()
            .put("model", ajustes.modeloVivo)
            .put("delay", "low")
        if (ajustes.idioma.isNotBlank()) transcripcion.put("languages", JSONArray().put(ajustes.idioma))
        if (ajustes.vocabulario.isNotEmpty()) transcripcion.put("keywords", JSONArray(ajustes.vocabulario))
        val sesion = JSONObject().put("type", "session.update").put(
            "session", JSONObject().put("type", "transcription").put(
                "audio", JSONObject().put(
                    "input", JSONObject()
                        .put("format", JSONObject().put("type", "audio/pcm").put("rate", Grabadora.TASA))
                        .put("transcription", transcripcion)
                        .put("turn_detection", JSONObject.NULL)
                )
            )
        )

        // OkHttp encola lo que se mande antes de abrir: el audio no se pierde mientras conecta.
        socket = cliente.newWebSocket(pedido, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val evento = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (evento.optString("type")) {
                    "conversation.item.input_audio_transcription.delta" -> {
                        texto.append(evento.optString("delta"))
                        alTexto(texto.toString().trim())
                    }
                    "conversation.item.input_audio_transcription.completed" ->
                        terminar(evento.optString("transcript").trim().ifBlank { texto.toString().trim() })
                    "error" -> {
                        Log.w(TAG, "En vivo: ${evento.optJSONObject("error")?.optString("message")}")
                        terminar(null)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "En vivo falló (${response?.code}): $t")
                terminar(null)
            }
        })
        socket.send(sesion.toString())
    }

    fun enviar(pcm: ByteArray) {
        if (terminado) return
        val evento = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.encodeToString(pcm, Base64.NO_WRAP))
        socket.send(evento.toString())
    }

    /** Cierra el audio; [alTerminar] llega con el texto completo (o null si falló). */
    fun cerrar() {
        if (terminado) return
        socket.send(JSONObject().put("type", "input_audio_buffer.commit").toString())
    }

    /** Texto recibido hasta ahora. */
    val parcial: String get() = texto.toString().trim()

    fun cancelar() {
        terminado = true
        socket.cancel()
    }

    @Synchronized
    private fun terminar(final: String?) {
        if (terminado) return
        terminado = true
        socket.close(1000, null)
        alTerminar(final)
    }

    companion object {
        private val cliente = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}

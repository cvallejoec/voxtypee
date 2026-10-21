package com.deibee.voxtypee

import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ErrorTranscripcion(mensaje: String) : Exception(mensaje)

/** Manda el audio a /v1/audio/transcriptions (API de OpenAI o compatible). Bloqueante: llamar fuera del hilo principal. */
object Transcriptor {

    fun transcribir(audio: File, ajustes: Ajustes): String {
        val clave = ajustes.clave
        if (clave.isBlank()) throw ErrorTranscripcion("Falta la API key: ábrela en VoxTypee → Proveedor.")

        val base = ajustes.endpoint.trimEnd('/')
        val url = if (base.endsWith("/v1")) "$base/audio/transcriptions" else "$base/v1/audio/transcriptions"
        val limite = "voxtypee-" + UUID.randomUUID()

        val conexion = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer $clave")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$limite")
        }

        try {
            DataOutputStream(conexion.outputStream).use { salida ->
                fun campo(nombre: String, valor: String) {
                    salida.writeBytes("--$limite\r\nContent-Disposition: form-data; name=\"$nombre\"\r\n\r\n")
                    salida.write(valor.toByteArray())
                    salida.writeBytes("\r\n")
                }
                campo("model", ajustes.modelo)
                campo("response_format", "json")
                if (ajustes.idioma.isNotBlank()) campo("language", ajustes.idioma)
                if (ajustes.prompt.isNotBlank()) campo("prompt", ajustes.prompt)
                salida.writeBytes(
                    "--$limite\r\nContent-Disposition: form-data; name=\"file\"; filename=\"dictado.m4a\"\r\n" +
                        "Content-Type: audio/mp4\r\n\r\n"
                )
                audio.inputStream().use { it.copyTo(salida) }
                salida.writeBytes("\r\n--$limite--\r\n")
            }

            val codigo = conexion.responseCode
            val cuerpo = (if (codigo in 200..299) conexion.inputStream else conexion.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (codigo !in 200..299) {
                val mensaje = runCatching { JSONObject(cuerpo).getJSONObject("error").getString("message") }
                    .getOrDefault(cuerpo.take(200))
                throw ErrorTranscripcion(
                    when (codigo) {
                        401 -> "La API key no es válida (401)."
                        429 -> "Límite o saldo agotado en el proveedor (429): $mensaje"
                        else -> "Error $codigo del proveedor: $mensaje"
                    }
                )
            }
            return JSONObject(cuerpo).optString("text").trim()
        } finally {
            conexion.disconnect()
        }
    }
}

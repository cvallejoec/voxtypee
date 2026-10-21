package com.deibee.voxtypee

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Graba el micrófono a m4a (AAC mono 16 kHz): liviano para subir y aceptado por la API. */
class Grabadora(private val context: Context) {

    private var recorder: MediaRecorder? = null
    val archivo: File = File(context.cacheDir, "dictado.m4a")
    var inicio = 0L
        private set

    /** Mayor amplitud vista en esta grabación; 0 al final = Android entregó silencio. */
    var amplitudMaxima = 0
        private set

    val grabando: Boolean get() = recorder != null

    fun iniciar() {
        archivo.delete()
        amplitudMaxima = 0
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16_000)
        r.setAudioEncodingBitRate(32_000)
        r.setOutputFile(archivo.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        inicio = System.currentTimeMillis()
    }

    /** Amplitud desde la última llamada, de 0 a 1. */
    fun nivel(): Float {
        val a = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        if (a > amplitudMaxima) amplitudMaxima = a
        return (a / 12_000f).coerceIn(0f, 1f)
    }

    /** Devuelve los milisegundos grabados; el archivo queda listo solo si fue > 0. */
    fun detener(): Long {
        val r = recorder ?: return 0
        recorder = null
        nivel()
        val duracion = System.currentTimeMillis() - inicio
        return try {
            r.stop()
            duracion
        } catch (e: RuntimeException) {
            // stop() falla si no alcanzó a grabar nada
            archivo.delete()
            0
        } finally {
            r.release()
        }
    }
}

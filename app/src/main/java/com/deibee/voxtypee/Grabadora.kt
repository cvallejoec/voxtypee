package com.deibee.voxtypee

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Graba PCM 16 bits mono a 24 kHz (lo que pide la API en vivo). Cada pedazo va a [alAudio]
 * para el streaming y, a la vez, a un WAV para la pasada final.
 */
class Grabadora(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private var record: AudioRecord? = null
    private var hilo: Thread? = null
    @Volatile private var activa = false
    private var usaComunicacion = false
    private var modoAnterior: Int? = null
    @Volatile private var enBluetooth = false

    val archivo: File = File(context.cacheDir, "dictado.wav")
    var inicio = 0L
        private set

    /** Mayor amplitud vista en esta grabación; 0 al final = Android entregó silencio. */
    @Volatile var amplitudMaxima = 0
        private set
    @Volatile private var nivelActual = 0

    /** Micrófono que se está usando, para mostrarlo. */
    var dispositivo: String = ""
        private set

    val grabando: Boolean get() = activa

    @SuppressLint("MissingPermission")
    private fun crearRecord(fuente: Int): AudioRecord {
        val minimo = AudioRecord.getMinBufferSize(TASA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(fuente, TASA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, PEDAZO * 4))
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("AudioRecord no se inicializó")
        }
        return r
    }

    /** Abre el micrófono: los audífonos si se piden y hay, si no el del teléfono. */
    private fun abrir(conAudifonos: Boolean): AudioRecord {
        val elegido = if (conAudifonos) audifonos() else null
        val bluetooth = elegido?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || elegido?.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        if (bluetooth) {
            // El micrófono Bluetooth solo existe en modo llamada (SCO / LE Audio); Samsung pide además el modo.
            modoAnterior = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            usaComunicacion = audio.setCommunicationDevice(elegido!!)
        }
        val r = crearRecord(if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC)
        if (elegido != null) r.setPreferredDevice(elegido)
        dispositivo = elegido?.productName?.toString()?.takeIf { it.isNotBlank() } ?: if (elegido != null) "Audífonos" else "Micrófono del teléfono"
        enBluetooth = bluetooth
        Log.i(TAG, "Micrófono: $dispositivo (tipo ${elegido?.type ?: "integrado"})")
        return r
    }

    private fun soltarComunicacion() {
        if (usaComunicacion) {
            audio.clearCommunicationDevice()
            usaComunicacion = false
        }
        modoAnterior?.let { audio.mode = it }
        modoAnterior = null
    }

    fun iniciar(soloTelefono: Boolean, alAudio: (ByteArray) -> Unit) {
        archivo.delete()
        amplitudMaxima = 0
        nivelActual = 0

        var r = abrir(conAudifonos = !soloTelefono)
        RandomAccessFile(archivo, "rw").use { it.setLength(0); it.write(cabeceraWav(0)) }
        record = r
        r.startRecording()
        activa = true
        inicio = System.currentTimeMillis()

        hilo = thread(name = "grabadora") {
            val buffer = ByteArray(PEDAZO)
            RandomAccessFile(archivo, "rw").use { wav ->
                wav.seek(44)
                var total = 0
                while (activa) {
                    // Audífonos conectados pero mudos (no abrió el SCO, están guardados…): pasar al teléfono.
                    if (enBluetooth && amplitudMaxima == 0 && System.currentTimeMillis() - inicio > ESPERA_BLUETOOTH_MS) {
                        Log.w(TAG, "$dispositivo no entrega audio: cambio al micrófono del teléfono")
                        runCatching { r.stop() }
                        r.release()
                        soltarComunicacion()
                        r = runCatching { abrir(conAudifonos = false) }.getOrNull() ?: break
                        record = r
                        r.startRecording()
                    }
                    val leidos = r.read(buffer, 0, buffer.size)
                    if (leidos <= 0) continue
                    val pedazo = buffer.copyOf(leidos)
                    medir(pedazo)
                    wav.write(pedazo)
                    total += leidos
                    runCatching { alAudio(pedazo) }
                }
                wav.seek(0)
                wav.write(cabeceraWav(total))
            }
        }
    }

    /** Nivel de voz desde la última llamada, de 0 a 1. */
    fun nivel(): Float {
        val n = nivelActual
        nivelActual = 0
        return (n / 12_000f).coerceIn(0f, 1f)
    }

    /** Devuelve los milisegundos grabados; el WAV queda cerrado y listo. */
    fun detener(): Long {
        if (record == null) return 0
        activa = false
        hilo?.join(1500)
        hilo = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        soltarComunicacion()
        return System.currentTimeMillis() - inicio
    }

    /** Audífonos conectados, del más al menos preferido; null si no hay. */
    fun audifonos(): AudioDeviceInfo? {
        val orden = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
        val entradas = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return orden.firstNotNullOfOrNull { tipo -> entradas.firstOrNull { it.type == tipo } }
    }

    private fun medir(pcm: ByteArray) {
        var pico = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val muestra = abs(((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt())
            if (muestra > pico) pico = muestra
            i += 2
        }
        if (pico > nivelActual) nivelActual = pico
        if (pico > amplitudMaxima) amplitudMaxima = pico
    }

    private fun cabeceraWav(datos: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + datos).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        b.putInt(TASA).putInt(TASA * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(datos)
        return b.array()
    }

    companion object {
        const val TASA = 24_000
        /** 100 ms de audio. */
        private const val PEDAZO = TASA * 2 / 10
        private const val ESPERA_BLUETOOTH_MS = 1500L
    }
}

package com.deibee.voxtypee

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Servicio en primer plano de tipo micrófono mientras dura un dictado. Android corta el
 * micrófono a las apps en segundo plano; con este servicio el proceso puede grabar.
 * La grabación en sí la hace BurbujaService (mismo proceso).
 */
class GrabacionService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CANAL, "Dictado en curso", NotificationManager.IMPORTANCE_LOW)
        )
        val aviso = Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("VoxTypee está escuchando")
            .setOngoing(true)
            .build()
        val ok = try {
            startForeground(1, aviso, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            true
        } catch (e: Exception) {
            Log.w(TAG, "No pude pasar a primer plano: $e")
            false
        }
        alIniciar?.invoke(ok)
        alIniciar = null
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CANAL = "dictado"
        /** Lo llama el servicio cuando ya está en primer plano (true) o no pudo (false). */
        var alIniciar: ((Boolean) -> Unit)? = null
    }
}

const val TAG = "VoxTypee"

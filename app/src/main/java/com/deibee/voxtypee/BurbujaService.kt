package com.deibee.voxtypee

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Servicio de accesibilidad: muestra la burbuja cuando hay un teclado abierto (cualquiera),
 * graba al tocarla, transcribe y escribe el texto en el campo con el cursor.
 */
class BurbujaService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var ajustes: Ajustes
    private lateinit var wm: WindowManager
    private lateinit var grabadora: Grabadora
    private val principal = Handler(Looper.getMainLooper())

    private var boton: BotonBurbuja? = null
    private var params: WindowManager.LayoutParams? = null
    private var tecladoArriba = -1

    private var estado = Estado.REPOSO
    private var destino: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        ajustes = Ajustes(this)
        wm = getSystemService(WindowManager::class.java)
        grabadora = Grabadora(this)
        ajustes.prefs.registerOnSharedPreferenceChangeListener(this)
        revisarProgramado()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = revisarProgramado()

    override fun onInterrupt() {}

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, clave: String?) {
        if (clave == Ajustes.K_ACTIVO) revisarProgramado()
    }

    override fun onDestroy() {
        cancelarGrabacion()
        ocultar()
        ajustes.prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    // ---------- ¿Hay teclado? ----------

    private val revisar = Runnable { revisarTeclado() }

    private fun revisarProgramado() {
        principal.removeCallbacks(revisar)
        principal.postDelayed(revisar, 120)
    }

    private fun revisarTeclado() {
        val teclado = runCatching { windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            .getOrNull()
        // Mientras graba o transcribe la burbuja se queda, aunque el teclado se cierre.
        if (estado != Estado.REPOSO) return
        if (teclado == null || !ajustes.activo || campoConFoco()?.isPassword == true) {
            ocultar()
            return
        }
        val zona = Rect().also { teclado.getBoundsInScreen(it) }
        mostrar(zona.top)
    }

    private fun campoConFoco(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        return runCatching {
            windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
                .firstOrNull()
        }.getOrNull()
    }

    // ---------- La burbuja ----------

    private fun mostrar(arribaDelTeclado: Int) {
        val b = boton ?: crearBoton()
        val p = params!!
        val ancho = wm.currentWindowMetrics.bounds.width()
        val margen = ajustes.margenDerecho.takeIf { it >= 0 } ?: (8 * resources.displayMetrics.density).toInt()
        val altura = ajustes.alturaSobreTeclado.takeIf { it >= 0 } ?: 0
        p.x = (ancho - b.lado - margen).coerceIn(0, ancho - b.lado)
        p.y = (arribaDelTeclado - b.lado - altura).coerceAtLeast(0)
        tecladoArriba = arribaDelTeclado
        if (b.parent == null) wm.addView(b, p) else wm.updateViewLayout(b, p)
    }

    private fun ocultar() {
        boton?.takeIf { it.parent != null }?.let { runCatching { wm.removeView(it) } }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun crearBoton(): BotonBurbuja {
        val b = BotonBurbuja(this)
        params = WindowManager.LayoutParams(
            b.lado, b.lado,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val umbral = ViewConfiguration.get(this).scaledTouchSlop
        var x0 = 0f; var y0 = 0f; var px0 = 0; var py0 = 0
        var arrastrando = false

        b.setOnTouchListener { _, e ->
            val p = params!!
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX; y0 = e.rawY; px0 = p.x; py0 = p.y
                    arrastrando = false
                    b.isPressed = true
                    if (ajustes.mantenerParaHablar && estado == Estado.REPOSO) iniciar()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (ajustes.mantenerParaHablar) {
                        b.cancelando = estado == Estado.GRABANDO && hypot(dx, dy) > b.diametro * 1.5f
                    } else {
                        if (!arrastrando && (abs(dx) > umbral || abs(dy) > umbral)) arrastrando = true
                        if (arrastrando) {
                            p.x = px0 + dx.toInt(); p.y = py0 + dy.toInt()
                            wm.updateViewLayout(b, p)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    b.isPressed = false
                    if (ajustes.mantenerParaHablar) {
                        if (b.cancelando || e.actionMasked == MotionEvent.ACTION_CANCEL) cancelarGrabacion()
                        else if (estado == Estado.GRABANDO) detener()
                        b.cancelando = false
                    } else if (arrastrando) {
                        guardarPosicion(b, p)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        when (estado) {
                            Estado.REPOSO -> iniciar()
                            Estado.GRABANDO -> detener()
                            Estado.TRANSCRIBIENDO -> {}
                        }
                    }
                }
            }
            true
        }
        boton = b
        return b
    }

    private fun guardarPosicion(b: BotonBurbuja, p: WindowManager.LayoutParams) {
        val ancho = wm.currentWindowMetrics.bounds.width()
        ajustes.margenDerecho = (ancho - b.lado - p.x).coerceAtLeast(0)
        if (tecladoArriba > 0) ajustes.alturaSobreTeclado = (tecladoArriba - b.lado - p.y).coerceAtLeast(0)
    }

    private fun cambiarEstado(nuevo: Estado) {
        estado = nuevo
        boton?.estado = nuevo
        if (nuevo == Estado.REPOSO) revisarProgramado()
    }

    // ---------- Dictado ----------

    private fun iniciar() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            aviso("VoxTypee necesita permiso de micrófono")
            abrirAjustes()
            return
        }
        if (!ajustes.tieneClave) {
            aviso("Falta la API key en VoxTypee")
            abrirAjustes()
            return
        }
        destino = campoConFoco()
        cambiarEstado(Estado.GRABANDO)
        boton?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        var arrancado = false
        val arrancar = arrancar@{ enPrimerPlano: Boolean ->
            if (arrancado || estado != Estado.GRABANDO) return@arrancar
            arrancado = true
            Log.i(TAG, "Grabando (servicio en primer plano: $enPrimerPlano)")
            try {
                grabadora.iniciar()
                principal.post(medirNivel)
            } catch (e: Exception) {
                Log.e(TAG, "No pude grabar", e)
                aviso("No pude abrir el micrófono")
                detenerServicio()
                cambiarEstado(Estado.REPOSO)
            }
        }
        try {
            GrabacionService.alIniciar = { ok -> principal.post { arrancar(ok) } }
            startForegroundService(Intent(this, GrabacionService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Sin servicio en primer plano: $e")
            GrabacionService.alIniciar = null
            arrancar(false)
        }
        principal.postDelayed({ arrancar(false) }, 1500)
    }

    private val medirNivel = object : Runnable {
        override fun run() {
            if (!grabadora.grabando) return
            boton?.nivel = grabadora.nivel()
            if (System.currentTimeMillis() - grabadora.inicio > MAXIMO_MS) {
                detener()
                return
            }
            principal.postDelayed(this, 80)
        }
    }

    private fun detener() {
        principal.removeCallbacks(medirNivel)
        val duracion = grabadora.detener()
        val silencio = grabadora.amplitudMaxima == 0
        detenerServicio()
        boton?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        if (duracion < MINIMO_MS) {
            if (ajustes.mantenerParaHablar) aviso("Mantén apretado mientras hablas")
            cambiarEstado(Estado.REPOSO)
            return
        }
        if (silencio) {
            Log.w(TAG, "La grabación salió en silencio total")
            aviso("El micrófono no entregó audio")
            cambiarEstado(Estado.REPOSO)
            return
        }
        cambiarEstado(Estado.TRANSCRIBIENDO)
        thread(name = "transcribir") {
            val resultado = runCatching { Transcriptor.transcribir(grabadora.archivo, ajustes) }
            grabadora.archivo.delete()
            principal.post {
                resultado
                    .onSuccess { texto -> if (texto.isNotBlank()) insertar(texto) else aviso("No se entendió nada") }
                    .onFailure { e ->
                        Log.e(TAG, "Transcripción falló", e)
                        aviso(e.message ?: "No se pudo transcribir")
                    }
                cambiarEstado(Estado.REPOSO)
            }
        }
    }

    private fun cancelarGrabacion() {
        principal.removeCallbacks(medirNivel)
        if (::grabadora.isInitialized) {
            grabadora.detener()
            grabadora.archivo.delete()
        }
        detenerServicio()
        if (estado == Estado.GRABANDO) cambiarEstado(Estado.REPOSO)
    }

    private fun detenerServicio() {
        GrabacionService.alIniciar = null
        stopService(Intent(this, GrabacionService::class.java))
    }

    // ---------- Escribir el texto ----------

    private fun insertar(dictado: String) {
        val campo = destino?.takeIf { it.refresh() } ?: campoConFoco()
        destino = null
        if (campo == null) {
            copiar(dictado)
            aviso("Copiado: no encontré dónde escribir")
            return
        }

        val actual = if (campo.isShowingHintText) "" else campo.text?.toString().orEmpty()
        var ini = campo.textSelectionStart
        var fin = campo.textSelectionEnd
        if (ini < 0 || fin < 0 || ini > actual.length || fin > actual.length) {
            ini = actual.length; fin = actual.length
        }
        if (ini > fin) ini = fin.also { fin = ini }

        val antes = actual.substring(0, ini)
        var texto = dictado
        if (antes.isNotEmpty() && !antes.last().isWhitespace() && texto.first() !in ".,;:!?)") texto = " $texto"
        val nuevo = antes + texto + actual.substring(fin)

        val escrito = campo.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, nuevo) }
        )
        if (escrito) {
            val cursor = ini + texto.length
            campo.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                }
            )
            return
        }
        // Algunas apps no aceptan reemplazar el texto: probar pegando.
        copiar(texto)
        if (!campo.performAction(AccessibilityNodeInfo.ACTION_PASTE)) aviso("Copiado: pégalo con el teclado")
    }

    private fun copiar(texto: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VoxTypee", texto))
    }

    private fun aviso(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_SHORT).show()

    private fun abrirAjustes() {
        startActivity(Intent(this, AjustesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val MINIMO_MS = 400L
        private const val MAXIMO_MS = 5 * 60_000L
    }
}

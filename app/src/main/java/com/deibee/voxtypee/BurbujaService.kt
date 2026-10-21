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
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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
import android.widget.TextView
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
        val x = (ancho - b.lado - margen).coerceIn(0, ancho - b.lado)
        val y = (arribaDelTeclado - b.lado - altura).coerceAtLeast(0)
        tecladoArriba = arribaDelTeclado
        // Mover la burbuja genera otro evento de ventanas: solo tocarla si cambió algo.
        if (b.parent != null && p.x == x && p.y == y) return
        p.x = x
        p.y = y
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

    /** Cada dictado tiene su número: los callbacks de uno viejo se ignoran. */
    private var turno = 0
    private var escritor: Escritor? = null
    private var enVivo: EnVivo? = null
    private var vivoFinal: String? = null
    private var vivoTerminado = false
    private var esperandoVivo = false
    private var finalizado = true
    private var franja: Franja? = null

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
        val miTurno = ++turno
        escritor = Escritor(campoConFoco())
        vivoFinal = null
        vivoTerminado = false
        esperandoVivo = false
        finalizado = false
        cambiarEstado(Estado.GRABANDO)
        boton?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        enVivo = if (ajustes.vivo == Ajustes.VIVO_APAGADO) null else runCatching {
            EnVivo(
                ajustes,
                alTexto = { texto -> principal.post { if (miTurno == turno) mostrarParcial(texto) } },
                alTerminar = { texto -> principal.post { if (miTurno == turno) alTerminarVivo(texto) } },
            )
        }.onFailure { Log.w(TAG, "No pude abrir la sesión en vivo: $it") }.getOrNull()

        var arrancado = false
        val arrancar = arrancar@{ enPrimerPlano: Boolean ->
            if (arrancado || miTurno != turno || estado != Estado.GRABANDO) return@arrancar
            arrancado = true
            Log.i(TAG, "Grabando (servicio en primer plano: $enPrimerPlano, en vivo: ${enVivo != null})")
            try {
                val vivo = enVivo
                grabadora.iniciar(ajustes.soloMicrofonoTelefono) { pcm -> vivo?.enviar(pcm) }
                principal.post(medirNivel)
            } catch (e: Exception) {
                Log.e(TAG, "No pude grabar", e)
                aviso("No pude abrir el micrófono")
                cancelarGrabacion()
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

    /** Texto en vivo: en el campo si se puede, si no en la franja. */
    private fun mostrarParcial(texto: String) {
        if (finalizado || texto.isBlank()) return
        val e = escritor
        if (ajustes.vivo == Ajustes.VIVO_CAMPO && e != null && e.enCampo && e.escribir(texto)) return
        (franja ?: Franja().also { franja = it }).mostrar(texto)
    }

    private fun detener() {
        principal.removeCallbacks(medirNivel)
        val duracion = grabadora.detener()
        val silencio = grabadora.amplitudMaxima == 0
        detenerServicio()
        boton?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        if (duracion < MINIMO_MS || silencio) {
            if (silencio && duracion >= MINIMO_MS) {
                Log.w(TAG, "La grabación salió en silencio total")
                aviso("El micrófono no entregó audio")
            } else if (ajustes.mantenerParaHablar) {
                aviso("Mantén apretado mientras hablas")
            }
            descartar()
            return
        }
        cambiarEstado(Estado.TRANSCRIBIENDO)
        enVivo?.cerrar()

        // Sin pasada final (o sin sesión en vivo que la reemplace) manda el modelo en vivo.
        if (!ajustes.pasadaFinal && enVivo != null) {
            esperandoVivo = true
            if (vivoTerminado) alTerminarVivo(vivoFinal)
            val miTurno = turno
            principal.postDelayed({ if (miTurno == turno && !finalizado) pasadaConArchivo() }, ESPERA_VIVO_MS)
        } else {
            pasadaConArchivo()
        }
    }

    private fun alTerminarVivo(texto: String?) {
        vivoTerminado = true
        vivoFinal = texto ?: enVivo?.parcial?.takeIf { it.isNotBlank() }
        if (!esperandoVivo || finalizado) return
        val t = vivoFinal
        if (t.isNullOrBlank()) pasadaConArchivo() else finalizar(t)
    }

    private fun pasadaConArchivo() {
        esperandoVivo = false
        val miTurno = turno
        thread(name = "transcribir") {
            val resultado = runCatching { Transcriptor.transcribir(grabadora.archivo, ajustes) }
            principal.post {
                if (miTurno != turno || finalizado) return@post
                resultado
                    .onSuccess { texto ->
                        if (texto.isNotBlank()) finalizar(texto)
                        else {
                            aviso("No se entendió nada")
                            descartar()
                        }
                    }
                    .onFailure { e ->
                        Log.e(TAG, "Transcripción falló", e)
                        val respaldo = vivoFinal ?: enVivo?.parcial?.takeIf { it.isNotBlank() }
                        if (respaldo != null) {
                            aviso("Falló la pasada final; quedó el texto en vivo")
                            finalizar(respaldo)
                        } else {
                            aviso(e.message ?: "No se pudo transcribir")
                            descartar()
                        }
                    }
            }
        }
    }

    /** Escribe el texto definitivo y deja todo en reposo. */
    private fun finalizar(texto: String) {
        if (finalizado) return
        finalizado = true
        enVivo?.cancelar()
        enVivo = null
        franja?.ocultar()
        val e = escritor
        escritor = null
        grabadora.archivo.delete()
        if (e == null || !e.escribir(texto)) pegar(e?.conEspacio(texto) ?: texto)
        cambiarEstado(Estado.REPOSO)
    }

    /** Termina sin escribir nada y deja el campo como estaba. */
    private fun descartar() {
        finalizado = true
        enVivo?.cancelar()
        enVivo = null
        franja?.ocultar()
        escritor?.restaurar()
        escritor = null
        grabadora.archivo.delete()
        cambiarEstado(Estado.REPOSO)
    }

    private fun cancelarGrabacion() {
        principal.removeCallbacks(medirNivel)
        if (::grabadora.isInitialized) grabadora.detener()
        detenerServicio()
        if (estado != Estado.REPOSO || !finalizado) descartar()
    }

    private fun detenerServicio() {
        GrabacionService.alIniciar = null
        stopService(Intent(this, GrabacionService::class.java))
    }

    // ---------- Escribir el texto ----------

    /**
     * Escribe en el campo que tenía el foco al empezar, entre el texto que había antes y
     * después del cursor. Cada llamada reemplaza lo escrito por la anterior.
     */
    private inner class Escritor(private val campo: AccessibilityNodeInfo?) {
        private val original: String
        private val selIni: Int
        private val selFin: Int
        private val antes: String
        private val despues: String
        private var tocado = false

        /** Pasa a false si la app no acepta que le cambien el texto. */
        var enCampo = campo != null
            private set

        init {
            val actual = if (campo == null || campo.isShowingHintText) "" else campo.text?.toString().orEmpty()
            var ini = campo?.textSelectionStart ?: -1
            var fin = campo?.textSelectionEnd ?: -1
            if (ini < 0 || fin < 0 || ini > actual.length || fin > actual.length) {
                ini = actual.length; fin = actual.length
            }
            if (ini > fin) ini = fin.also { fin = ini }
            original = actual
            selIni = ini
            selFin = fin
            antes = actual.substring(0, ini)
            despues = actual.substring(fin)
        }

        fun conEspacio(texto: String) =
            if (antes.isNotEmpty() && !antes.last().isWhitespace() && texto.isNotEmpty() && texto.first() !in ".,;:!?)") " $texto"
            else texto

        fun escribir(texto: String): Boolean {
            val c = campo ?: return false
            if (!enCampo) return false
            c.refresh()
            val nuevo = conEspacio(texto)
            if (!fijar(c, antes + nuevo + despues, antes.length + nuevo.length, antes.length + nuevo.length)) {
                enCampo = false
                return false
            }
            tocado = true
            return true
        }

        fun restaurar() {
            val c = campo ?: return
            if (tocado && c.refresh()) fijar(c, original, selIni, selFin)
        }

        private fun fijar(c: AccessibilityNodeInfo, texto: String, ini: Int, fin: Int): Boolean {
            val ok = c.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto) }
            )
            if (ok) {
                c.performAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, ini)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, fin)
                    }
                )
            }
            return ok
        }
    }

    /** Si la app no deja escribir directo: portapapeles y pegar. */
    private fun pegar(texto: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VoxTypee", texto))
        val campo = campoConFoco()
        if (campo == null || !campo.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            aviso("Copiado: pégalo con el teclado")
        }
    }

    // ---------- Franja con el texto en vivo ----------

    /** Muestra el texto en vivo sobre la burbuja, sin tocar el campo. */
    private inner class Franja {
        private val dp = resources.displayMetrics.density
        private val vista = TextView(this@BurbujaService).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.START
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(0xE61C1C24.toInt())
            }
        }
        private val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            horizontalMargin = 0f
        }

        fun mostrar(texto: String) {
            vista.text = texto
            val alto = wm.currentWindowMetrics.bounds.height()
            val arribaBurbuja = params().y
            val lado = (12 * dp).toInt()
            params.width = wm.currentWindowMetrics.bounds.width() - lado * 2
            params.x = lado
            params.y = (alto - arribaBurbuja + (4 * dp).toInt()).coerceAtLeast(0)
            if (vista.parent == null) wm.addView(vista, params) else wm.updateViewLayout(vista, params)
        }

        fun ocultar() {
            if (vista.parent != null) runCatching { wm.removeView(vista) }
        }

        private fun params() = this@BurbujaService.params ?: WindowManager.LayoutParams()
    }

    private fun aviso(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_SHORT).show()

    private fun abrirAjustes() {
        startActivity(Intent(this, AjustesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val MINIMO_MS = 400L
        private const val MAXIMO_MS = 5 * 60_000L
        /** Cuánto esperar el texto final del modelo en vivo antes de recurrir al archivo. */
        private const val ESPERA_VIVO_MS = 6_000L
    }
}

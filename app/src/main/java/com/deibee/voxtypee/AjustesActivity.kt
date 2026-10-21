package com.deibee.voxtypee

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Única pantalla de la app: permisos, proveedor, modo, vocabulario y una zona para probar. */
class AjustesActivity : Activity() {

    private lateinit var ajustes: Ajustes
    private val dp by lazy { resources.displayMetrics.density }

    private lateinit var estadoMicrofono: TextView
    private lateinit var botonMicrofono: Button
    private lateinit var estadoAccesibilidad: TextView
    private lateinit var botonAccesibilidad: Button
    private lateinit var ayudaRestringidos: LinearLayout
    private lateinit var campoClave: EditText
    private lateinit var listaVocabulario: LinearLayout

    private val idiomas = listOf(
        "es" to "Español", "en" to "English", "pt" to "Português", "fr" to "Français",
        "it" to "Italiano", "de" to "Deutsch", "" to "Detectar automáticamente"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ajustes = Ajustes(this)

        val contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(32))
        }
        contenido.addView(texto("VoxTypee", 30f, negrita = true))
        contenido.addView(texto("Dictado con IA sobre cualquier teclado", 15f, suave = true).abajo(20))

        contenido.addView(tarjetaPermisos())
        contenido.addView(tarjetaProveedor())
        contenido.addView(tarjetaModo())
        contenido.addView(tarjetaVocabulario())
        contenido.addView(tarjetaProbar())

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        contenido.addView(texto("VoxTypee $version · Creado por Deibee", 13f, suave = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, px(12), 0, px(4))
        })
        contenido.addView(texto("github.com/cvallejoec/voxtypee", 13f).apply {
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.acento))
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO))) }
        })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(contenido)
            // Android 15+ dibuja de borde a borde: dejar espacio a las barras y al teclado.
            setOnApplyWindowInsetsListener { v, insets ->
                val barras = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(barras.left, barras.top, barras.right, barras.bottom)
                insets
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refrescarPermisos()
    }

    // ---------- Tarjetas ----------

    private fun tarjetaPermisos() = tarjeta("Permisos").apply {
        val (filaMic, estadoMic, botonMic) = filaEstado("Micrófono")
        estadoMicrofono = estadoMic
        botonMicrofono = botonMic.apply {
            setOnClickListener {
                val permisos = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) permisos += Manifest.permission.POST_NOTIFICATIONS
                requestPermissions(permisos.toTypedArray(), 1)
            }
        }
        addView(filaMic)

        val (filaAcc, estadoAcc, botonAcc) = filaEstado("Accesibilidad")
        estadoAccesibilidad = estadoAcc
        botonAccesibilidad = botonAcc.apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        addView(filaAcc)

        ayudaRestringidos = LinearLayout(this@AjustesActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(texto(
                "Si el interruptor de VoxTypee aparece gris («ajuste restringido»), Android lo bloquea " +
                    "porque la app no viene de Play Store. Ábrelo aquí, toca ⋮ arriba a la derecha → " +
                    "«Permitir ajustes restringidos» y vuelve a intentar.", 13f, suave = true
            ).arriba(8))
            addView(boton("Abrir información de la app") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.arriba(6))
        }
        addView(ayudaRestringidos)

        addView(Switch(this@AjustesActivity).apply {
            text = "Mostrar la burbuja al abrir el teclado"
            textSize = 15f
            setTextColor(getColor(R.color.texto))
            isChecked = ajustes.activo
            setOnCheckedChangeListener { _, activo -> ajustes.activo = activo }
        }.arriba(12))
    }

    private fun tarjetaProveedor() = tarjeta("Proveedor de IA").apply {
        addView(etiqueta("API key"))
        campoClave = campo(if (ajustes.tieneClave) "Guardada · escribe otra para cambiarla" else "sk-…").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        addView(campoClave)

        addView(etiqueta("Endpoint"))
        val campoEndpoint = campo(Ajustes.ENDPOINT_OPENAI).apply {
            setText(ajustes.endpoint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        addView(campoEndpoint)

        addView(etiqueta("Modelo"))
        val campoModelo = campo(Ajustes.MODELO_POR_DEFECTO).apply { setText(ajustes.modelo) }
        addView(campoModelo)

        addView(etiqueta("Idioma"))
        val selectorIdioma = Spinner(this@AjustesActivity).apply {
            adapter = object : ArrayAdapter<String>(
                this@AjustesActivity, android.R.layout.simple_spinner_dropdown_item, idiomas.map { it.second }
            ) {
                // El layout del sistema trae texto blanco en algunos temas (One UI): forzar el nuestro.
                override fun getView(posicion: Int, vista: View?, padre: ViewGroup) =
                    (super.getView(posicion, vista, padre) as TextView).apply { setTextColor(getColor(R.color.texto)) }

                override fun getDropDownView(posicion: Int, vista: View?, padre: ViewGroup) =
                    (super.getDropDownView(posicion, vista, padre) as TextView).apply {
                        setTextColor(getColor(R.color.texto))
                        setBackgroundColor(getColor(R.color.tarjeta))
                    }
            }
            setSelection(idiomas.indexOfFirst { it.first == ajustes.idioma }.coerceAtLeast(0))
        }
        addView(selectorIdioma)

        addView(boton("Guardar") {
            val clave = campoClave.text.toString()
            if (clave.isNotBlank()) {
                ajustes.clave = clave
                campoClave.setText("")
                campoClave.hint = "Guardada · escribe otra para cambiarla"
            }
            ajustes.endpoint = campoEndpoint.text.toString()
            ajustes.modelo = campoModelo.text.toString()
            ajustes.idioma = idiomas[selectorIdioma.selectedItemPosition].first
            Toast.makeText(this@AjustesActivity, "Guardado", Toast.LENGTH_SHORT).show()
        }.arriba(14))
    }

    private fun tarjetaModo() = tarjeta("Cómo se usa la burbuja").apply {
        val grupo = RadioGroup(this@AjustesActivity)
        val tocar = radio("Tocar para empezar y otra vez para terminar (se puede arrastrar)")
        val mantener = radio("Mantener apretado mientras hablas (arrastra afuera para cancelar)")
        grupo.addView(tocar)
        grupo.addView(mantener)
        grupo.check(if (ajustes.mantenerParaHablar) mantener.id else tocar.id)
        grupo.setOnCheckedChangeListener { _, id -> ajustes.mantenerParaHablar = id == mantener.id }
        addView(grupo)
    }

    private fun tarjetaVocabulario() = tarjeta("Vocabulario").apply {
        addView(texto(
            "Nombres, siglas y jerga que el dictado debe escribir así. Van al modelo como pista.",
            13f, suave = true
        ).abajo(8))
        listaVocabulario = LinearLayout(this@AjustesActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(listaVocabulario)
        pintarVocabulario()

        val fila = LinearLayout(this@AjustesActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nuevo = campo("Agregar término")
        fila.addView(nuevo, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        fila.addView(boton("+") {
            val termino = nuevo.text.toString().trim()
            if (termino.isNotEmpty() && termino !in ajustes.vocabulario) {
                ajustes.vocabulario = ajustes.vocabulario + termino
                pintarVocabulario()
            }
            nuevo.setText("")
        })
        addView(fila.arriba(6))
    }

    private fun pintarVocabulario() {
        listaVocabulario.removeAllViews()
        if (ajustes.vocabulario.isEmpty()) {
            listaVocabulario.addView(texto("Todavía no hay términos.", 14f, suave = true))
        }
        ajustes.vocabulario.forEach { termino ->
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fila.addView(texto(termino, 15f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            fila.addView(texto("✕", 16f, suave = true).apply {
                setPadding(px(14), px(8), px(4), px(8))
                setOnClickListener {
                    ajustes.vocabulario = ajustes.vocabulario - termino
                    pintarVocabulario()
                }
            })
            listaVocabulario.addView(fila)
        }
    }

    private fun tarjetaProbar() = tarjeta("Probar").apply {
        addView(texto("Toca el campo, espera la burbuja y dicta.", 13f, suave = true).abajo(8))
        addView(campo("Aquí aparece lo que dictas").apply {
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        })
    }

    // ---------- Estado de permisos ----------

    private fun refrescarPermisos() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        pintarEstado(estadoMicrofono, botonMicrofono, mic, "Permitir")

        val acc = accesibilidadActiva()
        pintarEstado(estadoAccesibilidad, botonAccesibilidad, acc, "Activar")
        ayudaRestringidos.visibility = if (acc) View.GONE else View.VISIBLE
    }

    private fun accesibilidadActiva(): Boolean {
        val propio = ComponentName(this, BurbujaService::class.java)
        return getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName.unflattenFromString(it.id) == propio }
    }

    private fun pintarEstado(estado: TextView, boton: Button, ok: Boolean, accion: String) {
        estado.text = if (ok) "✓ Listo" else "Falta"
        estado.setTextColor(getColor(if (ok) R.color.ok else R.color.falta))
        boton.text = accion
        boton.visibility = if (ok) View.GONE else View.VISIBLE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refrescarPermisos()
    }

    // ---------- Piezas de interfaz ----------

    private fun px(valor: Int) = (valor * dp).toInt()

    private fun tarjeta(titulo: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(18), px(16), px(18), px(18))
        background = GradientDrawable().apply {
            cornerRadius = 18 * dp
            setColor(getColor(R.color.tarjeta))
            setStroke(px(1), getColor(R.color.borde))
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(14) }
        addView(texto(titulo, 17f, negrita = true).abajo(8))
    }

    private fun texto(valor: String, tamano: Float, negrita: Boolean = false, suave: Boolean = false) =
        TextView(this).apply {
            text = valor
            textSize = tamano
            setTextColor(getColor(if (suave) R.color.texto_suave else R.color.texto))
            if (negrita) typeface = Typeface.DEFAULT_BOLD
        }

    private fun etiqueta(valor: String) = texto(valor, 13f, suave = true).arriba(10)

    private fun campo(pista: String) = EditText(this).apply {
        hint = pista
        textSize = 15f
        isSingleLine = true
        setTextColor(getColor(R.color.texto))
        setHintTextColor(getColor(R.color.texto_suave))
    }

    private fun boton(valor: String, alTocar: () -> Unit) = Button(this).apply {
        text = valor
        isAllCaps = false
        setOnClickListener { alTocar() }
    }

    private fun radio(valor: String) = RadioButton(this).apply {
        id = View.generateViewId()
        text = valor
        textSize = 15f
        setTextColor(getColor(R.color.texto))
        setPadding(px(6), px(8), 0, px(8))
    }

    private fun filaEstado(nombre: String): Triple<LinearLayout, TextView, Button> {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(48)
        }
        fila.addView(texto(nombre, 15f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val estado = texto("", 14f).apply { setPadding(0, 0, px(10), 0) }
        val boton = boton("") {}
        fila.addView(estado)
        fila.addView(boton)
        return Triple(fila, estado, boton)
    }

    private fun <T : View> T.arriba(margen: Int) = apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(margen) }
    }

    private fun <T : View> T.abajo(margen: Int) = apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(margen) }
    }

    companion object {
        const val REPO = "https://github.com/cvallejoec/voxtypee"
    }
}

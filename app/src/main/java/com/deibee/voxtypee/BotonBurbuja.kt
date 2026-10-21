package com.deibee.voxtypee

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

enum class Estado { REPOSO, GRABANDO, TRANSCRIBIENDO }

/** El botón flotante: un círculo con micrófono que cambia según el estado del dictado. */
class BotonBurbuja(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    val diametro = (56 * dp).toInt()
    val margen = (12 * dp).toInt()
    val lado = diametro + margen * 2

    private val acento = context.getColor(R.color.acento)
    private val rojo = context.getColor(R.color.grabando)
    private val icono = context.getDrawable(R.drawable.ic_mic)!!.mutate()

    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sombra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val anillo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val ovalo = RectF()

    var estado = Estado.REPOSO
        set(valor) {
            field = valor
            if (valor == Estado.TRANSCRIBIENDO) giro.start() else giro.cancel()
            if (valor != Estado.GRABANDO) nivel = 0f
            invalidate()
        }

    /** Nivel de voz de 0 a 1 mientras graba. */
    var nivel = 0f
        set(valor) {
            field = field * 0.5f + valor * 0.5f
            invalidate()
        }

    /** Arrastrar hacia afuera en modo "mantener" para cancelar. */
    var cancelando = false
        set(valor) { field = valor; invalidate() }

    private var angulo = 0f
    private val giro = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angulo = it.animatedValue as Float; invalidate() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(lado, lado)

    override fun onDraw(canvas: Canvas) {
        val c = lado / 2f
        val r = diametro / 2f
        val color = when {
            cancelando -> 0xFF6B6B7B.toInt()
            estado == Estado.GRABANDO -> rojo
            else -> acento
        }

        if (estado == Estado.GRABANDO && !cancelando) {
            anillo.color = rojo
            anillo.alpha = 70
            canvas.drawCircle(c, c, r + margen * nivel, anillo)
        }
        canvas.drawCircle(c, c + 2 * dp, r, sombra)
        relleno.color = color
        relleno.alpha = if (isPressed) 220 else 255
        canvas.drawCircle(c, c, r, relleno)

        val i = (12 * dp).toInt()
        icono.setBounds((c - i).toInt(), (c - i).toInt(), (c + i).toInt(), (c + i).toInt())
        icono.alpha = if (estado == Estado.TRANSCRIBIENDO) 110 else 255
        icono.draw(canvas)

        if (estado == Estado.TRANSCRIBIENDO) {
            val a = r - 6 * dp
            ovalo.set(c - a, c - a, c + a, c + a)
            canvas.drawArc(ovalo, angulo, 100f, false, arco)
        }
    }

    override fun onDetachedFromWindow() {
        giro.cancel()
        super.onDetachedFromWindow()
    }
}

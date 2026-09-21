package com.example.temiapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * Cara animada simple:
 * - Fondo de color.
 * - Dos ojos que parpadean de forma aleatoria (no sincronizada, para que se vea más "vivo").
 * - Una boca en forma de sonrisa fija.
 *
 * Uso:
 *   setContentView(FaceView(this))
 *
 * o desde XML:
 *   <com.example.temiface.FaceView
 *       android:layout_width="match_parent"
 *       android:layout_height="match_parent" />
 */
class FaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Colores / pinceles ---
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#2C3E50")
        style = Paint.Style.FILL
    }

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F1C40F")
        style = Paint.Style.FILL
    }

    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2C3E50")
        style = Paint.Style.FILL
    }

    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F1C40F")
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
    }

    // --- Estado del parpadeo ---
    // 1f = ojo totalmente abierto, 0f = ojo totalmente cerrado
    private var leftEyeOpenness = 1f
    private var rightEyeOpenness = 1f

    private val blinkDurationMs = 120L
    private val random = Random(System.currentTimeMillis())

    // Runnable que programa el próximo parpadeo en un intervalo aleatorio
    private val scheduleNextBlink = object : Runnable {
        override fun run() {
            blinkEye(isLeft = true)
            // A veces parpadean los dos ojos casi a la vez, a veces solo uno,
            // para que la cara se vea más natural / graciosa.
            if (random.nextFloat() < 0.85f) {
                postDelayed({ blinkEye(isLeft = false) }, random.nextLong(0, 80))
            } else {
                blinkEye(isLeft = false)
            }
            postDelayed(this, random.nextLong(1500, 4500))
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // mejor rendimiento para el clip/canvas en algunos dispositivos
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(scheduleNextBlink, 1000)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(scheduleNextBlink)
    }

    private fun blinkEye(isLeft: Boolean) {
        val closeAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = blinkDurationMs
            addUpdateListener {
                val value = it.animatedValue as Float
                if (isLeft) leftEyeOpenness = value else rightEyeOpenness = value
                invalidate()
            }
        }
        val openAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = blinkDurationMs
            addUpdateListener {
                val value = it.animatedValue as Float
                if (isLeft) leftEyeOpenness = value else rightEyeOpenness = value
                invalidate()
            }
        }
        closeAnim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                openAnim.start()
            }
        })
        closeAnim.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Fondo
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        val centerX = w / 2f
        val centerY = h / 2f
        val eyeRadius = minOf(w, h) * 0.09f
        val eyeOffsetX = minOf(w, h) * 0.16f
        val eyeCenterY = centerY - minOf(w, h) * 0.08f

        drawEye(canvas, centerX - eyeOffsetX, eyeCenterY, eyeRadius, leftEyeOpenness)
        drawEye(canvas, centerX + eyeOffsetX, eyeCenterY, eyeRadius, rightEyeOpenness)

        // Boca: arco de sonrisa
        val mouthWidth = minOf(w, h) * 0.32f
        val mouthHeight = minOf(w, h) * 0.18f
        val mouthTop = centerY + minOf(w, h) * 0.08f
        val mouthRect = RectF(
            centerX - mouthWidth / 2f,
            mouthTop,
            centerX + mouthWidth / 2f,
            mouthTop + mouthHeight
        )
        canvas.drawArc(mouthRect, 20f, 140f, false, mouthPaint)
    }

    /**
     * Dibuja un ojo. `openness` 1f = abierto, 0f = cerrado.
     * Cuando está cerrado se dibuja como una línea horizontal en vez de un círculo.
     */
    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, radius: Float, openness: Float) {
        if (openness > 0.08f) {
            // Ojo (más o menos) abierto: círculo achatado verticalmente según el parpadeo
            canvas.save()
            canvas.scale(1f, openness, cx, cy)
            canvas.drawCircle(cx, cy, radius, eyePaint)
            canvas.drawCircle(cx, cy, radius * 0.45f, pupilPaint)
            canvas.restore()
        } else {
            // Ojo cerrado: una rayita
            val halfWidth = radius * 0.9f
            canvas.drawLine(cx - halfWidth, cy, cx + halfWidth, cy, mouthPaint.apply {
                strokeWidth = radius * 0.22f
            })
        }
    }

    /** Llama a esto si en algún momento quieres forzar un parpadeo manual (p.ej. al recibir un evento del robot). */
    fun blinkNow() {
        blinkEye(isLeft = true)
        blinkEye(isLeft = false)
    }
}

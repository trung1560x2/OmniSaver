package com.omnisaver.videodownloader.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.Window
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Renders an iOS 27 "Liquid Glass" (Solarium / VisionOS-inspired) living aurora mesh background
 * with floating iridescent light spheres and subtle specular refraction.
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 16000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            phase = anim.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                setRenderEffect(RenderEffect.createBlurEffect(45f, 45f, Shader.TileMode.MIRROR))
            } catch (_: Exception) {
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) {
            animator.start()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            basePaint.shader = LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                intArrayOf(
                    Color.parseColor("#040714"),
                    Color.parseColor("#09102A"),
                    Color.parseColor("#110A2B")
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, basePaint)

        val maxDim = max(w, h)

        // Orb 1: Electric Cyan (Top-Left drifting)
        val cx1 = w * (0.20f + 0.12f * cos(phase))
        val cy1 = h * (0.16f + 0.08f * sin(phase))
        drawLiquidOrb(canvas, cx1, cy1, maxDim * 0.45f, Color.argb(76, 0, 229, 255))

        // Orb 2: Spatial Violet (Center-Right drifting)
        val cx2 = w * (0.82f + 0.10f * sin(phase * 0.8f + 1.2f))
        val cy2 = h * (0.42f + 0.10f * cos(phase * 0.8f + 1.2f))
        drawLiquidOrb(canvas, cx2, cy2, maxDim * 0.48f, Color.argb(68, 168, 85, 247))

        // Orb 3: Rose Magenta (Bottom-Left drifting)
        val cx3 = w * (0.25f + 0.14f * sin(phase * 1.1f + 2.5f))
        val cy3 = h * (0.78f + 0.08f * cos(phase * 1.1f + 2.5f))
        drawLiquidOrb(canvas, cx3, cy3, maxDim * 0.42f, Color.argb(56, 236, 72, 153))

        // Orb 4: Emerald Aqua (Bottom-Right drifting)
        val cx4 = w * (0.75f + 0.10f * cos(phase * 0.9f + 4.0f))
        val cy4 = h * (0.86f + 0.07f * sin(phase * 0.9f + 4.0f))
        drawLiquidOrb(canvas, cx4, cy4, maxDim * 0.38f, Color.argb(52, 16, 185, 129))
    }

    private fun drawLiquidOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float, centerColor: Int) {
        if (radius <= 1f) return
        val midColor = Color.argb(
            (Color.alpha(centerColor) * 0.45f).toInt(),
            Color.red(centerColor),
            Color.green(centerColor),
            Color.blue(centerColor)
        )
        orbPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(centerColor, midColor, Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, orbPaint)
    }
}

object LiquidGlassHelper {

    /**
     * Builds an iOS 27 translucent Liquid Glass capsule drawable tinted with [hexColor]
     * and a diagonal specular white-cyan rim reflection.
     */
    fun createPlatformGlassPill(hexColor: String, cornerRadiusPx: Float = 999f, fillAlpha: Int = 95): Drawable {
        val baseColor = try {
            Color.parseColor(hexColor)
        } catch (_: Exception) {
            Color.parseColor("#38BDF8")
        }

        val tintedTop = Color.argb(
            (fillAlpha * 1.25f).toInt().coerceAtMost(210),
            Color.red(baseColor),
            Color.green(baseColor),
            Color.blue(baseColor)
        )
        val tintedBottom = Color.argb(
            (fillAlpha * 0.65f).toInt().coerceAtMost(160),
            Color.red(baseColor),
            Color.green(baseColor),
            Color.blue(baseColor)
        )
        val rimHighlight = Color.argb(
            185,
            ((Color.red(baseColor) + 255) / 2).coerceAtMost(255),
            ((Color.green(baseColor) + 255) / 2).coerceAtMost(255),
            ((Color.blue(baseColor) + 255) / 2).coerceAtMost(255)
        )

        val outerRim = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.argb(200, 255, 255, 255), rimHighlight, Color.argb(70, 255, 255, 255))
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }

        val innerGlass = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(tintedTop, tintedBottom)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = (cornerRadiusPx - 3f).coerceAtLeast(8f)
        }

        return LayerDrawable(arrayOf(outerRim, innerGlass)).apply {
            setLayerInset(1, 3, 3, 3, 3)
        }
    }

    /**
     * Enables real-time window background blur on Android 12+ (iOS 27 sheet/dialog effect).
     */
    fun applyWindowBlurIfSupported(window: Window?) {
        if (window == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.addFlags( android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    blurBehindRadius = 52
                }
            } catch (_: Exception) {
            }
        }
    }
}

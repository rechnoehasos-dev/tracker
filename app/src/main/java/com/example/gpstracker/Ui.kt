package com.example.gpstracker

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Дизайн-токены «Тавиат» и маленькие фабрики вида, чтобы экраны собирались одинаково. */
object Ui {
    const val YELLOW = 0xFFFFBF2E.toInt()
    const val GRAPHITE = 0xFF525252.toInt()
    const val MUTED = 0xFF6E6E6E.toInt()
    const val SURFACE = Color.WHITE
    const val SURFACE_ALT = 0xFFF3F3F2.toInt()
    const val BORDER = 0xFFD4D4D2.toInt()
    const val BORDER_STRONG = 0xFF8A8A8A.toInt()

    private val fonts = HashMap<Int, Typeface>()

    /** Montserrat нужной насыщенности (вариативный шрифт из assets), иначе системный. */
    fun font(ctx: Context, weight: Int): Typeface = fonts.getOrPut(weight) {
        try {
            Typeface.Builder(ctx.assets, "fonts/Montserrat.ttf")
                .setFontVariationSettings("'wght' $weight")
                .setWeight(weight)
                .build() ?: Typeface.create(Typeface.SANS_SERIF, weight, false)
        } catch (e: Exception) {
            Typeface.create(Typeface.SANS_SERIF, weight, false)
        }
    }

    fun dp(ctx: Context, v: Number): Int = (v.toFloat() * ctx.resources.displayMetrics.density).roundToInt()

    fun text(ctx: Context, sizeSp: Float, weight: Int = 400, color: Int = GRAPHITE, value: CharSequence = "") =
        TextView(ctx).apply {
            textSize = sizeSp
            typeface = font(ctx, weight)
            setTextColor(color)
            text = value
            includeFontPadding = false
        }

    /** Метка: 12 sp, 600, заглавные, разрядка 0.06em. */
    fun label(ctx: Context, value: String, color: Int = MUTED) =
        text(ctx, 12f, 600, color, value.uppercase()).apply { letterSpacing = 0.06f }

    fun oval(fill: Int, stroke: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (strokePx > 0) setStroke(strokePx, stroke)
    }

    fun rect(fill: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(fill)
        if (strokePx > 0) setStroke(strokePx, stroke)
    }

    fun ripple(bg: Drawable, mask: Drawable? = null) =
        RippleDrawable(ColorStateList.valueOf(0x33525252), bg, mask)

    /** Круглая кнопка с иконкой. */
    fun circleButton(
        ctx: Context, sizeDp: Int, fill: Int, icon: Drawable, iconDp: Int, desc: String,
        stroke: Int = 0, strokeDp: Int = 0, onClick: () -> Unit
    ): FrameLayout = FrameLayout(ctx).apply {
        val bg = oval(fill, stroke, if (strokeDp > 0) dp(ctx, strokeDp) else 0)
        background = ripple(bg, oval(Color.BLACK))
        contentDescription = desc
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(ImageView(ctx).apply { setImageDrawable(icon) },
            FrameLayout.LayoutParams(dp(ctx, iconDp), dp(ctx, iconDp), Gravity.CENTER))
        layoutParams = ViewGroup.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
    }

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun spacer(ctx: Context, weight: Float = 1f) = View(ctx).apply { layoutParams = lp(0, 0, weight) }
}

/** Линейные иконки 24×24 с круглыми концами, как в макете. */
class Icon(private val kind: Kind, private val color: Int, private val accent: Int = Ui.YELLOW) : Drawable() {

    enum class Kind { PLAY, PAUSE, STOP, LIST, MAP, RECORD, BACK, CHEVRON, LAYERS, REWIND, INFO }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
        color = this@Icon.color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = this@Icon.color }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        canvas.scale(s, s)
        when (kind) {
            Kind.PLAY -> canvas.drawPath(poly(8f, 5f, 19f, 12f, 8f, 19f, close = true), fill)
            Kind.PAUSE -> {
                stroke.strokeWidth = 2.5f
                canvas.drawLine(9f, 6f, 9f, 18f, stroke); canvas.drawLine(15f, 6f, 15f, 18f, stroke)
            }
            Kind.STOP -> canvas.drawRoundRect(RectF(6f, 6f, 18f, 18f), 2f, 2f, fill)
            Kind.LIST -> {
                for (y in floatArrayOf(6f, 12f, 18f)) {
                    canvas.drawLine(8f, y, 20f, y, stroke); canvas.drawCircle(4f, y, 1f, fill)
                }
            }
            Kind.MAP -> {
                canvas.drawPath(poly(9f, 4f, 3f, 6f, 3f, 20f, 9f, 18f, 15f, 20f, 21f, 18f, 21f, 4f, 15f, 6f, close = true), stroke)
                canvas.drawLine(9f, 4f, 9f, 18f, stroke); canvas.drawLine(15f, 6f, 15f, 20f, stroke)
            }
            Kind.RECORD -> {
                canvas.drawCircle(12f, 12f, 8f, stroke)
                canvas.drawCircle(12f, 12f, 3f, Paint(fill).apply { color = accent })
                canvas.drawCircle(12f, 12f, 3f, stroke)
            }
            Kind.BACK -> canvas.drawPath(poly(15f, 6f, 9f, 12f, 15f, 18f), stroke)
            Kind.CHEVRON -> canvas.drawPath(poly(9f, 6f, 15f, 12f, 9f, 18f), stroke)
            Kind.LAYERS -> {
                canvas.drawPath(poly(12f, 3f, 21f, 8f, 12f, 13f, 3f, 8f, close = true), stroke)
                canvas.drawPath(poly(3f, 13f, 12f, 18f, 21f, 13f), stroke)
            }
            Kind.REWIND -> {
                canvas.drawPath(poly(11f, 6f, 4f, 12f, 11f, 18f, close = true), stroke)
                canvas.drawPath(poly(20f, 6f, 13f, 12f, 20f, 18f, close = true), stroke)
            }
            Kind.INFO -> {
                canvas.drawCircle(12f, 12f, 9f, stroke)
                canvas.drawLine(12f, 8f, 12f, 12f, stroke); canvas.drawPoint(12f, 16f, stroke)
            }
        }
        canvas.restore()
    }

    private fun poly(vararg p: Float, close: Boolean = false) = Path().apply {
        moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
        if (close) close()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

package com.picfilter.spike

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import coil.load
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * PixKin's design system: the tokens from design-tokens.json and the handful of
 * components every screen is built from.
 *
 * No screen names a color. The pk_* resources resolve to the light palette or
 * the dark one for us, so a view built here is correct in both. Red is the
 * brand, so it reaches the UI only as [primary]: one main action per screen,
 * plus selection states.
 */
object Ui {

    /** Decode target for a face circle. */
    internal const val FACE_PX = 160


    // ---------- spacing (4dp grid) ----------

    const val XXS = 4
    const val XS = 8
    const val SM = 12
    const val MD = 16          // screen side margin
    const val LG = 24
    const val XL = 32
    const val XXL = 48

    // ---------- radius ----------

    const val R_SM = 8         // chips, small thumbnails
    const val R_MD = 12        // cards, photo tiles
    const val R_LG = 16        // sheets, banners
    const val R_XL = 28        // dialogs

    // ---------- sizes ----------

    const val TOP_BAR = 64
    const val LOGO = 28
    const val TOUCH = 48       // minimum touch target
    const val ICON = 24
    const val AVATAR = 40
    const val GRID_GAP = 2

    /**
     * Margins that mirror in a right-to-left locale.
     *
     * setMarginsRelative() takes left and right, which are physical sides. In Hebrew
     * the start of a line is on the right, so a left margin that was meant as
     * "space before this" lands after it instead. Setting start and end as
     * well lets the framework resolve them per direction; the plain values
     * stay as the left-to-right fallback.
     */
    fun ViewGroup.MarginLayoutParams.setMarginsRelative(
        start: Int, top: Int, end: Int, bottom: Int,
    ) {
        setMargins(start, top, end, bottom)
        marginStart = start
        marginEnd = end
    }

    fun Context.dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ---------- color roles ----------

    fun color(ctx: Context, @ColorRes id: Int) = ContextCompat.getColor(ctx, id)

    fun primary(ctx: Context) = color(ctx, R.color.pk_primary)
    fun onPrimary(ctx: Context) = color(ctx, R.color.pk_on_primary)
    fun primaryContainer(ctx: Context) = color(ctx, R.color.pk_primary_container)
    fun onPrimaryContainer(ctx: Context) = color(ctx, R.color.pk_on_primary_container)
    fun secondary(ctx: Context) = color(ctx, R.color.pk_secondary)
    fun onSecondary(ctx: Context) = color(ctx, R.color.pk_on_secondary)
    fun secondaryContainer(ctx: Context) = color(ctx, R.color.pk_secondary_container)
    fun onSecondaryContainer(ctx: Context) = color(ctx, R.color.pk_on_secondary_container)
    fun background(ctx: Context) = color(ctx, R.color.pk_background)
    fun surface(ctx: Context) = color(ctx, R.color.pk_surface)
    fun onSurface(ctx: Context) = color(ctx, R.color.pk_on_surface)
    fun surfaceVariant(ctx: Context) = color(ctx, R.color.pk_surface_variant)
    fun onSurfaceVariant(ctx: Context) = color(ctx, R.color.pk_on_surface_variant)
    fun outline(ctx: Context) = color(ctx, R.color.pk_outline)
    fun outlineVariant(ctx: Context) = color(ctx, R.color.pk_outline_variant)
    fun warning(ctx: Context) = color(ctx, R.color.pk_warning)

    // ---------- type ----------

    /** The scale from the style guide. Sizes are sp; [line] is the line height. */
    enum class Type(val size: Float, val line: Float, val weight: Int, val tracking: Float) {
        DISPLAY(32f, 40f, 800, -0.5f),
        HEADLINE(24f, 32f, 800, -0.25f),
        TITLE(20f, 28f, 700, 0f),
        TITLE_SMALL(16f, 24f, 700, 0f),
        BODY(16f, 24f, 400, 0f),
        BODY_SMALL(14f, 20f, 400, 0f),
        LABEL(14f, 20f, 600, 0.1f),
        CAPTION(12f, 16f, 600, 0.2f),
    }

    private val fonts = HashMap<Int, Typeface>()

    /** Nunito at [weight]. One variable font, so the weights are instances of it. */
    fun font(ctx: Context, weight: Int): Typeface = fonts.getOrPut(weight) {
        val base = try {
            ResourcesCompat.getFont(ctx, R.font.nunito)
        } catch (e: android.content.res.Resources.NotFoundException) {
            null
        } ?: Typeface.SANS_SERIF
        Typeface.create(base, weight, false)
    }

    /** A TextView carrying one step of the scale. Sentence case, never all-caps. */
    fun text(
        ctx: Context, style: Type, t: CharSequence = "",
        @ColorRes colour: Int = R.color.pk_on_surface,
    ): TextView = TextView(ctx).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style.size)
        // The token is an exact line height, not a multiplier: multiplying would
        // compound with Nunito's own generous leading and the text would drift
        // off the 4dp grid.
        setLineHeight(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, style.line, resources.displayMetrics).toInt())
        letterSpacing = style.tracking / style.size
        typeface = font(ctx, style.weight)
        setTextColor(color(ctx, colour))
    }

    enum class Btn { PRIMARY, SECONDARY, OUTLINED, TEXT, LINK }

    class Preview(val view: View, val strip: LinearLayout)

    data class Face(val name: String, val caption: String, val facePath: String?)
}

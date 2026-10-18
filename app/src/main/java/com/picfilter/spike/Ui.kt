package com.picfilter.spike

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel

/** Shared Material 3 pieces so every screen looks like one product. */
object Ui {

    const val SURFACE = "#131314"
    const val SURFACE_VARIANT = "#1E1F20"
    const val ON_SURFACE = "#E3E3E3"
    const val ON_SURFACE_VARIANT = "#9AA0A6"
    const val PRIMARY = "#A8C7FA"
    const val ON_PRIMARY = "#0A305F"
    const val GREEN = "#6DD58C"
    const val ON_GREEN = "#003917"
    const val RED = "#F2B8B5"
    const val ON_RED = "#601410"
    const val NEUTRAL = "#2D2F31"

    fun Context.dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    fun screen(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor(SURFACE))
    }

    /** Edge-to-edge without letting system bars eat the controls. */
    fun applyInsets(root: View, ctx: Context, extraBottom: Int = 0) {
        with(ctx) {
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val b = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom + dp(extraBottom))
                insets
            }
        }
    }

    fun title(ctx: Context, t: String, size: Float = 28f): TextView = TextView(ctx).apply {
        text = t; textSize = size
        setTextColor(Color.parseColor(ON_SURFACE))
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        letterSpacing = -0.01f
        with(ctx) { setPadding(dp(24), dp(16), dp(24), dp(8)) }
    }

    fun body(ctx: Context, t: String, muted: Boolean = true): TextView = TextView(ctx).apply {
        text = t; textSize = 14f
        setTextColor(Color.parseColor(if (muted) ON_SURFACE_VARIANT else ON_SURFACE))
        setLineSpacing(0f, 1.25f)
        with(ctx) { setPadding(dp(24), 0, dp(24), dp(16)) }
    }

    /** Google-Photos-style row: title, supporting line, whole card tappable. */
    fun actionCard(
        ctx: Context, label: String, supporting: String, accent: Boolean = false,
        onClick: () -> Unit,
    ): View = MaterialCardView(ctx).apply {
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), dp(5), dp(16), dp(5)) }
            radius = dp(20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(
                Color.parseColor(if (accent) "#284777" else SURFACE_VARIANT))
            isClickable = true
            isFocusable = true
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(18))
                addView(TextView(ctx).apply {
                    text = label; textSize = 16f
                    setTextColor(Color.parseColor(if (accent) "#D7E3FF" else ON_SURFACE))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                if (supporting.isNotEmpty()) addView(TextView(ctx).apply {
                    text = supporting; textSize = 13f
                    setTextColor(Color.parseColor(ON_SURFACE_VARIANT))
                    setPadding(0, dp(3), 0, 0)
                })
            })
        }
        setOnClickListener { onClick() }
    }

    fun filled(ctx: Context, label: String, bg: String, fg: String, onClick: () -> Unit) =
        MaterialButton(ctx).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor(fg))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(bg))
            with(ctx) {
                cornerRadius = dp(20)
                insetTop = 0; insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
                    .apply { setMargins(dp(4), 0, dp(4), 0) }
            }
            setOnClickListener { onClick() }
        }

    fun wideButton(ctx: Context, label: String, bg: String, fg: String, onClick: () -> Unit) =
        MaterialButton(ctx).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor(fg))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(bg))
            with(ctx) {
                cornerRadius = dp(24)
                insetTop = 0; insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
                ).apply { setMargins(dp(24), dp(6), dp(24), dp(6)) }
            }
            setOnClickListener { onClick() }
        }

    fun photo(ctx: Context): ShapeableImageView = ShapeableImageView(ctx).apply {
        with(ctx) {
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(18).toFloat()).build()
        }
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.parseColor(SURFACE_VARIANT))
    }

    fun thumb(ctx: Context, sizeDp: Int): ShapeableImageView = photo(ctx).apply {
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
                .apply { setMargins(0, 0, dp(8), 0) }
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(14).toFloat()).build()
        }
    }

    /** Pill showing a stat, like Photos' storage chips. */
    fun statRow(ctx: Context, pairs: List<Pair<String, String>>): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        with(ctx) { setPadding(dp(16), dp(4), dp(16), dp(12)) }
        for ((value, caption) in pairs) {
            addView(MaterialCardView(ctx).apply {
                with(ctx) {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins(dp(4), 0, dp(4), 0) }
                    radius = dp(16).toFloat()
                }
                cardElevation = 0f
                setCardBackgroundColor(Color.parseColor(SURFACE_VARIANT))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    with(ctx) { setPadding(dp(8), dp(14), dp(8), dp(14)) }
                    addView(TextView(ctx).apply {
                        text = value; textSize = 20f
                        setTextColor(Color.parseColor(PRIMARY))
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        gravity = Gravity.CENTER
                    })
                    addView(TextView(ctx).apply {
                        text = caption; textSize = 11f
                        setTextColor(Color.parseColor(ON_SURFACE_VARIANT))
                        gravity = Gravity.CENTER
                    })
                })
            })
        }
    }
}

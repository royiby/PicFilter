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
 * Buttons, in the five roles this app has.
 *
 * Extensions on [Ui] rather than members of it. Same package as every
 * caller, so `Ui.foo(...)` still resolves with no import and not a single
 * call site changed. Ui itself keeps the tokens -- spacing, radius, colour
 * roles, type scale -- which are the part worth reading end to end.
 */

// ---------- buttons ----------

/**
 * Which role a button plays. Only one [Ui.Btn.PRIMARY] per screen. [Ui.Btn.TEXT]
 * is the quiet way out of a decision (skip); [Ui.Btn.LINK] is the guide's text
 * button, a primary-colored label with no container.
 */

internal fun Ui.pill(ctx: Context, label: String, role: Ui.Btn, onClick: () -> Unit) =
    MaterialButton(ctx).apply {
        text = label
        isAllCaps = false
        typeface = font(ctx, Ui.Type.LABEL.weight)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.Type.LABEL.size)
        letterSpacing = Ui.Type.LABEL.tracking / Ui.Type.LABEL.size
        with(ctx) {
            cornerRadius = dp(TOUCH) / 2
            insetTop = 0; insetBottom = 0
            setPaddingRelative(dp(SM), 0, dp(SM), 0)
        }
        when (role) {
            Ui.Btn.PRIMARY -> {
                setTextColor(onPrimary(ctx))
                backgroundTintList =
                    android.content.res.ColorStateList.valueOf(primary(ctx))
            }
            Ui.Btn.SECONDARY -> {
                setTextColor(onSecondaryContainer(ctx))
                backgroundTintList =
                    android.content.res.ColorStateList.valueOf(secondaryContainer(ctx))
            }
            Ui.Btn.OUTLINED -> {
                setTextColor(onSurface(ctx))
                backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                strokeColor = android.content.res.ColorStateList.valueOf(outline(ctx))
                with(ctx) { strokeWidth = dp(1) }
            }
            Ui.Btn.TEXT -> {
                setTextColor(onSurfaceVariant(ctx))
                backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            }
            Ui.Btn.LINK -> {
                setTextColor(primary(ctx))
                backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            }
        }
        setOnClickListener { onClick() }
    }

/** One of several buttons sharing a row. */
fun Ui.filled(ctx: Context, label: String, role: Ui.Btn, onClick: () -> Unit) =
    pill(ctx, label, role, onClick).apply {
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(0, dp(TOUCH), 1f)
                .apply { setMarginsRelative(dp(XXS), 0, dp(XXS), 0) }
        }
    }

/** Full-width button, for the one decision a screen is asking for. */
fun Ui.wideButton(ctx: Context, label: String, role: Ui.Btn, onClick: () -> Unit) =
    pill(ctx, label, role, onClick).apply {
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TOUCH)
            ).apply { setMarginsRelative(dp(MD), dp(XXS), dp(MD), dp(XXS)) }
        }
    }

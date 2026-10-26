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
 * Text, and the one place that decides what a size means.
 *
 * Extensions on [Ui] rather than members of it. Same package as every
 * caller, so `Ui.foo(...)` still resolves with no import and not a single
 * call site changed. Ui itself keeps the tokens -- spacing, radius, colour
 * roles, type scale -- which are the part worth reading end to end.
 */

// ---------- text blocks ----------

fun Ui.title(ctx: Context, t: String, style: Ui.Type = Ui.Type.HEADLINE): TextView =
    text(ctx, style, t).apply {
        with(ctx) { setPaddingRelative(dp(MD), dp(MD), dp(MD), dp(XS)) }
    }

fun Ui.body(ctx: Context, t: String, muted: Boolean = true): TextView =
    text(ctx, Ui.Type.BODY, t,
        if (muted) R.color.pk_on_surface_variant else R.color.pk_on_surface).apply {
        with(ctx) { setPaddingRelative(dp(MD), 0, dp(MD), dp(MD)) }
    }

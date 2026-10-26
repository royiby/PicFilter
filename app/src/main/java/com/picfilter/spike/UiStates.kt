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
 * Telling the user what is true: a fact, a state, or nothing at all.
 *
 * Extensions on [Ui] rather than members of it. Same package as every
 * caller, so `Ui.foo(...)` still resolves with no import and not a single
 * call site changed. Ui itself keeps the tokens -- spacing, radius, colour
 * roles, type scale -- which are the part worth reading end to end.
 */

// ---------- facts and states ----------

/**
 * An icon and one line. Three of these say what a paragraph was saying,
 * and the eye can skip the ones it already knows.
 */
fun Ui.factRow(
    ctx: Context, icon: Int, t: CharSequence,
    @ColorRes tint: Int = R.color.pk_on_surface_variant,
): View = factRow(ctx, icon, text(ctx, Ui.Type.BODY, t), tint)

/** The same row, with a line the caller keeps a handle on and fills in later. */
fun Ui.factRow(
    ctx: Context, icon: Int, line: TextView,
    @ColorRes tint: Int = R.color.pk_on_surface_variant,
): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    with(ctx) {
        setPaddingRelative(dp(MD), dp(XS), dp(MD), dp(XS))
        addView(ImageView(ctx).apply {
            // Decorative: the line beside it is the content, and an
            // unlabelled icon is just an extra stop that says nothing.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(color(ctx, tint))
            layoutParams = LinearLayout.LayoutParams(dp(ICON), dp(ICON))
                .apply { marginEnd = dp(SM) }
        })
        addView(line)
    }
}

/**
 * One folder and what happens to it, as a row: the backup step is two
 * states to compare, not five sentences to read.
 */
fun Ui.stateRow(
    ctx: Context, icon: Int, name: String, state: String, good: Boolean,
    tintIcon: Boolean = true,
): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    with(ctx) {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMarginsRelative(dp(MD), dp(XXS), dp(MD), dp(XXS)) }
        setPaddingRelative(dp(SM), dp(SM), dp(SM), dp(SM))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(R_MD).toFloat()
            setColor(if (good) secondaryContainer(ctx) else surfaceVariant(ctx))
        }
        val on = if (good) onSecondaryContainer(ctx) else onSurfaceVariant(ctx)
        addView(ImageView(ctx).apply {
            setImageResource(icon)
            // The mark is three brand colors; tinting it would flatten it.
            if (tintIcon) imageTintList =
                android.content.res.ColorStateList.valueOf(on)
            layoutParams = LinearLayout.LayoutParams(dp(ICON), dp(ICON))
                .apply { marginEnd = dp(SM) }
        })
        addView(text(ctx, Ui.Type.TITLE_SMALL, name,
            if (good) R.color.pk_on_secondary_container else R.color.pk_on_surface).apply {
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            // These names are folder names, so they stay Latin in every
            // language -- "PixKin", "WhatsApp Images". The default text
            // alignment resolves from the first strong character, which is
            // Latin, so in Hebrew the name sat hard against the state label
            // on the left while the row's own start was on the right. Align
            // to the VIEW's start instead of the text's.
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        addView(text(ctx, Ui.Type.LABEL, state,
            if (good) R.color.pk_on_secondary_container
            else R.color.pk_on_surface_variant))
        addView(ImageView(ctx).apply {
            setImageResource(if (good) R.drawable.ic_check else R.drawable.ic_block)
            imageTintList = android.content.res.ColorStateList.valueOf(on)
            layoutParams = LinearLayout.LayoutParams(dp(ICON), dp(ICON))
                .apply { marginStart = dp(XS) }
        })
    }
}

/** The mark on its own, for the top of a step. */
fun Ui.mark(ctx: Context, sizeDp: Int = 72): View = ImageView(ctx).apply {
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    setImageResource(R.drawable.ic_logo_mark)
    with(ctx) {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            .apply { setMarginsRelative(dp(MD), dp(XS), dp(MD), dp(XS)) }
    }
}

// ---------- empty state ----------

/** The mark, a line of text and one action — the guide's empty state. */
fun Ui.emptyState(
    ctx: Context, line: String, action: String? = null, onAction: () -> Unit = {},
): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    setBackgroundColor(surfaceVariant(ctx))
    with(ctx) {
        setPaddingRelative(dp(LG), dp(XL), dp(LG), dp(XL))
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_logo_mark)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        })
        addView(text(ctx, Ui.Type.TITLE_SMALL, line).apply {
            gravity = Gravity.CENTER
            setPaddingRelative(0, dp(MD), 0, 0)
        })
        if (action != null) addView(wideButton(ctx, action, Ui.Btn.PRIMARY, onAction).apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(MD), 0, 0)
        })
    }
}

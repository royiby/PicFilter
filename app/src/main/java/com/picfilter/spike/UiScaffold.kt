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
 * The frame every screen sits in: the root column, the bars at the top, and the insets that keep content clear of the system.
 *
 * Extensions on [Ui] rather than members of it. Same package as every
 * caller, so `Ui.foo(...)` still resolves with no import and not a single
 * call site changed. Ui itself keeps the tokens -- spacing, radius, colour
 * roles, type scale -- which are the part worth reading end to end.
 */

// ---------- screen scaffolding ----------

fun Ui.screen(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(background(ctx))
}

/** Edge-to-edge without letting system bars eat the controls. */
fun Ui.applyInsets(root: View, ctx: Context, extraBottom: Int = 0) {
    with(ctx) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPaddingRelative(b.left, b.top, b.right, b.bottom + dp(extraBottom))
            insets
        }
    }
}

/**
 * Home top bar: the logo lockup carries the brand, so there is no title and
 * no colored bar. The drawable switches itself in dark mode.
 */
fun Ui.logoBar(ctx: Context): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setBackgroundColor(surface(ctx))
    with(ctx) {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(TOP_BAR))
        setPaddingRelative(dp(MD), 0, dp(MD), 0)
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_logo_wordmark)
            adjustViewBounds = true
            contentDescription = ctx.getString(R.string.logo_description)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(LOGO))
        })
    }
}

/** Inner screens: back arrow, a title, and at most one action on the right. */
/**
 * [actionLabel] is what a screen reader says for the icon on the right.
 * An unlabelled ImageView is announced as "button" and nothing else, which
 * in this app meant the only way to switch between the grid and the single
 * card was invisible to anyone using TalkBack.
 */
fun Ui.titleBar(
    ctx: Context, title: String, actionIcon: Int? = null,
    actionLabel: String = ctx.getString(R.string.a11y_more),
    onAction: () -> Unit = {}, onBack: () -> Unit,
): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setBackgroundColor(surface(ctx))
    with(ctx) {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(TOP_BAR))
        setPaddingRelative(dp(XS), 0, dp(MD), 0)
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = android.content.res.ColorStateList.valueOf(onSurfaceVariant(ctx))
            contentDescription = ctx.getString(R.string.a11y_back)
            layoutParams = LinearLayout.LayoutParams(dp(TOUCH), dp(TOUCH))
            val p = (dp(TOUCH) - dp(ICON)) / 2
            setPaddingRelative(p, p, p, p)
            isClickable = true
            isFocusable = true
            setOnClickListener { onBack() }
        })
        addView(text(ctx, Ui.Type.TITLE, title).apply {
            setPaddingRelative(dp(XS), 0, 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (actionIcon != null) addView(ImageView(ctx).apply {
            setImageResource(actionIcon)
            imageTintList = android.content.res.ColorStateList.valueOf(onSurfaceVariant(ctx))
            contentDescription = actionLabel
            layoutParams = LinearLayout.LayoutParams(dp(TOUCH), dp(TOUCH))
            val p = (dp(TOUCH) - dp(ICON)) / 2
            setPaddingRelative(p, p, p, p)
            isClickable = true
            isFocusable = true
            setOnClickListener { onAction() }
        })
    }
}

/**
 * A square tile in the photo grid: no rounding inside the grid, a kept
 * badge when PixKin saved it, and dimmed when it is one of the ones being
 * left behind — never red, which would read as an error.
 */
fun Ui.gridTile(
    ctx: Context, side: Int, kept: Boolean, dimmed: Boolean,
    label: String = ctx.getString(R.string.a11y_photo),
): FrameLayout =
    FrameLayout(ctx).apply {
        // The tile is the tappable thing, so it carries the description and
        // its children are hidden from the reader -- otherwise the badge and
        // the image are announced as two more nameless nodes.
        contentDescription = label
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        // Plain ViewGroup params: a RecyclerView wraps these into its own,
        // and the gaps are an ItemDecoration rather than margins now.
        layoutParams = ViewGroup.LayoutParams(side, side)
        addView(ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(surfaceVariant(ctx))
            layoutParams = FrameLayout.LayoutParams(side, side)
            alpha = if (dimmed) 0.4f else 1f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        if (kept) addView(keptBadge(ctx))
        isClickable = true
        isFocusable = true
    }

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
 * Photos as furniture: thumbnails and tiles.
 *
 * Extensions on [Ui] rather than members of it. Same package as every
 * caller, so `Ui.foo(...)` still resolves with no import and not a single
 * call site changed. Ui itself keeps the tokens -- spacing, radius, colour
 * roles, type scale -- which are the part worth reading end to end.
 */

// ---------- photos ----------

/**
 * Decorative by default.
 *
 * A photo is only meaningful to a screen reader if something can describe
 * it, and PixKin cannot describe someone's picture. The screens that DO
 * know what a photo is -- the review card knows whose face matched and at
 * what similarity -- set a description and turn this back on.
 */
fun Ui.photo(ctx: Context): ShapeableImageView = ShapeableImageView(ctx).apply {
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    with(ctx) {
        shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCorners(CornerFamily.ROUNDED, dp(R_MD).toFloat()).build()
    }
    scaleType = ImageView.ScaleType.CENTER_CROP
    setBackgroundColor(surfaceVariant(ctx))
}

fun Ui.thumb(ctx: Context, sizeDp: Int): ShapeableImageView = photo(ctx).apply {
    with(ctx) {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            .apply { setMarginsRelative(0, 0, dp(XS), 0) }
        shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCorners(CornerFamily.ROUNDED, dp(R_SM).toFloat()).build()
    }
}

/** Square tile that shares the row width evenly with its siblings. */
fun Ui.previewTile(ctx: Context): ShapeableImageView = photo(ctx).apply {
    with(ctx) {
        layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f)
            .apply { setMarginsRelative(0, 0, dp(XS), 0) }
        shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCorners(CornerFamily.ROUNDED, dp(R_SM).toFloat()).build()
    }
}

/** A photo PixKin kept: a check on secondaryContainer, bottom-right. */
fun Ui.keptBadge(ctx: Context): View = ImageView(ctx).apply {
    // Decorative: gridTile's own description already says the photo is saved.
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    setImageResource(R.drawable.ic_check)
    imageTintList = android.content.res.ColorStateList.valueOf(onSecondaryContainer(ctx))
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(secondaryContainer(ctx))
    }
    with(ctx) {
        val s = dp(20)
        layoutParams = FrameLayout.LayoutParams(s, s, Gravity.END or Gravity.BOTTOM)
            .apply { setMarginsRelative(0, 0, dp(XXS), dp(XXS)) }
        val p = dp(XXS) / 2
        setPaddingRelative(p, p, p, p)
    }
}

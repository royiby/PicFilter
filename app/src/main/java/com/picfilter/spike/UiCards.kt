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
 * Cards: the things on the home screen that preview what is inside them.
 *
 * Extensions on [Ui] rather than members of it. Same package as every
 * caller, so `Ui.foo(...)` still resolves with no import and not a single
 * call site changed. Ui itself keeps the tokens -- spacing, radius, colour
 * roles, type scale -- which are the part worth reading end to end.
 */

// ---------- cards ----------

/**
 * A whole-card tap target. Tonal surface with an outlineVariant border
 * rather than a shadow; [accent] marks the one highlighted row on a screen.
 */
fun Ui.actionCard(
    ctx: Context, label: String, supporting: String, accent: Boolean = false,
    onClick: () -> Unit,
): View = MaterialCardView(ctx).apply {
    with(ctx) {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMarginsRelative(dp(MD), dp(XXS), dp(MD), dp(XXS)) }
        radius = dp(R_MD).toFloat()
        cardElevation = 0f
        strokeWidth = if (accent) 0 else dp(1)
        strokeColor = outlineVariant(ctx)
        setCardBackgroundColor(if (accent) primaryContainer(ctx) else surface(ctx))
        isClickable = true
        isFocusable = true
        // The card is the tappable node, so it says the whole thing in one
        // stop. A reader focusing a clickable ViewGroup will stitch the
        // child text together on its own, but the result depends on the
        // reader; this does not.
        contentDescription = if (supporting.isEmpty()) label
            else ctx.getString(R.string.a11y_card, label, supporting)
        isScreenReaderFocusable = true
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(MD), dp(MD), dp(MD), dp(MD))
            addView(text(ctx, Ui.Type.TITLE_SMALL, label,
                if (accent) R.color.pk_on_primary_container else R.color.pk_on_surface))
            if (supporting.isNotEmpty()) addView(text(ctx, Ui.Type.BODY_SMALL, supporting,
                if (accent) R.color.pk_on_primary_container
                else R.color.pk_on_surface_variant
            ).apply { setPaddingRelative(0, dp(XXS), 0, 0) })
        })
    }
    setOnClickListener { onClick() }
}

/** An [actionCard] with an empty thumbnail row the caller fills in later. */

fun Ui.previewCard(
    ctx: Context, label: String, supporting: String, onClick: () -> Unit,
): Ui.Preview {
    val card = actionCard(ctx, label, supporting, onClick = onClick) as MaterialCardView
    val column = card.getChildAt(0) as LinearLayout
    val strip = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMarginsRelative(0, dp(SM), 0, 0) }
        }
        visibility = View.GONE          // stays hidden until a thumbnail lands
    }
    column.addView(strip)
    return Ui.Preview(card, strip)
}

/**
 * The auto-rescue switch, as one cell of the stat row.
 *
 * It sits between the two numbers it governs: what has been saved, and what
 * has been held back. A check when it is on, a block when it is off, and
 * two words -- the card used to spend two lines of prose explaining itself
 * in a place where the eye is reading counts.
 */
fun Ui.toggleCell(ctx: Context, on: Boolean, onToggle: () -> Unit): View =
    MaterialCardView(ctx).apply {
        with(ctx) {
            // WRAP_CONTENT, so this cell sets the row height: its caption
            // is the longest text in the row in every language.
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMarginsRelative(dp(XXS), 0, dp(XXS), 0) }
            radius = dp(R_MD).toFloat()
            cardElevation = 0f
            strokeWidth = if (on) 0 else dp(1)
            strokeColor = outlineVariant(ctx)
            setCardBackgroundColor(if (on) primaryContainer(ctx) else surfaceVariant(ctx))
            isClickable = true
            isFocusable = true
            contentDescription = ctx.getString(
                if (on) R.string.autosave_on_description
                else R.string.autosave_off_description)
            isScreenReaderFocusable = true
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                // Fills the card, so a MATCH_PARENT caption has a width to
                // wrap against. A WRAP_CONTENT column is sized by its
                // widest child, which is the caption -- so the caption
                // never wrapped, it just overflowed and was clipped.
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                setPaddingRelative(dp(XS), dp(SM), dp(XS), dp(SM))
                addView(ImageView(ctx).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setImageResource(if (on) R.drawable.ic_check else R.drawable.ic_block)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        if (on) onPrimaryContainer(ctx) else onSurfaceVariant(ctx))
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                })
                addView(text(ctx, Ui.Type.CAPTION,
                    ctx.getString(if (on) R.string.autosave_on else R.string.autosave_off),
                    if (on) R.color.pk_on_primary_container else R.color.pk_on_surface_variant
                ).apply {
                    gravity = Gravity.CENTER
                    // "Auto-save on" is two words; the Hebrew is five, and
                    // the Spanish is "Guardado automático activado". A cell
                    // a third of the screen wide cannot hold all of those
                    // at one size, and the first attempt simply clipped the
                    // Hebrew to its first word with no ellipsis to admit
                    // it. So the caption is allowed two lines AND allowed
                    // to shrink: whatever the locale, it fits.
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                    maxLines = 2
                    androidx.core.widget.TextViewCompat
                        .setAutoSizeTextTypeUniformWithConfiguration(
                            this, 8, Ui.Type.CAPTION.size.toInt(), 1,
                            TypedValue.COMPLEX_UNIT_SP)
                    setPaddingRelative(0, dp(XXS), 0, 0)
                })
            })
            setOnClickListener { onToggle() }
        }
    }

/**
 * Shorten a count so it always fits a stat cell.
 *
 * Probed on the device: six digits reaches the cell's padding with nothing
 * to spare, and "148912" is not a number anyone reads at a glance. Four
 * characters always fit and always read.
 */
fun Ui.shortCount(n: Int): String {
    fun scaled(v: Double, suffix: String): String {
        // One decimal below ten, none above: 1.1K, 11K, 148K.
        val r = if (v < 10) Math.round(v * 10) / 10.0 else Math.round(v).toDouble()
        return if (r % 1.0 == 0.0) "${r.toInt()}$suffix" else "%.1f%s".format(r, suffix)
    }
    return when {
        n < 1_000 -> n.toString()
        // 999_500 and up round to 1000K, which is both wrong and five
        // characters wide. The boundary has to account for the rounding,
        // not sit where the arithmetic suggests.
        n < 999_500 -> scaled(n / 1_000.0, "K")
        else -> scaled(n / 1_000_000.0, "M")
    }
}

/** A single counted pill, for building a row by hand. */
fun Ui.statCell(ctx: Context, count: Int, caption: String): View {
    val value = shortCount(count)
    return MaterialCardView(ctx).apply {
        // The exact figure stays available to a screen reader.
        contentDescription = ctx.getString(R.string.a11y_stat, count, caption)
        isScreenReaderFocusable = true
        with(ctx) {
            // MATCH_PARENT: stretches to whatever the tallest cell needs,
            // so the three cards stay the same height in every locale.
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            ).apply { setMarginsRelative(dp(XXS), 0, dp(XXS), 0) }
            radius = dp(R_MD).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = outlineVariant(ctx)
            setCardBackgroundColor(surfaceVariant(ctx))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                setPaddingRelative(dp(XS), dp(SM), dp(XS), dp(SM))
                addView(text(ctx, Ui.Type.HEADLINE, value).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
                addView(text(ctx, Ui.Type.CAPTION, caption, R.color.pk_on_surface_variant)
                    .apply {
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT)
                        maxLines = 2
                        androidx.core.widget.TextViewCompat
                            .setAutoSizeTextTypeUniformWithConfiguration(
                                this, 8, Ui.Type.CAPTION.size.toInt(), 1,
                                TypedValue.COMPLEX_UNIT_SP)
                    })
            })
        }
    }
}

/** A row of cells built by the caller, sharing the width evenly. */
fun Ui.cellRow(ctx: Context, cells: List<View>): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    // Baselines differ between a big number and a small caption; aligning
    // them would push the cards out of line with each other.
    isBaselineAligned = false
    with(ctx) { setPaddingRelative(dp(SM), dp(XXS), dp(SM), dp(SM)) }
    for (c in cells) addView(c)
}

/** Pills showing a count each, like the stats block in the guide. */
fun Ui.statRow(ctx: Context, pairs: List<Pair<String, String>>): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    with(ctx) { setPaddingRelative(dp(MD), dp(XXS), dp(MD), dp(SM)) }
    for ((i, p) in pairs.withIndex()) {
        val (value, caption) = p
        addView(MaterialCardView(ctx).apply {
            // "48, backed up" as one stop rather than a number and then a
            // word, which is what a reader makes of two stacked TextViews.
            contentDescription = ctx.getString(R.string.a11y_stat_text, value, caption)
            isScreenReaderFocusable = true
            with(ctx) {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    setMarginsRelative(if (i == 0) 0 else dp(XXS), 0,
                        if (i == pairs.size - 1) 0 else dp(XXS), 0)
                }
                radius = dp(R_MD).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = outlineVariant(ctx)
                setCardBackgroundColor(surfaceVariant(ctx))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPaddingRelative(dp(XS), dp(SM), dp(XS), dp(SM))
                    addView(text(ctx, Ui.Type.HEADLINE, value).apply {
                        gravity = Gravity.CENTER
                    })
                    addView(text(ctx, Ui.Type.CAPTION, caption,
                        R.color.pk_on_surface_variant).apply {
                        gravity = Gravity.CENTER
                    })
                })
            }
        })
    }
}

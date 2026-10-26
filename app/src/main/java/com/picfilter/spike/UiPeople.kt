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
 * The people row, which is the home screen's real control.
 *
 * Extensions on [Ui] rather than members of it. Same package as every
 * caller, so `Ui.foo(...)` still resolves with no import and not a single
 * call site changed. Ui itself keeps the tokens -- spacing, radius, colour
 * roles, type scale -- which are the part worth reading end to end.
 */

// ---------- people ("Kin") ----------

/** Circular image, for a face crop. */
fun Ui.circle(ctx: Context, sizeDp: Int): ShapeableImageView = ShapeableImageView(ctx).apply {
    // personCell announces the name; the crop and the monogram behind it
    // would otherwise be two extra stops with nothing to say.
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    with(ctx) {
        layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
        shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCorners(CornerFamily.ROUNDED, dp(sizeDp) / 2f).build()
    }
    scaleType = ImageView.ScaleType.CENTER_CROP
}

/** Initials in a disc — what a person without a face crop gets. */
fun Ui.monogram(ctx: Context, sizeDp: Int, name: String): TextView =
    text(ctx, Ui.Type.TITLE, name.trim().firstOrNull()?.uppercase() ?: "?",
        R.color.pk_on_primary_container).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(primaryContainer(ctx))
        }
        with(ctx) { layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp)) }
    }

/** One entry in the people row. [thumbPaths] are face crops on disk,
 *  biggest first; the cleanest of them becomes the avatar. */
/** [facePath] is already chosen; see [Clusterer.cleanestCrop]. */

/**
 * The people whose photos PixKin keeps, as face circles. Crops are decoded
 * off the main thread; until one arrives (or if there is none) the circle
 * shows the person's initial.
 */
/** The people whose photos PixKin keeps. Add lives in [sectionHeader]. */
/**
 * A labelled section with one action on the right.
 *
 * Add used to be a 56dp circle with a caption, sitting in a row of 56dp
 * circles with captions -- so it read as a person called "Add". Moving it
 * into a header makes it a control rather than a face, gives the row the
 * name it never had, and fixes its position whatever the row is doing.
 */
fun Ui.sectionHeader(
    ctx: Context, title: String, actionLabel: String,
    actionDescription: String? = null, onAction: () -> Unit,
): View = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    with(ctx) { setPaddingRelative(dp(MD), dp(SM), dp(XS), 0) }
    addView(text(ctx, Ui.Type.TITLE_SMALL, title).apply {
        layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    addView(MaterialButton(ctx, null, android.R.attr.borderlessButtonStyle).apply {
        text = actionLabel
        // The button's own text is terse by design; a reader gets the
        // longer version, which says what the action adds someone to.
        actionDescription?.let { contentDescription = it }
        setTextColor(primary(ctx))
        typeface = font(ctx, 600)
        isAllCaps = false
        with(ctx) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.Type.LABEL.size)
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPaddingRelative(dp(SM), dp(XS), dp(SM), dp(XS))
        }
        setOnClickListener { onAction() }
    })
}

fun Ui.peopleRow(
    ctx: Context, faces: List<Ui.Face>,
    onPerson: (Int) -> Unit,
): View {
    val strip = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        with(ctx) { setPaddingRelative(dp(MD), dp(XXS), dp(MD), dp(XS)) }
    }
    for ((i, f) in faces.withIndex()) strip.addView(personCell(ctx, f) { onPerson(i) })
    return HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        // A half-scrolled face reads as "there is more" rather than as a
        // clipped mistake.
        isHorizontalFadingEdgeEnabled = true
        with(ctx) { setFadingEdgeLength(dp(LG)) }
        addView(strip)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

internal fun Ui.cellColumn(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    with(ctx) {
        layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMarginsRelative(0, 0, dp(XS), 0) }
    }
    isClickable = true
    isFocusable = true
}

internal fun Ui.caption(ctx: Context, t: String, @ColorRes colour: Int): TextView =
    text(ctx, Ui.Type.CAPTION, t, colour).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

internal fun Ui.personCell(ctx: Context, f: Ui.Face, onClick: () -> Unit): View {
    @Suppress("NAME_SHADOWING")
    val size = 56
    val stack = FrameLayout(ctx).apply {
        with(ctx) { layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)) }
        addView(monogram(ctx, size, f.name))
    }
    val img = circle(ctx, size)
    stack.addView(img)
    // Coil, like every other image in the app. This was the last bare
    // thread: it decoded up to six crops to pick the cleanest, every time
    // the home screen was drawn, with no cache. The picking moved to the
    // background pass that already reads this data, and the loading to the
    // loader that already has a memory cache.
    // A file:// Uri rather than a File: Coil stats a File on the calling
    // thread while building the request, and that calling thread is the one
    // drawing the frame. One millisecond, but StrictMode is right about it
    // and the Uri costs nothing.
    f.facePath?.let { img.load("file://$it") { size(FACE_PX) } }

    return cellColumn(ctx).apply {
        addView(stack)
        addView(text(ctx, Ui.Type.TITLE_SMALL, f.name).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            with(ctx) { setPaddingRelative(0, dp(XXS), 0, 0) }
        })
        if (f.caption.isNotEmpty())
            addView(caption(ctx, f.caption, R.color.pk_on_surface_variant))
        // One stop that says who it is and what tapping does, instead of
        // three nameless nodes and a count.
        contentDescription = if (f.caption.isEmpty())
            ctx.getString(R.string.a11y_person_opens, f.name)
        else ctx.getString(R.string.a11y_person_with_caption, f.name, f.caption)
        isScreenReaderFocusable = true
        setOnClickListener { onClick() }
    }
}

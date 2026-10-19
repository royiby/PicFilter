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
        } catch (e: Exception) { null } ?: Typeface.SANS_SERIF
        Typeface.create(base, weight, false)
    }

    /** A TextView carrying one step of the scale. Sentence case, never all-caps. */
    fun text(
        ctx: Context, style: Type, t: CharSequence = "",
        @ColorRes colour: Int = R.color.pk_on_surface,
    ): TextView = TextView(ctx).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style.size)
        setLineSpacing(0f, style.line / style.size)
        letterSpacing = style.tracking / style.size
        typeface = font(ctx, style.weight)
        setTextColor(color(ctx, colour))
    }

    // ---------- screen scaffolding ----------

    fun screen(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(background(ctx))
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

    /**
     * Home top bar: the logo lockup carries the brand, so there is no title and
     * no colored bar. The drawable switches itself in dark mode.
     */
    fun logoBar(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(surface(ctx))
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TOP_BAR))
            setPadding(dp(MD), 0, dp(MD), 0)
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_logo_wordmark)
                adjustViewBounds = true
                contentDescription = ctx.getString(R.string.logo_description)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(LOGO))
            })
        }
    }

    /** Inner screens: back arrow and a title, no logo. */
    fun titleBar(ctx: Context, title: String, onBack: () -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(surface(ctx))
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TOP_BAR))
            setPadding(dp(XS), 0, dp(MD), 0)
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_arrow_back)
                imageTintList = android.content.res.ColorStateList.valueOf(onSurfaceVariant(ctx))
                contentDescription = "Back"
                layoutParams = LinearLayout.LayoutParams(dp(TOUCH), dp(TOUCH))
                val p = (dp(TOUCH) - dp(ICON)) / 2
                setPadding(p, p, p, p)
                isClickable = true
                isFocusable = true
                setOnClickListener { onBack() }
            })
            addView(text(ctx, Type.TITLE, title).apply {
                setPadding(dp(XS), 0, 0, 0)
            })
        }
    }

    // ---------- text blocks ----------

    fun title(ctx: Context, t: String, style: Type = Type.HEADLINE): TextView =
        text(ctx, style, t).apply {
            with(ctx) { setPadding(dp(MD), dp(MD), dp(MD), dp(XS)) }
        }

    fun body(ctx: Context, t: String, muted: Boolean = true): TextView =
        text(ctx, Type.BODY, t,
            if (muted) R.color.pk_on_surface_variant else R.color.pk_on_surface).apply {
            with(ctx) { setPadding(dp(MD), 0, dp(MD), dp(MD)) }
        }

    // ---------- cards ----------

    /**
     * A whole-card tap target. Tonal surface with an outlineVariant border
     * rather than a shadow; [accent] marks the one highlighted row on a screen.
     */
    fun actionCard(
        ctx: Context, label: String, supporting: String, accent: Boolean = false,
        onClick: () -> Unit,
    ): View = MaterialCardView(ctx).apply {
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(MD), dp(XXS), dp(MD), dp(XXS)) }
            radius = dp(R_MD).toFloat()
            cardElevation = 0f
            strokeWidth = if (accent) 0 else dp(1)
            strokeColor = outlineVariant(ctx)
            setCardBackgroundColor(if (accent) primaryContainer(ctx) else surface(ctx))
            isClickable = true
            isFocusable = true
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(MD), dp(MD), dp(MD), dp(MD))
                addView(text(ctx, Type.TITLE_SMALL, label,
                    if (accent) R.color.pk_on_primary_container else R.color.pk_on_surface))
                if (supporting.isNotEmpty()) addView(text(ctx, Type.BODY_SMALL, supporting,
                    if (accent) R.color.pk_on_primary_container
                    else R.color.pk_on_surface_variant
                ).apply { setPadding(0, dp(XXS), 0, 0) })
            })
        }
        setOnClickListener { onClick() }
    }

    /** An [actionCard] with an empty thumbnail row the caller fills in later. */
    class Preview(val view: View, val strip: LinearLayout)

    fun previewCard(
        ctx: Context, label: String, supporting: String, onClick: () -> Unit,
    ): Preview {
        val card = actionCard(ctx, label, supporting, onClick = onClick) as MaterialCardView
        val column = card.getChildAt(0) as LinearLayout
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            with(ctx) {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(SM), 0, 0) }
            }
            visibility = View.GONE          // stays hidden until a thumbnail lands
        }
        column.addView(strip)
        return Preview(card, strip)
    }

    /** Pills showing a count each, like the stats block in the guide. */
    fun statRow(ctx: Context, pairs: List<Pair<String, String>>): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        with(ctx) { setPadding(dp(MD), dp(XXS), dp(MD), dp(SM)) }
        for ((i, p) in pairs.withIndex()) {
            val (value, caption) = p
            addView(MaterialCardView(ctx).apply {
                with(ctx) {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ).apply {
                        setMargins(if (i == 0) 0 else dp(XXS), 0,
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
                        setPadding(dp(XS), dp(SM), dp(XS), dp(SM))
                        addView(text(ctx, Type.HEADLINE, value).apply {
                            gravity = Gravity.CENTER
                        })
                        addView(text(ctx, Type.CAPTION, caption,
                            R.color.pk_on_surface_variant).apply {
                            gravity = Gravity.CENTER
                        })
                    })
                }
            })
        }
    }

    // ---------- buttons ----------

    /**
     * Which role a button plays. Only one [Btn.PRIMARY] per screen. [Btn.TEXT]
     * is the quiet way out of a decision (skip); [Btn.LINK] is the guide's text
     * button, a primary-colored label with no container.
     */
    enum class Btn { PRIMARY, SECONDARY, OUTLINED, TEXT, LINK }

    private fun pill(ctx: Context, label: String, role: Btn, onClick: () -> Unit) =
        MaterialButton(ctx).apply {
            text = label
            isAllCaps = false
            typeface = font(ctx, Type.LABEL.weight)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL.size)
            letterSpacing = Type.LABEL.tracking / Type.LABEL.size
            with(ctx) {
                cornerRadius = dp(TOUCH) / 2
                insetTop = 0; insetBottom = 0
                setPadding(dp(SM), 0, dp(SM), 0)
            }
            when (role) {
                Btn.PRIMARY -> {
                    setTextColor(onPrimary(ctx))
                    backgroundTintList =
                        android.content.res.ColorStateList.valueOf(primary(ctx))
                }
                Btn.SECONDARY -> {
                    setTextColor(onSecondaryContainer(ctx))
                    backgroundTintList =
                        android.content.res.ColorStateList.valueOf(secondaryContainer(ctx))
                }
                Btn.OUTLINED -> {
                    setTextColor(onSurface(ctx))
                    backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                    strokeColor = android.content.res.ColorStateList.valueOf(outline(ctx))
                    with(ctx) { strokeWidth = dp(1) }
                }
                Btn.TEXT -> {
                    setTextColor(onSurfaceVariant(ctx))
                    backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                }
                Btn.LINK -> {
                    setTextColor(primary(ctx))
                    backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                }
            }
            setOnClickListener { onClick() }
        }

    /** One of several buttons sharing a row. */
    fun filled(ctx: Context, label: String, role: Btn, onClick: () -> Unit) =
        pill(ctx, label, role, onClick).apply {
            with(ctx) {
                layoutParams = LinearLayout.LayoutParams(0, dp(TOUCH), 1f)
                    .apply { setMargins(dp(XXS), 0, dp(XXS), 0) }
            }
        }

    /** Full-width button, for the one decision a screen is asking for. */
    fun wideButton(ctx: Context, label: String, role: Btn, onClick: () -> Unit) =
        pill(ctx, label, role, onClick).apply {
            with(ctx) {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(TOUCH)
                ).apply { setMargins(dp(MD), dp(XXS), dp(MD), dp(XXS)) }
            }
        }

    // ---------- photos ----------

    fun photo(ctx: Context): ShapeableImageView = ShapeableImageView(ctx).apply {
        with(ctx) {
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(R_MD).toFloat()).build()
        }
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(surfaceVariant(ctx))
    }

    fun thumb(ctx: Context, sizeDp: Int): ShapeableImageView = photo(ctx).apply {
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
                .apply { setMargins(0, 0, dp(XS), 0) }
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(R_SM).toFloat()).build()
        }
    }

    /** Square tile that shares the row width evenly with its siblings. */
    fun previewTile(ctx: Context): ShapeableImageView = photo(ctx).apply {
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f)
                .apply { setMargins(0, 0, dp(XS), 0) }
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(R_SM).toFloat()).build()
        }
    }

    /** A photo PixKin kept: a check on secondaryContainer, bottom-right. */
    fun keptBadge(ctx: Context): View = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_check)
        imageTintList = android.content.res.ColorStateList.valueOf(onSecondaryContainer(ctx))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(secondaryContainer(ctx))
        }
        with(ctx) {
            val s = dp(20)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.END or Gravity.BOTTOM)
                .apply { setMargins(0, 0, dp(XXS), dp(XXS)) }
            val p = dp(XXS) / 2
            setPadding(p, p, p, p)
        }
    }

    // ---------- people ("Kin") ----------

    /** Circular image, for a face crop. */
    fun circle(ctx: Context, sizeDp: Int): ShapeableImageView = ShapeableImageView(ctx).apply {
        with(ctx) {
            layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(sizeDp) / 2f).build()
        }
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    /** Initials in a disc — what a person without a face crop gets. */
    fun monogram(ctx: Context, sizeDp: Int, name: String): TextView =
        text(ctx, Type.TITLE, name.trim().firstOrNull()?.uppercase() ?: "?",
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
    data class Face(val name: String, val caption: String, val thumbPaths: List<String>)

    /**
     * The people whose photos PixKin keeps, as face circles. Crops are decoded
     * off the main thread; until one arrives (or if there is none) the circle
     * shows the person's initial.
     */
    fun peopleRow(ctx: Context, faces: List<Face>, onClick: () -> Unit): View {
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            with(ctx) { setPadding(dp(MD), dp(XXS), dp(MD), dp(XS)) }
        }
        for (f in faces) strip.addView(personCell(ctx, f, onClick))
        strip.addView(addCell(ctx, onClick))
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
    }

    private fun cellColumn(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, dp(XS), 0) }
        }
        isClickable = true
        isFocusable = true
    }

    private fun caption(ctx: Context, t: String, @ColorRes colour: Int): TextView =
        text(ctx, Type.CAPTION, t, colour).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun personCell(ctx: Context, f: Face, onClick: () -> Unit): View {
        val size = 56
        val stack = FrameLayout(ctx).apply {
            with(ctx) { layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)) }
            addView(monogram(ctx, size, f.name))
        }
        val img = circle(ctx, size)
        stack.addView(img)
        if (f.thumbPaths.isNotEmpty()) Thread {
            val bmp = bestCrop(f.thumbPaths)
            if (bmp != null) img.post { img.setImageBitmap(bmp) }
        }.start()

        return cellColumn(ctx).apply {
            addView(stack)
            addView(text(ctx, Type.TITLE_SMALL, f.name).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                with(ctx) { setPadding(0, dp(XXS), 0, 0) }
            })
            if (f.caption.isNotEmpty())
                addView(caption(ctx, f.caption, R.color.pk_on_surface_variant))
            setOnClickListener { onClick() }
        }
    }

    private fun addCell(ctx: Context, onClick: () -> Unit): View {
        val size = 56
        val plus = text(ctx, Type.HEADLINE, "+", R.color.pk_primary).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(surfaceVariant(ctx))
            }
            with(ctx) { layoutParams = ViewGroup.LayoutParams(dp(size), dp(size)) }
        }
        return cellColumn(ctx).apply {
            addView(plus)
            addView(text(ctx, Type.TITLE_SMALL, "Add",
                R.color.pk_on_surface_variant).apply {
                gravity = Gravity.CENTER
                with(ctx) { setPadding(0, dp(XXS), 0, 0) }
            })
            setOnClickListener { onClick() }
        }
    }

    /**
     * The crop that shows the most face. An aligned crop whose 112x112 window
     * ran off the edge of its photo is empty there, and JPEG stores that as
     * black — a wedge across the circle. Candidates arrive biggest first, so
     * take the first clean one and only keep looking while there is black.
     */
    private fun bestCrop(paths: List<String>): android.graphics.Bitmap? {
        var best: android.graphics.Bitmap? = null
        var bestScore = Float.MAX_VALUE
        for (path in paths) {
            val bmp = try {
                android.graphics.BitmapFactory.decodeFile(path)
            } catch (e: Exception) { null } ?: continue
            val score = blackBorder(bmp)
            if (score < bestScore) { best?.recycle(); best = bmp; bestScore = score }
            else bmp.recycle()
            if (bestScore <= 0.01f) break
        }
        return best
    }

    /** Fraction of the outer ring of [bmp] that is near-black. */
    private fun blackBorder(bmp: android.graphics.Bitmap): Float {
        val w = bmp.width; val h = bmp.height
        if (w < 8 || h < 8) return 1f
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val ring = maxOf(2, minOf(w, h) / 12)
        var dark = 0; var seen = 0
        for (y in 0 until h) {
            val edgeRow = y < ring || y >= h - ring
            for (x in 0 until w) {
                if (!edgeRow && x >= ring && x < w - ring) continue
                val p = px[y * w + x]
                seen++
                val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 +
                    (p and 0xFF) * 114) / 1000
                if (lum < 20) dark++
            }
        }
        return if (seen == 0) 1f else dark.toFloat() / seen
    }

    // ---------- empty state ----------

    /** The mark, a line of text and one action — the guide's empty state. */
    fun emptyState(
        ctx: Context, line: String, action: String? = null, onAction: () -> Unit = {},
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(surfaceVariant(ctx))
        with(ctx) {
            setPadding(dp(LG), dp(XL), dp(LG), dp(XL))
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_logo_mark)
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            })
            addView(text(ctx, Type.TITLE_SMALL, line).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(MD), 0, 0)
            })
            if (action != null) addView(wideButton(ctx, action, Btn.PRIMARY, onAction).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(MD), 0, 0)
            })
        }
    }
}

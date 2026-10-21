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
    private const val FACE_PX = 160


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
                v.setPaddingRelative(b.left, b.top, b.right, b.bottom + dp(extraBottom))
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
    fun titleBar(
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
            addView(text(ctx, Type.TITLE, title).apply {
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
    fun gridTile(
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

    // ---------- text blocks ----------

    fun title(ctx: Context, t: String, style: Type = Type.HEADLINE): TextView =
        text(ctx, style, t).apply {
            with(ctx) { setPaddingRelative(dp(MD), dp(MD), dp(MD), dp(XS)) }
        }

    fun body(ctx: Context, t: String, muted: Boolean = true): TextView =
        text(ctx, Type.BODY, t,
            if (muted) R.color.pk_on_surface_variant else R.color.pk_on_surface).apply {
            with(ctx) { setPaddingRelative(dp(MD), 0, dp(MD), dp(MD)) }
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
                addView(text(ctx, Type.TITLE_SMALL, label,
                    if (accent) R.color.pk_on_primary_container else R.color.pk_on_surface))
                if (supporting.isNotEmpty()) addView(text(ctx, Type.BODY_SMALL, supporting,
                    if (accent) R.color.pk_on_primary_container
                    else R.color.pk_on_surface_variant
                ).apply { setPaddingRelative(0, dp(XXS), 0, 0) })
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
                ).apply { setMarginsRelative(0, dp(SM), 0, 0) }
            }
            visibility = View.GONE          // stays hidden until a thumbnail lands
        }
        column.addView(strip)
        return Preview(card, strip)
    }

    /**
     * The auto-rescue switch, as one cell of the stat row.
     *
     * It sits between the two numbers it governs: what has been saved, and what
     * has been held back. A check when it is on, a block when it is off, and
     * two words -- the card used to spend two lines of prose explaining itself
     * in a place where the eye is reading counts.
     */
    fun toggleCell(ctx: Context, on: Boolean, onToggle: () -> Unit): View =
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
                    addView(text(ctx, Type.CAPTION,
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
                                this, 8, Type.CAPTION.size.toInt(), 1,
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
    fun shortCount(n: Int): String {
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
    fun statCell(ctx: Context, count: Int, caption: String): View {
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
                    addView(text(ctx, Type.HEADLINE, value).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                    })
                    addView(text(ctx, Type.CAPTION, caption, R.color.pk_on_surface_variant)
                        .apply {
                            gravity = Gravity.CENTER
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT)
                            maxLines = 2
                            androidx.core.widget.TextViewCompat
                                .setAutoSizeTextTypeUniformWithConfiguration(
                                    this, 8, Type.CAPTION.size.toInt(), 1,
                                    TypedValue.COMPLEX_UNIT_SP)
                        })
                })
            }
        }
    }

    /** A row of cells built by the caller, sharing the width evenly. */
    fun cellRow(ctx: Context, cells: List<View>): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        // Baselines differ between a big number and a small caption; aligning
        // them would push the cards out of line with each other.
        isBaselineAligned = false
        with(ctx) { setPaddingRelative(dp(SM), dp(XXS), dp(SM), dp(SM)) }
        for (c in cells) addView(c)
    }

    /** Pills showing a count each, like the stats block in the guide. */
    fun statRow(ctx: Context, pairs: List<Pair<String, String>>): View = LinearLayout(ctx).apply {
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
                setPaddingRelative(dp(SM), 0, dp(SM), 0)
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
                    .apply { setMarginsRelative(dp(XXS), 0, dp(XXS), 0) }
            }
        }

    /** Full-width button, for the one decision a screen is asking for. */
    fun wideButton(ctx: Context, label: String, role: Btn, onClick: () -> Unit) =
        pill(ctx, label, role, onClick).apply {
            with(ctx) {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(TOUCH)
                ).apply { setMarginsRelative(dp(MD), dp(XXS), dp(MD), dp(XXS)) }
            }
        }

    // ---------- photos ----------

    /**
     * Decorative by default.
     *
     * A photo is only meaningful to a screen reader if something can describe
     * it, and PixKin cannot describe someone's picture. The screens that DO
     * know what a photo is -- the review card knows whose face matched and at
     * what similarity -- set a description and turn this back on.
     */
    fun photo(ctx: Context): ShapeableImageView = ShapeableImageView(ctx).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
                .apply { setMarginsRelative(0, 0, dp(XS), 0) }
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(R_SM).toFloat()).build()
        }
    }

    /** Square tile that shares the row width evenly with its siblings. */
    fun previewTile(ctx: Context): ShapeableImageView = photo(ctx).apply {
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f)
                .apply { setMarginsRelative(0, 0, dp(XS), 0) }
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(R_SM).toFloat()).build()
        }
    }

    /** A photo PixKin kept: a check on secondaryContainer, bottom-right. */
    fun keptBadge(ctx: Context): View = ImageView(ctx).apply {
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

    // ---------- people ("Kin") ----------

    /** Circular image, for a face crop. */
    fun circle(ctx: Context, sizeDp: Int): ShapeableImageView = ShapeableImageView(ctx).apply {
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
    /** [facePath] is already chosen; see [Clusterer.cleanestCrop]. */
    data class Face(val name: String, val caption: String, val facePath: String?)

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
    fun sectionHeader(
        ctx: Context, title: String, actionLabel: String,
        actionDescription: String? = null, onAction: () -> Unit,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        with(ctx) { setPaddingRelative(dp(MD), dp(SM), dp(XS), 0) }
        addView(text(ctx, Type.TITLE_SMALL, title).apply {
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL.size)
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                setPaddingRelative(dp(SM), dp(XS), dp(SM), dp(XS))
            }
            setOnClickListener { onAction() }
        })
    }

    fun peopleRow(
        ctx: Context, faces: List<Face>,
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

    private fun cellColumn(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMarginsRelative(0, 0, dp(XS), 0) }
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
            addView(text(ctx, Type.TITLE_SMALL, f.name).apply {
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


    // ---------- facts and states ----------

    /**
     * An icon and one line. Three of these say what a paragraph was saying,
     * and the eye can skip the ones it already knows.
     */
    fun factRow(
        ctx: Context, icon: Int, t: CharSequence,
        @ColorRes tint: Int = R.color.pk_on_surface_variant,
    ): View = factRow(ctx, icon, text(ctx, Type.BODY, t), tint)

    /** The same row, with a line the caller keeps a handle on and fills in later. */
    fun factRow(
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
    fun stateRow(
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
            addView(text(ctx, Type.TITLE_SMALL, name,
                if (good) R.color.pk_on_secondary_container else R.color.pk_on_surface).apply {
                layoutParams = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(text(ctx, Type.LABEL, state,
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
    fun mark(ctx: Context, sizeDp: Int = 72): View = ImageView(ctx).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setImageResource(R.drawable.ic_logo_mark)
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
                .apply { setMarginsRelative(dp(MD), dp(XS), dp(MD), dp(XS)) }
        }
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
            setPaddingRelative(dp(LG), dp(XL), dp(LG), dp(XL))
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_logo_mark)
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            })
            addView(text(ctx, Type.TITLE_SMALL, line).apply {
                gravity = Gravity.CENTER
                setPaddingRelative(0, dp(MD), 0, 0)
            })
            if (action != null) addView(wideButton(ctx, action, Btn.PRIMARY, onAction).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(MD), 0, 0)
            })
        }
    }
}

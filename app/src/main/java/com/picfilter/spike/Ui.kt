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

    /** Square tile that shares the row width evenly with its siblings. */
    fun previewTile(ctx: Context): ShapeableImageView = photo(ctx).apply {
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(0, dp(66), 1f)
                .apply { setMargins(0, 0, dp(6), 0) }
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(12).toFloat()).build()
        }
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
                ).apply { setMargins(0, dp(12), 0, 0) }
            }
            visibility = View.GONE          // stays hidden until a thumbnail lands
        }
        column.addView(strip)
        return Preview(card, strip)
    }

    // ---------- people row ----------

    private val AVATAR_BG = listOf("#3B4A6B", "#4A3B5C", "#2F5248", "#5C4433", "#3E4A5C")

    /** Circular image, for a face crop. */
    fun circle(ctx: Context, sizeDp: Int): ShapeableImageView = ShapeableImageView(ctx).apply {
        with(ctx) {
            layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(sizeDp) / 2f).build()
        }
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
    }

    /** Initials in a coloured disc — what a person without a face crop gets. */
    fun monogram(ctx: Context, sizeDp: Int, name: String): TextView = TextView(ctx).apply {
        val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
        text = initial
        textSize = sizeDp / 2.6f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor(ON_SURFACE))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(AVATAR_BG[Math.floorMod(name.hashCode(), AVATAR_BG.size)]))
        }
        with(ctx) { layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp)) }
    }

    /** One entry in the people row. [thumbPaths] are face crops on disk,
     *  biggest first; the cleanest of them becomes the avatar. */
    data class Face(val name: String, val caption: String, val thumbPaths: List<String>)

    /**
     * Photos-style row of face circles — the people whose photos are being
     * saved. Face crops are decoded off the main thread; until one arrives (or
     * if there is none) the circle shows the person's initial.
     */
    fun peopleRow(ctx: Context, faces: List<Face>, onClick: () -> Unit): View {
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            with(ctx) { setPadding(dp(24), dp(4), dp(24), dp(8)) }
        }
        for (f in faces) strip.addView(personCell(ctx, f, onClick))
        strip.addView(addCell(ctx, onClick))
        return android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
    }

    private fun cellColumn(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        with(ctx) {
            layoutParams = LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, dp(6), 0) }
        }
        isClickable = true
        isFocusable = true
    }

    private fun caption(ctx: Context, t: String, colour: String): TextView = TextView(ctx).apply {
        text = t; textSize = 11f
        setTextColor(Color.parseColor(colour))
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun personCell(ctx: Context, f: Face, onClick: () -> Unit): View {
        val size = 64
        val stack = android.widget.FrameLayout(ctx).apply {
            with(ctx) {
                layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            }
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
            addView(caption(ctx, f.name, ON_SURFACE).apply {
                with(ctx) { setPadding(0, dp(6), 0, 0) }
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            if (f.caption.isNotEmpty()) addView(caption(ctx, f.caption, ON_SURFACE_VARIANT))
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

    private fun addCell(ctx: Context, onClick: () -> Unit): View {
        val size = 64
        val plus = TextView(ctx).apply {
            text = "+"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(PRIMARY))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor(SURFACE_VARIANT))
            }
            with(ctx) { layoutParams = ViewGroup.LayoutParams(dp(size), dp(size)) }
        }
        return cellColumn(ctx).apply {
            addView(plus)
            addView(caption(ctx, "Add", ON_SURFACE_VARIANT).apply {
                with(ctx) { setPadding(0, dp(6), 0, 0) }
            })
            setOnClickListener { onClick() }
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

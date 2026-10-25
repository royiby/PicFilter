package com.picfilter.spike

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import androidx.core.net.toUri

/** Columns in every photo grid in the app. */
/**
 * How wide a tile should be, give or take, before another column is better
 * value than a bigger picture.
 *
 * Chosen so that every phone in portrait still gets the three columns this
 * grid has always had: the narrowest common width is 360dp, and 360/120 is
 * exactly 3. Landscape is where the old fixed 3 fell apart -- an 892dp window
 * made 770px tiles, so one cropped row filled the screen and the rest of the
 * width went to nothing.
 */
private const val GRID_TILE_TARGET_DP = 120

/**
 * Columns for the width we actually have, not for the width a phone has in
 * portrait.
 *
 * Floored at 3 so no layout loses a column it used to have, and capped at 8
 * so a large tablet does not render a mosaic of thumbnails too small to read.
 */
fun gridSpans(ctx: Context): Int =
    spansFor(ctx.resources.displayMetrics.run { widthPixels / density })

/** The arithmetic on its own, so it can be pinned without a device. */
internal fun spansFor(widthDp: Float): Int =
    (widthDp / GRID_TILE_TARGET_DP).toInt().coerceIn(3, 8)

/** Decode target for a grid tile: sharper than it looks, far smaller than the source. */
const val GRID_THUMB_PX = 320

/**
 * One photo in a grid, as the grid needs it.
 *
 * Both grids used to carry their own item type, their own adapter and their own
 * copy of the gap decoration. Mapping to this instead means there is one grid
 * in the app, and [id] gives DiffUtil something stable to compare.
 */
data class PhotoItem(val id: Long, val uri: String?, val label: String)

/**
 * The photo grid: recycled tiles, Coil for the images, DiffUtil for updates.
 *
 * A ListAdapter rather than notifyDataSetChanged, which rebinds every visible
 * tile whether or not it changed. Recording a verdict removes one item from a
 * queue of a hundred; only that one needs to move.
 */
class PhotoGridAdapter(
    private val ctx: Context,
    private val side: Int,
    private val kept: Boolean = false,
    private val dimmed: Boolean = false,
    private val onTap: ((Int) -> Unit)? = null,
) : ListAdapter<PhotoItem, PhotoGridAdapter.Tile>(DIFF) {

    class Tile(val view: FrameLayout) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.getChildAt(0) as ImageView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Tile(Ui.gridTile(ctx, side, kept, dimmed))

    override fun onBindViewHolder(holder: Tile, position: Int) {
        val item = getItem(position)
        holder.view.contentDescription = item.label
        if (onTap == null) {
            holder.view.setOnClickListener(null)
            holder.view.isClickable = false
        } else {
            holder.view.setOnClickListener {
                val at = holder.bindingAdapterPosition
                if (at != RecyclerView.NO_POSITION) onTap.invoke(at)
            }
        }
        val uri = item.uri
        if (uri == null) holder.image.setImageDrawable(null)
        else holder.image.load(uri.toUri()) {
            size(GRID_THUMB_PX)
            scale(Scale.FILL)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<PhotoItem>() {
            override fun areItemsTheSame(a: PhotoItem, b: PhotoItem) = a.id == b.id
            override fun areContentsTheSame(a: PhotoItem, b: PhotoItem) = a == b
        }
    }
}

/**
 * Even gaps between tiles, so the grid needs no margins of its own.
 *
 * [spans] must be the same count the GridLayoutManager was given, or the
 * columns drift out of line with their gutters.
 */
class GridGaps(
    private val gap: Int,
    private val spans: Int,
) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State,
    ) {
        val i = parent.getChildAdapterPosition(view)
        if (i == RecyclerView.NO_POSITION) return
        val col = i % spans
        // outRect is in physical sides, while the column index counts from the
        // start of the line -- which is the right-hand side in Hebrew. Mirror
        // it, or the outer column loses its gutter and the grid sits off centre.
        val before = col * gap / spans
        val after = gap - (col + 1) * gap / spans
        val rtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
        outRect.left = if (rtl) after else before
        outRect.right = if (rtl) before else after
        if (i >= spans) outRect.top = gap
    }
}

/** The side of a square tile, for a [spans]-column grid filling the screen. */
fun gridTileSide(ctx: Context, gapPx: Int, spans: Int): Int =
    (ctx.resources.displayMetrics.widthPixels - gapPx * (spans - 1)) / spans

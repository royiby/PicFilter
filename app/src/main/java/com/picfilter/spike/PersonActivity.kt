package com.picfilter.spike

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import android.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.picfilter.spike.data.Db
import com.picfilter.spike.data.DbQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PICFILTER"
private const val GRID_COLS = 3
private const val THUMB_PX = 320

/**
 * One person: the photos PixKin keeps because they are in them, and the way to
 * stop it.
 *
 * This is what tapping a face does now. Before, every face opened the same
 * setup flow, so the people row was five different buttons that all did one
 * thing, and there was no way to see WHY someone was enrolled -- which photos
 * that enrolment was actually protecting -- or to undo it.
 */
class PersonActivity : ComponentActivity() {

    private lateinit var db: Db
    private val dbq by lazy { DbQueue(db) }
    private var personId = -1L
    private var personName = ""
    private var photos: List<Pair<Long, String>> = emptyList()

    private lateinit var root: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var countLine: android.widget.TextView
    private val adapter by lazy { PhotoAdapter() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        personId = intent?.getLongExtra("person_id", -1L) ?: -1L
        personName = intent?.getStringExtra("person_name") ?: "This person"
        if (personId <= 0) { finish(); return }
        setContentView(build())
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        dbq.closeAfterPending()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun build(): View {
        root = Ui.screen(this)
        root.addView(Ui.titleBar(this, personName) { finish() })

        countLine = Ui.text(this, Ui.Type.BODY_SMALL, "Looking…",
            R.color.pk_on_surface_variant).apply {
            setPadding(dp(Ui.MD), dp(Ui.XS), dp(Ui.MD), dp(Ui.XS))
        }
        root.addView(countLine)

        grid = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = GridLayoutManager(this@PersonActivity, GRID_COLS)
            addItemDecoration(GapDecoration(dp(Ui.GRID_GAP)))
            setHasFixedSize(true)
            adapter = this@PersonActivity.adapter
        }
        root.addView(grid)

        root.addView(Ui.wideButton(this, "Remove $personName", Ui.Btn.OUTLINED) { confirmRemove() })
        Ui.applyInsets(root, this, extraBottom = Ui.SM)
        return root
    }

    private fun load() {
        lifecycleScope.launch {
            photos = withContext(dbq.context) { db.photosOf(personId) }
            countLine.text = when (photos.size) {
                0 -> "No photos matched to $personName yet."
                1 -> "1 photo kept because $personName is in it."
                else -> "${photos.size} photos kept because $personName is in them."
            }
            adapter.notifyDataSetChanged()
        }
    }

    /**
     * Three columns, recycled. Someone with 2,000 photos is the normal case for
     * a child, not an edge case, and a view per photo in a ScrollView does not
     * survive it.
     */
    private inner class PhotoAdapter : RecyclerView.Adapter<PhotoAdapter.Tile>() {

        inner class Tile(val view: android.widget.FrameLayout) :
            RecyclerView.ViewHolder(view) {
            val image: ImageView = view.getChildAt(0) as ImageView
        }

        private val side: Int
            get() = (resources.displayMetrics.widthPixels -
                dp(Ui.GRID_GAP) * (GRID_COLS - 1)) / GRID_COLS

        override fun getItemCount() = photos.size

        // No kept badge: the line above already says every photo here is kept,
        // so a tick on all of them is decoration that has to be read.
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Tile(Ui.gridTile(this@PersonActivity, side, kept = false, dimmed = false))

        override fun onBindViewHolder(holder: Tile, position: Int) {
            holder.view.contentDescription =
                "A photo of $personName, ${position + 1} of ${photos.size}."
            holder.image.load(Uri.parse(photos[position].second)) {
                size(THUMB_PX)
                scale(Scale.FILL)
            }
        }
    }

    /** Even gaps between tiles. */
    private class GapDecoration(private val gap: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect, view: View,
            parent: RecyclerView, state: RecyclerView.State,
        ) {
            val i = parent.getChildAdapterPosition(view)
            if (i == RecyclerView.NO_POSITION) return
            val col = i % GRID_COLS
            outRect.left = col * gap / GRID_COLS
            outRect.right = gap - (col + 1) * gap / GRID_COLS
            if (i >= GRID_COLS) outRect.top = gap
        }
    }

    /**
     * Removing someone is not deleting their photos, and the dialog has to say
     * so plainly -- this screen is a grid of pictures with a Remove button
     * under it, which is a sentence that reads the wrong way if left alone.
     */
    private fun confirmRemove() {
        val kept = photos.size
        AlertDialog.Builder(this)
            .setTitle("Remove $personName?")
            .setMessage(
                "PixKin will stop recognising $personName, so new photos of them " +
                "will no longer be saved automatically.\n\n" +
                "No photos are deleted. The $kept already saved stay where they are, " +
                "and $personName can be added again later."
            )
            .setPositiveButton("Remove") { _, _ -> remove() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun remove() {
        lifecycleScope.launch {
            val requeued = withContext(dbq.context) {
                try { db.removePerson(personId) } catch (e: Exception) {
                    Log.w(TAG, "REMOVEPERSON ${e.javaClass.simpleName}: ${e.message}")
                    -1
                }
            }
            if (requeued < 0) return@launch
            withContext(Dispatchers.IO) {
                com.picfilter.spike.engine.Clusterer.personFaces(
                    this@PersonActivity, personId).forEach { java.io.File(it).delete() }
            }
            Log.i(TAG, "REMOVEPERSON '$personName' removed; $requeued items to score again")
            finish()
        }
    }
}

package com.picfilter.spike

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import android.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.picfilter.spike.Ui.setMarginsRelative
import com.picfilter.spike.data.Db
import com.picfilter.spike.data.DbQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PICFILTER"

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
    private val adapter by lazy {
        PhotoGridAdapter(this, gridTileSide(this, dp(Ui.GRID_GAP)), kept = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        personId = intent?.getLongExtra("person_id", -1L) ?: -1L
        personName = intent?.getStringExtra("person_name") ?: getString(R.string.person_fallback)
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

        countLine = Ui.text(this, Ui.Type.BODY_SMALL, getString(R.string.person_loading),
            R.color.pk_on_surface_variant).apply {
            setPaddingRelative(dp(Ui.MD), dp(Ui.XS), dp(Ui.MD), dp(Ui.XS))
        }
        root.addView(countLine)

        grid = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = GridLayoutManager(this@PersonActivity, GRID_COLS)
            addItemDecoration(GridGaps(dp(Ui.GRID_GAP)))
            setHasFixedSize(true)
            adapter = this@PersonActivity.adapter
        }
        root.addView(grid)

        root.addView(Ui.wideButton(
            this, getString(R.string.person_remove, personName), Ui.Btn.OUTLINED,
        ) { confirmRemove() })
        Ui.applyInsets(root, this, extraBottom = Ui.SM)
        return root
    }

    private fun load() {
        lifecycleScope.launch {
            photos = withContext(dbq.context) { db.photosOf(personId) }
            countLine.text = if (photos.isEmpty())
                getString(R.string.person_none, personName)
            else resources.getQuantityString(
                R.plurals.person_kept_because, photos.size, personName, photos.size)
            adapter.submitList(photos.mapIndexed { i, photo ->
                PhotoItem(
                    id = photo.first,
                    uri = photo.second,
                    label = getString(
                        R.string.person_photo_description, personName, i + 1, photos.size),
                )
            })
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
            .setTitle(getString(R.string.person_remove_title, personName))
            .setMessage(getString(R.string.person_remove_body, personName, kept))
            .setPositiveButton(R.string.action_remove) { _, _ -> remove() }
            .setNegativeButton(R.string.action_cancel, null)
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

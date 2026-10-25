package com.picfilter.spike

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * One person: the photos PixKin keeps because they are in them, and the way to
 * stop it.
 *
 * The Activity draws; [PersonViewModel] remembers. Rotating no longer reloads
 * the grid, because the list it is drawn from was never thrown away.
 */
class PersonActivity : ComponentActivity() {

    private val vm: PersonViewModel by viewModels { PersonViewModel.Factory }

    private lateinit var root: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var countLine: android.widget.TextView
    private lateinit var removeButton: View
    // Recomputed per instance, and the Activity is recreated on rotation, so
    // landscape gets the columns its width is worth rather than portrait's 3.
    private val spans by lazy { gridSpans(this) }
    private val adapter by lazy {
        PhotoGridAdapter(this, gridTileSide(this, dp(Ui.GRID_GAP), spans), kept = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (vm.personId <= 0) { finish(); return }
        setContentView(build())
        observe()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun build(): View {
        root = Ui.screen(this)
        root.addView(Ui.titleBar(this, vm.personName) { finish() })

        countLine = Ui.text(this, Ui.Type.BODY_SMALL, getString(R.string.person_loading),
            R.color.pk_on_surface_variant).apply {
            setPaddingRelative(dp(Ui.MD), dp(Ui.XS), dp(Ui.MD), dp(Ui.XS))
        }
        root.addView(countLine)

        grid = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = GridLayoutManager(this@PersonActivity, spans)
            addItemDecoration(GridGaps(dp(Ui.GRID_GAP), spans))
            setHasFixedSize(true)
            adapter = this@PersonActivity.adapter
        }
        root.addView(grid)

        removeButton = Ui.wideButton(
            this, getString(R.string.person_remove, vm.personName), Ui.Btn.OUTLINED,
        ) { confirmRemove() }
        removeButton.visibility = View.GONE   // until we know how many there are
        root.addView(removeButton)
        Ui.applyInsets(root, this, extraBottom = Ui.SM)
        return root
    }

    /**
     * Collect only while the screen is actually visible.
     *
     * repeatOnLifecycle(STARTED) rather than a bare launch: a collector started
     * in onCreate and never stopped keeps the Activity referenced from the
     * ViewModel's flow for as long as the process lives.
     */
    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.photos.collect { render(it) } }
                launch { vm.removed.collect { if (it) finish() } }
            }
        }
    }

    private fun render(photos: List<Pair<Long, String>>?) {
        if (photos == null) return      // still loading; the line already says so
        countLine.text = if (photos.isEmpty())
            getString(R.string.person_none, vm.personName)
        else resources.getQuantityString(
            R.plurals.person_kept_because, photos.size, vm.personName, photos.size)
        removeButton.visibility = View.VISIBLE
        adapter.submitList(photos.mapIndexed { i, photo ->
            PhotoItem(
                id = photo.first,
                uri = photo.second,
                label = getString(
                    R.string.person_photo_description, vm.personName, i + 1, photos.size),
            )
        })
    }

    /**
     * Removing someone is not deleting their photos, and the dialog has to say
     * so plainly -- this screen is a grid of pictures with a Remove button
     * under it, which is a sentence that reads the wrong way if left alone.
     */
    private fun confirmRemove() {
        val kept = vm.photos.value?.size ?: 0
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.person_remove_title, vm.personName))
            .setMessage(getString(R.string.person_remove_body, vm.personName, kept))
            .setPositiveButton(R.string.action_remove) { _, _ -> vm.remove() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}

package com.picfilter.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * One person: the photos PixKin keeps because they are in them, and the way to
 * stop it.
 *
 * The only Compose screen in the app, kept as a comparison against the rest --
 * see [PersonScreen] for what that is for and TESTING.md for what it showed.
 *
 * What is left here is the whole of the Activity now: get the ViewModel,
 * collect its state, hand it to a function. The lifecycle-aware collection is
 * `collectAsStateWithLifecycle` rather than `collectAsState`, for the same
 * reason the View screens use `repeatOnLifecycle` -- a collector that runs
 * while the screen is off is a screen that cannot be stopped.
 */
class PersonActivity : ComponentActivity() {

    private val vm: PersonViewModel by viewModels { PersonViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (vm.personId <= 0) { finish(); return }
        setContent {
            val photos by vm.photos.collectAsStateWithLifecycle()
            val removed by vm.removed.collectAsStateWithLifecycle()
            if (removed) finish()
            PixKinTheme {
                PersonScreen(
                    name = vm.personName,
                    photos = photos,
                    spans = gridSpans(this),
                    onBack = { finish() },
                    onRemove = { vm.remove() },
                )
            }
        }
    }
}

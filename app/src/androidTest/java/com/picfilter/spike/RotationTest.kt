package com.picfilter.spike

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picfilter.spike.data.Db
import com.picfilter.spike.data.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What survives the screen being destroyed and rebuilt.
 *
 * *What is not covered yet* #1: the review queue's position, onboarding's step
 * and the person grid's list were given SavedStateHandle and ViewModels, hand-
 * verified once on a phone, and never tested. A rotation mid-review that lost
 * someone's place would send them back through photos they had already judged,
 * which is both annoying and how spurious judgements get made.
 *
 * Two different things are being tested here and the distinction matters:
 *
 *  - **`recreate()` is a configuration change.** The Activity dies, the
 *    ViewModel does not. It proves the screen rebuilds from state it still
 *    holds -- which is what rotating a phone does.
 *  - **A ViewModel built from a pre-seeded SavedStateHandle is process death.**
 *    The ViewModel is gone and everything must come back from the bundle. Only
 *    this exercises the `persist()` calls.
 *
 * A test that only called recreate() would pass with SavedStateHandle removed
 * entirely, so both are here.
 */
@RunWith(AndroidJUnit4::class)
class RotationTest {

    /**
     * Empty rather than createAndroidComposeRule: the Activity under test needs
     * intent extras, so it is launched by hand and this only supplies the
     * synchronisation with Compose's frame clock.
     */
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = ApplicationProvider.getApplicationContext<PixKinApp>()
    private val dbName = "ui-rotation-test.db"
    private lateinit var db: Db
    private var realDeps: Deps? = null

    @Before fun setUp() {
        ctx.deleteDatabase(dbName)
        db = Db(ctx, dbName)
        for (id in 1L..4L) {
            db.upsertItem(id, "content://test/$id", "IMG-$id.jpg",
                "Pictures/WhatsApp/Media/WhatsApp Images/", "WhatsApp Images",
                100L, 1_767_000_000L, 1000, 1000)
            db.putScore(id, 1, 0, 1, 0.5f, 0, "REVIEW", "INDETERMINATE_FACE", 0.5f)
        }
        realDeps = app.deps
        app.deps = object : Deps {
            override fun openDb(): Db = Db(ctx, dbName)
        }
    }

    @After fun tearDown() {
        realDeps?.let { app.deps = it }
        db.close()
        ctx.deleteDatabase(dbName)
    }

    private fun reviewIntent() = Intent(ctx, ReviewActivity::class.java)
        .putExtra("mode", "rescue")

    private fun View.all(): List<View> =
        listOf(this) + if (this is ViewGroup) (0 until childCount).flatMap {
            getChildAt(it).all()
        } else emptyList()

    private fun <A : android.app.Activity> ActivityScenario<A>.visibleText(): List<String> {
        val out = mutableListOf<String>()
        onActivity { a ->
            a.window.decorView.all().filterIsInstance<TextView>()
                .filter { it.isShown }
                .forEach { out += it.text?.toString().orEmpty() }
        }
        return out
    }

    private fun <A : android.app.Activity> ActivityScenario<A>.click(text: String) {
        onActivity { a ->
            val v = a.window.decorView.all().firstOrNull {
                it is TextView && it.isShown && it.text?.toString() == text
            }
            assertNotNull("no visible control labelled \"$text\"", v)
            var target: View = v!!
            while (!target.hasOnClickListeners() && target.parent is View) {
                target = target.parent as View
            }
            target.performClick()
        }
    }

    private fun <A : android.app.Activity> ActivityScenario<A>.await(want: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (visibleText().any { it == want }) return
            Thread.sleep(50)
        }
        throw AssertionError("never saw \"$want\"; saw ${visibleText()}")
    }

    private fun counter(n: Int, of: Int) = ctx.getString(R.string.counter_position, n, of)

    // ---------- configuration change ----------

    @Test fun the_review_queue_keeps_its_place_across_a_rotation() {
        ActivityScenario.launch<ReviewActivity>(reviewIntent()).use { s ->
            s.await(counter(1, 4))
            s.click(ctx.getString(R.string.action_skip))
            s.await(counter(2, 4))

            s.recreate()

            s.await(counter(2, 4))
        }
    }

    /**
     * The bug this is really about: coming back to a photo already judged.
     * A lost position does not look like a crash, it looks like the queue
     * going backwards, and the second judgement overwrites the first.
     */
    @Test fun a_rotation_does_not_send_a_judged_photo_round_again() {
        ActivityScenario.launch<ReviewActivity>(reviewIntent()).use { s ->
            s.await(counter(1, 4))
            s.click(ctx.getString(R.string.action_keep))
            s.await(counter(2, 4))

            s.recreate()
            s.await(counter(2, 4))

            // Whatever is on screen now must not be the photo just decided.
            val labelled = db.readableDatabase.rawQuery(
                "SELECT item_id FROM label", null
            ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            assertEquals("exactly one decision so far", 1, labelled.size)
        }
    }

    @Test fun the_grid_toggle_survives_a_rotation() {
        ActivityScenario.launch<ReviewActivity>(reviewIntent()).use { s ->
            s.await(counter(1, 4))
            // rescue mode starts on a card; switch to the grid.
            s.onActivity { a ->
                a.window.decorView.all().firstOrNull { it.contentDescription?.toString() ==
                    a.getString(R.string.grid_show_grid) }?.performClick()
            }
            s.await(ctx.resources.getQuantityString(R.plurals.grid_photo_count, 4, 4))

            s.recreate()

            s.await(ctx.resources.getQuantityString(R.plurals.grid_photo_count, 4, 4))
        }
    }

    /**
     * The person screen is the one Compose screen, so this is the one test that
     * cannot walk the view tree.
     *
     * It used to, and it broke the moment the screen was ported: Compose draws
     * into a single AndroidComposeView and emits no TextViews at all, so a tree
     * walk finds nothing and the assertion fails with no clue why. The replacement
     * asks the semantics tree instead, which is the only thing a Compose screen
     * exposes -- and the reason every node this test touches had to be given a
     * testTag in the screen itself.
     *
     * That cost is the finding, not an inconvenience: porting a screen means
     * porting its tests too, and the two test APIs do not meet in the middle.
     */
    @Test fun the_person_screen_keeps_its_list_across_a_rotation() {
        val alice = db.upsertPerson("alice")
        val intent = Intent(ctx, PersonActivity::class.java)
            .putExtra("person_id", alice)
            .putExtra("person_name", "alice")
        ActivityScenario.launch<PersonActivity>(intent).use { s ->
            compose.onNodeWithTag("title").assertTextEquals("alice")
            compose.onNodeWithTag("grid").assertExists()

            s.recreate()

            compose.onNodeWithTag("title").assertTextEquals("alice")
            compose.onNodeWithTag("grid").assertExists()
        }
    }

    // ---------- process death ----------

    /**
     * The ViewModel is gone and the bundle is all there is.
     *
     * `recreate()` cannot test this: it keeps the ViewModel, so a screen would
     * pass that test with its SavedStateHandle writes deleted. This builds the
     * ViewModel the way the framework does after the process was killed.
     */
    @Test fun the_review_position_comes_back_from_the_bundle_alone() {
        val deps = app.deps
        val state = SavedStateHandle(mapOf("mode" to "rescue"))
        val first = ReviewViewModel(app as Application, state, deps)
        first.queue.value   // touch, so the queue loads
        Thread.sleep(500)
        first.label("SKIP")
        first.label("SKIP")
        assertEquals(2, first.idx.value)

        // The process dies here. Only `state` crosses over.
        val revived = ReviewViewModel(app, state, deps)
        assertEquals("position restored from the bundle", 2, revived.idx.value)
    }

    @Test fun the_grid_choice_comes_back_from_the_bundle_alone() {
        val state = SavedStateHandle(mapOf("mode" to "rescue"))
        val first = ReviewViewModel(app as Application, state, app.deps)
        assertEquals("rescue starts on a card", false, first.grid.value)
        first.setGrid(true)

        val revived = ReviewViewModel(app, state, app.deps)
        assertEquals("grid choice restored", true, revived.grid.value)
    }

    @Test fun onboarding_keeps_its_step_across_process_death() {
        val state = SavedStateHandle()
        val first = OnboardingViewModel(app as Application, state, app.deps)
        first.goTo(OnboardingViewModel.STEP_SUMMARY)

        val revived = OnboardingViewModel(app, state, app.deps)
        assertEquals(OnboardingViewModel.STEP_SUMMARY, revived.step.value)
    }
}

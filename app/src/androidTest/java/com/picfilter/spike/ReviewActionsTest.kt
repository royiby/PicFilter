package com.picfilter.spike

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picfilter.spike.data.Db
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The review screen's buttons, against a fixture queue.
 *
 * This is the test *What is not covered yet* called the highest-value missing
 * one, for a bug that has actually happened: every rescue button became a
 * no-op because a `when` over card types quietly stopped being exhaustive, and
 * nothing failed -- the buttons simply did nothing and the queue stopped
 * moving. No unit test can see that, because the defect is in the wiring
 * between a tap and a row.
 *
 * It could not be written before [Deps] existed. The screen built its own `Db`,
 * so driving it meant driving it against the user's real library, and the
 * standing rule here is that tap automation never touches those photos --
 * automation once recorded 43 spurious judgements about someone's family.
 * Swapping the container gives the Activity a database with its own name and
 * three invented rows, and the rule is kept by construction rather than by
 * being careful.
 *
 * `mode` is deliberately "rescue" and never "verify": the verify queue's
 * DISCARD path calls `Promoter.unpromote`, which reaches MediaStore and the
 * file system. Nothing here may touch either.
 */
@RunWith(AndroidJUnit4::class)
class ReviewActionsTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = ApplicationProvider.getApplicationContext<PixKinApp>()
    private val dbName = "ui-review-test.db"
    private lateinit var db: Db
    private var realDeps: Deps? = null

    @Before fun setUp() {
        ctx.deleteDatabase(dbName)
        db = Db(ctx, dbName)
        for (id in 1L..3L) {
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

    private fun intent() = Intent(ctx, ReviewActivity::class.java)
        .putExtra("mode", "rescue")

    /** Every view in the tree, depth first. */
    private fun View.all(): List<View> =
        listOf(this) + if (this is ViewGroup) (0 until childCount).flatMap {
            getChildAt(it).all()
        } else emptyList()

    /**
     * Click the visible control whose label is [text].
     *
     * Deliberately not Espresso: these screens are built in code with no ids,
     * the clicks are plain and synchronous, and onActivity already runs on the
     * main thread -- so a tree walk is deterministic and needs no animation
     * settings on the device. The cost is this helper; the benefit is a test
     * that cannot be flaky for reasons unrelated to what it asserts.
     */
    private fun ActivityScenario<ReviewActivity>.click(text: String) {
        var found = false
        onActivity { a ->
            val v = a.window.decorView.all().firstOrNull {
                it is TextView && it.isShown && it.text?.toString() == text
            }
            assertNotNull("no visible control labelled \"$text\"", v)
            var target: View = v!!
            while (!target.hasOnClickListeners() && target.parent is View) {
                target = target.parent as View
            }
            found = target.performClick()
        }
        assertEquals("\"$text\" was not clickable", true, found)
    }

    /** Wait for the queue to load; it is read off the main thread. */
    private fun ActivityScenario<ReviewActivity>.awaitCard() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            onActivity { a ->
                ready = a.window.decorView.all().any {
                    it is TextView && it.isShown &&
                        it.text?.toString() == a.getString(R.string.action_skip)
                }
            }
            if (ready) return
            Thread.sleep(50)
        }
        throw AssertionError("the review card never appeared")
    }

    private fun labelOf(id: Long): String? =
        db.readableDatabase.rawQuery(
            "SELECT label FROM label WHERE item_id=?", arrayOf(id.toString())
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun labelCount(): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM label", null)
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }

    /** Nothing is decided until something is tapped. */
    @Test fun opening_the_screen_writes_nothing() {
        ActivityScenario.launch<ReviewActivity>(intent()).use {
            it.awaitCard()
            assertEquals(0, labelCount())
        }
    }

    @Test fun keep_writes_a_keep_for_the_card_on_screen() {
        ActivityScenario.launch<ReviewActivity>(intent()).use { s ->
            s.awaitCard()
            s.click(ctx.getString(R.string.action_keep))
            settle(1)
            assertEquals("KEEP", labelOf(1))
        }
    }

    @Test fun not_mine_writes_a_discard() {
        ActivityScenario.launch<ReviewActivity>(intent()).use { s ->
            s.awaitCard()
            s.click(ctx.getString(R.string.action_not_mine))
            settle(1)
            assertEquals("DISCARD", labelOf(1))
        }
    }

    @Test fun skip_writes_a_skip() {
        ActivityScenario.launch<ReviewActivity>(intent()).use { s ->
            s.awaitCard()
            s.click(ctx.getString(R.string.action_skip))
            settle(1)
            assertEquals("SKIP", labelOf(1))
        }
    }

    /**
     * The queue advances, and each tap lands on the card that was showing.
     *
     * The no-op bug looked exactly like this test failing on the second row:
     * the first tap appeared to work because the screen moved on anyway.
     */
    @Test fun consecutive_taps_decide_consecutive_photos() {
        ActivityScenario.launch<ReviewActivity>(intent()).use { s ->
            s.awaitCard()
            s.click(ctx.getString(R.string.action_keep))
            settle(1)
            s.click(ctx.getString(R.string.action_not_mine))
            settle(2)
            s.click(ctx.getString(R.string.action_skip))
            settle(3)

            assertEquals("KEEP", labelOf(1))
            assertEquals("DISCARD", labelOf(2))
            assertEquals("SKIP", labelOf(3))
            assertEquals(3, labelCount())
        }
    }

    /** A verdict is one row per photo, not one per tap. */
    @Test fun deciding_the_same_photo_twice_leaves_one_row() {
        ActivityScenario.launch<ReviewActivity>(intent()).use { s ->
            s.awaitCard()
            s.click(ctx.getString(R.string.action_keep))
            settle(1)
        }
        // A second visit starts from the undecided remainder.
        ActivityScenario.launch<ReviewActivity>(intent()).use { s ->
            s.awaitCard()
            s.click(ctx.getString(R.string.action_keep))
            settle(2)
        }
        assertEquals("KEEP", labelOf(1))
        assertEquals(2, labelCount())
        assertNull("item 3 was never decided", labelOf(3))
    }

    /**
     * Wait until exactly [expected] rows have landed.
     *
     * The write goes through DbQueue on its own thread, so the assertion needs
     * a point to wait for rather than a sleep to guess at. Waiting for a number
     * also fails loudly when a tap writes nothing, which is the bug this file
     * exists for -- a sleep would simply assert against a stale count.
     */
    private fun settle(expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (labelCount() == expected) return
            Thread.sleep(25)
        }
        assertEquals("rows written", expected, labelCount())
    }
}

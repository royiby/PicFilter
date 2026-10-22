package com.picfilter.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.picfilter.spike.engine.Watcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The watcher's arming, which is where the content trigger lives.
 *
 * The bug this guards: arming with ExistingWorkPolicy.REPLACE cancelled a
 * trigger that was already waiting. Every app open re-arms to heal a broken
 * chain, so REPLACE meant routine use kept resetting the trigger -- and it
 * cancelled a run in progress during testing.
 */
@RunWith(AndroidJUnit4::class)
class WatcherArmingTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun states(name: String): List<WorkInfo.State> =
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(name).get().map { it.state }

    private val net = "picfilter-watch"
    private val slotA = "picfilter-trigger-a"
    private val slotB = "picfilter-trigger-b"

    @Test fun enabling_arms_both_the_trigger_and_the_safety_net() {
        Watcher.enable(ctx)
        assertTrue("periodic net", states(net).any { !it.isFinished })
        assertTrue("content trigger", states(slotA).any { !it.isFinished })
        assertTrue(Watcher.isEnabled(ctx))
        assertTrue(Watcher.isArmed(ctx))
    }

    @Test fun re_enabling_does_not_cancel_the_waiting_trigger() {
        Watcher.enable(ctx)
        val before = WorkManager.getInstance(ctx)
            .getWorkInfosForUniqueWork(slotA).get().single().id

        Watcher.enable(ctx)                          // what every app open does
        Watcher.enable(ctx)

        val after = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(slotA).get()
        assertEquals("the same request, not a replacement", 1, after.size)
        assertEquals(before, after.single().id)
        assertFalse("must not be cancelled", after.single().state == WorkInfo.State.CANCELLED)
        assertTrue(Watcher.isArmed(ctx))
    }

    @Test fun disabling_cancels_the_net_and_both_slots() {
        Watcher.enable(ctx)
        Watcher.disable(ctx)
        assertFalse(Watcher.isEnabled(ctx))
        assertFalse(Watcher.isArmed(ctx))
        for (name in listOf(net, slotA, slotB)) {
            assertTrue("$name should be finished", states(name).all { it.isFinished })
        }
    }

    // ---------- healing the schedule from the recorded intent ----------

    /**
     * The restore case, and the reason healSchedule exists.
     *
     * A restored database carries the people, the labels and the intent;
     * WorkManager's own database is private to the applicationId and does not
     * travel, so nothing is scheduled. The old heal was
     * `if (isEnabled && !isArmed) enable()`, which needed auto-save to be on
     * already and so could never see this.
     */
    @Test fun a_restored_intent_with_no_schedule_re_arms() {
        assertFalse("nothing scheduled to begin with", Watcher.isEnabled(ctx))

        val on = Watcher.healSchedule(ctx, wanted = true)

        assertTrue("must report itself on", on)
        assertTrue("the net must be back", Watcher.isEnabled(ctx))
        assertTrue("and a trigger armed", Watcher.isArmed(ctx))
    }

    @Test fun intent_off_leaves_an_empty_schedule_alone() {
        assertFalse(Watcher.healSchedule(ctx, wanted = false))
        assertFalse(Watcher.isEnabled(ctx))
        assertFalse(Watcher.isArmed(ctx))
    }

    /** Intent off but work somehow scheduled: the intent wins. */
    @Test fun intent_off_cancels_work_scheduled_behind_it() {
        Watcher.enable(ctx)
        assertTrue(Watcher.isEnabled(ctx))

        assertFalse(Watcher.healSchedule(ctx, wanted = false))

        assertFalse("the user said off", Watcher.isEnabled(ctx))
        assertFalse(Watcher.isArmed(ctx))
    }

    /**
     * No intent recorded: whatever WorkManager holds is the answer, and
     * healing must not invent a schedule. HomeViewModel writes the returned
     * value down, so this is asked once per install.
     */
    @Test fun no_recorded_intent_reports_the_schedule_and_changes_nothing() {
        assertFalse("off stays off", Watcher.healSchedule(ctx, wanted = null))
        assertFalse(Watcher.isEnabled(ctx))

        Watcher.enable(ctx)
        assertTrue("on stays on", Watcher.healSchedule(ctx, wanted = null))
        assertTrue(Watcher.isEnabled(ctx))
    }

    /** The case the old heal did cover, which must keep working. */
    @Test fun a_broken_chain_is_healed_from_the_intent_too() {
        Watcher.enable(ctx)
        WorkManager.getInstance(ctx).cancelUniqueWork(slotA)
        assertFalse(Watcher.isArmed(ctx))

        assertTrue(Watcher.healSchedule(ctx, wanted = true))
        assertTrue("trigger must be back", Watcher.isArmed(ctx))
    }

    @Test fun healing_an_intact_schedule_does_not_replace_the_trigger() {
        Watcher.enable(ctx)
        val before = WorkManager.getInstance(ctx)
            .getWorkInfosForUniqueWork(slotA).get().single().id

        assertTrue(Watcher.healSchedule(ctx, wanted = true))

        val after = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(slotA).get()
        assertEquals("the same waiting trigger", 1, after.size)
        assertEquals(before, after.single().id)
    }

    @Test fun arming_is_recoverable_after_a_broken_chain() {
        // A force-stop leaves the net alive and the trigger gone. Opening the
        // app calls enable() again, which must put a trigger back.
        Watcher.enable(ctx)
        WorkManager.getInstance(ctx).cancelUniqueWork(slotA)
        assertFalse(Watcher.isArmed(ctx))

        Watcher.enable(ctx)
        assertTrue("a cancelled slot must be re-armable", Watcher.isArmed(ctx))
    }
}

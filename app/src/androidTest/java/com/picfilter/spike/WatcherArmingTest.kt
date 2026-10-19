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

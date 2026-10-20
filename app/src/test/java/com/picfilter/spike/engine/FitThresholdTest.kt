package com.picfilter.spike.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a person's match threshold is allowed to land.
 *
 * The defect these pin is a ratchet: fitting from matched faces alone can only
 * ever see points above the current bar, so the bar could rise and never fall
 * however much evidence said it should. Three people's thresholds (0.55, 0.63,
 * 0.65) became permanently unreachable that way.
 *
 * The cost of over-correcting is a stranger's child in someone's cloud library,
 * so the downward direction is guarded rather than merely enabled.
 */
class FitThresholdTest {

    private fun p(sim: Float, isPerson: Boolean) = ThresholdPoint(1L, "alice", sim, isPerson)

    /** n points at [sim], all correct or all not. */
    private fun many(n: Int, sim: Float, isPerson: Boolean) = List(n) { p(sim, isPerson) }

    // ---------------------------------------------------------- the ratchet

    @Test fun `a bar can come back down when the faces below it are really that person`() {
        // Seven faces between the floor and the default, all confirmed family,
        // and nothing wrong anywhere. There is no honest reason to keep
        // rejecting them, and the old fitter could not see them at all.
        val points = many(7, 0.60f, true) + many(4, 0.80f, true)
        val fit = Fitter.fitThreshold(points)
        assertNotNull(fit)
        assertEquals(0.60f, fit!!.threshold, 1e-6f)
        assertTrue("the fit must say it used the evidence below", fit.belowDefault >= 5)
    }

    @Test fun `matched faces alone can still only raise it`() {
        // The old input shape: every point above the default. Nothing here
        // justifies a lower bar, and none is offered.
        val points = many(6, 0.72f, true) + many(6, 0.90f, true)
        val fit = Fitter.fitThreshold(points)
        assertNotNull(fit)
        assertTrue("cannot drop below the default without evidence below it",
            fit!!.threshold >= FaceEngine.MATCH_THRESHOLD)
    }

    @Test fun `a false match above the bar still pushes it up`() {
        // The original purpose of fitting, unaffected by the new points: one
        // person's centroid matching strangers at 0.72 has to be contained.
        val points = many(9, 0.72f, false) + many(9, 0.88f, true)
        val fit = Fitter.fitThreshold(points)
        assertNotNull(fit)
        assertTrue("must climb past the false matches", fit!!.threshold > 0.72f)
        assertTrue(fit.precision!! >= 0.90)
    }

    // ------------------------------------------------- guards on going down

    @Test fun `two confirmed faces below the bar are not enough to move it`() {
        // Perfect precision, and still not a claim worth making: a handful of
        // points reaches 90% by being a handful of points, and the sample down
        // there is biased toward the top of the band.
        val points = many(2, 0.60f, true) + many(8, 0.80f, true)
        val fit = Fitter.fitThreshold(points)
        assertNotNull(fit)
        assertEquals(FaceEngine.MATCH_THRESHOLD, fit!!.threshold, 1e-6f)
        assertTrue("and it should say why", fit.note.contains("too little evidence"))
    }

    @Test fun `strangers below the bar keep it where it is`() {
        // The case the guard exists for. Six faces below the default, mostly
        // NOT the person -- exactly what a too-low bar looks like.
        val points = many(2, 0.60f, true) + many(6, 0.62f, false) + many(8, 0.85f, true)
        val fit = Fitter.fitThreshold(points)
        assertNotNull(fit)
        assertTrue("must not drop into a band full of strangers",
            fit!!.threshold >= FaceEngine.MATCH_THRESHOLD)
    }

    @Test fun `nothing is fitted below the absolute floor`() {
        // Calibration found the keep-rate ZERO in the two bands just under
        // 0.55: down there a near miss is evidence of a stranger, not weak
        // evidence of family. Even a pile of agreeing labels cannot buy it.
        val points = many(20, 0.40f, true)
        val fit = Fitter.fitThreshold(points)
        assertTrue("either no fit, or one at the floor",
            fit == null || fit.threshold >= Fitter.ABSOLUTE_FLOOR)
    }

    @Test fun `the floor itself is reachable with enough support`() {
        val points = many(8, Fitter.ABSOLUTE_FLOOR, true) + many(4, 0.90f, true)
        val fit = Fitter.fitThreshold(points)
        assertNotNull(fit)
        assertEquals(Fitter.ABSOLUTE_FLOOR, fit!!.threshold, 1e-6f)
    }

    // ------------------------------------------------------------ fail safe

    @Test fun `near misses alone hold the bar rather than condemning the person`() {
        // The real library's current state: one person has 25 discarded near
        // misses, two kept ones, and not a single reviewed match. Every point
        // is below the bar, so they say the bar is not too LOW and nothing
        // whatever about how it performs above. Clamping to the 0.85 fail-safe
        // on that would punish a person for evidence gathered entirely in the
        // band the bar already rejects.
        val points = many(25, 0.60f, false) + many(2, 0.62f, true)
        val fit = Fitter.fitThreshold(points)
        assertNotNull("must not fall through to the fail-safe", fit)
        assertEquals(FaceEngine.MATCH_THRESHOLD, fit!!.threshold, 1e-6f)
        assertNull("there is nothing above the bar to measure", fit.precision)
        assertTrue(fit.note.contains("nothing labelled above it"))
    }

    @Test fun `a bad centroid is still condemned when the evidence is above the bar`() {
        // The distinction that makes the test above safe: here the failures are
        // where the matcher is most confident, which is the fail-safe's case.
        val points = many(10, 0.78f, false) + many(4, 0.60f, true)
        assertNull(Fitter.fitThreshold(points))
    }

    @Test fun `a centroid that is wrong everywhere fits nothing`() {
        // No cut reaches the target, so the caller must fall back rather than
        // be handed a permissive number.
        assertNull(Fitter.fitThreshold(many(10, 0.75f, false)))
    }

    @Test fun `no points, no fit`() {
        assertNull(Fitter.fitThreshold(emptyList()))
    }

    // -------------------------------------------------------- the arithmetic

    @Test fun `the chosen cut is the lowest one that reaches the target`() {
        // 0.60 would be 50% precise, 0.70 is 100%. Both are candidates; the
        // search must not stop at the first one it sees.
        val points = many(6, 0.60f, false) + many(6, 0.60f, true) + many(6, 0.78f, true)
        val fit = Fitter.fitThreshold(points)
        assertNotNull(fit)
        assertTrue("0.60 is only 50% precise", fit!!.threshold > 0.60f)
        assertEquals(1.0, fit.precision!!, 1e-9)
    }

    @Test fun `precision is measured over what the threshold keeps, not everything`() {
        val points = many(10, 0.50f, false) + many(10, 0.90f, true)
        val fit = Fitter.fitThreshold(points)
        assertNotNull(fit)
        assertEquals("the ten rejected strangers must not count against it",
            1.0, fit!!.precision!!, 1e-9)
        assertEquals(10, fit.kept)
    }
}

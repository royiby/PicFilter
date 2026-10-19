package com.picfilter.spike.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three-state matching. The INDETERMINATE state is what keeps a face we could
 * not read from being silently treated as a stranger, so it is tested as
 * carefully as the match itself.
 */
class MatchFaceTest {

    private fun vec(vararg f: Float) = f.copyOf().also { l2(it) }

    private fun obs(embedding: FloatArray?, quality: Boolean = true) =
        FaceObs(origPx = 120, sharpness = 50.0, yaw = 0f, roll = 0f,
            embedding = embedding, quality = quality)

    private val alice = 1L
    private val bob = 2L

    @Test
    fun `a face below the quality gate is indeterminate, not a stranger`() {
        val (state, pid, sim) = matchFace(obs(vec(1f, 0f, 0f), quality = false),
            mapOf(alice to listOf(vec(1f, 0f, 0f))))
        assertEquals(MatchState.INDETERMINATE, state)
        assertNull(pid)
        assertNull(sim)
    }

    @Test
    fun `a missing embedding is indeterminate even when quality passed`() {
        assertEquals(
            MatchState.INDETERMINATE,
            matchFace(obs(null), mapOf(alice to listOf(vec(1f, 0f, 0f)))).first,
        )
    }

    @Test
    fun `with nobody enrolled every face is a clean miss`() {
        // Not INDETERMINATE: we read the face fine, there is just no one to match.
        val (state, pid, _) = matchFace(obs(vec(1f, 0f, 0f)), emptyMap())
        assertEquals(MatchState.NO_MATCH, state)
        assertNull(pid)
    }

    @Test
    fun `an identical embedding matches its person`() {
        val v = vec(1f, 2f, 3f)
        val (state, pid, sim) = matchFace(obs(v), mapOf(alice to listOf(v)))
        assertEquals(MatchState.MATCH, state)
        assertEquals(alice, pid)
        assertEquals(1f, sim!!, 1e-4f)
    }

    @Test
    fun `a person's score is the best of their centroids`() {
        // Multiple dated centroids per person: a child at 4 and at 7. Matching
        // the second must not be diluted by the first.
        val now = vec(0f, 1f, 0f)
        val centroids = mapOf(alice to listOf(vec(1f, 0f, 0f), now))
        val (state, pid, _) = matchFace(obs(now), centroids)
        assertEquals(MatchState.MATCH, state)
        assertEquals(alice, pid)
    }

    @Test
    fun `the nearest person is reported even on a miss`() {
        // The review screen leans on this: "probably אבי, at 0.52" is a useful
        // hint, and dropping the person on a miss would throw it away.
        val target = vec(1f, 0.6f, 0f)
        val centroids = mapOf(
            alice to listOf(vec(1f, 0f, 0f)),
            bob to listOf(vec(0f, 0f, 1f)),
        )
        val (state, pid, sim) = matchFace(obs(target), centroids, default = 0.99f)
        assertEquals(MatchState.NO_MATCH, state)
        assertEquals(alice, pid)
        assertTrue("similarity should still be reported", sim!! > 0.8f)
    }

    @Test
    fun `a per-person threshold overrides the default`() {
        val target = vec(1f, 0.35f, 0f)              // ~0.944 against alice
        val centroids = mapOf(alice to listOf(vec(1f, 0f, 0f)))
        // A person whose cluster was noisy gets a stricter bar than the default.
        assertEquals(
            MatchState.NO_MATCH,
            matchFace(obs(target), centroids, thresholds = mapOf(alice to 0.97f)).first,
        )
        assertEquals(
            MatchState.MATCH,
            matchFace(obs(target), centroids, thresholds = mapOf(alice to 0.90f)).first,
        )
    }

    @Test
    fun `a threshold set for one person does not leak to another`() {
        val target = vec(1f, 0.35f, 0f)
        val centroids = mapOf(alice to listOf(vec(1f, 0f, 0f)))
        // bob's strict threshold must not apply to a face nearest alice.
        assertEquals(
            MatchState.MATCH,
            matchFace(obs(target), centroids, thresholds = mapOf(bob to 0.99f), default = 0.7f).first,
        )
    }

    @Test
    fun `the shipped default threshold is the measured one`() {
        // 0.55 gave 47% precision; 0.70 gave 95%. A silent revert would quietly
        // start proposing family photos for deletion.
        assertEquals(0.70f, FaceEngine.MATCH_THRESHOLD, 1e-6f)
    }
}

/**
 * The failure that made the guard in [Pipeline.score] necessary.
 *
 * With people enrolled but an empty centroid set, every face reads as a clean
 * miss rather than as "cannot tell" -- so a crowd shot of the user's own family
 * becomes CROWD_NO_MATCH, a discard candidate. 290 images were mislabelled this
 * way before the guard existed. These tests pin the two halves of the reasoning:
 * matching really does return NO_MATCH on an empty set, and the decision rules
 * really do turn that into a discard. Neither is wrong on its own; together they
 * are why the scorer must refuse to run at all.
 */
class EmptyCentroidsTest {

    private fun vec(vararg f: Float) = f.copyOf().also { l2(it) }

    private fun obs(embedding: FloatArray?) =
        FaceObs(origPx = 120, sharpness = 50.0, yaw = 0f, roll = 0f,
            embedding = embedding, quality = true)

    @org.junit.Test
    fun `an empty centroid set reads as a clean miss, not as uncertainty`() {
        val (state, pid, sim) = matchFace(obs(vec(1f, 0f, 0f)), emptyMap())
        org.junit.Assert.assertEquals(MatchState.NO_MATCH, state)
        org.junit.Assert.assertNull(pid)
        org.junit.Assert.assertNull(sim)
    }

    @org.junit.Test
    fun `and a clean miss on a crowd is a discard candidate`() {
        val (decision, reason, _) = Pipeline.decide(
            faces = 3, matched = 0, indet = 0, textFrac = 0f, blocks = 0)
        org.junit.Assert.assertEquals(Decision.DISCARD_CANDIDATE, decision)
        org.junit.Assert.assertEquals("CROWD_NO_MATCH", reason)
    }
}

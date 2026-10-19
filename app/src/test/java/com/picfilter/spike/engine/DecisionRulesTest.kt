package com.picfilter.spike.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The decision rules decide which of the user's photos get proposed for
 * deletion, so the tests here are mostly about what must NEVER happen.
 */
class DecisionRulesTest {

    private fun decide(faces: Int, matched: Int, indet: Int, textFrac: Float = 0f, blocks: Int = 0) =
        Pipeline.decide(faces, matched, indet, textFrac, blocks)

    @Test
    fun `a matched face always wins, whatever else is in the frame`() {
        // A crowd shot with one family member is a keeper, not a crowd discard.
        assertEquals(Decision.KEEP, decide(faces = 12, matched = 1, indet = 4).first)
        assertEquals("ENROLLED_PERSON", decide(faces = 12, matched = 1, indet = 4).second)
    }

    @Test
    fun `a face we could not judge forces review, never a discard`() {
        assertEquals(Decision.REVIEW, decide(faces = 1, matched = 0, indet = 1).first)
        assertEquals(Decision.REVIEW, decide(faces = 9, matched = 0, indet = 1).first)
        // Even a text-heavy image: an unjudged face outranks the text signal.
        assertEquals(
            Decision.REVIEW,
            decide(faces = 1, matched = 0, indet = 1, textFrac = 0.9f, blocks = 40).first,
        )
    }

    @Test
    fun `discard is only ever proposed when every face was judged and missed`() {
        // Exhaustive over the shapes a scored image can take. This is the
        // safety property: auto-discard must never fire on an uncertain read.
        for (faces in 0..6) for (matched in 0..faces) for (indet in 0..(faces - matched).coerceAtLeast(0)) {
            for (textFrac in listOf(0f, 0.05f, 0.5f)) for (blocks in listOf(0, 3, 30)) {
                val (decision, reason, _) = decide(faces, matched, indet, textFrac, blocks)
                if (decision == Decision.DISCARD_CANDIDATE) {
                    assertEquals("discard with a match ($reason)", 0, matched)
                    assertEquals("discard with an unjudged face ($reason)", 0, indet)
                }
            }
        }
    }

    @Test
    fun `a crowd with no match is the discard case`() {
        val (decision, reason, conf) = decide(faces = 3, matched = 0, indet = 0)
        assertEquals(Decision.DISCARD_CANDIDATE, decision)
        assertEquals("CROWD_NO_MATCH", reason)
        assertEquals(0.8f, conf, 1e-6f)
    }

    @Test
    fun `one or two unmatched faces go to review, not the crowd rule`() {
        // Someone else's child at a party is not junk by itself.
        assertEquals(Decision.REVIEW, decide(faces = 1, matched = 0, indet = 0).first)
        assertEquals(Decision.REVIEW, decide(faces = 2, matched = 0, indet = 0).first)
        assertEquals("FEW_FACES_NO_MATCH", decide(faces = 2, matched = 0, indet = 0).second)
    }

    @Test
    fun `faceless text images are the junk the app exists for`() {
        // School-group forwards: a flyer, a screenshot, a sticker.
        assertEquals(
            Decision.DISCARD_CANDIDATE,
            decide(faces = 0, matched = 0, indet = 0, textFrac = 0.2f, blocks = 4).first,
        )
        assertEquals(
            Decision.DISCARD_CANDIDATE,
            decide(faces = 0, matched = 0, indet = 0, textFrac = 0.01f, blocks = 20).first,
        )
    }

    @Test
    fun `a faceless image with no text is only a review`() {
        // A landscape is not junk, and we must not guess that it is.
        val (decision, reason, _) = decide(faces = 0, matched = 0, indet = 0, textFrac = 0.01f, blocks = 2)
        assertEquals(Decision.REVIEW, decision)
        assertEquals("NO_FACE_PLAIN", reason)
        assertNotEquals(Decision.DISCARD_CANDIDATE, decision)
    }
}

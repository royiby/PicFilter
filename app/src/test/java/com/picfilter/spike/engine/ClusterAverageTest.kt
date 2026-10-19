package com.picfilter.spike.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos as kcos
import kotlin.math.sin

/**
 * Average-linkage clustering, which replaced greedy-centroid linkage after the
 * greedy version chained two people together through a bridging face and
 * produced a 205-face garbage cluster.
 */
class ClusterAverageTest {

    /** A unit vector at `deg` in the x-y plane; cosine similarity is then just the angle. */
    private fun at(deg: Double) = floatArrayOf(
        kcos(Math.toRadians(deg)).toFloat(), sin(Math.toRadians(deg)).toFloat(), 0f,
    )

    private fun groups(labels: IntArray) = labels.toList()
        .withIndex().groupBy({ it.value }, { it.index }).values.map { it.toSet() }.toSet()

    @Test
    fun `a bridging face does not chain two people into one cluster`() {
        // P at 0 and 5 degrees, Q at 60 and 65, and a bridge at 30 that sits
        // within single-linkage reach of both (cos 30 = 0.866 >= 0.85).
        val vecs = listOf(at(0.0), at(5.0), at(30.0), at(60.0), at(65.0))
        val labels = Clusterer.clusterAverage(vecs, 0.85f)

        assertEquals("two people, not one", 2, labels.distinct().size)
        // The bridge joins whichever side it is closer to on average, but the
        // two tight pairs must never end up in the same cluster.
        assertTrue("0 and 5 belong together", labels[0] == labels[1])
        assertTrue("60 and 65 belong together", labels[3] == labels[4])
        assertTrue("the two pairs must stay apart", labels[0] != labels[3])
    }

    @Test
    fun `identical faces collapse into one cluster`() {
        val v = at(10.0)
        val labels = Clusterer.clusterAverage(listOf(v, v, v), 0.9f)
        assertEquals(1, labels.distinct().size)
    }

    @Test
    fun `faces further apart than the threshold stay separate`() {
        val labels = Clusterer.clusterAverage(listOf(at(0.0), at(80.0)), 0.6f)
        assertEquals(2, labels.distinct().size)
    }

    @Test
    fun `a lower threshold merges what a higher one separates`() {
        val vecs = listOf(at(0.0), at(40.0))          // cos 40 = 0.766
        assertEquals(2, Clusterer.clusterAverage(vecs, 0.85f).distinct().size)
        assertEquals(1, Clusterer.clusterAverage(vecs, 0.70f).distinct().size)
    }

    @Test
    fun `an empty input is not a crash`() {
        assertEquals(0, Clusterer.clusterAverage(emptyList(), 0.6f).size)
    }

    @Test
    fun `labels are compacted to a dense range`() {
        // Downstream code indexes by label, so gaps would be a silent bug.
        val vecs = listOf(at(0.0), at(2.0), at(90.0), at(92.0), at(180.0))
        val labels = Clusterer.clusterAverage(vecs, 0.95f)
        assertEquals((0 until labels.distinct().size).toSet(), labels.toSet())
        assertEquals(setOf(setOf(0, 1), setOf(2, 3), setOf(4)), groups(labels))
    }

    @Test
    fun `the shipped clustering threshold is the measured one`() {
        assertEquals(0.60f, FaceEngine.CLUSTER_THRESHOLD, 1e-6f)
    }
}

package com.picfilter.spike.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class VecMathTest {

    @Test
    fun `l2 makes a unit vector`() {
        val v = floatArrayOf(3f, 4f, 0f)
        l2(v)
        assertEquals(1f, sqrt(v.sumOf { (it * it).toDouble() }).toFloat(), 1e-5f)
        assertEquals(0.6f, v[0], 1e-5f)
        assertEquals(0.8f, v[1], 1e-5f)
    }

    @Test
    fun `l2 leaves a zero vector alone rather than dividing by zero`() {
        val v = floatArrayOf(0f, 0f, 0f)
        l2(v)
        assertTrue(v.all { it.isFinite() })
    }

    @Test
    fun `cos is 1 for identical and -1 for opposite`() {
        val a = floatArrayOf(1f, 0f, 0f)
        assertEquals(1f, cos(a, a), 1e-6f)
        assertEquals(-1f, cos(a, floatArrayOf(-1f, 0f, 0f)), 1e-6f)
        assertEquals(0f, cos(a, floatArrayOf(0f, 1f, 0f)), 1e-6f)
    }

    @Test
    fun `cos is symmetric`() {
        val a = floatArrayOf(0.2f, 0.9f, -0.3f).also { l2(it) }
        val b = floatArrayOf(-0.5f, 0.4f, 0.7f).also { l2(it) }
        assertEquals(cos(a, b), cos(b, a), 1e-6f)
    }
}

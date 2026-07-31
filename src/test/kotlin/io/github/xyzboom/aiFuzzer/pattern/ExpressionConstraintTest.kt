package io.github.xyzboom.aiFuzzer.pattern

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ExpressionConstraintTest {

    @Test
    fun `test mul expression`() {
        val ec = ExpressionConstraint(
            dimIndices = listOf(1, 2),
            op = "mul",
            allowedValues = setOf(10, 14)
        )
        assertEquals(10, ec.evaluate(listOf(4, 5, 2)))
        assertEquals(14, ec.evaluate(listOf(4, 2, 7)))
        assertNotEquals(10, ec.evaluate(listOf(4, 2, 3)))
        assertNotEquals(14, ec.evaluate(listOf(4, 2, 3)))
    }

    @Test
    fun `test add expression`() {
        val ec = ExpressionConstraint(
            dimIndices = listOf(0, 1),
            op = "add",
            allowedValues = setOf(5)
        )
        assertEquals(5, ec.evaluate(listOf(2, 3)))
        assertEquals(5, ec.evaluate(listOf(1, 4)))
        assertNotEquals(5, ec.evaluate(listOf(2, 2)))
    }

    @Test
    fun `test sub expression`() {
        val ec = ExpressionConstraint(
            dimIndices = listOf(0, 1),
            op = "sub",
            allowedValues = setOf(1)
        )
        assertEquals(1, ec.evaluate(listOf(5, 4)))
        assertEquals(-1, ec.evaluate(listOf(4, 5)))
        assertEquals(1, ec.evaluate(listOf(3, 2)))
    }

    @Test
    fun `test mod expression`() {
        val ec = ExpressionConstraint(
            dimIndices = listOf(0, 1),
            op = "mod",
            allowedValues = setOf(0)
        )
        assertEquals(0, ec.evaluate(listOf(6, 3)))
        assertEquals(1, ec.evaluate(listOf(7, 3)))
        assertEquals(1, ec.evaluate(listOf(5, 2)))
    }

    @Test
    fun `test mul with 3 dims`() {
        val ec = ExpressionConstraint(
            dimIndices = listOf(1, 2, 3),
            op = "mul",
            allowedValues = setOf(10, 14)
        )
        assertEquals(10, ec.evaluate(listOf(4, 1, 2, 5)))
        assertEquals(10, ec.evaluate(listOf(4, 2, 1, 5)))
        assertEquals(10, ec.evaluate(listOf(4, 2, 5, 1)))
        assertNotEquals(10, ec.evaluate(listOf(1, 4, 2, 5)))
        assertNotEquals(14, ec.evaluate(listOf(1, 4, 2, 5)))
    }

    @Test
    fun `test out of bounds index returns null`() {
        val ec = ExpressionConstraint(
            dimIndices = listOf(5),
            op = "mul",
            allowedValues = setOf(10)
        )
        assertNull(ec.evaluate(listOf(1, 2, 3)))
    }

    @Test
    fun `test null value in dims returns null`() {
        val ec = ExpressionConstraint(
            dimIndices = listOf(1),
            op = "mul",
            allowedValues = setOf(10)
        )
        assertNull(ec.evaluate(listOf(1, null, 3)))
    }

    @Test
    fun `test mod by zero returns null`() {
        val ec = ExpressionConstraint(
            dimIndices = listOf(0, 1),
            op = "mod",
            allowedValues = setOf(0)
        )
        assertNull(ec.evaluate(listOf(5, 0)))
    }

    @Test
    fun `test unknown op returns null`() {
        val ec = ExpressionConstraint(
            dimIndices = listOf(0, 1),
            op = "unknown",
            allowedValues = setOf(10)
        )
        assertNull(ec.evaluate(listOf(2, 5)))
    }

    @Test
    fun `test mul with divisors`() {
        val ec = ExpressionConstraint(
            dimIndices = listOf(2, 3),
            op = "mul",
            divisors = listOf(2, 2),
            allowedValues = setOf(2, 3, 5, 10)
        )
        assertEquals(10, ec.evaluate(listOf(1, 4, 10, 4)))
        assertEquals(2, ec.evaluate(listOf(4, 5, 2, 4)))
        assertEquals(2, ec.evaluate(listOf(4, 5, 3, 5)))
        assertEquals(3, ec.evaluate(listOf(4, 6, 6, 2)))
        assertEquals(1, ec.evaluate(listOf(1, 4, 2, 3)))
        assertEquals(6, ec.evaluate(listOf(1, 4, 6, 5)))
    }

    @Test
    fun `test add with divisors`() {
        val ec = ExpressionConstraint(
            dimIndices = listOf(0, 1),
            op = "add",
            divisors = listOf(2, 2),
            allowedValues = setOf(3)
        )
        assertNotEquals(3, ec.evaluate(listOf(5, 4)))
        assertEquals(3, ec.evaluate(listOf(4, 2)))
    }

    @Test
    fun `test excludeWhen blocks matching shape`() {
        val ec = ExpressionConstraint(
            dimIndices = listOf(0, 1, 2, 3),
            op = "mul",
            allowedValues = setOf(40, 56, 112),
            excludeWhen = listOf(
                ExpressionConstraint(
                    dimIndices = listOf(0),
                    op = "mul",
                    allowedValues = setOf(2)
                )
            )
        )
        assertTrue(ec.matches(listOf(1, 4, 2, 5)))
        assertTrue(ec.matches(listOf(4, 1, 2, 5)))
        assertFalse(ec.matches(listOf(2, 4, 2, 7)))
        assertFalse(ec.matches(listOf(2, 2, 2, 5)))
    }

    @Test
    fun `test excludeWhen with divisors`() {
        val ec = ExpressionConstraint(
            dimIndices = listOf(1, 2, 3),
            op = "mul",
            divisors = listOf(1, 2, 2),
            allowedValues = setOf(10, 14, 18, 40),
            excludeWhen = listOf(
                ExpressionConstraint(
                    dimIndices = listOf(1),
                    op = "mul",
                    allowedValues = setOf(1)
                )
            )
        )
        assertTrue(ec.matches(listOf(1, 4, 10, 4)))
        assertFalse(ec.matches(listOf(1, 1, 10, 4)))
    }
}
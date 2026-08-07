package io.github.xyzboom.aiFuzzer.pattern

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * 值域分析测试：验证 ValueRangeAnalyzer 和 Pattern 值域约束。
 */
class ValueRangeAnalyzerTest {

    private fun analyze(op: String, inputs: List<ValueRange>, attrs: Map<String, Any> = emptyMap()): ValueRange =
        ValueRangeAnalyzer.outputRange(op, inputs, attrs)

    // ===== ValueRangeAnalyzer 测试 =====

    @Test
    fun `ZEROS produces exact zero range`() {
        val r = analyze("ZEROS", emptyList())
        assertTrue(r.isKnown)
        assertEquals(0.0, r.min)
        assertEquals(0.0, r.max)
    }

    @Test
    fun `ONES produces exact one range`() {
        val r = analyze("ONES", emptyList())
        assertTrue(r.isKnown)
        assertEquals(1.0, r.min)
        assertEquals(1.0, r.max)
    }

    @Test
    fun `FULL reads fill_value`() {
        val r = analyze("FULL", emptyList(), mapOf("fill_value" to "0.5"))
        assertTrue(r.isKnown)
        assertEquals(0.5, r.min)
    }

    @Test
    fun `SQRT on non-negative input is finite`() {
        val r = analyze("SQRT", listOf(ValueRange.range(4.0, 16.0)))
        assertTrue(r.isKnown)
        assertEquals(2.0, r.min)
        assertEquals(4.0, r.max)
    }

    @Test
    fun `SQRT on possibly-negative input is unknown`() {
        val r = analyze("SQRT", listOf(ValueRange.range(-1.0, 16.0)))
        assertFalse(r.isKnown)
    }

    @Test
    fun `RECIPROCAL on zero-containing input is unknown`() {
        val r = analyze("RECIPROCAL", listOf(ValueRange.range(-1.0, 1.0)))
        assertFalse(r.isKnown)
    }

    @Test
    fun `RECIPROCAL on positive input is finite`() {
        val r = analyze("RECIPROCAL", listOf(ValueRange.range(1.0, 4.0)))
        assertTrue(r.isKnown)
    }

    @Test
    fun `ADD combines ranges`() {
        val r = analyze("ADD", listOf(ValueRange.range(1.0, 2.0), ValueRange.range(3.0, 4.0)))
        assertTrue(r.isKnown)
        assertEquals(4.0, r.min)
        assertEquals(6.0, r.max)
    }

    // ===== ValueRangeMatcher.fromJson 测试 =====

    @Test
    fun `parse contains_zero matcher`() {
        val m = ValueRangeMatcher.fromJson(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"${'$'}contains_zero": true}""")
        )
        assertTrue(ValueRangeMatcher.ContainsZero == m)
        assertTrue(m.matches(ValueRange.range(-1.0, 1.0)))
        assertFalse(m.matches(ValueRange.range(1.0, 2.0)))
    }

    @Test
    fun `parse non_negative matcher`() {
        val m = ValueRangeMatcher.fromJson(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"${'$'}non_negative": true}""")
        )
        assertTrue(ValueRangeMatcher.NonNegative == m)
        assertTrue(m.matches(ValueRange.range(0.0, 5.0)))
        assertFalse(m.matches(ValueRange.range(-1.0, 5.0)))
    }

    @Test
    fun `parse string matcher`() {
        val m = ValueRangeMatcher.fromJson(
            kotlinx.serialization.json.Json.parseToJsonElement("\"contains_zero\"")
        )
        assertTrue(ValueRangeMatcher.ContainsZero == m)
    }

    @Test
    fun `ZEROS range matches contains_zero`() {
        val zeros = analyze("ZEROS", emptyList())
        assertTrue(ValueRangeMatcher.ContainsZero.matches(zeros))
    }

    @Test
    fun `ONES range does not match contains_zero`() {
        val ones = analyze("ONES", emptyList())
        assertFalse(ValueRangeMatcher.ContainsZero.matches(ones))
    }
}
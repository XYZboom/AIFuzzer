package io.github.xyzboom.aiFuzzer.fuzzer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * 验证 ErrorAnalyzer 正确处理跨目标差分测试的 [DIFF-MISMATCH] 错误。
 */
class CrossTargetDiffErrorTest {

    @Test
    fun `detect DIFF-MISMATCH as CROSS_TARGET_DIFF`() {
        val stderr = """
[DIFF-MISMATCH] graph_0: CPU vs GPU mismatch, max_diff=2.5
Some other output
""".trim()
        val info = ErrorAnalyzer.analyze(stderr, 2)
        assertEquals(ErrorCategory.CROSS_TARGET_DIFF, info.category,
            "Expected CROSS_TARGET_DIFF, got ${info.category}")
        assertTrue(info.summary.contains("[DIFF-MISMATCH]"),
            "Summary should contain DIFF-MISMATCH: ${info.summary}")
    }

    @Test
    fun `shape mismatch also detected as CROSS_TARGET_DIFF`() {
        val stderr = """
[DIFF-MISMATCH] graph_0: shape mismatch CPU=[4, 16] GPU=[4, 8]
""".trim()
        val info = ErrorAnalyzer.analyze(stderr, 2)
        assertEquals(ErrorCategory.CROSS_TARGET_DIFF, info.category)
    }

    @Test
    fun `no DIFF-MISMATCH falls through to other categories`() {
        val stderr = "TVMError: Some internal error"
        val info = ErrorAnalyzer.analyze(stderr, 1)
        assertEquals(ErrorCategory.TVM_ERROR, info.category)
    }

    @Test
    fun `successful diff does not trigger CROSS_TARGET_DIFF`() {
        val stdout = "Execution: OK (CPU vs GPU match)"
        val info = ErrorAnalyzer.analyze("", 0)
        assertEquals(ErrorCategory.NONE, info.category)
    }
}
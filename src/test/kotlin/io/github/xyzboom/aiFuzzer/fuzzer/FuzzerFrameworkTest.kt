package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.ir.builder.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class FuzzerFrameworkTest {

    @Test
    fun `error analyzer should detect syntax errors`() {
        val stderr = """
            Traceback (most recent call last):
              File "program.py", line 10
                v_0 = bb.emit(relax.op.relu(v_0))
                      ^
            SyntaxError: invalid syntax
        """.trimIndent()
        val info = ErrorAnalyzer.analyze(stderr, 1)
        assertEquals(ErrorCategory.SYNTAX_ERROR, info.category)
    }

    @Test
    fun `error analyzer should detect type errors`() {
        val stderr = """
            Traceback (most recent call last):
              File "program.py", line 15, in build_mod
                v_3 = bb.emit(relax.op.add(v_0, v_1))
            TypeError: expected Tensor, got str
        """.trimIndent()
        val info = ErrorAnalyzer.analyze(stderr, 1)
        assertEquals(ErrorCategory.TYPE_ERROR, info.category)
    }

    @Test
    fun `error analyzer should detect TVM errors`() {
        val stderr = """
            Traceback (most recent call last):
              File "program.py", line 12, in build_mod
            tvm.error.TVMError: Check failed: ...
        """.trimIndent()
        val info = ErrorAnalyzer.analyze(stderr, 1)
        assertEquals(ErrorCategory.TVM_ERROR, info.category)
    }

    @Test
    fun `error analyzer should detect attribute errors`() {
        val stderr = """
            AttributeError: module 'tvm.relax.op' has no attribute 'foo'
        """.trimIndent()
        val info = ErrorAnalyzer.analyze(stderr, 1)
        assertEquals(ErrorCategory.ATTRIBUTE_ERROR, info.category)
    }

    @Test
    fun `error analyzer should report none on success`() {
        val info = ErrorAnalyzer.analyze("", 0)
        assertEquals(ErrorCategory.NONE, info.category)
    }

    @Test
    fun `TvmBackend checkEnvironment should work when TVM installed`() {
        val backend = TvmBackend(File(System.getProperty("java.io.tmpdir"), "test_tvm_backend"))
        // 这里假设 TVM 已安装，如果环境没装 TVM 这个测试会跳过
        val envReady = backend.checkEnvironment()
        if (envReady) {
            println("TVM environment is ready")
        } else {
            println("TVM not available, skipping runtime test")
        }
    }

    @Test
    fun `pipeline should run with multiple backends`() {
        val generator = UirGenerator(GeneratorConfig(seed = 1, minNodesPerGraph = 2, maxNodesPerGraph = 4))
        val backends = listOf(
            TvmBackend(File(System.getProperty("java.io.tmpdir"), "test_fuzzer_multi"))
        )
        val pipeline = FuzzingPipeline(
            generator = generator,
            backends = backends,
            config = FuzzingPipeline.FuzzingConfig(keepArtifacts = false)
        )
        val results = pipeline.runOnce(seed = 42)
        assertTrue(results.isNotEmpty())
        assertEquals(1, results.size)
        assertEquals("TVM Relax", results[0].backendName)
    }

    @Test
    fun `batch run should collect statistics`() {
        val generator = UirGenerator(GeneratorConfig(seed = 1, minNodesPerGraph = 2, maxNodesPerGraph = 3))
        val backends = listOf(
            TvmBackend(File(System.getProperty("java.io.tmpdir"), "test_fuzzer_batch"))
        )
        val pipeline = FuzzingPipeline(
            generator = generator,
            backends = backends,
            config = FuzzingPipeline.FuzzingConfig(
                keepArtifacts = false,
                reportInterval = 5,
            )
        )
        val summary = pipeline.runBatch(count = 3, startSeed = 1)
        assertEquals(3, summary.total)
        assertTrue(summary.totalTimeMs >= 0)
        summary.printReport()
    }
}
package io.github.xyzboom.aiFuzzer.config

import io.github.xyzboom.aiFuzzer.fuzzer.FuzzingPipeline
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PipelineConfigDedupTest {

    @Test
    fun `dedup enabled config propagates to FuzzingConfig`() {
        val cfg = PipelineConfig(
            workers = 4,
            batchSize = 200,
            dedup = PipelineConfig.DedupConfig(
                enabled = true,
                compiler = "tvm",
                target = "cuda",
                patternDir = "/tmp/patterns",
            ),
        )
        val fc = cfg.toFuzzingConfig()
        assertTrue(fc.dedup.enabled, "dedup.enabled should be true")
        assertEquals("tvm", fc.dedup.compiler)
        assertEquals("cuda", fc.dedup.target)
        assertEquals("/tmp/patterns", fc.dedup.patternDir)
    }

    @Test
    fun `dedup disabled by default`() {
        val cfg = PipelineConfig()
        val fc = cfg.toFuzzingConfig()
        assertFalse(fc.dedup.enabled, "default dedup.enabled should be false")
        assertEquals("tvm", fc.dedup.compiler)
        assertEquals("llvm", fc.dedup.target)
        assertTrue(fc.dedup.patternDir.isEmpty())
    }

    @Test
    fun `reducer disabled produces null config`() {
        val cfg = PipelineConfig(reducer = PipelineConfig.ReducerConfig(enabled = false))
        val fc = cfg.toFuzzingConfig()
        assertNull(fc.reducerConfig, "reducer disabled should be null")
    }

    @Test
    fun `reducer enabled produces valid config`() {
        val cfg = PipelineConfig(reducer = PipelineConfig.ReducerConfig(enabled = true))
        val fc = cfg.toFuzzingConfig()
        assertNotNull(fc.reducerConfig)
        assertTrue(fc.reducerConfig!!.enabled)
    }

    @Test
    fun `dedup and reducer both work together`() {
        val cfg = PipelineConfig(
            workers = 4,
            batchSize = 200,
            dedup = PipelineConfig.DedupConfig(
                enabled = true,
                compiler = "tvm",
                target = "llvm",
                patternDir = "",
            ),
            reducer = PipelineConfig.ReducerConfig(enabled = true),
        )
        val fc = cfg.toFuzzingConfig()
        assertTrue(fc.dedup.enabled)
        assertEquals("tvm", fc.dedup.compiler)
        assertEquals("llvm", fc.dedup.target)
        assertNotNull(fc.reducerConfig)
        assertTrue(fc.reducerConfig!!.enabled)
        assertEquals(4, fc.workers)
        assertEquals(60, fc.runTimeoutSeconds) // default, not set from batchSize
    }
}
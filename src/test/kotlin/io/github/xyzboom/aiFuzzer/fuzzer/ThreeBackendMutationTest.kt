package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.config.MutationConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * 30 轮，3 后端（TVM CPU + PyTorch CPU + ONNX CPU），变异 maxMutations=5。
 */
class ThreeBackendMutationTest {

    private fun cpuOnlyBackend(name: String): FuzzerBackend {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "aifuzzer_3b_mut_${System.currentTimeMillis()}")
        tmpDir.mkdirs()
        return when (name) {
            "tvm" -> TvmBackend(tmpDir)
            "pytorch" -> PytorchBackend(tmpDir)
            "onnx" -> OnnxBackend(tmpDir)
            else -> throw IllegalArgumentException("Unknown backend: $name")
        }
    }

    @Test
    fun `30 rounds 3 backends mutation 5`() {
        // Backends: TVM CPU, PyTorch CPU, ONNX CPU
        val tvmDir = File(System.getProperty("java.io.tmpdir"), "aifuzzer_3bm_tvm")
        tvmDir.mkdirs()
        val pytorchDir = File(System.getProperty("java.io.tmpdir"), "aifuzzer_3bm_pytorch")
        pytorchDir.mkdirs()
        val onnxDir = File(System.getProperty("java.io.tmpdir"), "aifuzzer_3bm_onnx")
        onnxDir.mkdirs()

        val backends = listOf(
            TvmBackend(tvmDir, target = "llvm", device = "cpu"),
            PytorchBackend(pytorchDir, device = "cpu"),
            OnnxBackend(onnxDir),
        )

        // Check environment
        println("=== Backend Environment Check ===")
        val ready = backends.filter { b ->
            val ok = b.checkEnvironment()
            println("  ${b.name}: ${if (ok) "✓" else "✗"} ")
            ok
        }
        if (ready.isEmpty()) {
            backends.forEach { it.close() }
            fail("No backend ready")
        }
        println("Ready backends: ${ready.map { it.name }}")

        val genConfig = GeneratorConfig(
            seed = 42,
            minNodesPerGraph = 4,
            maxNodesPerGraph = 12,
            graphCount = 1..2,
            minInputs = 1,
            maxInputs = 3,
            avoidNaNInf = false,
            avoidExtremeOps = false,
            mutationConfig = MutationConfig(
                enabled = true,
                rate = 0.4,
                maxMutations = 5,
                maxSeeds = 100,
                opMutation = true,
                insertMutation = true,
                deleteMutation = true,
                attributeMutation = true,
            ),
        )

        val pipeline = FuzzingPipeline(
            generatorConfig = genConfig,
            backends = ready,
            config = FuzzingPipeline.FuzzingConfig(
                keepArtifacts = false,
                seedBasedMutation = true,
            ),
        )

        println()
        println("=== Running 30 rounds ===")
        val summary = pipeline.runBatch(SeedSequence.range(startSeed = 100, count = 30))

        println()
        summary.printReport()

        // Cleanup
        backends.forEach { it.close() }

        // Assertions
        assertEquals(30, summary.total)
        assertTrue(summary.successRate >= 0.5, "Success rate too low: ${"%.1f".format(summary.successRate * 100)}%")

        println()
        println("=== Per-backend breakdown ===")
        for ((backendName, count) in summary.totalByBackend) {
            val successCount = summary.successesByBackend.getOrDefault(backendName, 0)
            val failCount = count - successCount
            println("  $backendName: $successCount success, $failCount failures (total $count)")
        }
    }
}

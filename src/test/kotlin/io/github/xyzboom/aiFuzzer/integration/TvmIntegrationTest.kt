package io.github.xyzboom.aiFuzzer.integration

import io.github.xyzboom.aiFuzzer.fuzzer.FuzzingPipeline
import io.github.xyzboom.aiFuzzer.fuzzer.TvmBackend
import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import org.junit.jupiter.api.Test
import java.io.File

class TvmIntegrationTest {

    @Test
    fun `fuzz TVM with 50 programs`() {
        val backend = TvmBackend(File(System.getProperty("java.io.tmpdir"), "tvm_integration_test"))
        if (!backend.checkEnvironment()) {
            println("⚠ TVM not available, skipping")
            return
        }
        val generator = UirGenerator(GeneratorConfig(
            seed = 42, minNodesPerGraph = 2, maxNodesPerGraph = 5,
            graphCount = 1, minInputs = 1, maxInputs = 3,
        ))
        val pipeline = FuzzingPipeline(
            generator = generator, backends = listOf(backend),
            config = FuzzingPipeline.FuzzingConfig(keepArtifacts = true, reportInterval = 10)
        )
        val summary = pipeline.runBatch(count = 50, startSeed = 10001)
        summary.printReport()
    }
}

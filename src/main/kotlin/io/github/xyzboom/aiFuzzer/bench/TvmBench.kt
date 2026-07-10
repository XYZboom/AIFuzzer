package io.github.xyzboom.aiFuzzer.bench

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.fuzzer.FuzzingPipeline
import io.github.xyzboom.aiFuzzer.fuzzer.TvmBackend
import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import java.io.File

private val log = KotlinLogging.logger {}

/**
 * TVM 编译器基准测试。
 *
 * 运行 Fuzzer 并输出 TVM Relax 后端的测试结果。
 * 属于 "测试 AI 编译器" 的代码，独立于 "测试本项目" 的测试。
 */
class TvmBench {

    /**
     * 运行指定次数的 Fuzzing 测试。
     */
    fun run(count: Int = 50, startSeed: Long = 10001) {
        val backend = TvmBackend(
            File(System.getProperty("java.io.tmpdir"), "tvm_integration_test")
        )
        if (!backend.checkEnvironment()) {
            log.warn { "TVM 不可用，跳过" }
            return
        }
        val generator = UirGenerator(GeneratorConfig(
            seed = 42, minNodesPerGraph = 2, maxNodesPerGraph = 5,
            graphCount = 1, minInputs = 1, maxInputs = 3,
        ))
        val pipeline = FuzzingPipeline(
            generator = generator, backends = listOf(backend),
            config = FuzzingPipeline.FuzzingConfig(
                keepArtifacts = true,
                workers = 16
            )
        )
        val summary = pipeline.runBatch(count = count, startSeed = startSeed)
        summary.printReport()
    }
}

fun main(args: Array<String>) {
    val count = args.getOrNull(0)?.toIntOrNull() ?: 50
    val startSeed = args.getOrNull(1)?.toLongOrNull() ?: 10001L
    log.info { "TVM Bench: count=$count, startSeed=$startSeed" }
    TvmBench().run(count = count, startSeed = startSeed)
}
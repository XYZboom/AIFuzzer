package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 可配置的 Fuzzing 流水线。
 *
 * 生成 → 执行 → 收集 → 分析
 */
class FuzzingPipeline(
    private val generator: UirGenerator = UirGenerator(),
    private val backends: List<Backend<*>>,
    private val config: FuzzingConfig = FuzzingConfig(),
) {
    data class FuzzingConfig(
        val runTimeoutSeconds: Int = 60,
        val workers: Int = 1,
        val keepArtifacts: Boolean = false,
        val reportInterval: Int = 10,
    )

    /**
     * 单次 Fuzzing 运行。
     */
    fun runOnce(seed: Long = System.currentTimeMillis()): List<FuzzingResult> {
        val program = generator.generate()
        return backends.map { backend ->
            runOnBackend(program, backend, seed)
        }
    }

    private fun runOnBackend(program: UirProgram, backend: Backend<*>, seed: Long): FuzzingResult {
        val startTime = System.currentTimeMillis()
        val result = backend.execute(program)
        val elapsed = System.currentTimeMillis() - startTime

        // 如果后端返回的是 TvmResult 等具体类型，提取错误信息
        val errorCategory = when (result) {
            is TvmBackend.TvmResult -> result.errorCategory
            else -> ErrorCategory.UNKNOWN
        }
        val errorSummary = when (result) {
            is TvmBackend.TvmResult -> result.errorSummary
            else -> result.stderr.take(200)
        }

        return FuzzingResult(
            seed = seed,
            backendName = backend.name,
            backendResult = result,
            errorCategory = errorCategory,
            errorSummary = errorSummary,
        )
    }

    /**
     * 批量运行，可选并行。
     */
    fun runBatch(count: Int, startSeed: Long = 1): FuzzingSummary {
        val allResults = mutableListOf<FuzzingResult>()
        val startTime = System.currentTimeMillis()

        if (config.workers <= 1) {
            // 串行运行
            for (i in 0 until count) {
                val seed = startSeed + i
                printStatus(i, count, seed)
                allResults.addAll(runOnce(seed))
            }
        } else {
            // 并行运行
            val executor = Executors.newFixedThreadPool(config.workers)
            val tasks = (0 until count).map { i ->
                val seed = startSeed + i
                Callable {
                    runOnce(seed)
                }
            }
            try {
                val futures = executor.invokeAll(tasks, config.runTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                futures.forEach { future ->
                    if (future.isDone && !future.isCancelled) {
                        allResults.addAll(future.get())
                    }
                }
            } finally {
                executor.shutdown()
            }
        }

        if (!config.keepArtifacts) {
            backends.filterIsInstance<TvmBackend>().forEach { it.cleanup() }
        }

        return FuzzingSummary.fromResults(allResults, System.currentTimeMillis() - startTime)
    }

    private fun printStatus(current: Int, total: Int, seed: Long) {
        if (current % config.reportInterval == 0 || current == total - 1) {
            println("[${current + 1}/$total] seed=$seed")
        }
    }
}
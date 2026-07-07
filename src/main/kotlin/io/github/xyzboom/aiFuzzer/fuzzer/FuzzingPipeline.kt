package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlin.time.Duration.Companion.milliseconds

/**
 * 可配置的 Fuzzing 流水线。
 *
 * 生成 → 执行 → 收集 → 分析
 *
 * 并行模式使用 Kotlin 协程管理，每个测试有独立的超时时间。
 */
class FuzzingPipeline(
    private val generator: UirGenerator = UirGenerator(),
    private val backends: List<Backend<*>>,
    private val config: FuzzingConfig = FuzzingConfig(),
) {
    data class FuzzingConfig(
        /** 每个测试的超时时间（秒），0 表示不限时 */
        val runTimeoutSeconds: Int = 60,
        /** 并行 worker 数（≤1 时串行） */
        val workers: Int = 1,
        /** 是否保留临时产物 */
        val keepArtifacts: Boolean = false,
    )

    @OptIn(DelicateCoroutinesApi::class)
    private val dispatcher: CoroutineDispatcher = when {
        config.workers <= 1 -> Dispatchers.Unconfined // 串行时不切换线程
        else -> newFixedThreadPoolContext(config.workers, "fuzzer-worker")
    }

    /**
     * 单次 Fuzzing 运行（单线程，调用方负责上下文）。
     */
    fun runOnce(seed: Long = System.currentTimeMillis()): List<FuzzingResult> {
        val program = generator.generate()
        return backends.map { backend ->
            runOnBackend(program, backend, seed)
        }
    }

    /**
     * 批量运行，协程并行调度。
     *
     * 每个测试有独立的 [FuzzingConfig.runTimeoutSeconds] 超时时间。
     * 超时的测试将被取消并记录为超时结果。
     */
    fun runBatch(count: Int, startSeed: Long = 1): FuzzingSummary {
        BugCollector.reset()
        val allResults = mutableListOf<FuzzingResult>()
        val startTime = System.currentTimeMillis()

        runBlocking(dispatcher) {
            // 信号量控制并发数
            val semaphore = Semaphore(config.workers.coerceAtLeast(1))

            coroutineScope {
                (0 until count).map { i ->
                    val seed = startSeed + i
                    async {
                        if (config.workers > 1) {
                            semaphore.acquire()
                        }
                        try {
                            printStatus(i, count, seed)

                            // 每个单独测试的超时
                            val results = if (config.runTimeoutSeconds > 0) {
                                withTimeout((config.runTimeoutSeconds * 1000L).milliseconds) {
                                    runOnce(seed)
                                }
                            } else {
                                runOnce(seed)
                            }
                            results
                        } catch (_: TimeoutCancellationException) {
                            // 超时：生成一个占位结果
                            println("[!] Test seed=$seed timed out after ${config.runTimeoutSeconds}s")
                            backends.map { backend ->
                                FuzzingResult(
                                    seed = seed,
                                    backendName = backend.name,
                                    backendResult = object : BackendResult(false, -1, "", "", 0) {},
                                    errorCategory = ErrorCategory.TIMEOUT,
                                    errorSummary = "timed out after ${config.runTimeoutSeconds}s",
                                )
                            }
                        } catch (e: CancellationException) {
                            // 全局取消
                            throw e
                        } catch (e: Exception) {
                            println("[!] Test seed=$seed threw ${e.javaClass.simpleName}: ${e.message}")
                            backends.map { backend ->
                                FuzzingResult(
                                    seed = seed,
                                    backendName = backend.name,
                                    backendResult = object : BackendResult(false, -1, "", e.message ?: "", 0) {
                                    },
                                    errorCategory = ErrorCategory.UNKNOWN,
                                    errorSummary = e.message ?: "unknown",
                                )
                            }
                        } finally {
                            if (config.workers > 1) {
                                semaphore.release()
                            }
                        }
                    }
                }.awaitAll().flatten().also { allResults.addAll(it) }
            }
        }

        if (dispatcher is ExecutorCoroutineDispatcher) {
            dispatcher.close()
        }

        // 清理临时产物
        if (!config.keepArtifacts) {
            backends.filterIsInstance<TvmBackend>().forEach { it.cleanup() }
        }

        // 关闭所有 backend（daemon 进程等）
        backends.forEach { it.close() }

        return FuzzingSummary.fromResults(allResults, System.currentTimeMillis() - startTime)
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

        // 获取源码内容（用于 bug 报告）
        val sourceCode = when (result) {
            is TvmBackend.TvmResult -> {
                // 从 TvmResult.sourceFile 读回源码
                try {
                    java.io.File(result.sourceFile).readText()
                } catch (_: Exception) {
                    null
                }
            }
            else -> null
        }

        // 自动收集疑似 bug（传入 UirProgram 和源码，生成文件夹报告）
        BugCollector.collect(
            result = result,
            seed = seed,
            backendName = backend.name,
            program = program,
            sourceCode = sourceCode,
        )

        return FuzzingResult(
            seed = seed,
            backendName = backend.name,
            backendResult = result,
            errorCategory = errorCategory,
            errorSummary = errorSummary,
        )
    }

    private fun printStatus(current: Int, total: Int, seed: Long) {
        println("[${current + 1}/$total] seed=$seed")
    }
}
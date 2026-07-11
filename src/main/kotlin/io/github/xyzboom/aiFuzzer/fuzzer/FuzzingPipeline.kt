package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private val log = KotlinLogging.logger {}

/**
 * 可配置的 Fuzzing 流水线。
 *
 * 生成 → 执行 → 收集 → 分析
 *
 * 并行模式使用 Java ThreadPool + Future 确保可中断超时。
 * 串行模式直接在主线程执行。
 *
 * 注意：[generatorConfig] 用于每次生成时创建新的 [UirGenerator] 实例，
 * 避免16线程并发共享可变状态的问题。
 *
 * 并行模式下，backend 列表会根据 workers 数量扩展：
 * - 如果只有一个 backend 且 workers > 1，会创建 workers 个 backend 副本
 * - 每个 worker 线程使用独立的 backend 实例（避免 daemon 竞争）
 */
class FuzzingPipeline(
    private val generatorConfig: GeneratorConfig = GeneratorConfig(),
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
        /** 遇到失败是否立即终止 */
        val failFast: Boolean = false,
    )

    /**
     * 单次 Fuzzing 运行（单线程，调用方负责上下文）。
     * 每次调用创建新的 [UirGenerator] 实例，确保线程安全。
     */
    fun runOnce(seed: Long = System.currentTimeMillis()): List<FuzzingResult> {
        log.debug { "运行单次测试: seed=$seed" }
        // 每次创建新的 generator，避免共享可变状态
        val generator = UirGenerator(generatorConfig.copy(seed = seed))
        val program = generator.generate()
        log.trace { "生成程序: ${program.graphs.size} 个图" }
        return backends.map { backend ->
            runOnBackend(program, backend, seed)
        }
    }

    /**
     * 批量运行，协程并行调度。
     *
     * 每个测试有独立的 [FuzzingConfig.runTimeoutSeconds] 超时时间。
     * 超时的测试将被取消并记录为超时结果。
     *
     * 注意：daemon 执行是同步阻塞调用，Kotlin 协程的 withTimeout 无法打断
     * 阻塞在 synchronized / readLine 中的线程。因此超时由两端共同保证：
     * - 客户端侧：DaemonClient 层有 requestTimeoutMs 超时，超时后杀 daemon
     * - 服务端侧：tvm_daemon.py 有 signal.alarm 超时保护
     * - 并行模式：改用 Thread + Future 确保超时可中断
     */
    fun runBatch(count: Int, startSeed: Long = 1): FuzzingSummary {
        BugCollector.reset()
        val allResults = java.util.Collections.synchronizedList(mutableListOf<FuzzingResult>())
        val startTime = System.currentTimeMillis()

        // 原子计数器：已完成数、成功数、失败数
        val completed = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        
        // 并行模式下，为每个 worker 创建独立的 backend 副本
        // 这样每个线程有自己的 daemon，避免竞争
        val backendPool: Array<List<Backend<*>>> = if (config.workers > 1) {
            Array(config.workers) { backends.map { it.createCopy() } }
        } else {
            arrayOf(backends)
        }

        // 定时报告线程（每 5 秒）
        val progressReporter = thread(name = "fuzzer-progress") {
            var lastCompleted = 0
            while (completed.get() < count) {
                Thread.sleep(5000)
                val now = completed.get()
                val rate = if (now - lastCompleted > 0) {
                    "${((now - lastCompleted).toDouble() / 5.0).toInt()}/s"
                } else { "0/s" }
                lastCompleted = now
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                val ok = successCount.get()
                val fail = failureCount.get()
                log.info { "进度: $now/$count  成功=$ok  失败=$fail  耗时=${elapsed}s  速率=$rate" }
            }
        }

        if (config.workers <= 1) {
            // 串行模式：使用原始 backend
            for (i in 0 until count) {
                val seed = startSeed + i
                var shouldBreak = false
                try {
                    val results = runOnce(seed)
                    allResults.addAll(results)
                    results.forEach {
                        if (it.backendResult.success) successCount.incrementAndGet()
                        else {
                            failureCount.incrementAndGet()
                            // failFast: 遇到失败立即终止
                            if (config.failFast) {
                                log.error { "failFast=true: 检测到失败，终止测试" }
                                shouldBreak = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    failureCount.addAndGet(backends.size)
                    log.error(e) { "测试 seed=$seed 失败" }
                    allResults.addAll(
                        backends.map { backend ->
                            FuzzingResult(
                                seed = seed,
                                backendName = backend.name,
                                backendResult = object : BackendResult(false, -1, "", e.message ?: "", 0) {},
                                errorCategory = ErrorCategory.UNKNOWN,
                                errorSummary = e.message ?: "unknown",
                            )
                        }
                    )
                    // failFast: 异常也终止
                    if (config.failFast) {
                        log.error { "failFast=true: 检测到异常，终止测试" }
                        shouldBreak = true
                    }
                }
                completed.incrementAndGet()
                if (shouldBreak) break
            }
        } else {
            // 并行模式：每个 worker 线程使用独立的 backend 副本
            // 启动所有 backend 副本（启动 daemon）
            log.info { "并行模式: 启动 ${backendPool.size} 个 backend 实例" }
            backendPool.forEachIndexed { idx, backends ->
                backends.forEach { backend ->
                    if (!backend.checkEnvironment()) {
                        log.error { "Backend 副本 #$idx 初始化失败: ${backend.name}" }
                    } else {
                        log.debug { "Backend 副本 #$idx 就绪: ${backend.name}" }
                    }
                }
            }
            
            val executor = java.util.concurrent.Executors.newFixedThreadPool(config.workers) { r ->
                Thread(r, "fuzzer-worker").also { it.isDaemon = true }
            }
            
            // failFast 标志：使用 AtomicBoolean 确保线程安全
            val failFastTriggered = java.util.concurrent.atomic.AtomicBoolean(false)
            
            // 用 AtomicLong 分配 workerId
            val nextWorkerId = AtomicLong(0)

            val futures = (0 until count).map { i ->
                val seed = startSeed + i
                // 为每个任务预创建 generator 配置（seed 已确定）
                val taskGenConfig = generatorConfig.copy(seed = seed)
                executor.submit<List<FuzzingResult>> {
                    // 获取当前线程的 workerId 和专属 backend
                    val workerId = (nextWorkerId.getAndIncrement() % config.workers).toInt()
                    val threadBackends = backendPool[workerId]
                    
                    // 每个任务创建独立的 generator 实例，完全避免共享状态
                    val taskGenerator = UirGenerator(taskGenConfig)
                    try {
                        // 内联 runOnce 逻辑，使用任务专属的 generator 和 backend
                        val program = taskGenerator.generate()
                        val results = threadBackends.map { backend ->
                            runOnBackend(program, backend, seed)
                        }
                        results.forEach {
                            if (it.backendResult.success) successCount.incrementAndGet()
                            else {
                                failureCount.incrementAndGet()
                                // failFast: 遇到失败立即终止
                                if (config.failFast && failFastTriggered.compareAndSet(false, true)) {
                                    log.error { "failFast=true: 检测到失败，终止测试" }
                                }
                            }
                        }
                        completed.incrementAndGet()
                        results
                    } catch (e: Exception) {
                        failureCount.addAndGet(threadBackends.size)
                        completed.incrementAndGet()
                        // failFast: 异常也终止
                        if (config.failFast && failFastTriggered.compareAndSet(false, true)) {
                            log.error(e) { "failFast=true: 检测到异常，终止测试" }
                        }
                        threadBackends.map { backend ->
                            FuzzingResult(
                                seed = seed,
                                backendName = backend.name,
                                backendResult = object : BackendResult(false, -1, "", e.message ?: "", 0) {},
                                errorCategory = ErrorCategory.UNKNOWN,
                                errorSummary = e.message ?: "unknown",
                            )
                        }
                    }
                }
            }

            for ((i, future) in futures.withIndex()) {
                // 如果 failFast 已触发，立即终止
                if (failFastTriggered.get()) {
                    executor.shutdownNow()
                    break
                }
                val seed = startSeed + i
                try {
                    val results = future.get(
                        if (config.runTimeoutSeconds > 0) config.runTimeoutSeconds.toLong() else Long.MAX_VALUE,
                        java.util.concurrent.TimeUnit.SECONDS
                    )
                    allResults.addAll(results)
                } catch (_: java.util.concurrent.TimeoutException) {
                    future.cancel(true)
                    failureCount.addAndGet(backends.size)
                    completed.incrementAndGet()
                    log.warn { "测试 seed=$seed 超时 (${config.runTimeoutSeconds}s)" }
                    allResults.addAll(
                        backends.map { backend ->
                            FuzzingResult(
                                seed = seed,
                                backendName = backend.name,
                                backendResult = object : BackendResult(false, -1, "", "", 0) {},
                                errorCategory = ErrorCategory.TIMEOUT,
                                errorSummary = "timed out after ${config.runTimeoutSeconds}s",
                            )
                        }
                    )
                } catch (e: Exception) {
                    failureCount.addAndGet(backends.size)
                    completed.incrementAndGet()
                    log.error(e) { "测试 seed=$seed 执行异常" }
                    allResults.addAll(
                        backends.map { backend ->
                            FuzzingResult(
                                seed = seed,
                                backendName = backend.name,
                                backendResult = object : BackendResult(false, -1, "", e.message ?: "", 0) {},
                                errorCategory = ErrorCategory.UNKNOWN,
                                errorSummary = e.message ?: "unknown",
                            )
                        }
                    )
                }
            }

            executor.shutdownNow()
        }

        progressReporter.join()

        // 清理临时产物
        if (!config.keepArtifacts) {
            backends.filterIsInstance<TvmBackend>().forEach { it.cleanup() }
            // 清理 backend 副本的临时产物
            backendPool.forEach { backendList ->
                backendList.filterIsInstance<TvmBackend>().forEach { it.cleanup() }
            }
        }

        // 关闭所有 backend（原始 + 副本）
        backends.forEach { it.close() }
        backendPool.forEach { backendList ->
            backendList.forEach { it.close() }
        }

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
}
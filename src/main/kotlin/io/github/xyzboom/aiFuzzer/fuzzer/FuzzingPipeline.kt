package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer
import io.github.xyzboom.aiFuzzer.reducer.AutoReducer
import io.github.xyzboom.aiFuzzer.reducer.PropertyChecker
import io.github.xyzboom.aiFuzzer.translator.UirTranslator
import io.github.xyzboom.aiFuzzer.translator.pytorch.PytorchTranslator
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private val log = KotlinLogging.logger {}

/**
 * 可配置的 Fuzzing 流水线。
 *
 * 生成 → 执行 → 收集 → 缩减（可选）→ 分析
 *
 * 注意：缩减仅影响 bug 目录中的额外产物，不影响原始程序、结果和统计。
 */
class FuzzingPipeline(
    private val generatorConfig: GeneratorConfig = GeneratorConfig(),
    private val backends: List<Backend<*>>,
    private val config: FuzzingConfig = FuzzingConfig(),
) {
    data class FuzzingConfig(
        val runTimeoutSeconds: Int = 60,
        val workers: Int = 1,
        val keepArtifacts: Boolean = false,
        val failFast: Boolean = false,
        /** 缩减配置，null 表示不启用缩减 */
        val reducerConfig: AutoReducer.ReducerConfig? = null,
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
     * - 服务端侧：daemon/tvm_daemon.py 有 signal.alarm 超时保护
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

        // 获取源码内容
        val sourceCode = getSourceCode(result)

        // 收集 bug（保存原始程序）
        val bugDir = BugCollector.collect(
            result = result,
            seed = seed,
            backendName = backend.name,
            program = program,
            sourceCode = sourceCode,
        )

        // 如果启用了缩减，对 bug 程序执行缩减并保存缩减产物
        if (bugDir.exists() && config.reducerConfig != null && config.reducerConfig.enabled) {
            reduceAndSave(bugDir, program, backend, result, getSourceCode(result), seed)
        }

        // 错误分类（与原有逻辑一致）
        val errorCategory = when (result) {
            is TvmBackend.TvmResult -> result.errorCategory
            is PytorchDaemonBackend.PytorchResult -> result.errorCategory
            else -> ErrorAnalyzer.analyze(result.stderr, result.exitCode).category
        }
        val errorSummary = when (result) {
            is TvmBackend.TvmResult -> result.errorSummary
            is PytorchDaemonBackend.PytorchResult -> result.errorSummary
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
     * 对 bug 程序执行缩减并保存缩减产物。
     * 缩减失败不影响原始程序和数据。
     *
     * 缩减过程中的属性检查通过 daemon 实际执行翻译后的 Python 代码完成，
     * 确认 bug 仍然触发。如果缩减后属性丢失，则不保存缩减产物。
     */
    private fun reduceAndSave(
        bugDir: File,
        program: UirProgram,
        backend: Backend<*>,
        result: BackendResult,
        sourceCode: String?,
        seed: Long,
    ) {
        try {
            // 深拷贝：序列化→反序列化
            val jsonl = UirSerializer.toJsonl(program)
            val programCopy = UirSerializer.fromJsonl(jsonl)

            // 获取 backend 的 translator 和 daemon
            val (translator, daemon) = backendToTranslatorAndDaemon(backend)
                ?: run {
                    log.warn { "seed=$seed 不支持的 backend 类型，跳过缩减" }
                    return
                }

            // 构建属性检查器：通过 daemon 执行翻译后的代码，匹配错误特征
            val expectedError = result.stderr
            val propertyChecker = object : PropertyChecker {
                override fun check(program: UirProgram): Boolean {
                    val totalNodes = program.graphs.sumOf { it.nodes.size }
                    return try {
                        val source = translator.translate(program)
                        val daemonResult = daemon.sendAndWait(source)
                        val matched = !daemonResult.success && matchesBug(daemonResult.stderr, expectedError)
                        log.debug { "缩减 check: nodes=$totalNodes, matched=$matched" }
                        matched
                    } catch (e: Exception) {
                        log.debug { "缩减 check: nodes=$totalNodes, exception=${e.message}" }
                        false
                    }
                }

                override fun bugSignature(): String = expectedError.take(200)
            }

            // 执行缩减
            val reducer = AutoReducer(config.reducerConfig!!)
            val reductionResult = reducer.reduce(programCopy, propertyChecker)

            if (reductionResult.propertyPreserved && reductionResult.minifiedProgram != null) {
                // 重新翻译缩减后的程序并通过 daemon 执行，获取执行结果
                val reducedSource = translator.translate(programCopy)
                val finalResult = try {
                    daemon.sendAndWait(reducedSource)
                } catch (e: Exception) {
                    DaemonResult(false, -1, "", "缩减后 daemon 执行异常: ${e.message}", 0)
                }

                // 保存缩减产物（含 stderr）
                BugCollector.saveReductionArtifacts(
                    bugDir = bugDir,
                    reducedProgram = programCopy,
                    reducedSource = reducedSource,
                    reducedStderr = finalResult.stderr,
                    reducedStdout = finalResult.stdout,
                )
                log.info { "seed=$seed 缩减完成: ${"%.1f".format(reductionResult.reductionRatio * 100)}% 缩减率" }
            } else {
                log.warn { "seed=$seed 缩减后属性未保持，保留原始程序" }
            }
        } catch (e: Exception) {
            log.warn(e) { "seed=$seed 缩减异常，保留原始程序" }
        }
    }

    /**
     * 从 backend 中提取 translator 和 daemon 客户端。
     * 返回 null 表示不支持的 backend 类型。
     */
    private fun backendToTranslatorAndDaemon(backend: Backend<*>): Pair<UirTranslator<UirProgram, String>, DaemonClient>? {
        return when (backend) {
            is PytorchDaemonBackend -> {
                @Suppress("UNCHECKED_CAST")
                val t = backend.translator as UirTranslator<UirProgram, String>
                Pair(t, backend.daemon)
            }
            is TvmDaemonBackend -> {
                @Suppress("UNCHECKED_CAST")
                val t = backend.translator as UirTranslator<UirProgram, String>
                Pair(t, backend.daemon)
            }
            is OnnxDaemonBackend -> {
                @Suppress("UNCHECKED_CAST")
                val t = backend.translator as UirTranslator<UirProgram, String>
                Pair(t, backend.daemon)
            }
            else -> null
        }
    }

    /**
     * 判断 daemon 的 stderr 输出是否匹配原始 bug 的错误特征。
     *
     * 匹配策略：只比较 Python 异常报错行（RuntimeError / NameError 等行）。
     * - VERIFY: FAIL → 精确匹配
     * - 其他 RuntimeError → 去除数字后匹配（忽略 shape 值、大小等动态内容）
     * - 其他错误类型 → 同上去除数字后匹配
     *
     * 排除 traceback 行、路径、文件信息等无关内容。
     */
    private fun matchesBug(currentStderr: String, originalStderr: String): Boolean {
        if (currentStderr.isBlank()) return false

        // 提取原始和当前 stderr 中第一个异常报错行
        val originalErrorLine = extractErrorLine(originalStderr)
        val currentErrorLine = extractErrorLine(currentStderr)

        if (originalErrorLine == null || currentErrorLine == null) return false

        // 策略 1: VERIFY: FAIL 精确匹配（差分测试失败）
        if (originalErrorLine.contains("VERIFY: FAIL") && currentErrorLine.contains("VERIFY: FAIL")) {
            return true
        }

        // 策略 2: 去除数字后比较（忽略 shape 值、大小等动态内容）
        val originalCleaned = originalErrorLine.replace(Regex("\\d+"), "N")
        val currentCleaned = currentErrorLine.replace(Regex("\\d+"), "N")
        return originalCleaned == currentCleaned
    }

    /**
     * 从 stderr 中提取第一个异常报错行（如 RuntimeError: ...、NameError: ... 等）。
     * 排除所有的 traceback 行、路径、文件信息。
     */
    private fun extractErrorLine(stderr: String): String? {
        val errorPrefixes = listOf(
            "RuntimeError:", "TypeError:", "NameError:", "IndexError:", "ValueError:",
            "KeyError:", "AttributeError:", "ModuleNotFoundError:", "ImportError:",
            "SyntaxError:", "IndentationError:", "ZeroDivisionError:",
            "AssertionError:", "NotImplementedError:", "StopIteration:",
        )
        return stderr.lines()
            .map { it.trim() }
            .firstOrNull { line ->
                errorPrefixes.any { line.startsWith(it) }
            }
    }

    private fun getSourceCode(result: BackendResult): String? {
        return when (result) {
            is TvmBackend.TvmResult -> {
                try { File(result.sourceFile).readText() } catch (_: Exception) { null }
            }
            is PytorchDaemonBackend.PytorchResult -> {
                try { File(result.sourceFile).readText() } catch (_: Exception) { null }
            }
            is OnnxDaemonBackend.OnnxResult -> {
                try { File(result.sourceFile).readText() } catch (_: Exception) { null }
            }
            else -> null
        }
    }

    private fun translateProgram(program: UirProgram, backend: Backend<*>): String {
        return when (backend) {
            is PytorchDaemonBackend -> PytorchTranslator().translate(program)
            is TvmDaemonBackend -> TvmRelaxTranslator().translate(program)
            else -> "// re-translation not supported for ${backend.name}"
        }
    }
}
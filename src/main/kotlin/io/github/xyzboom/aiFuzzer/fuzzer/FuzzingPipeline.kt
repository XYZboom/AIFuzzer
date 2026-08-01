package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.config.MutationConfig
import io.github.xyzboom.aiFuzzer.config.PipelineConfig
import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.generator.UirMutator
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
        val reducerConfig: AutoReducer.ReducerConfig? = AutoReducer.ReducerConfig(enabled = true),
        /** 去重配置 */
        val dedup: DedupConfig = DedupConfig(),
    ) {
        data class DedupConfig(
            val enabled: Boolean = false,
            val compiler: String = "tvm",
            val target: String = "llvm",
            val patternDir: String = "",
            val patternMode: PipelineConfig.PatternMode = PipelineConfig.PatternMode.BUILTIN,
        )
    }

    /** 缓存的 pattern 数据库 */
    private val patternDatabase: io.github.xyzboom.aiFuzzer.pattern.PatternDatabase? by lazy {
        loadPatternDatabase(config.dedup)
    }

    /** 变异器 */
    private val mutator: UirMutator? by lazy {
        if (mutationConfig.enabled) {
            log.info { "变异器已启用: rate=${mutationConfig.rate}, maxMutations=${mutationConfig.maxMutations}" }
            val dedupTarget = resolveDedupTarget()
            UirMutator(
                config = mutationConfig,
                patternMatcher = if (config.dedup.enabled) patternDatabase?.let { db ->
                    io.github.xyzboom.aiFuzzer.pattern.PatternMatcher(db, config.dedup.compiler, dedupTarget)
                } else null,
            )
        } else {
            null
        }
    }

    /** 变异配置（从 generatorConfig 派生） */
    private val mutationConfig: MutationConfig
        get() = generatorConfig.mutationConfig

    /**
     * 自动推断去重 target。
     * 跨目标差分模式（CPU vs GPU）时返回 null（不过滤 target），
     * 否则返回配置的 target。
     */
    private fun resolveDedupTarget(): String? {
        val isCrossTarget = backends.any { backend ->
            (backend as? TvmDaemonBackend)?.crossTargetDifferential == true ||
            (backend as? PytorchDaemonBackend)?.crossTargetDifferential == true
        }
        return if (isCrossTarget) null else config.dedup.target
    }

    private fun loadPatternDatabase(dedup: FuzzingConfig.DedupConfig): io.github.xyzboom.aiFuzzer.pattern.PatternDatabase {
        val allPatterns = mutableListOf<io.github.xyzboom.aiFuzzer.pattern.PatternDef>()

        // 1. 从内置 resources/patterns/ 加载（BUILTIN 或 BOTH 模式）
        if (dedup.patternMode != PipelineConfig.PatternMode.CUSTOM) {
            try {
                val resource = this::class.java.classLoader.getResource("patterns")
                if (resource != null) {
                    val dir = java.io.File(resource.toURI())
                    if (dir.isDirectory) {
                        val files = dir.listFiles { f -> f.extension == "json" } ?: emptyArray()
                        for (file in files) {
                            try {
                                val db = io.github.xyzboom.aiFuzzer.pattern.PatternParser.parse(file.readText())
                                allPatterns.addAll(db.patterns)
                            } catch (e: Exception) {
                                log.warn(e) { "加载内置 pattern 文件 ${file.name} 失败" }
                            }
                        }
                        log.info { "从内置资源加载了 ${allPatterns.size} 个 pattern (${files.size} 个文件)" }
                    }
                } else {
                    log.warn { "resources/patterns 目录未找到" }
                }
            } catch (e: Exception) {
                log.warn(e) { "从 classpath 加载 pattern 失败" }
            }
        }

        // 2. 从外部 pattern_dir 加载（CUSTOM 或 BOTH 模式）
        if (dedup.patternMode != PipelineConfig.PatternMode.BUILTIN && dedup.patternDir.isNotBlank()) {
            val dir = java.io.File(dedup.patternDir)
            if (dir.exists()) {
                val files = if (dir.isDirectory) {
                    dir.listFiles { f -> f.extension == "json" } ?: emptyArray()
                } else {
                    arrayOf(dir)
                }
                val externalCount = allPatterns.size
                for (file in files) {
                    try {
                        val db = io.github.xyzboom.aiFuzzer.pattern.PatternParser.parse(file.readText())
                        allPatterns.addAll(db.patterns)
                    } catch (e: Exception) {
                        log.warn(e) { "加载外部 pattern 文件 ${file.name} 失败" }
                    }
                }
                log.info { "从外部加载了 ${allPatterns.size - externalCount} 个 pattern (${files.size} 个文件, $dedup.patternMode 模式)" }
            } else {
                log.warn { "外部 pattern 目录 $dedup.patternDir 不存在，跳过" }
            }
        }

        log.info { "共计 ${allPatterns.size} 个 pattern (模式=${dedup.patternMode})" }
        return io.github.xyzboom.aiFuzzer.pattern.PatternDatabase(patterns = allPatterns)
    }

    /** 累积的 pattern 匹配统计 */
    private val patternMatchCount = java.util.concurrent.ConcurrentSkipListMap<String, Int>()
    private val totalNodesChecked = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 单次 Fuzzing 运行（单线程，调用方负责上下文）。
     * 每次调用创建新的 [UirGenerator] 实例，确保线程安全。
     */
    fun runOnce(seed: Long = System.currentTimeMillis()): List<FuzzingResult> {
        log.debug { "运行单次测试: seed=$seed" }
        // 每次创建新的 generator，避免共享可变状态
        var genConfig = generatorConfig.copy(seed = seed)
        if (patternDatabase != null) {
            // 自动推断去重 target：跨目标差分时不过滤 target
            val dedupTarget = resolveDedupTarget()
            genConfig = genConfig.copy(
                dedup = io.github.xyzboom.aiFuzzer.generator.DedupConfig(
                    enabled = config.dedup.enabled,
                    patternDatabase = patternDatabase,
                    compiler = config.dedup.compiler,
                    target = dedupTarget,
                    maxRetries = 10,
                )
            )
        }
        val generator = UirGenerator(genConfig)
        val originalProgram = generator.generate()

        // 收集 pattern 匹配统计
        generator.patternMatcher?.let { pm ->
            totalNodesChecked.addAndGet(pm.totalNodesChecked.toLong())
            if (pm.matchCount > 0) {
                patternMatchCount.merge("TOTAL_MATCHES", pm.matchCount) { a, b -> a + b }
                pm.matchCountByPattern.forEach { (pid, cnt) ->
                    patternMatchCount.merge(pid, cnt) { a, b -> a + b }
                }
                // 生成 pattern 匹配摘要，用于写入 bug 报告
                val detail = buildString {
                    appendLine("# Pattern matches during generation (seed=$seed)")
                    appendLine("Total matches: ${pm.matchCount}")
                    appendLine("Per-pattern:")
                    pm.matchCountByPattern.forEach { (pid, cnt) ->
                        appendLine("  $pid: $cnt")
                    }
                }
                BugCollector.lastPatternMatches = detail
            } else {
                BugCollector.lastPatternMatches = null
            }
        }

        // 尝试变异（如果变异器已启用且有种子）
        val program = mutator?.let { m ->
            synchronized(m) {
                m.mutate()
            }
        } ?: originalProgram

        // 标记程序来源：generated（原始生成）或 mutated（变异产生）
        program.metadata["source"] = if (program === originalProgram) "generated" else "mutated"

        // 只有原始生成的程序加入种子池（变异程序本身来自种子池，不入池）
        if (mutator != null && program === originalProgram) {
            synchronized(mutator!!) {
                mutator!!.addSeed(originalProgram)
                // 如果种子池超过上限，丢弃最旧的种子
                while (mutator!!.seedCount > mutationConfig.maxSeeds) {
                    mutator!!.removeOldestSeed()
                }
            }
        }

        log.trace { "程序: ${program.graphs.size} 个图 (种子池: ${mutator?.seedCount ?: 0})" }
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
     *
     * @param seeds 种子序列，可以是 [SeedSequence.Range]（连续范围）或 [SeedSequence.Explicit]（指定列表）
     */
    fun runBatch(seeds: SeedSequence): FuzzingSummary {
        BugCollector.reset()
        val allResults = java.util.Collections.synchronizedList(mutableListOf<FuzzingResult>())
        val startTime = System.currentTimeMillis()

        // 原子计数器：已完成数、成功数、失败数
        val completed = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        val count = seeds.size
        
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
                val seed = seeds[i]
                var shouldBreak = false
                try {
                    val results = runOnce(seed)
                    allResults.addAll(results)
                    results.forEach {
                        if (it.backendResult.success) successCount.incrementAndGet()
                        else {
                            failureCount.incrementAndGet()
                            if (config.failFast) {
                                log.error { "failFast=true: 检测到失败，终止测试" }
                                shouldBreak = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    failureCount.addAndGet(backends.size)
                    log.error(e) { "测试 seed=${seed} 失败" }
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
            log.info { "并行模式: 启动 ${backendPool.size} 个 backend 实例" }
            backendPool.forEachIndexed { idx, backs ->
                backs.forEach { backend ->
                    if (!backend.checkEnvironment()) {
                        log.error { "Backend 副本 #$idx 初始化失败: ${backend.name}" }
                    }
                }
            }

            val executor = java.util.concurrent.Executors.newFixedThreadPool(config.workers) { r ->
                Thread(r, "fuzzer-worker").also { it.isDaemon = true }
            }
            val failFastTriggered = java.util.concurrent.atomic.AtomicBoolean(false)
            val nextWorkerId = AtomicLong(0)

            val futures = (0 until count).map { i ->
                val seed = seeds[i]
                val taskGenConfig = generatorConfig.copy(seed = seed)
                val finalTaskGenConfig = if (patternDatabase != null) {
                    val dedupTarget = resolveDedupTarget()
                    taskGenConfig.copy(
                        dedup = io.github.xyzboom.aiFuzzer.generator.DedupConfig(
                            enabled = config.dedup.enabled,
                            patternDatabase = patternDatabase,
                            compiler = config.dedup.compiler,
                            target = dedupTarget,
                            maxRetries = 10,
                        )
                    )
                } else taskGenConfig
                executor.submit<List<FuzzingResult>> {
                    val workerId = (nextWorkerId.getAndIncrement() % config.workers).toInt()
                    val threadBackends = backendPool[workerId]
                    val taskGenerator = UirGenerator(finalTaskGenConfig)
                    try {
                        val program = taskGenerator.generate()
                        taskGenerator.patternMatcher?.let { pm ->
                            totalNodesChecked.addAndGet(pm.totalNodesChecked.toLong())
                            if (pm.matchCount > 0) {
                                patternMatchCount.merge("TOTAL_MATCHES", pm.matchCount) { a, b -> a + b }
                                pm.matchCountByPattern.forEach { (pid, cnt) ->
                                    patternMatchCount.merge(pid, cnt) { a, b -> a + b }
                                }
                            }
                        }
                        val results = threadBackends.map { backend ->
                            runOnBackend(program, backend, seed)
                        }
                        results.forEach {
                            if (it.backendResult.success) successCount.incrementAndGet()
                            else if (config.failFast && failFastTriggered.compareAndSet(false, true)) {
                                log.error { "failFast=true: 检测到失败，终止测试" }
                            } else {
                                failureCount.incrementAndGet()
                            }
                        }
                        completed.incrementAndGet()
                        results
                    } catch (e: Exception) {
                        failureCount.addAndGet(threadBackends.size)
                        completed.incrementAndGet()
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
                if (failFastTriggered.get()) {
                    executor.shutdownNow()
                    break
                }
                val seed = seeds[i]
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

        // 输出 pattern 匹配统计
        val totalMatches = patternMatchCount["TOTAL_MATCHES"] ?: 0
        val checked = totalNodesChecked.get()
        if (totalMatches > 0 || checked > 0) {
            log.info { "Pattern match stats (recorded): matched=$totalMatches times, checked=$checked nodes" }
            if (totalMatches > 0) {
                println("  Pattern matched (recorded): $totalMatches matches (${checked} nodes checked)")
                // Per-pattern distribution (sorted by count, descending)
                val perPattern = patternMatchCount.filterKeys { it != "TOTAL_MATCHES" }
                    .entries.sortedByDescending { it.value }
                if (perPattern.isNotEmpty()) {
                    println("  Per-pattern breakdown:")
                    for ((pid, cnt) in perPattern) {
                        println("    $pid: $cnt")
                    }
                }
            }
        }

        return FuzzingSummary.fromResults(allResults, System.currentTimeMillis() - startTime)
    }

    /**
     * Dedup 效率评估：对每个种子同时用 no-dedup 和 with-dedup 生成，
     * 只执行 A≠B 的种子，比较两组结果。
     * 使用 runOnBackend 以确保 bug 收集和对齐正常 fuzz 逻辑。
     * 每个 seed 独立 try-catch，单次超时不崩全局。
     * 多线程并行，每个 worker 有独立的 backend 副本。
     */
    fun runDedupEval(seeds: SeedSequence, verbose: Boolean = false): DedupEvalSummary {
        BugCollector.reset()
        val startTime = System.currentTimeMillis()

        val skipped = AtomicInteger(0)
        val bugPrevented = AtomicInteger(0)
        val dedupOnlyFail = AtomicInteger(0)
        val bothFailed = AtomicInteger(0)
        val bothSuccess = AtomicInteger(0)
        val failedSeeds = AtomicInteger(0)
        val bugPreventedSeeds = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val dedupOnlyFailSeeds = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val bothFailedSeeds = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val completed = AtomicInteger(0)
        val count = seeds.size

        val dedupTarget = resolveDedupTarget()
        val workerCount = config.workers.coerceAtLeast(1)

        // 为每个 worker 创建独立的 backend 副本
        val backendPool: Array<List<Backend<*>>> = if (workerCount > 1) {
            Array(workerCount) { backends.map { it.createCopy() } }
        } else {
            arrayOf(backends)
        }
        backendPool.forEachIndexed { idx, bl ->
            bl.forEach { if (!it.checkEnvironment()) log.error { "Backend 副本 #$idx 初始化失败: ${it.name}" } }
        }

        val nextWorkerId = AtomicLong(0)

        // 进度报告线程
        val progressReporter = thread(name = "dedup-progress") {
            var last = 0
            while (completed.get() < count) {
                Thread.sleep(10000)
                val now = completed.get()
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                val rate = if (now - last > 0) "${((now - last).toDouble() / 10.0).toInt()}/s" else "0/s"
                last = now
                log.info { "进度: $now/$count  耗时=${elapsed}s  速率=$rate" }
            }
        }

        // 线程池
        val executor = java.util.concurrent.Executors.newFixedThreadPool(workerCount) {
            Thread(it, "dedup-worker").also { it.isDaemon = true }
        }

        val workers = mutableListOf<java.util.concurrent.Future<*>>()
        for (i in 0 until count) {
            val seed = seeds[i]
            workers.add(executor.submit {
                try {
                    val workerId = (nextWorkerId.getAndIncrement() % workerCount).toInt()
                    val threadBackends = backendPool[workerId]

                    // 1. 生成 no-dedup 程序（不启用 pattern 去重）
                    val generatorNoDedup = io.github.xyzboom.aiFuzzer.generator.UirGenerator(
                        generatorConfig.copy(seed = seed, dedup = io.github.xyzboom.aiFuzzer.generator.DedupConfig())
                    )
                    val genNoDedup = generatorNoDedup.generate()
                    val serialNoDedup = io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer.toJsonl(genNoDedup)

                    // 2. 生成 dedup 程序（启用 pattern 去重）
                    val genConfigDedup = if (generatorConfig.dedup.patternDatabase != null) {
                        generatorConfig.copy(
                            seed = seed,
                            dedup = io.github.xyzboom.aiFuzzer.generator.DedupConfig(
                                enabled = true,
                                patternDatabase = generatorConfig.dedup.patternDatabase,
                                compiler = config.dedup.compiler,
                                target = dedupTarget,
                                maxRetries = 10,
                            )
                        )
                    } else generatorConfig.copy(seed = seed)
                    val generatorDedup = io.github.xyzboom.aiFuzzer.generator.UirGenerator(genConfigDedup)
                    val genDedup = generatorDedup.generate()

                    // 3. 检查 dedup 是否阻止了生成（pattern 匹配导致重试）
                    val dedupTriggered = generatorDedup.dedupPreventedCount > 0
                    if (!dedupTriggered) {
                        skipped.incrementAndGet()
                        completed.incrementAndGet()
                        return@submit
                    }

                    // verbose: 打印 no-dedup vs dedup 程序的 pool/silu 差异（默认开启）
                    val noDedupInfo = extractPoolSiluInfo(genNoDedup)
                    val dedupInfo = extractPoolSiluInfo(genDedup)
                    println("[seed=$seed] no-dedup: $noDedupInfo")
                    println("[seed=$seed] dedup:    $dedupInfo")

                    // 4. 去重触发了，两个程序不同，分别执行
                    val resultsNoDedup = threadBackends.map { runOnBackend(genNoDedup, it, seed) }
                    val resultsDedup = threadBackends.map { runOnBackend(genDedup, it, seed) }

                    val noDedupFailed = resultsNoDedup.any { !it.backendResult.success }
                    val dedupFailed = resultsDedup.any { !it.backendResult.success }

                    // 保存 no-dedup 程序完整 IR（Both succeeded 时离线分析 pattern 是否过宽）
                    if (!noDedupFailed && !dedupFailed) {
                        if (noDedupInfo.isNotEmpty()) {
                            val fpDir = java.io.File("reports/fp-analysis")
                            fpDir.mkdirs()
                            val fpFile = java.io.File(fpDir, "fp_seed${seed}.jsonl")
                            fpFile.writeText(serialNoDedup)
                        }
                    }

                    when {
                        noDedupFailed && !dedupFailed -> { bugPrevented.incrementAndGet(); bugPreventedSeeds.add(seed) }
                        !noDedupFailed && dedupFailed -> { dedupOnlyFail.incrementAndGet(); dedupOnlyFailSeeds.add(seed) }
                        noDedupFailed && dedupFailed -> { bothFailed.incrementAndGet(); bothFailedSeeds.add(seed) }
                        else -> bothSuccess.incrementAndGet()
                    }
                    completed.incrementAndGet()
                } catch (e: Exception) {
                    log.warn(e) { "seed=$seed 执行异常，跳过" }
                    failedSeeds.incrementAndGet()
                    completed.incrementAndGet()
                }
            })
        }

        // 等待所有任务完成
        for (w in workers) {
            try { w.get() } catch (_: Exception) { }
        }
        executor.shutdownNow()
        progressReporter.join()

        // 关闭所有 backend 副本
        backendPool.forEach { it.forEach { b -> b.close() } }

        val collected = bugPrevented.get() + dedupOnlyFail.get() + bothFailed.get() + bothSuccess.get()
        return DedupEvalSummary(
            totalSeeds = count,
            skipped = skipped.get(),
            collected = collected,
            bugPrevented = bugPrevented.get(),
            dedupOnlyFail = dedupOnlyFail.get(),
            bothFailed = bothFailed.get(),
            bothSuccess = bothSuccess.get(),
            failedSeeds = failedSeeds.get(),
            bugPreventedSeeds = bugPreventedSeeds.toList(),
            dedupOnlyFailSeeds = dedupOnlyFailSeeds.toList(),
            bothFailedSeeds = bothFailedSeeds.toList(),
            totalTimeMs = System.currentTimeMillis() - startTime,
        )
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
            "AssertionError:", "AssertionError", "NotImplementedError:", "StopIteration:",
            "tvm.error.InternalError:", "tvm.s_tir.schedule.schedule.ScheduleError:",
            "tvm.error.TVMError:", "ScheduleError:",
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
            is TvmDaemonBackend -> TvmRelaxTranslator(
                shapeRank = backend.shapeRank,
                dtype = backend.dtype,
                target = backend.target,
                device = backend.device,
            ).translate(program)
            else -> "// re-translation not supported for ${backend.name}"
        }
    }

    /**
     * 提取程序 A 中 pool/silu 的详细信息用于分析 pattern 过宽问题。
     * 保存完整程序到文件，便于后续分析。
     */
    private fun extractPoolSiluInfo(program: io.github.xyzboom.aiFuzzer.ir.UirProgram): String {
        val parts = mutableListOf<String>()
        // 收集所有节点 output -> 上游节点名
        val outputToNode = mutableMapOf<String, String>()
        for (graph in program.graphs) {
            for (node in graph.nodes) {
                for (outRef in node.outputs) {
                    outputToNode[outRef.valueId] = node.op.name
                }
            }
        }
        for (graph in program.graphs) {
            for (node in graph.nodes) {
                val op = node.op.name
                if (op !in setOf("AVG_POOL2D", "MAX_POOL2D", "SILU")) continue
                val inputShapes = node.inputs.map { ref ->
                    val dims = ref.type.shape.dims.joinToString(",") { it.value.toString() }
                    "[$dims]"
                }
                val attrs = node.attributes.entries.joinToString(",") { (k, v) ->
                    val vStr = when (v) {
                        is io.github.xyzboom.aiFuzzer.ir.types.UirIntAttr -> v.value.toString()
                        is io.github.xyzboom.aiFuzzer.ir.types.UirStringAttr -> v.value
                        else -> v.toString()
                    }
                    "$k=$vStr"
                }
                // 上游算子
                val upstreamOps = node.inputs.mapNotNull { ref ->
                    outputToNode[ref.valueId]
                }.distinct()
                val upstreamStr = if (upstreamOps.isNotEmpty()) upstreamOps.joinToString(",") else "none"
                parts.add("$op($attrs)${inputShapes}<-[$upstreamStr]")
            }
        }
        return parts.joinToString(" | ")
    }
}
package io.github.xyzboom.aiFuzzer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.config.ConfigLoader
import io.github.xyzboom.aiFuzzer.config.FuzzerConfig
import io.github.xyzboom.aiFuzzer.fuzzer.Backend
import io.github.xyzboom.aiFuzzer.fuzzer.BugCollector
import io.github.xyzboom.aiFuzzer.fuzzer.FuzzingPipeline
import io.github.xyzboom.aiFuzzer.fuzzer.TvmBackend
import io.github.xyzboom.aiFuzzer.fuzzer.TvmDaemonBackend
import io.github.xyzboom.aiFuzzer.fuzzer.DaemonClient
import io.github.xyzboom.aiFuzzer.fuzzer.DaemonResult
import io.github.xyzboom.aiFuzzer.fuzzer.PytorchDaemonBackend
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer
import io.github.xyzboom.aiFuzzer.reducer.AutoReducer
import io.github.xyzboom.aiFuzzer.reducer.PropertyChecker
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.translator.pytorch.PytorchTranslator
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/**
 * AiFuzzer CLI 入口 —— 使用 CLIKT 命令行解析。
 *
 * 命令模式：
 *   aiFuzzer                               正常 fuzzing
 *   aiFuzzer --run-ir <file|dir>           从 IR 文件复现
 *   aiFuzzer --reduce <file|dir>           缩减 IR 文件
 */
class AiFuzzerCommand : CliktCommand(
    name = "aiFuzzer",
    help = "AI Compiler Fuzzing Framework",
) {
    init {
        context {
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }

    private val configPath by option("--config", "-c")
        .file(mustExist = true, canBeFile = true, canBeDir = false, mustBeReadable = true)
        .help("Path to YAML/JSON config file (default: uses built-in defaults)")

    private val runs by option("--runs", "-n")
        .int()
        .default(-1)
        .help("Number of fuzzing rounds (overrides config)")

    private val workers by option("--workers", "-w")
        .int()
        .default(-1)
        .help("Number of parallel workers (overrides config)")

    private val seedStr by option("--seed", "-s")
        .help("Random seed (overrides config)")

    private val opsStr by option("--ops", "-o")
        .help("Comma-separated op names (overrides config)")

    private val outputDir by option("--report", "-r")
        .help("Output directory for reports (overrides config)")

    private val inputIR by option("--run-ir", "-i")
        .file(mustExist = true, canBeFile = true, canBeDir = true, mustBeReadable = true)
        .help("Run from IR file/dir instead of fuzzing. If directory, all *.jsonl files will be executed")

    // 缩减模式参数
    private val reduceIR by option("--reduce", "-R")
        .file(mustExist = true, canBeFile = true, canBeDir = true, mustBeReadable = true)
        .help("Reduce IR file(s) to minimal form. Reads ir.jsonl, writes minimal_ir.jsonl + reduction_summary.txt")

    private val reduceOutput by option("--reduce-output", "-O")
        .help("Output directory for reduction results (default: same directory as input)")

    private val reduceCheck by option("--reduce-check", "-C")
        .help("Path to Python property checker script for reduction validation (default: skip check, keep all nodes)")
        .file(mustExist = true, canBeFile = true, canBeDir = false, mustBeReadable = true)

    override fun run() {
        LogUtils.withTrace {
            runWithLog()
        }
    }

    private fun runWithLog() {
        when {
            reduceIR != null -> runReduceMode()
            inputIR != null -> runReproduceMode()
            else -> runFuzzingMode()
        }
    }

    // ──────────────────────────────────────────────
    // 模式 1: Fuzzing 模式（默认）
    // ──────────────────────────────────────────────

    private fun runFuzzingMode() {
        val config = if (configPath != null) {
            val overridesMap = buildOverridesMap()
            log.info { "加载配置: ${configPath!!.absolutePath}" }
            echo("Loading config from: ${configPath!!.absolutePath}")
            ConfigLoader.load(configPath!!.absolutePath, overridesMap)
        } else {
            val config = ConfigLoader.default()
            applyOverrides(config)
            log.info { "使用默认配置" }
            echo("Using default config")
            config
        }

        log.info { "描述: ${config.run.description}" }
        log.info { "后端: ${config.backends.enabled}" }

        echo("Description: ${config.run.description}")
        echo("Backends: ${config.backends.enabled}")
        echo()

        val seed = config.run.seed?.toLongOrNull() ?: System.currentTimeMillis()
        echo("Seed: $seed")

        val genConfig = config.generator.toGeneratorConfig(seed)

        val backends = mutableListOf<Backend<*>>()
        if ("tvm" in config.backends.enabled) {
            val tvmCfg = config.backends.tvm
            if (tvmCfg.mode == "daemon") {
                echo("  TVM backend: daemon mode (python=${tvmCfg.python})")
                backends.add(TvmDaemonBackend(tvmCfg))
            } else {
                echo("  TVM backend: process mode")
                val workDir = File(tvmCfg.workDir)
                backends.add(TvmBackend(workDir, tvmCfg))
            }
        }
        if ("pytorch" in config.backends.enabled) {
            val pytorchCfg = config.backends.pytorch
            echo("  PyTorch backend: daemon mode (python=${pytorchCfg.python}, device=${pytorchCfg.device})")
            backends.add(PytorchDaemonBackend(pytorchCfg))
        }

        if (backends.isEmpty()) {
            echo("No backends enabled! Check config.", err = true)
            return
        }

        echo("Initializing backends...")
        val readyBackends = backends.filter { backend ->
            echo("  ${backend.name}: ")
            val ok = backend.checkEnvironment()
            if (ok) { echo("✓") } else { echo("✗ FAILED") }
            ok
        }
        if (readyBackends.isEmpty()) {
            echo("All backends failed to initialize! Aborting.", err = true)
            backends.forEach { it.close() }
            return
        }
        echo()

        val pipeline = FuzzingPipeline(
            generatorConfig = genConfig,
            backends = backends,
            config = config.pipeline.toFuzzingConfig(),
        )

        val reportDir = File(config.run.outputDir)
        reportDir.mkdirs()

        BugCollector.reset()
        val batchSize = config.pipeline.batchSize
        echo()
        echo("Running $batchSize rounds...")
        echo()

        val summary = pipeline.runBatch(count = batchSize, startSeed = seed)

        echo()
        summary.printReport()

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        val timestamp = LocalDateTime.now().format(formatter)
        val reportFile = File(reportDir, "run_report_$timestamp.txt")
        val bugFolders = reportDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("bug_") }
            ?.sortedBy { it.name }
            ?: emptyList()
        PrintWriter(reportFile).use { pw ->
            pw.println("AiFuzzer Run Report")
            pw.println("Date: ${LocalDateTime.now()}")
            pw.println("Config: ${configPath?.absolutePath ?: "default"}")
            pw.println("Seed: $seed")
            pw.println()
            pw.println("Total: ${summary.total}")
            pw.println("Success: ${summary.successes} (${String.format("%.1f", summary.successRate * 100)}%)")
            pw.println("Failures: ${summary.failures}")
            pw.println("Time: ${summary.totalTimeMs}ms")
            pw.println()
            pw.println("Bug reports (folder-based):")
            if (bugFolders.isEmpty()) {
                pw.println("  (none)")
            } else {
                bugFolders.forEachIndexed { idx, dir ->
                    val files = dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
                    pw.println("  ${idx + 1}. ${dir.name}/")
                    files.forEach { f -> pw.println("       - $f") }
                }
            }
        }
        log.info { "报告已保存: ${reportFile.absolutePath}" }
    }

    private fun buildOverridesMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        if (runs > 0) map["runs"] = runs
        if (workers > 0) map["workers"] = workers
        seedStr?.let { map["seed"] = it }
        opsStr?.let { map["ops"] = it }
        outputDir?.let { map["report"] = it }
        return map
    }

    private fun applyOverrides(config: FuzzerConfig) {
        if (runs > 0) config.pipeline.batchSize = runs
        if (workers > 0) config.pipeline.workers = workers
        seedStr?.let { config.run.seed = it }
        opsStr?.let {
            config.generator.ops.includeAll = false
            config.generator.ops.include = it.split(",").map { s -> s.trim() }
        }
        outputDir?.let { config.run.outputDir = it }
    }

    // ──────────────────────────────────────────────
    // 模式 2: 复现模式（--run-ir）
    // ──────────────────────────────────────────────

    private fun runReproduceMode() {
        log.info { "复现模式：从 IR 文件执行" }
        echo("Reproduce mode: running from IR file(s)")
        echo("Input: ${inputIR!!.absolutePath}")

        val irFiles = if (inputIR!!.isDirectory) {
            inputIR!!.listFiles()
                ?.filter { it.extension == "jsonl" || it.extension == "json" }
                ?.sortedBy { it.name }
                ?: emptyList()
        } else {
            listOf(inputIR!!)
        }

        if (irFiles.isEmpty()) {
            echo("No IR files found!", err = true)
            return
        }

        echo("Found ${irFiles.size} IR file(s)")
        echo()

        val config = if (configPath != null) {
            ConfigLoader.load(configPath!!.absolutePath, emptyMap())
        } else {
            ConfigLoader.default()
        }

        val backends = mutableListOf<Backend<*>>()
        if ("tvm" in config.backends.enabled) {
            val tvmCfg = config.backends.tvm
            if (tvmCfg.mode == "daemon") {
                echo("  TVM backend: daemon mode (python=${tvmCfg.python})")
                backends.add(TvmDaemonBackend(tvmCfg))
            } else {
                echo("  TVM backend: process mode")
                backends.add(TvmBackend(File(tvmCfg.workDir), tvmCfg))
            }
        }
        if ("pytorch" in config.backends.enabled) {
            val pytorchCfg = config.backends.pytorch
            echo("  PyTorch backend: daemon mode (python=${pytorchCfg.python}, device=${pytorchCfg.device})")
            backends.add(PytorchDaemonBackend(pytorchCfg))
        }

        if (backends.isEmpty()) {
            echo("No backends enabled! Check config.", err = true)
            return
        }

        echo("Initializing backends...")
        val readyBackends = backends.filter { backend ->
            echo("  ${backend.name}: ")
            val ok = backend.checkEnvironment()
            if (ok) { echo("✓") } else { echo("✗ FAILED") }
            ok
        }
        if (readyBackends.isEmpty()) {
            echo("All backends failed to initialize! Aborting.", err = true)
            backends.forEach { it.close() }
            return
        }
        echo()

        var totalSuccess = 0
        var totalFail = 0

        for (irFile in irFiles) {
            echo("Processing: ${irFile.name}")
            try {
                val jsonl = irFile.readText()
                val program = UirSerializer.fromJsonl(jsonl)

                for (backend in readyBackends) {
                    echo("  Running on ${backend.name}...")
                    try {
                        val result = backend.execute(program)
                        if (result.success) {
                            echo("    ✓ Success (${result.elapsedMs}ms)")
                            totalSuccess++
                        } else {
                            val errorMsg = result.stderr.lines().firstOrNull()?.take(100) ?: "Unknown error"
                            echo("    ✗ Failed: $errorMsg")
                            totalFail++
                        }
                    } catch (e: Exception) {
                        echo("    ✗ Exception: ${e.message}")
                        totalFail++
                    }
                }
            } catch (e: Exception) {
                echo("  ✗ Failed to load IR: ${e.message}")
                totalFail++
            }
            echo()
        }

        echo("=" .repeat(60))
        echo("Summary:")
        echo("  Total IR files: ${irFiles.size}")
        echo("  Total executions: ${totalSuccess + totalFail}")
        echo("  Success: $totalSuccess")
        echo("  Failed: $totalFail")
        echo("=" .repeat(60))

        backends.forEach { it.close() }
        log.info { "复现模式完成" }
    }

    // ──────────────────────────────────────────────
    // 模式 3: 缩减模式（--reduce）
    // ──────────────────────────────────────────────

    private fun runReduceMode() {
        log.info { "缩减模式：从 IR 文件缩减" }
        echo("Reduce mode: reducing IR file(s)")
        echo("Input: ${reduceIR!!.absolutePath}")

        val irFiles = if (reduceIR!!.isDirectory) {
            reduceIR!!.listFiles()
                ?.filter { it.name == "ir.jsonl" || it.extension == "jsonl" }
                ?.sortedBy { it.name }
                ?: emptyList()
        } else {
            listOf(reduceIR!!)
        }

        if (irFiles.isEmpty()) {
            echo("No IR files found!", err = true)
            return
        }

        val outputDirPath = reduceOutput ?: irFiles.first().parentFile?.absolutePath ?: "."
        val outputDirFile = File(outputDirPath)
        outputDirFile.mkdirs()

        echo("Found ${irFiles.size} IR file(s)")
        echo("Output directory: ${outputDirFile.absolutePath}")
        echo()

        val reducer = AutoReducer()

        for (irFile in irFiles) {
            echo("Processing: ${irFile.name}")
            try {
                val jsonl = irFile.readText()
                val originalProgram = UirSerializer.fromJsonl(jsonl)
                val originalNodeCount = originalProgram.graphs.sumOf { it.nodes.size }
                echo("  Original nodes: $originalNodeCount")

                // 构建属性检查器：通过 daemon 执行翻译后的代码验证
                val (translator, daemon) = createDaemonBackend()
                val originalSource = translator.translate(originalProgram)
                val originalResult = daemon.sendAndWait(originalSource)
                val originalError = originalResult.stderr

                val propertyChecker = object : PropertyChecker {
                    override fun check(program: UirProgram): Boolean {
                        return try {
                            val source = translator.translate(program)
                            val daemonResult = daemon.sendAndWait(source)
                            val matched = matchesBugSignature(daemonResult.stderr, originalError)
                            log.debug { "属性检查: success=${daemonResult.success}, matched=$matched, stderr=${daemonResult.stderr.take(100)}" }
                            // 属性保持 = 执行失败 + 错误特征匹配
                            !daemonResult.success && matched
                        } catch (e: Exception) {
                            log.debug { "属性检查失败 (daemon 异常): ${e.message}" }
                            false
                        }
                    }

                    override fun bugSignature(): String = originalError.take(200)
                }

                val result = reducer.reduceFromJsonl(jsonl, propertyChecker)

                if (result.minifiedProgram != null && result.propertyPreserved) {
                    val minifiedNodeCount = result.minifiedProgram.graphs.sumOf { it.nodes.size }
                    val ratio = result.reductionRatio

                    // 决定输出文件名
                    val baseName = irFile.nameWithoutExtension
                    val minimalIrFile = File(outputDirFile, "${baseName}_minimal.jsonl")
                    val minimalIrJsonl = UirSerializer.toJsonl(result.minifiedProgram)
                    minimalIrFile.writeText(minimalIrJsonl)

                    // 翻译并保存缩减后的源码
                    try {
                        val (translator, daemon) = createDaemonBackend()
                        val minimalSource = translator.translate(result.minifiedProgram)
                        val minimalSourceFile = File(outputDirFile, "${baseName}_minimal_source.py")
                        minimalSourceFile.writeText(minimalSource)

                        // 运行缩减后的程序，保存 stderr
                        val runResult = daemon.sendAndWait(minimalSource)
                        val stderrFile = File(outputDirFile, "${baseName}_minimal_stderr.log")
                        stderrFile.writeText("=== STDOUT ===\n${runResult.stdout}\n=== STDERR ===\n${runResult.stderr}")
                    } catch (e: Exception) {
                        log.warn { "翻译/运行缩减后程序失败: ${e.message}" }
                    }

                    // 写入缩减摘要
                    val summaryFile = File(outputDirFile, "${baseName}_reduction_summary.txt")
                    summaryFile.writeText("""
                        |Reduction Summary
                        |=================
                        |Input file: ${irFile.absolutePath}
                        |Original nodes: $originalNodeCount
                        |Reduced nodes: $minifiedNodeCount
                        |Reduction ratio: ${String.format("%.1f%%", ratio * 100)}
                        |Reduced IR: ${minimalIrFile.absolutePath}
                        |
                        |Steps:
                        ${result.steps.joinToString("\n") { "  - ${it.type}: ${it.description}" }}
                    """.trimMargin())

                    echo("  Reduced: $originalNodeCount → $minifiedNodeCount nodes (${String.format("%.1f", ratio * 100)}%)")
                    echo("  Output: ${minimalIrFile.name}")
                } else {
                    echo("  ✗ Reduction failed: ${result.errorMessage ?: "property not preserved"}")
                }
            } catch (e: Exception) {
                echo("  ✗ Error: ${e.message}")
                log.warn(e) { "缩减文件 ${irFile.name} 失败" }
            }
            echo()
        }

        echo("=" .repeat(60))
        echo("Reduce mode completed")
        echo("=" .repeat(60))
        log.info { "缩减模式完成" }
    }

    /**
     * 为缩减模式创建 PyTorch daemon 后端。
     */
    private fun createDaemonBackend(): Pair<PytorchTranslator, DaemonClient> {
        val pythonPath = try {
            val config = if (configPath != null) ConfigLoader.load(configPath!!.absolutePath) else null
            config?.backends?.pytorch?.python ?: "python3"
        } catch (_: Exception) {
            "python3"
        }
        log.info { "缩减模式 daemon python: $pythonPath" }
        val backend = PytorchDaemonBackend(pythonPath = pythonPath)
        return Pair(backend.translator, backend.daemon)
    }

    /**
     * 判断 daemon stderr 是否匹配原始 bug 特征。
     */
    private fun matchesBugSignature(currentStderr: String, originalStderr: String): Boolean {
        if (currentStderr.isBlank()) return false

        // VERIFY: FAIL 类型：必须精确匹配 "VERIFY: FAIL"
        if (originalStderr.contains("VERIFY: FAIL")) {
            return currentStderr.contains("VERIFY: FAIL")
        }

        // 非 VERIFY 类型：匹配 RuntimeError 行
        val originalLines = originalStderr.lines().map { it.trim() }.filter { it.isNotBlank() }
        val originalErrorType = originalLines.lastOrNull { it.startsWith("RuntimeError:") }
        if (originalErrorType != null && currentStderr.contains(originalErrorType)) return true

        return false
    }
}

fun main(args: Array<String>) = AiFuzzerCommand().main(args)
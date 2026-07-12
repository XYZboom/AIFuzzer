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
import io.github.xyzboom.aiFuzzer.fuzzer.PytorchDaemonBackend
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/**
 * AiFuzzer CLI 入口 —— 使用 CLIKT 命令行解析。
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

    override fun run() {
        LogUtils.withTrace {
            runWithLog()
        }
    }
    
    private fun runWithLog() {
        // 检查是否是复现模式
        if (inputIR != null) {
            runReproduceMode()
            return
        }

        // 正常 fuzzing 模式
        // 1. 加载配置
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

        // 2. 确定 seed
        val seed = config.run.seed?.toLongOrNull() ?: System.currentTimeMillis()
        echo("Seed: $seed")

        // 3. 创建生成器配置（不再直接创建 generator 实例）
        val genConfig = config.generator.toGeneratorConfig(seed)

        // 4. 创建后端
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
        // TODO: ONNX and IREE backends

        if (backends.isEmpty()) {
            echo("No backends enabled! Check config.", err = true)
            return
        }

        // 5. 预热后端（检查环境、启动 daemon）
        echo("Initializing backends...")
        val readyBackends = backends.filter { backend ->
            echo("  ${backend.name}: ")
            val ok = backend.checkEnvironment()
            if (ok) {
                echo("✓")
            } else {
                echo("✗ FAILED")
            }
            ok
        }
        if (readyBackends.isEmpty()) {
            echo("All backends failed to initialize! Aborting.", err = true)
            backends.forEach { it.close() }
            return
        }
        echo()

        // 6. 创建流水线
        val pipeline = FuzzingPipeline(
            generatorConfig = genConfig,
            backends = backends,
            config = config.pipeline.toFuzzingConfig(),
        )

        // 7. 确保输出目录存在
        val reportDir = File(config.run.outputDir)
        reportDir.mkdirs()

        // 8. 运行
        BugCollector.reset()
        val batchSize = config.pipeline.batchSize
        echo()
        echo("Running $batchSize rounds...")
        echo()

        val summary = pipeline.runBatch(count = batchSize, startSeed = seed)

        // 9. 打印报告
        echo()
        summary.printReport()

        // 10. 保存报告到文件
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

    /**
     * 复现模式：从 IR 文件执行，不启动 fuzzing
     */
    private fun runReproduceMode() {
        log.info { "复现模式：从 IR 文件执行" }
        echo("Reproduce mode: running from IR file(s)")
        echo("Input: ${inputIR!!.absolutePath}")

        // 1. 收集所有 IR 文件
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

        // 2. 加载配置（用于后端设置）
        val config = if (configPath != null) {
            log.info { "加载配置: ${configPath!!.absolutePath}" }
            ConfigLoader.load(configPath!!.absolutePath, emptyMap())
        } else {
            log.info { "使用默认配置" }
            ConfigLoader.default()
        }

        // 3. 创建后端
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

        // 4. 初始化后端
        echo("Initializing backends...")
        val readyBackends = backends.filter { backend ->
            echo("  ${backend.name}: ")
            val ok = backend.checkEnvironment()
            if (ok) {
                echo("✓")
            } else {
                echo("✗ FAILED")
            }
            ok
        }
        if (readyBackends.isEmpty()) {
            echo("All backends failed to initialize! Aborting.", err = true)
            backends.forEach { it.close() }
            return
        }
        echo()

        // 5. 执行每个 IR 文件
        var totalSuccess = 0
        var totalFail = 0

        for (irFile in irFiles) {
            echo("Processing: ${irFile.name}")
            log.info { "加载 IR 文件: ${irFile.absolutePath}" }

            try {
                // 加载 IR
                val jsonl = irFile.readText()
                val program = io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer.fromJsonl(jsonl)

                // 在每个后端执行
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
                        log.error(e) { "执行失败" }
                        totalFail++
                    }
                }
            } catch (e: Exception) {
                echo("  ✗ Failed to load IR: ${e.message}")
                log.error(e) { "加载 IR 失败" }
                totalFail++
            }
            echo()
        }

        // 6. 打印总结
        echo("=" .repeat(60))
        echo("Summary:")
        echo("  Total IR files: ${irFiles.size}")
        echo("  Total executions: ${totalSuccess + totalFail}")
        echo("  Success: $totalSuccess")
        echo("  Failed: $totalFail")
        echo("=" .repeat(60))

        // 7. 清理
        backends.forEach { it.close() }
        log.info { "复现模式完成" }
    }
}

fun main(args: Array<String>) = AiFuzzerCommand().main(args)

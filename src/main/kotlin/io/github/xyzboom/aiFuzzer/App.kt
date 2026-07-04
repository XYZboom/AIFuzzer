package io.github.xyzboom.aiFuzzer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int

import io.github.xyzboom.aiFuzzer.config.ConfigLoader
import io.github.xyzboom.aiFuzzer.config.FuzzerConfig
import io.github.xyzboom.aiFuzzer.fuzzer.Backend
import io.github.xyzboom.aiFuzzer.fuzzer.BugCollector
import io.github.xyzboom.aiFuzzer.fuzzer.FuzzingPipeline
import io.github.xyzboom.aiFuzzer.fuzzer.TvmBackend
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    override fun run() {
        // 1. 加载配置
        val config = if (configPath != null) {
            val overridesMap = buildOverridesMap()
            echo("Loading config from: ${configPath!!.absolutePath}")
            ConfigLoader.load(configPath!!.absolutePath, overridesMap)
        } else {
            val config = ConfigLoader.default()
            applyOverrides(config)
            echo("Using default config")
            config
        }

        echo("Description: ${config.run.description}")
        echo("Backends: ${config.backends.enabled}")

        // 2. 确定 seed
        val seed = config.run.seed?.toLongOrNull() ?: System.currentTimeMillis()
        echo("Seed: $seed")

        // 3. 创建生成器
        val genConfig = config.generator.toGeneratorConfig(seed)
        val generator = UirGenerator(genConfig)

        // 4. 创建后端
        val backends = mutableListOf<Backend<*>>()
        if ("tvm" in config.backends.enabled) {
            val workDir = File(config.backends.tvm.workDir)
            backends.add(TvmBackend(workDir, config.backends.tvm))
        }
        // TODO: ONNX and IREE backends

        if (backends.isEmpty()) {
            echo("No backends enabled! Check config.", err = true)
            return
        }

        // 5. 创建流水线
        val pipeline = FuzzingPipeline(
            generator = generator,
            backends = backends,
            config = config.pipeline.toFuzzingConfig(),
        )

        // 6. 确保输出目录存在
        val reportDir = File(config.run.outputDir)
        reportDir.mkdirs()

        // 7. 运行
        BugCollector.reset()
        val batchSize = config.pipeline.batchSize
        echo()
        echo("Running $batchSize rounds...")
        echo()

        val summary = pipeline.runBatch(count = batchSize, startSeed = seed)

        // 8. 打印报告
        echo()
        summary.printReport()

        // 9. 保存报告到文件
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        val timestamp = LocalDateTime.now().format(formatter)
        val reportFile = File(reportDir, "run_report_$timestamp.txt")
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
        }
        echo("Report saved to: ${reportFile.relativeTo(File(".").absoluteFile)}")
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
}

fun main(args: Array<String>) = AiFuzzerCommand().main(args)

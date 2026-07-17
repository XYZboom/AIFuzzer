package io.github.xyzboom.aiFuzzer.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.LogUtils
import io.github.xyzboom.aiFuzzer.config.ConfigLoader
import io.github.xyzboom.aiFuzzer.fuzzer.*
import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

class FuzzCommand : CliktCommand(
    name = "fuzz",
    help = "Run fuzzing campaigns",
) {
    init { context { helpFormatter = CliUtils.helpFormatter() } }

    private val configPath by option("--config", "-c")
        .file(mustExist = true, canBeFile = true, canBeDir = false, mustBeReadable = true)
        .help("Path to YAML/JSON config (default: built-in defaults)")

    private val runs by option("--runs", "-n")
        .int().default(-1)
        .help("Number of fuzzing rounds (overrides config)")

    private val workers by option("--workers", "-w")
        .int().default(-1)
        .help("Number of parallel workers (overrides config)")

    private val seedStr by option("--seed", "-s")
        .help("Random seed (overrides config)")

    private val opsStr by option("--ops", "-o")
        .help("Comma-separated op names (overrides config)")

    private val outputDir by option("--report", "-r")
        .help("Output directory for reports (overrides config)")

    override fun run() = LogUtils.withTrace {
        val config = loadConfig()
        log.info { "描述: ${config.run.description}, 后端: ${config.backends.enabled}" }
        echo("Description: ${config.run.description}")
        echo("Backends: ${config.backends.enabled}")

        val seed = config.run.seed?.toLongOrNull() ?: System.currentTimeMillis()
        echo("\nSeed: $seed")

        val backends = initBackends(config)
        echo("Initializing backends...")
        val ready = backends.filter { b ->
            echo("  ${b.name}: ")
            val ok = b.checkEnvironment()
            echo(if (ok) "✓" else "✗ FAILED")
            ok
        }
        if (ready.isEmpty()) { backends.forEach { it.close() }; return@withTrace }

        val summary = FuzzingPipeline(
            config.generator.toGeneratorConfig(seed),
            backends,
            config.pipeline.toFuzzingConfig(),
        ).runBatch(count = config.pipeline.batchSize, startSeed = seed)

        echo(); summary.printReport()
        saveReport(config, seed, summary)
    }

    private fun loadConfig(): io.github.xyzboom.aiFuzzer.config.FuzzerConfig {
        if (configPath != null) {
            val overrides = mutableMapOf<String, Any>()
            if (runs > 0) overrides["runs"] = runs
            if (workers > 0) overrides["workers"] = workers
            seedStr?.let { overrides["seed"] = it }
            opsStr?.let { overrides["ops"] = it }
            outputDir?.let { overrides["report"] = it }
            log.info { "加载配置: ${configPath!!.absolutePath}" }
            echo("Loading config from: ${configPath!!.absolutePath}")
            return ConfigLoader.load(configPath!!.absolutePath, overrides)
        }
        val c = ConfigLoader.default()
        if (runs > 0) c.pipeline.batchSize = runs
        if (workers > 0) c.pipeline.workers = workers
        seedStr?.let { c.run.seed = it }
        opsStr?.let { c.generator.ops.includeAll = false; c.generator.ops.include = it.split(",").map(String::trim) }
        outputDir?.let { c.run.outputDir = it }
        echo("Using default config")
        return c
    }

    private fun saveReport(config: io.github.xyzboom.aiFuzzer.config.FuzzerConfig, seed: Long, summary: FuzzingSummary) {
        val reportDir = File(config.run.outputDir).also { it.mkdirs() }
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val reportFile = File(reportDir, "run_report_$ts.txt")
        val bugDirs = reportDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("bug_") }?.sortedBy { it.name } ?: emptyList()
        PrintWriter(reportFile).use { pw ->
            pw.println("AiFuzzer Run Report\nDate: ${LocalDateTime.now()}\nConfig: ${configPath?.absolutePath ?: "default"}\nSeed: $seed\n")
            pw.println("Total: ${summary.total}\nSuccess: ${summary.successes} (${"%.1f".format(summary.successRate * 100)}%)\nFailures: ${summary.failures}\nTime: ${summary.totalTimeMs}ms\n")
            pw.println("Bug reports (folder-based):")
            if (bugDirs.isEmpty()) pw.println("  (none)")
            else bugDirs.forEachIndexed { i, d ->
                pw.println("  ${i + 1}. ${d.name}/"); d.listFiles()?.map { it.name }?.sorted()?.forEach { pw.println("       - $it") }
            }
        }
        log.info { "报告已保存: ${reportFile.absolutePath}" }
    }
}

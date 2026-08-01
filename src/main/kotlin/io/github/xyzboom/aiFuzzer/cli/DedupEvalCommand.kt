package io.github.xyzboom.aiFuzzer.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.LogUtils
import io.github.xyzboom.aiFuzzer.config.ConfigLoader
import io.github.xyzboom.aiFuzzer.fuzzer.*

private val log = KotlinLogging.logger {}

class DedupEvalCommand : CliktCommand(
    name = "dedup-eval",
    help = "Evaluate dedup efficiency: generate with/without dedup per seed, only execute when they differ",
) {
    init { context { helpFormatter = CliUtils.helpFormatter() } }

    private val configPath by option("--config", "-c")
        .file(mustExist = true, canBeFile = true, canBeDir = false, mustBeReadable = true)
        .help("Path to YAML/JSON config (default: built-in defaults)")

    private val runs by option("--runs", "-n")
        .int().default(200)
        .help("Number of seeds to evaluate")

    private val seedStr by option("--seed", "-s")
        .help("Start seed (overrides config)")

    private val seedFile by option("--seed-file", "-S")
        .file(mustExist = true, canBeFile = true, canBeDir = false, mustBeReadable = true)
        .help("Seed sequence file (one seed per line, overrides --seed and --runs)")

    private val verbose by option("--verbose", "-v").flag()
        .help("Print no-dedup vs dedup program details when they differ")

    override fun run() = LogUtils.withTrace {
        val config = loadConfig()
        echo("Description: ${config.run.description}")
        echo("Backends: ${config.backends.enabled}")

        val startSeed = seedStr?.toLongOrNull() ?: config.run.seed?.toLongOrNull() ?: 1L
        echo("Start seed: $startSeed")

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
            config.generator.toGeneratorConfig(startSeed),
            backends,
            config.pipeline.toFuzzingConfig(),
        ).let { pipeline ->
            val seeds = if (config.run.seedFile != null) {
                SeedSequence.fromFile(java.io.File(config.run.seedFile!!))
            } else {
                SeedSequence.range(startSeed, runs)
            }
            echo("Seeds: $seeds")
            pipeline.runDedupEval(seeds, verbose = verbose)
        }

        summary.printReport()
    }

    private fun loadConfig(): io.github.xyzboom.aiFuzzer.config.FuzzerConfig {
        if (configPath != null) {
            val overrides = mutableMapOf<String, Any>()
            seedStr?.let { overrides["seed"] = it }
            seedFile?.let { overrides["seed_file"] = it.absolutePath }
            log.info { "加载配置: ${configPath!!.absolutePath}" }
            echo("Loading config from: ${configPath!!.absolutePath}")
            return ConfigLoader.load(configPath!!.absolutePath, overrides)
        }
        val c = ConfigLoader.default()
        seedStr?.let { c.run.seed = it }
        seedFile?.let { c.run.seedFile = it.absolutePath }
        echo("Using default config")
        return c
    }
}
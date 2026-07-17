package io.github.xyzboom.aiFuzzer.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.types.file
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.LogUtils
import io.github.xyzboom.aiFuzzer.config.ConfigLoader
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer
import java.io.File

private val log = KotlinLogging.logger {}

class ReproduceCommand : CliktCommand(
    name = "reproduce",
    help = "Re-run IR file(s) on backends",
) {
    init { context { helpFormatter = CliUtils.helpFormatter() } }

    private val inputIR by option("--ir", "-i")
        .file(mustExist = true, canBeFile = true, canBeDir = true, mustBeReadable = true)
        .required()
        .help("IR file or directory containing *.jsonl files")

    override fun run() = LogUtils.withTrace {
        log.info { "复现模式: ${inputIR.absolutePath}" }
        echo("Reproduce mode: running from IR file(s)")
        echo("Input: ${inputIR.absolutePath}")

        val irFiles: List<File> = if (inputIR.isDirectory) {
            val files = inputIR.listFiles() ?: emptyArray()
            files.filter { it.extension == "jsonl" }.sortedBy { it.name }
        } else listOf(inputIR)
        if (irFiles.isEmpty()) { echo("No IR files found!", err = true); return@withTrace }

        echo("Found ${irFiles.size} IR file(s)\n")

        val backends = initBackends(ConfigLoader.default())
        echo("Initializing backends...")
        val ready = backends.filter { b ->
            echo("  ${b.name}: ")
            val ok = b.checkEnvironment()
            echo(if (ok) "✓" else "✗ FAILED")
            ok
        }
        if (ready.isEmpty()) { backends.forEach { it.close() }; return@withTrace }
        echo()

        var success = 0; var fail = 0
        for (irFile in irFiles) {
            echo("Processing: ${irFile.name}")
            try {
                val program = UirSerializer.fromJsonl(irFile.readText())
                for (backend in ready) {
                    echo("  Running on ${backend.name}...")
                    try {
                        val result = backend.execute(program)
                        if (result.success) { echo("    ✓ Success (${result.elapsedMs}ms)"); success++ }
                        else { echo("    ✗ Failed: ${result.stderr.lines().firstOrNull()?.take(100) ?: "Unknown error"}"); fail++ }
                    } catch (e: Exception) { echo("    ✗ Exception: ${e.message}"); fail++ }
                }
            } catch (e: Exception) { echo("  ✗ Failed to load IR: ${e.message}"); fail++ }
            echo()
        }

        echo("=".repeat(60))
        echo("Summary:\n  Total IR files: ${irFiles.size}\n  Total executions: ${success + fail}\n  Success: $success\n  Failed: $fail")
        echo("=".repeat(60))
        backends.forEach { it.close() }
        log.info { "复现模式完成" }
    }
}

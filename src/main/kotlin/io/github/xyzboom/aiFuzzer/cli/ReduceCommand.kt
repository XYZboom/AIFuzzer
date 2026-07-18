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
import io.github.xyzboom.aiFuzzer.fuzzer.*
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer
import io.github.xyzboom.aiFuzzer.reducer.AutoReducer
import io.github.xyzboom.aiFuzzer.reducer.PropertyChecker
import io.github.xyzboom.aiFuzzer.translator.pytorch.PytorchTranslator
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import java.io.File

private val log = KotlinLogging.logger {}

class ReduceCommand : CliktCommand(
    name = "reduce",
    help = "Minimize IR file(s) while preserving bug properties",
) {
    init { context { helpFormatter = CliUtils.helpFormatter() } }

    private val inputIR by option("--ir", "-i")
        .file(mustExist = true, canBeFile = true, canBeDir = true, mustBeReadable = true)
        .required()
        .help("IR file or directory containing ir.jsonl")

    private val outputDirArg by option("--output", "-o")
        .help("Output directory (default: same as input)")

    private val reduceBackend by option("--backend", "-b")
        .help("Backend for reduction validation: 'tvm' or 'pytorch' (default: pytorch)")

    private val pythonPath by option("--python", "-p")
        .help("Python executable for the backend daemon (default: python3)")

    override fun run() = LogUtils.withTrace {
        log.info { "缩减模式: ${inputIR.absolutePath}" }
        echo("Reduce mode: reducing IR file(s)")
        echo("Input: ${inputIR.absolutePath}")

        val irFiles = if (inputIR.isDirectory) {
            inputIR.listFiles()?.filter { it.name == "ir.jsonl" || it.extension == "jsonl" }?.sortedBy { it.name } ?: emptyList()
        } else listOf(inputIR)
        if (irFiles.isEmpty()) { echo("No IR files found!", err = true); return@withTrace }

        val outDir = File(outputDirArg ?: irFiles.first().parentFile?.absolutePath ?: ".").also { it.mkdirs() }
        val backendChoice = reduceBackend?.lowercase() ?: "pytorch"
        echo("Found ${irFiles.size} IR file(s)\nOutput: ${outDir.absolutePath}\nBackend: $backendChoice\n")

        val reducer = AutoReducer()
        for (irFile in irFiles) {
            echo("Processing: ${irFile.name}")
            try {
                val jsonl = irFile.readText()
                val originalProgram = UirSerializer.fromJsonl(jsonl)
                val originalNodeCount = originalProgram.graphs.sumOf { it.nodes.size }
                echo("  Original nodes: $originalNodeCount")

                val (translator, daemon) = createDaemonForBackend(backendChoice, pythonPath)
                val originalSource = translator(originalProgram)
                val originalResult = daemon.sendAndWait(originalSource)
                val originalError = originalResult.stderr
                echo("  Original error signature: ${originalError.take(200)}")

                val propertyChecker = object : PropertyChecker {
                    override fun check(program: UirProgram): Boolean = try {
                        val daemonResult = daemon.sendAndWait(translator(program))
                        val matched = matchesBugSignature(daemonResult.stderr, originalError)
                        !daemonResult.success && matched
                    } catch (_: Exception) { false }

                    override fun bugSignature(): String = originalError.take(200)
                }

                val result = reducer.reduceFromJsonl(jsonl, propertyChecker)
                if (result.minifiedProgram != null && result.propertyPreserved) {
                    val minCount = result.minifiedProgram.graphs.sumOf { it.nodes.size }
                    val baseName = irFile.nameWithoutExtension
                    val minimalIrFile = File(outDir, "${baseName}_minimal.jsonl")
                    minimalIrFile.writeText(UirSerializer.toJsonl(result.minifiedProgram))

                    try {
                        val (minTranslator, minDaemon) = createDaemonForBackend(backendChoice, pythonPath)
                        val minSource = minTranslator(result.minifiedProgram)
                        File(outDir, "${baseName}_minimal_source.py").writeText(minSource)
                        val runResult = minDaemon.sendAndWait(minSource)
                        File(outDir, "${baseName}_minimal_stderr.log").writeText(
                            "=== STDOUT ===\n${runResult.stdout}\n=== STDERR ===\n${runResult.stderr}"
                        )
                    } catch (e: Exception) { log.warn { "保存缩减后源码失败: ${e.message}" } }

                    val summaryFile = File(outDir, "${baseName}_reduction_summary.txt")
                    summaryFile.writeText("""
                        |Reduction Summary
                        |=================
                        |Input: ${irFile.absolutePath}
                        |Original nodes: $originalNodeCount
                        |Reduced nodes: $minCount
                        |Ratio: ${"%.1f%%".format(result.reductionRatio * 100)}
                        |Reduced IR: ${minimalIrFile.absolutePath}
                        |
                        |Steps:
                        ${result.steps.joinToString("\n") { "  - ${it.type}: ${it.description}" }}
                    """.trimMargin())

                    echo("  Reduced: $originalNodeCount → $minCount nodes (${"%.1f".format(result.reductionRatio * 100)}%)")
                    echo("  Output: ${minimalIrFile.name}")
                } else {
                    echo("  ✗ Reduction failed: ${result.errorMessage ?: "property not preserved"}")
                }
            } catch (e: Exception) {
                echo("  ✗ Error: ${e.message}")
                log.warn(e) { "缩减 ${irFile.name} 失败" }
            }
            echo()
        }
        echo("=".repeat(60)); echo("Reduce mode completed"); echo("=".repeat(60))
        log.info { "缩减模式完成" }
    }

    companion object {
        fun matchesBugSignature(currentStderr: String, originalStderr: String): Boolean {
            if (currentStderr.isBlank()) return false
            // fast-path: exact type signatures
            if (originalStderr.contains("VERIFY: FAIL")) return currentStderr.contains("VERIFY: FAIL")
            if (originalStderr.contains("tvm.error.InternalError")) return currentStderr.contains("tvm.error.InternalError")
            // 匹配实际抛出错误类型（原始错误链的末行）
            val originalErrorType = originalStderr.lines().map { it.trim() }.filter { it.isNotBlank() }.lastOrNull {
                it.startsWith("RuntimeError:") || it.startsWith("tvm.error.")
                    || it.startsWith("torch._inductor.exc.InductorError:")
                    || it.startsWith("AssertionError:")
            }
            if (originalErrorType != null && currentStderr.contains(originalErrorType)) return true
            return false
        }

        fun createDaemonForBackend(backend: String, pythonOverride: String? = null): Pair<(UirProgram) -> String, DaemonClient> {
            val pythonPath = pythonOverride ?: "python3"
            val workDir = "/tmp/aiFuzzer_$backend"
            log.info { "缩减 daemon: python=$pythonPath, backend=$backend" }
            if (backend == "tvm") {
                val b = TvmDaemonBackend(pythonPath = pythonPath, workDir = File(workDir))
                return Pair(b.translator::translate, b.daemon)
            }
            val b = PytorchDaemonBackend(pythonPath = pythonPath, workDir = File(workDir))
            return Pair(b.translator::translate, b.daemon)
        }
    }
}

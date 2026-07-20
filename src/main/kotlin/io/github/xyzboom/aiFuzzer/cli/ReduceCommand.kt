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
import io.github.xyzboom.aiFuzzer.translator.onnx.OnnxTranslator
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
        .help("Backend for reduction validation: 'tvm', 'pytorch', or 'onnx' (default: pytorch)")

    private val pythonPath by option("--python", "-p")
        .help("Python executable for the backend daemon (default: python3)")

    private val configFile by option("--config", "-c")
        .file(mustExist = true, canBeFile = true, mustBeReadable = true)
        .help("Config file for backend settings (target, device, remote SSH, etc.)")

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

                val (translator, daemon) = createDaemonForBackend(backendChoice, pythonPath, configFile)
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
                        val (minTranslator, minDaemon) = createDaemonForBackend(backendChoice, pythonPath, configFile)
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
            if (originalStderr.contains("[ONNXRuntimeError]")) {
                // Match by error code + distinctive message content to avoid false positives
                // when different errors share the same code (e.g., code 2 = INVALID_ARGUMENT)
                val codePat = Regex("""\[ONNXRuntimeError]\s*:\s*(\d+)\s*:""")
                val origCode = codePat.find(originalStderr)?.groupValues?.getOrNull(1)
                val currCode = codePat.find(currentStderr)?.groupValues?.getOrNull(1)
                if (origCode == null || currCode == null || origCode != currCode) return false
                if (!currentStderr.contains("[ONNXRuntimeError]")) return false
                // Extract distinctive message, normalizing tensor/value names
                // (names in single quotes can change after reduction)
                val msgPat = Regex("""\[ONNXRuntimeError]\s*:\s*\d+\s*:\s*\w+\s*:\s*(.+)""")
                val origMsg = msgPat.find(originalStderr)?.groupValues?.getOrNull(1) ?: return true
                val curMsg = msgPat.find(currentStderr)?.groupValues?.getOrNull(1) ?: return true
                val normalizeName = { s: String -> s.replace(Regex("""'[^']*'"""), "'X'") }
                val phrase = normalizeName(origMsg).take(80)
                return normalizeName(curMsg).contains(phrase)
            }
            // 匹配实际抛出错误类型（原始错误链的末行）
            val originalErrorType = originalStderr.lines().map { it.trim() }.filter { it.isNotBlank() }.lastOrNull {
                it.startsWith("RuntimeError:") || it.startsWith("tvm.error.")
                    || it.startsWith("torch._inductor.exc.InductorError:")
                    || it.startsWith("AssertionError:")
                    || it.startsWith("IndexError:")
                    || it.startsWith("onnxruntime.capi.onnxruntime_pybind11_state.")
            }
            if (originalErrorType != null && currentStderr.contains(originalErrorType)) return true
            return false
        }

        fun createDaemonForBackend(
            backend: String,
            pythonOverride: String? = null,
            configFile: File? = null,
        ): Pair<(UirProgram) -> String, DaemonClient> {
            val pythonPath = pythonOverride ?: "python3"
            val workDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aiFuzzer_$backend")
            log.info { "缩减 daemon: python=$pythonPath, backend=$backend" }
            if (backend == "tvm") {
                // Load config if provided, to get target/device/remote settings
                val config = configFile?.let {
                    try { ConfigLoader.load(it.absolutePath) } catch (_: Exception) { null }
                }
                val target = config?.backends?.tvm?.target ?: "llvm"
                val device = config?.backends?.tvm?.device ?: "cpu"
                val remoteConfig = config?.backends?.tvm?.remote
                val b = TvmDaemonBackend(
                    pythonPath = pythonPath,
                    target = target,
                    device = device,
                    workDir = workDir,
                    remoteConfig = remoteConfig,
                )
                return Pair(b.translator::translate, b.daemon)
            }
            if (backend == "onnx") {
                val b = OnnxDaemonBackend(
                    pythonPath = pythonPath,
                    workDir = workDir,
                    opsetVersion = 11,
                )
                return Pair(b.translator::translate, b.daemon)
            }
            val b = PytorchDaemonBackend(pythonPath = pythonPath, workDir = workDir)
            return Pair(b.translator::translate, b.daemon)
        }
    }
}

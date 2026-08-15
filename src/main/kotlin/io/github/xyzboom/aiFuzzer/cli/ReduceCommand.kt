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
            var daemonToClose: DaemonClient? = null
            try {
                val jsonl = irFile.readText()
                val originalProgram = UirSerializer.fromJsonl(jsonl)
                val originalNodeCount = originalProgram.graphs.sumOf { it.nodes.size }
                echo("  Original nodes: $originalNodeCount")

                val (translator, daemon) = createDaemonForBackend(backendChoice, pythonPath, configFile).also { daemonToClose = it.second }
                // 首次执行原始程序，实际报什么错就以什么为 bug 签名
                val originalSource = translator(originalProgram)
                val originalResult = daemon.sendAndWait(originalSource)
                val originalError = originalResult.stderr
                echo("  Original error signature: ${originalError.take(200)}")

                val propertyChecker = object : PropertyChecker {
                    override fun check(program: UirProgram): Boolean = try {
                        val source = translator(program)
                        val daemonResult = daemon.sendAndWait(source)
                        val matched = matchesBugSignature(daemonResult.stderr, originalError)
                        val result = !daemonResult.success && matched
                        if (!result) {
                            val stderr = daemonResult.stderr
                            val ts = System.currentTimeMillis()
                            val shapeErrorHints = listOf(
                                "IndexError", "size mismatch", "tuple index out of range",
                                "The size of tensor", "must match", "out of bounds",
                                "shape", "Sizes of tensors", "mat1 and mat2",
                            )
                            val isShapeError = shapeErrorHints.any { stderr.contains(it) }
                            // 保存所有失败源码，用于分析 DDMin 拒绝原因
                            val failPath = if (isShapeError) {
                                "/tmp/reduce_fail_shape_${ts}.py"
                            } else {
                                "/tmp/reduce_fail_misc_${ts}.py"
                            }
                            try { File(failPath).writeText(source) } catch (_: Exception) {}
                            if (isShapeError) {
                                log.warn {
                                    "形状非法: 中间程序因形状不匹配被拒绝\n" +
                                    "  daemon stderr: ${stderr.lines().firstOrNull { it.contains("Error") || it.contains("error") }?.take(120) ?: stderr.take(200)}\n" +
                                    "  失败源码已保存: $failPath"
                                }
                            } else {
                                log.warn {
                                    "属性检查失败: success=${daemonResult.success}, matched=$matched\n" +
                                    "  daemon stderr (前200): ${stderr.take(200)}\n" +
                                    "  失败源码已保存: $failPath"
                                }
                            }
                        }
                        result
                    } catch (e: Exception) {
                        log.warn { "属性检查异常: ${e.message}" }
                        false
                    }

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
                        try {
                            val minSource = minTranslator(result.minifiedProgram)
                            File(outDir, "${baseName}_minimal_source.py").writeText(minSource)
                            val runResult = minDaemon.sendAndWait(minSource)
                            File(outDir, "${baseName}_minimal_stderr.log").writeText(
                                "=== STDOUT ===\n${runResult.stdout}\n=== STDERR ===\n${runResult.stderr}"
                            )
                        } finally {
                            try { minDaemon.close() } catch (_: Exception) {}
                        }
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
            } finally {
                try { daemonToClose?.close() } catch (_: Exception) {}
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
            if (originalStderr.contains("ScheduleError")) return currentStderr.contains("ScheduleError")
            if (originalStderr.contains("[ONNXRuntimeError]")) {
                // Match by error code + distinctive message content to avoid false positives
                val codePat = Regex("""\[ONNXRuntimeError]\s*:\s*(\d+)\s*:""")
                val origCode = codePat.find(originalStderr)?.groupValues?.getOrNull(1)
                val currCode = codePat.find(currentStderr)?.groupValues?.getOrNull(1)
                if (origCode == null || currCode == null || origCode != currCode) return false
                if (!currentStderr.contains("[ONNXRuntimeError]")) return false
                val msgPat = Regex("""\[ONNXRuntimeError]\s*:\s*\d+\s*:\s*\w+\s*:\s*(.+)""")
                val origMsg = msgPat.find(originalStderr)?.groupValues?.getOrNull(1) ?: return true
                val curMsg = msgPat.find(currentStderr)?.groupValues?.getOrNull(1) ?: return true
                val normalizeName = { s: String -> s.replace(Regex("""'[^']*'"""), "'X'") }
                val phrase = normalizeName(origMsg).take(80)
                return normalizeName(curMsg).contains(phrase)
            }
            // Match ONNX-DIFF output mismatch (wrong-output bugs from differential testing)
            if (originalStderr.contains("[ONNX-DIFF]")) {
                return currentStderr.contains("[ONNX-DIFF]")
            }
            // 匹配实际抛出错误类型（原始错误链的末行）
            // 所有已知 Python 异常类型
            val knownErrorPrefixes = listOf(
                "RuntimeError:", "tvm.error.", "AttributeError:", "TypeError:",
                "torch._inductor.exc.InductorError:", "AssertionError",
                "IndexError:", "KeyError:", "ValueError:", "ZeroDivisionError:",
                "onnxruntime.capi.onnxruntime_pybind11_state.",
                "tvm.s_tir.schedule.schedule.ScheduleError:", "ScheduleError:",
            )
            val originalErrorType = originalStderr.lines().map { it.trim() }.filter { it.isNotBlank() }.lastOrNull {
                knownErrorPrefixes.any { prefix -> it.startsWith(prefix) }
            }
            if (originalErrorType != null) {
                // For AttributeError, use a more flexible match: check the error type + key message fragment
                if (originalErrorType.startsWith("AttributeError:")) {
                    val origMsg = originalErrorType.removePrefix("AttributeError: ").trim()
                    val keyFragment = origMsg.take(80)
                    return currentStderr.contains("AttributeError") && currentStderr.contains(keyFragment)
                }
                // For AssertionError (both bare and with message), match the assert expression content
                if (originalErrorType == "AssertionError" || originalErrorType.startsWith("AssertionError:")) {
                    val assertLine = originalStderr.lines().map { it.trim() }.lastOrNull { it.startsWith("assert ") }
                    if (assertLine != null) {
                        return currentStderr.contains(assertLine)
                    }
                    // Fallback: no assert statement found, match by type name only
                    return currentStderr.contains(originalErrorType)
                }
                // For other errors, exact line match
                if (currentStderr.contains(originalErrorType)) return true
            }
            return false
        }

        fun createDaemonForBackend(
            backend: String,
            pythonOverride: String? = null,
            configFile: File? = null,
        ): Pair<(UirProgram) -> String, DaemonClient> {
            val pythonPath = pythonOverride ?: "python3"
            val workDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aiFuzzer_$backend")
            // Load config if provided, to get target/device/remote/timeout settings
            val config = configFile?.let {
                try { ConfigLoader.load(it.absolutePath) } catch (_: Exception) { null }
            }
            log.info { "缩减 daemon: python=$pythonPath, backend=$backend" }
            if (backend == "tvm") {
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
            val b = PytorchDaemonBackend(
                    pythonPath = pythonPath,
                    workDir = workDir,
                    requestTimeoutMs = (config?.backends?.pytorch?.timeoutSeconds?.times(1000L)) ?: 120_000L,
                )
            return Pair(b.translator::translate, b.daemon)
        }
    }
}

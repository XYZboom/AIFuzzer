package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.translator.onnx.OnnxTranslator
import java.io.File
import java.util.concurrent.TimeUnit

class OnnxBackend(
    workDir: File = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aiFuzzer_onnx"),
    private val opsetVersion: Int = 21,
    private val irVersion: Int = 8,
) : Backend<OnnxBackend.OnnxResult> {

    override val name = "ONNX Runtime"
    override val workDir = workDir.also { it.mkdirs() }

    private val translator = OnnxTranslator(opsetVersion = opsetVersion, irVersion = irVersion)
    private val onnxPython: String = "python3"

    override fun checkEnvironment(): Boolean {
        return try {
            val p = ProcessBuilder(onnxPython, "-c", "import onnx; import onnxruntime")
                .redirectErrorStream(true).start()
            p.waitFor(5, TimeUnit.SECONDS); p.exitValue() == 0
        } catch (_: Exception) { false }
    }

    override fun compile(program: UirProgram): CompilationArtifact {
        return CompilationArtifact(workDir.absolutePath, listOf(SourceFile("program.py", translator.translate(program))))
    }

    override fun execute(program: UirProgram): OnnxResult {
        val artifact = compile(program)
        val hash = Integer.toHexString(artifact.sources.first().content.hashCode())
        val scriptFile = File(workDir, "prog_${hash}.py")
        scriptFile.writeText(artifact.sources.first().content)
        val startTime = System.currentTimeMillis()
        val p = ProcessBuilder(onnxPython, scriptFile.absolutePath)
            .directory(workDir).redirectErrorStream(false).start()
        val stdout = p.inputStream.bufferedReader().readText()
        val stderr = p.errorStream.bufferedReader().readText()
        val exitCode = p.waitFor()
        val elapsed = System.currentTimeMillis() - startTime
        val ei = ErrorAnalyzer.analyze(stderr, exitCode)
        return OnnxResult(exitCode == 0, exitCode, stdout, stderr, elapsed, ei.category, ei.summary, scriptFile.absolutePath)
    }

    data class OnnxResult(
        override val success: Boolean, override val exitCode: Int,
        override val stdout: String, override val stderr: String,
        override val elapsedMs: Long, val errorCategory: ErrorCategory,
        val errorSummary: String, val sourceFile: String,
    ) : BackendResult(success, exitCode, stdout, stderr, elapsedMs)
}

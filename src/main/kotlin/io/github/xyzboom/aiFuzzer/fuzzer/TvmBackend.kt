package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.config.TvmConfig
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * TVM Relax 编译器后端。
 */
class TvmBackend(
    workDir: File = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aiFuzzer_tvm"),
    /** 从 config 创建 */
    config: TvmConfig? = null,
) : Backend<TvmBackend.TvmResult> {

    override val name = "TVM Relax"
    override val workDir = workDir.also { it.mkdirs() }

    private val translator = if (config != null) {
        TvmRelaxTranslator(shapeRank = config.shapeRank, dtype = config.dtype)
    } else {
        TvmRelaxTranslator()
    }

    private val tvmPython: String = config?.python ?: "python3"

    override fun checkEnvironment(): Boolean {
        return try {
            val process = ProcessBuilder(tvmPython, "-c", "import tvm")
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    override fun compile(program: UirProgram): CompilationArtifact {
        val pythonCode = translator.translate(program)
        return CompilationArtifact(
            sourcePath = workDir.absolutePath,
            sources = listOf(SourceFile("program.py", pythonCode)),
        )
    }

    override fun execute(program: UirProgram): TvmResult {
        val artifact = compile(program)
        val sourceFile = artifact.sources.first()
        // 使用 hash 确保唯一文件名
        val hash = Integer.toHexString(sourceFile.content.hashCode())
        val scriptFile = File(workDir, "prog_${hash}.py")
        scriptFile.writeText(artifact.sources.first().content)

        val startTime = System.currentTimeMillis()

        val process = ProcessBuilder(tvmPython, scriptFile.absolutePath)
            .directory(workDir)
            .redirectErrorStream(false)
            .start()

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        val elapsed = System.currentTimeMillis() - startTime

        val errorInfo = ErrorAnalyzer.analyze(stderr, exitCode)

        return TvmResult(
            success = exitCode == 0,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            elapsedMs = elapsed,
            errorCategory = errorInfo.category,
            errorSummary = errorInfo.summary,
            sourceFile = scriptFile.absolutePath,
        )
    }

    fun cleanup() {
        workDir.deleteRecursively()
    }

    data class TvmResult(
        override val success: Boolean,
        override val exitCode: Int,
        override val stdout: String,
        override val stderr: String,
        override val elapsedMs: Long,
        val errorCategory: ErrorCategory,
        val errorSummary: String,
        val sourceFile: String,
    ) : BackendResult(success, exitCode, stdout, stderr, elapsedMs)
}
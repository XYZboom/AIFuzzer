package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

private val log = KotlinLogging.logger {}

/**
 * Bug 收集器：自动将疑似 bug 的测试程序存为文件夹形式的报告。
 *
 * 每个 bug 在 reportsDir 下创建一个文件夹，包含：
 *   - source.py          ：生成的 Python 源文件
 *   - stderr.log         ：错误堆栈的完整信息
 *   - ir.jsonl           ：原始 IR 序列化文件
 *   - minimal_source.py  ：缩减后的源码（如果启用缩减）
 *   - minimal_ir.jsonl   ：缩减后的 IR（如果启用缩减）
 *   - reduction_summary.txt：缩减摘要（如果启用缩减）
 *
 * 缩减产物由 [saveReductionArtifacts] 单独写入，
 * 缩减失败不影响原始数据的保存。
 */
object BugCollector {

    /** Bug 报告输出目录 */
    val reportsDir: File = run {
        val dir = File("reports").absoluteFile
        dir.mkdirs()
        dir
    }

    private val bugCounter = AtomicInteger(0)

    fun isWorthyBug(result: BackendResult): Boolean {
        return !result.success
    }

    /**
     * 将疑似 bug 保存为文件夹报告。
     * @return 创建的 bug 目录，可用于后续写入缩减产物
     */
    fun collect(
        result: BackendResult,
        seed: Long,
        backendName: String,
        program: UirProgram? = null,
        sourceCode: String? = null,
    ): File {
        if (!isWorthyBug(result)) return File("")

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val bugId = bugCounter.getAndIncrement() + 1

        val bugDirName = "bug_%03d_%s_seed%s_%s_%s".format(
            bugId,
            backendName.replace(" ", "_"),
            seed,
            result.exitCode,
            timestamp,
        )
        val bugDir = File(reportsDir, bugDirName)
        bugDir.mkdirs()

        // 1. 写入源码文件
        if (sourceCode != null) {
            File(bugDir, "source.py").writeText(sourceCode)
        } else {
            val srcPath = when (result) {
                is TvmBackend.TvmResult -> result.sourceFile
                is PytorchDaemonBackend.PytorchResult -> result.sourceFile
                else -> null
            }
            if (srcPath != null) {
                val srcFile = File(srcPath)
                if (srcFile.exists()) {
                    srcFile.copyTo(File(bugDir, "source.py"), overwrite = true)
                }
            }
        }

        // 2. 写入错误堆栈
        val stderrContent = """
# ============================================================
# Bug Report - Auto Collected by aiFuzzer
# Backend: ${backendName}
# Seed: ${seed}
# Timestamp: ${timestamp}
# Exit code: ${result.exitCode}
# Elapsed: ${result.elapsedMs}ms
# ============================================================

--- STDOUT ---
${result.stdout.trimEnd()}

--- STDERR ---
${result.stderr.trimEnd()}
""".trimStart()
        File(bugDir, "stderr.log").writeText(stderrContent)

        // 3. 写入 IR 序列化文件
        if (program != null) {
            val irFile = File(bugDir, "ir.jsonl")
            irFile.writeText(UirSerializer.toJsonl(program))
        }

        // 4. 复制日志文件
        val logFile = File("logs/aifuzzer.log")
        if (logFile.exists()) {
            logFile.copyTo(File(bugDir, "aifuzzer.log"), overwrite = true)
        }
        val traceLogFile = File("logs/aifuzzer-trace.log")
        if (traceLogFile.exists()) {
            traceLogFile.copyTo(File(bugDir, "aifuzzer-trace.log"), overwrite = true)
        }

        log.info { "Bug 已保存: ${bugDir.name} (seed=$seed, backend=$backendName)" }
        return bugDir
    }

    /**
     * 将缩减产物保存到已有的 bug 目录中。
     * 缩减失败（或未启用缩减）时无需调用此方法。
     *
     * @param bugDir [collect] 返回的 bug 目录
     * @param reducedProgram 缩减后的 UIR 程序
     * @param reducedSource 缩减后重新翻译的源码（可选）
     * @param reducedStderr 缩减后 daemon 执行的 stderr 输出（可选）
     * @param reducedStdout 缩减后 daemon 执行的 stdout 输出（可选）
     */
    fun saveReductionArtifacts(
        bugDir: File,
        reducedProgram: UirProgram,
        reducedSource: String? = null,
        reducedStderr: String? = null,
        reducedStdout: String? = null,
    ) {
        if (!bugDir.exists()) bugDir.mkdirs()

        // 写入缩减后的 IR
        File(bugDir, "minimal_ir.jsonl").writeText(UirSerializer.toJsonl(reducedProgram))

        // 写入缩减后重新翻译的源码
        if (reducedSource != null) {
            File(bugDir, "minimal_source.py").writeText(reducedSource)
        }

        // 写入缩减后的 stderr
        if (reducedStderr != null) {
            val content = buildString {
                appendLine("# ============================================================")
                appendLine("# Reduced Program - stderr")
                appendLine("# ============================================================")
                appendLine()
                if (reducedStdout != null) {
                    appendLine("--- STDOUT ---")
                    appendLine(reducedStdout.trimEnd())
                    appendLine()
                }
                appendLine("--- STDERR ---")
                appendLine(reducedStderr.trimEnd())
            }
            File(bugDir, "minimal_stderr.log").writeText(content)
        }

        // 写入缩减摘要
        val originalNodeCount = try {
            File(bugDir, "ir.jsonl").readLines().count { it.contains("\"visitNode\"") }
        } catch (_: Exception) { 0 }
        val reducedNodeCount = try {
            File(bugDir, "minimal_ir.jsonl").readLines().count { it.contains("\"visitNode\"") }
        } catch (_: Exception) { 0 }

        val summary = """
# Reduction Summary
# Original nodes: $originalNodeCount
# Reduced nodes: $reducedNodeCount
# Reduction ratio: ${
            if (originalNodeCount > 0) String.format("%.1f%%",
                (1 - reducedNodeCount.toDouble() / originalNodeCount) * 100) else "N/A"
        }
# Timestamp: ${LocalDateTime.now()}
""".trimStart()
        File(bugDir, "reduction_summary.txt").writeText(summary)

        log.info { "缩减产物已保存: ${bugDir.name} (${originalNodeCount}→${reducedNodeCount} nodes)" }
    }

    fun reset() { bugCounter.set(0) }
}

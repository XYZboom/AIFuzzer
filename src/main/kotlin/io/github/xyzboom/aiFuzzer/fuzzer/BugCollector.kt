package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/**
 * Bug 收集器：自动将疑似 bug 的测试程序存为文件夹形式的报告。
 *
 * 每个 bug 在 reportsDir 下创建一个文件夹，包含：
 *   - source.py       ：生成的 Python 源文件（AI 编译器的输入）
 *   - stderr.log      ：错误堆栈的完整信息
 *   - ir.jsonl        ：IR 序列化文件（JSON Lines 格式）
 */
object BugCollector {

    /** Bug 报告输出目录 */
    val reportsDir: File = run {
        val dir = File("reports").absoluteFile
        dir.mkdirs()
        dir
    }

    private var bugCounter = 0

    /**
     * 所有非成功的执行结果都视为 bug。
     * 不管错误来自翻译器/生成器还是被测程序，都应当被记录。
     */
    fun isWorthyBug(result: BackendResult): Boolean {
        return !result.success
    }

    /**
     * 将疑似 bug 保存为文件夹报告。
     *
     * @param result 后端执行结果
     * @param seed 随机种子
     * @param backendName 后端名称
     * @param program 对应的 UIR 程序（用于序列化 IR）
     * @param sourceCode 生成的 Python 源码内容（AI 编译器的输入）
     */
    fun collect(
        result: BackendResult,
        seed: Long,
        backendName: String,
        program: UirProgram? = null,
        sourceCode: String? = null,
    ) {
        if (!isWorthyBug(result)) return

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        bugCounter++

        // 创建 bug 文件夹
        val bugDirName = "bug_%03d_%s_seed%s_%s_%s".format(
            bugCounter,
            backendName.replace(" ", "_"),
            seed,
            result.exitCode,
            timestamp,
        )
        val bugDir = File(reportsDir, bugDirName)
        bugDir.mkdirs()

        // 1. 写入源码文件（AI 编译器的输入）
        if (sourceCode != null) {
            val sourceFile = File(bugDir, "source.py")
            sourceFile.writeText(sourceCode)
        } else {
            // 从 BackendResult 中取出 sourceFile 路径
            val srcPath = when (result) {
                is TvmBackend.TvmResult -> result.sourceFile
                else -> null
            }
            if (srcPath != null) {
                val srcFile = File(srcPath)
                if (srcFile.exists()) {
                    srcFile.copyTo(File(bugDir, "source.py"), overwrite = true)
                }
            }
        }

        // 2. 写入错误堆栈完整信息
        val errorFile = File(bugDir, "stderr.log")
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
        errorFile.writeText(stderrContent)

        // 3. 写入 IR 序列化文件
        if (program != null) {
            val irFile = File(bugDir, "ir.jsonl")
            val irContent = UirSerializer.toJsonl(program)
            irFile.writeText(irContent)
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
        log.info { "  路径: ${bugDir.relativeTo(File(".").absoluteFile)}" }
        log.debug { "  文件: source.py, stderr.log${if (program != null) ", ir.jsonl" else ""}${if (logFile.exists()) ", aifuzzer.log" else ""}${if (traceLogFile.exists()) ", aifuzzer-trace.log" else ""}" }
    }

    /** 重置计数器（新的一轮 fuzzing 时调用） */
    fun reset() {
        bugCounter = 0
    }
}
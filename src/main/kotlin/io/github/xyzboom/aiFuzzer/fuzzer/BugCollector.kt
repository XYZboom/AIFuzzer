package io.github.xyzboom.aiFuzzer.fuzzer

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Bug 收集器：自动将疑似 bug 的测试程序拷贝到项目 reports/ 目录。
 */
object BugCollector {

    /** Bug 报告输出目录 */
    val reportsDir: File = run {
        val dir = File("reports").absoluteFile
        dir.mkdirs()
        dir
    }

    private var bugCounter = 0

    /** 可被误报忽略的错误模式（翻译器或生成器已知问题） */
    private val ignorePatterns = listOf(
        // 翻译器对某些 op 的映射可能不尽精确
        "OpNotImplemented",
        "AttributeError",
        // Python 语法错误（翻译器 bug）
        "SyntaxError",
        "IndentationError",
        // Import 错误
        "ImportError",
        "ModuleNotFoundError",
    )

    /**
     * 检查一个结果是否值得作为 bug 保存。
     * 仅保存非预期编译错误（UCTE 类）。
     */
    fun isWorthyBug(result: BackendResult): Boolean {
        if (result.success) return false
        val stderr = result.stderr
        return ignorePatterns.none { stderr.contains(it) }
    }

    /**
     * 将疑似 bug 保存到 reports/ 目录。
     * @param result 后端执行结果
     * @param seed 随机种子
     * @param backendName 后端名称
     */
    fun collect(result: BackendResult, seed: Long, backendName: String) {
        if (!isWorthyBug(result)) return

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val srcFile = when (result) {
            is TvmBackend.TvmResult -> File(result.sourceFile)
            else -> null
        }

        if (srcFile == null || !srcFile.exists()) return

        bugCounter++
        val bugFile = File(reportsDir, "bug_${"bug_%03d".format(bugCounter)}_${backendName.replace(" ", "_")}_seed${seed}_${timestamp}.py")

        // 在源码头部添加 bug 信息注释
        val bugInfo = """
# ============================================================
# Bug Report - Auto Collected by aiFuzzer
# Backend: ${backendName}
# Seed: ${seed}
# Timestamp: ${timestamp}
# Error: ${result.stderr.lines().firstOrNull()?.take(120) ?: "unknown"}
# ============================================================

""".trimStart()

        srcFile.copyTo(bugFile, overwrite = true)
        // 在文件开头插入注释
        val content = bugFile.readText()
        bugFile.writeText(bugInfo + content)

        println("  🔍 Bug saved to: ${bugFile.relativeTo(File(".").absoluteFile)}")
    }

    /** 重置计数器（新的一轮 fuzzing 时调用） */
    fun reset() {
        bugCounter = 0
    }
}
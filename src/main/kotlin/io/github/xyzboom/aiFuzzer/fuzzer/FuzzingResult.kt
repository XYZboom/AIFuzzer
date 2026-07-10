package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * 单次 Fuzzing 运行的结果。
 */
data class FuzzingResult(
    /** 使用的随机种子 */
    val seed: Long,
    /** 目标后端 */
    val backendName: String,
    /** 后端执行结果 */
    val backendResult: BackendResult,
    /** 错误分类 */
    val errorCategory: ErrorCategory = ErrorCategory.UNKNOWN,
    /** 错误摘要（一行总结） */
    val errorSummary: String = "",
)

/**
 * 批量 Fuzzing 运行的统计信息。
 */
data class FuzzingSummary(
    val total: Int,
    val successes: Int,
    val failures: Int,
    val successRate: Double,
    val groupedErrors: Map<ErrorCategory, Int>,
    val totalTimeMs: Long,
    val results: List<FuzzingResult>,
) {
    companion object {
        fun fromResults(results: List<FuzzingResult>, totalTimeMs: Long): FuzzingSummary {
            val successes = results.count { it.backendResult.success }
            val failures = results.count { !it.backendResult.success }
            return FuzzingSummary(
                total = results.size,
                successes = successes,
                failures = failures,
                successRate = if (results.isEmpty()) 0.0 else successes.toDouble() / results.size,
                groupedErrors = results.filter { !it.backendResult.success }
                    .groupBy { it.errorCategory }
                    .mapValues { it.value.size },
                totalTimeMs = totalTimeMs,
                results = results,
            )
        }
    }

    fun printReport() {
        val sb = StringBuilder()
        sb.appendLine("=".repeat(60))
        sb.appendLine("Fuzzing Report")
        sb.appendLine("=".repeat(60))
        sb.appendLine("Total runs:      $total")
        sb.appendLine("Successes:       $successes (${"%.1f".format(successRate * 100)}%)")
        sb.appendLine("Failures:        $failures")
        sb.appendLine("Total time:      ${"%.1f".format(totalTimeMs / 1000.0)}s")
        sb.appendLine()
        sb.appendLine("Error breakdown:")
        if (groupedErrors.isEmpty()) {
            sb.appendLine("  (none)")
        } else {
            groupedErrors.entries
                .sortedByDescending { it.value }
                .forEach { (category, count) ->
                    sb.appendLine("  ${"%-22s".format(category.name)}: $count")
                }
        }
        sb.appendLine("=".repeat(60))
        
        log.info { "\n$sb" }
    }
}
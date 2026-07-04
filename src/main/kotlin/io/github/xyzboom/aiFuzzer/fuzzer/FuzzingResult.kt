package io.github.xyzboom.aiFuzzer.fuzzer

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
        println("=".repeat(60))
        println("Fuzzing Report")
        println("=".repeat(60))
        println("Total runs:      $total")
        println("Successes:       $successes (${"%.1f".format(successRate * 100)}%)")
        println("Failures:        $failures")
        println("Total time:      ${"%.1f".format(totalTimeMs / 1000.0)}s")
        println()
        println("Error breakdown:")
        if (groupedErrors.isEmpty()) {
            println("  (none)")
        } else {
            groupedErrors.entries
                .sortedByDescending { it.value }
                .forEach { (category, count) ->
                    println("  ${"%-22s".format(category.name)}: $count")
                }
        }
        println("=".repeat(60))
    }
}
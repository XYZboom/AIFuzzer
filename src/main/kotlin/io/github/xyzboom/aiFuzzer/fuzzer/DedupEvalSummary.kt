package io.github.xyzboom.aiFuzzer.fuzzer

/**
 * Dedup 效率评估结果：对每个种子同时用 no-dedup（不启用 pattern 去重）和 with-dedup（启用 pattern 去重）生成，
 * 只执行 dedup 触发了重试的种子（dedup 程序 ≠ no-dedup 程序），比较两组结果。
 *
 * @param totalSeeds 遍历的种子总数
 * @param skipped dedup 未触发（重试次数为 0），跳过执行
 * @param collected dedup 触发了重试，已执行
 * @param bugPrevented no-dedup程序触发bug、dedup程序不触发bug — dedup 成功阻止了已知 bug
 * @param dedupOnlyFail no-dedup程序不触发bug、dedup程序触发bug — 仅 dedup 程序触发了 bug，需进一步分析是发现新 bug 还是 pattern 过宽导致
 * @param bothFailed no-dedup程序和dedup程序都触发bug（可能不同 bug）
 * @param bothSuccess no-dedup程序和dedup程序都不触发bug
 */
data class DedupEvalSummary(
    val totalSeeds: Int,
    val skipped: Int,
    val collected: Int,
    val bugPrevented: Int,
    val dedupOnlyFail: Int,
    val bothFailed: Int,
    val bothSuccess: Int,
    val failedSeeds: Int = 0,
    val bugPreventedSeeds: List<Long> = emptyList(),
    val dedupOnlyFailSeeds: List<Long> = emptyList(),
    val bothFailedSeeds: List<Long> = emptyList(),
    val totalTimeMs: Long = 0,
) {
    /** dedup 触发率 */
    val triggerRate: Double get() = if (totalSeeds > 0) collected.toDouble() / totalSeeds else 0.0
    /** bug 规避率（在触发的种子里，no-dedup触发bug/dedup不触发） */
    val preventRate: Double get() = if (collected > 0) bugPrevented.toDouble() / collected else 0.0
    /** 仅 dedup 失败率（在触发的种子里，dedup程序触发bug——可能是新bug或pattern过宽） */
    val dedupOnlyFailRate: Double get() = if (collected > 0) dedupOnlyFail.toDouble() / collected else 0.0

    fun printReport() {
        println()
        println("=".repeat(60))
        println("Dedup Efficiency Evaluation Report")
        println("=".repeat(60))
        println("Total seeds:        $totalSeeds")
        println("Collected (dedup触发):    $collected (" + "%.1f".format(triggerRate * 100) + "%)")
        println("Skipped (dedup未触发):     $skipped (" + "%.1f".format((1 - triggerRate) * 100) + "%)")
        if (failedSeeds > 0) {
            println("Failed (skipped):   $failedSeeds")
        }
        println()
        println("--- Collected seeds breakdown ---")
        println("Bug prevented:      $bugPrevented (" + "%.1f".format(preventRate * 100) + "%)")
        println("Dedup-only fail:    $dedupOnlyFail (" + "%.1f".format(dedupOnlyFailRate * 100) + "%)")
        println("Both failed:        $bothFailed")
        println("Both succeeded:     $bothSuccess")
        println()
        println("--- Details ---")
        if (bugPreventedSeeds.isNotEmpty()) {
            println("Bug prevented seeds: ${bugPreventedSeeds.joinToString(", ")}")
        }
        if (dedupOnlyFailSeeds.isNotEmpty()) {
            println("Dedup-only fail seeds: ${dedupOnlyFailSeeds.joinToString(", ")}")
        }
        if (bothFailedSeeds.isNotEmpty()) {
            println("Both failed seeds: ${bothFailedSeeds.joinToString(", ")}")
        }
        println("Total time:         " + "%.1f".format(totalTimeMs / 1000.0) + "s")
        println("=".repeat(60))
    }
}
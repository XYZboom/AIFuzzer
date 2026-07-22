package io.github.xyzboom.aiFuzzer.reducer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer

class AutoReducer(
    private val config: ReducerConfig = ReducerConfig(),
) {
    data class ReducerConfig(
        val enabled: Boolean = true,
        val timeoutSeconds: Int = 300,
    )

    fun reduce(
        program: UirProgram,
        propertyChecker: PropertyChecker,
    ): ReductionResult {
        if (!config.enabled) return ReductionResult.unchanged(program)
        if (program.graphs.isEmpty()) {
            return ReductionResult.failed(program, "no graphs to reduce")
        }
        val originalNodeCount = program.graphs.sumOf { it.nodes.size }
        return try {
            doReduce(program, originalNodeCount, propertyChecker)
        } catch (e: Exception) {
            log.error(e) { "Reduction failed" }
            ReductionResult.failed(program, "reduction failed: ${e.message}")
        }
    }

    fun reduceFromJsonl(
        jsonl: String,
        propertyChecker: PropertyChecker,
    ): ReductionResult {
        val program = UirSerializer.fromJsonl(jsonl)
        return reduce(program, propertyChecker)
    }

    private fun doReduce(
        program: UirProgram,
        originalNodeCount: Int,
        propertyChecker: PropertyChecker,
    ): ReductionResult {
        val steps = mutableListOf<ReductionStep>()
        val reducer = IrDdminReducer(propertyChecker, program)

        // 全局 DDMin：所有图的节点一起跑，DDMin 自然处理跨图场景
        reducer.reduceGlobal(steps)

        val preserved = propertyChecker.check(program)
        val minifiedNodeCount = program.graphs.sumOf { it.nodes.size }
        val ratio = if (originalNodeCount > 0) {
            1.0 - minifiedNodeCount.toDouble() / originalNodeCount
        } else 0.0

        return ReductionResult(
            originalProgram = program,
            minifiedProgram = program,
            reductionRatio = ratio,
            propertyPreserved = preserved,
            steps = steps,
        )
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
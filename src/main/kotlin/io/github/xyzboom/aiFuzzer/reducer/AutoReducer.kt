package io.github.xyzboom.aiFuzzer.reducer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer

/**
 * AutoReducer 入口：编排多阶段缩减流程。
 *
 * 使用方式：
 *   val reducer = AutoReducer()
 *   val result = reducer.reduceFromJsonl(jsonl, propertyChecker)
 */
class AutoReducer(
    private val config: ReducerConfig = ReducerConfig(),
) {
    data class ReducerConfig(
        val enabled: Boolean = true,
        val timeoutSeconds: Int = 300,
    )

    /**
     * 对 UIR Program 执行缩减（原地修改）。
     */
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

        for (graph in program.graphs) {
            val preserved = reducer.reduceGraph(graph, steps)
            if (!preserved) {
                log.warn { "Graph '${graph.name}' 缩减后属性丢失" }
            }
        }

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
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

    private fun testGraphSubset(
        program: UirProgram,
        candidateIndices: Set<Int>,
        propertyChecker: PropertyChecker,
    ): Boolean {
        if (candidateIndices.isEmpty()) return false
        return try {
            val jsonl = UirSerializer.toJsonl(program)
            val copy = UirSerializer.fromJsonl(jsonl)
            val toRemove = mutableListOf<Int>()
            for (idx in copy.graphs.indices) {
                if (idx !in candidateIndices) {
                    copy.graphs[idx].nodes.clear()
                    copy.graphs[idx].inputs.clear()
                    copy.graphs[idx].outputs.clear()
                    toRemove.add(idx)
                }
            }
            toRemove.sortedDescending().forEach { copy.graphs.removeAt(it) }
            if (copy.graphs.isEmpty()) return false
            propertyChecker.check(copy)
        } catch (e: Exception) {
            log.debug { "图级别 DDMin 测试异常: ${e.message}" }
            false
        }
    }

    private fun doReduce(
        program: UirProgram,
        originalNodeCount: Int,
        propertyChecker: PropertyChecker,
    ): ReductionResult {
        val steps = mutableListOf<ReductionStep>()
        val reducer = IrDdminReducer(propertyChecker, program)

        // 保存原始程序的深拷贝，用于最终属性检查失败时回滚
        val originalProgram = try {
            UirSerializer.fromJsonl(UirSerializer.toJsonl(program))
        } catch (e: Exception) {
            log.error(e) { "无法备份原始程序" }
            return ReductionResult.failed(program, "cannot backup original program: ${e.message}")
        }

        var changed = true
        var iterations = 0
        while (changed && iterations < 10) {
            changed = false
            iterations++
            val prevTotal = program.graphs.sumOf { it.nodes.size }

            // Stage 1: 图级别 DDMin（先删整图）
            if (program.graphs.size >= 2) {
                val graphIndices = program.graphs.indices.toList()
                val graphDdmin = DDMin<Int> { candidateIndices ->
                    if (candidateIndices.toSet() == graphIndices.toSet()) return@DDMin true
                    testGraphSubset(program, candidateIndices.toSet(), propertyChecker)
                }
                val minimalGraphs = graphDdmin.execute(graphIndices)
                val removedGraphs = graphIndices.filter { it !in minimalGraphs }
                for (idx in removedGraphs) {
                    val g = program.graphs[idx]
                    if (g.nodes.isNotEmpty()) {
                        log.info { "图级别 DDMin: graph_${idx} 完全删除 (${g.nodes.size} 节点)" }
                        steps.add(ReductionStep(
                            type = StepType.DDMIN_REMOVE,
                            description = "图级别 DDMin: graph_${idx} 完全删除 (${g.nodes.size} 节点)",
                            removedNodes = g.nodes.map { "${it.op}" },
                            remainingNodeCount = program.graphs.sumOf { it.nodes.size } - g.nodes.size,
                        ))
                        g.nodes.clear(); g.inputs.clear(); g.outputs.clear()
                        changed = true
                    }
                }
                program.graphs.removeAll { it.nodes.isEmpty() }
            }

            // Stage 2: 节点级别 DDMin（每个保留的图）
            for (graph in program.graphs) {
                if (graph.nodes.isEmpty()) continue
                val nodesBackup = graph.nodes.toList()
                val inputsBackup = graph.inputs.map { ref ->
                    io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef {
                        valueId = ref.valueId; type = ref.type
                    }
                }.toMutableList()
                val outputsBackup = graph.outputs.map { ref ->
                    io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef {
                        valueId = ref.valueId; type = ref.type
                    }
                }.toMutableList()

                val preserved = reducer.reduceGraph(graph, steps)
                if (!preserved) {
                    graph.nodes.clear(); graph.nodes.addAll(nodesBackup)
                    graph.inputs.clear(); graph.inputs.addAll(inputsBackup)
                    graph.outputs.clear(); graph.outputs.addAll(outputsBackup)
                } else if (graph.nodes.size < nodesBackup.size) {
                    if (!propertyChecker.check(program)) {
                        graph.nodes.clear(); graph.nodes.addAll(nodesBackup)
                        graph.inputs.clear(); graph.inputs.addAll(inputsBackup)
                        graph.outputs.clear(); graph.outputs.addAll(outputsBackup)
                    } else {
                        log.info { "Graph '${graph.name}' 节点缩减: ${nodesBackup.size} → ${graph.nodes.size} 节点" }
                        changed = true
                    }
                }
            }

            val newTotal = program.graphs.sumOf { it.nodes.size }
            if (newTotal < prevTotal) {
                log.info { "外层缩减第 $iterations 轮: ${prevTotal} → ${newTotal} 总节点" }
            }
        }

        val preserved = propertyChecker.check(program)
        val minifiedNodeCount = program.graphs.sumOf { it.nodes.size }
        val ratio = if (originalNodeCount > 0) {
            1.0 - minifiedNodeCount.toDouble() / originalNodeCount
        } else 0.0

        if (!preserved) {
            // 属性丢失，回滚到原始程序（使用已有的深拷贝备份）
            val restored = UirSerializer.fromJsonl(UirSerializer.toJsonl(originalProgram))
            program.graphs.clear()
            program.graphs.addAll(restored.graphs)
            log.warn { "最终属性检查失败，已回滚到原始程序 (${originalNodeCount} 节点)" }
            return ReductionResult(
                originalProgram = originalProgram,
                minifiedProgram = originalProgram,
                reductionRatio = 0.0,
                propertyPreserved = true,
                steps = steps,
                errorMessage = "property lost during reduction, rolled back to original",
            )
        }

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
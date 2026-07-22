package io.github.xyzboom.aiFuzzer.reducer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer

private val log = KotlinLogging.logger {}

/**
 * 全局 DDMin 缩减器。
 *
 * 所有图的节点放在一个列表里，DDMin 一次跑完。
 * 每个测试：深拷贝 → 删除非候选节点 → 依赖重建（含跨图）→ 验证
 */
class IrDdminReducer(
    private val propertyChecker: PropertyChecker,
    private val program: UirProgram,
) {
    /**
     * 全局缩减所有图的所有节点，到不动点。
     */
    fun reduceGlobal(
        steps: MutableList<ReductionStep>,
    ): Boolean {
        // 收集所有节点 + 所在图索引
        data class GraphNode(val graphIdx: Int, val node: UirNode)
        val allNodes = mutableListOf<GraphNode>()
        for ((idx, graph) in program.graphs.withIndex()) {
            for (node in graph.nodes) {
                allNodes.add(GraphNode(idx, node))
            }
        }
        if (allNodes.isEmpty()) return true

        var changed = true
        var iterations = 0
        var finalResult = true
        while (changed && iterations < 10) {
            changed = false
            iterations++
            val prevCount = allNodes.size

            // 准备 DDMin 测试函数
            val bestSubset = mutableListOf<GraphNode>()

            val ddmin = DDMin<GraphNode> { candidateNodes ->
                if (candidateNodes.toSet() == allNodes.toSet()) return@DDMin true
                val removedNodes = allNodes.filter { it !in candidateNodes }.toSet()
                if (removedNodes.isEmpty()) return@DDMin true

                val testResult = testSubsetGlobal(removedNodes.map { it.node }.toSet())
                if (testResult) {
                    bestSubset.clear()
                    bestSubset.addAll(candidateNodes)
                }
                testResult
            }

            // DDMin 执行，返回最小子集
            val minimal = ddmin.execute(allNodes)

            // 应用结果
            if (minimal.size < allNodes.size) {
                val removedGlobal = allNodes.filter { it !in minimal }
                val removedNodes = removedGlobal.map { it.node }.toSet()
                val removedCount = allNodes.size - minimal.size

                // 在原图上删除节点
                for (graph in program.graphs) {
                    graph.nodes.removeAll(removedNodes)
                }

                // 清理 inputs/outputs
                for (graph in program.graphs) {
                    val usedValueIds = graph.nodes.flatMap { it.inputs.map { i -> i.valueId } }.toSet()
                    graph.inputs.removeAll { it.valueId !in usedValueIds }
                    val producedValueIds = graph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet() +
                        graph.inputs.map { it.valueId }.toSet()
                    graph.outputs.removeAll { it.valueId !in producedValueIds }
                }
                // 删除空图
                program.graphs.removeAll { it.nodes.isEmpty() }

                steps.add(ReductionStep(
                    type = StepType.DDMIN_REMOVE,
                    description = "全局 DDMin 缩减：移除 $removedCount 个节点 → ${program.graphs.sumOf { it.nodes.size }} 总节点",
                    removedNodes = removedNodes.map { "${it.op}" },
                    remainingNodeCount = program.graphs.sumOf { it.nodes.size },
                ))
                log.info { "全局 DDMin 缩减: ${allNodes.size} → ${minimal.size} 节点 (移除 $removedCount 个)" }
                allNodes.clear(); allNodes.addAll(minimal)
                changed = true
                finalResult = true
            }
        }
        return finalResult
    }

    /**
     * 全局测试：深拷贝全程序，删除 [removedNodes]，重建依赖，验证属性。
     */
    private fun testSubsetGlobal(removedNodes: Set<UirNode>): Boolean {
        return try {
            val jsonl = UirSerializer.toJsonl(program)
            val copy = UirSerializer.fromJsonl(jsonl)

            // 在副本上找对应节点，按图分组
            val copyRemovedByGraph = mutableMapOf<UirGraph, MutableSet<UirNode>>()
            for (copyGraph in copy.graphs) {
                copyRemovedByGraph[copyGraph] = mutableSetOf()
                for (node in copyGraph.nodes) {
                    if (removedNodes.any { it.name == node.name && it.op == node.op }) {
                        copyRemovedByGraph[copyGraph]!!.add(node)
                    }
                }
            }

            // 对每个图：准备依赖重建 → 删除节点 → 重建
            val allZerosNodes = mutableListOf<UirNode>()
            for ((copyGraph, toRemove) in copyRemovedByGraph) {
                if (toRemove.isEmpty()) continue
                val reconstructor = DependencyReconstructor(copyGraph)
                val repairPlan = reconstructor.prepare(toRemove)
                copyGraph.nodes.removeAll(toRemove)
                val zerosNodes = reconstructor.apply(repairPlan)
                allZerosNodes.addAll(zerosNodes)
            }

            // 跨图引用修复：ZEROS 替代后，其他图的 inputs 需要更新
            for ((copyGraph, toRemove) in copyRemovedByGraph) {
                if (toRemove.isEmpty()) continue
                for (node in toRemove) {
                    for (outputRef in node.outputs) {
                        val newValueId = "${outputRef.valueId}_default"
                        for (otherGraph in copy.graphs) {
                            if (otherGraph === copyGraph) continue
                            for (input in otherGraph.inputs) {
                                if (input.valueId == outputRef.valueId) {
                                    input.valueId = newValueId
                                }
                            }
                        }
                    }
                }
            }

            // 清理 inputs/outputs
            for (copyGraph in copy.graphs) {
                val usedValueIds = copyGraph.nodes.flatMap { it.inputs.map { i -> i.valueId } }.toSet()
                copyGraph.inputs.removeAll { it.valueId !in usedValueIds }
                val producedValueIds = copyGraph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet() +
                    copyGraph.inputs.map { it.valueId }.toSet()
                copyGraph.outputs.removeAll { it.valueId !in producedValueIds }
            }
            copy.graphs.removeAll { it.nodes.isEmpty() }
            if (copy.graphs.isEmpty()) return false

            propertyChecker.check(copy)
        } catch (e: Exception) {
            log.debug { "全局 DDMin 测试异常: ${e.message}" }
            false
        }
    }
}
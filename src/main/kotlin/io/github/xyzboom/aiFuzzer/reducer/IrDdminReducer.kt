package io.github.xyzboom.aiFuzzer.reducer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer

private val log = KotlinLogging.logger {}

/**
 * 图内 DDMin 缩减器。
 * 每个图各自做节点级 DDMin，不处理跨图。
 */
class IrDdminReducer(
    private val propertyChecker: PropertyChecker,
    private val program: UirProgram,
) {
    fun reduceGraph(
        graph: UirGraph,
        steps: MutableList<ReductionStep>,
    ): Boolean {
        cleanupInputsOutputs(graph)
        val allNodes = graph.nodes.toList()
        if (allNodes.size <= 1) {
            return propertyChecker.check(program)
        }

        var bestSubset: Set<UirNode>? = null

        val ddmin = DDMin<UirNode> { candidateNodes ->
            if (candidateNodes.toSet() == allNodes.toSet()) return@DDMin true
            val removedNodes = allNodes.filter { it !in candidateNodes }.toSet()
            if (removedNodes.isEmpty()) return@DDMin true
            val testResult = testSubset(graph, removedNodes)
            if (testResult) {
                bestSubset = candidateNodes.toSet()
            }
            testResult
        }

        ddmin.execute(allNodes)

        if (bestSubset != null && bestSubset!!.size < allNodes.size) {
            val removedNodes = allNodes.filter { it !in bestSubset!! }.toSet()
            val snapshots = graph.nodes.map {
                InputSnapshot(it, it.inputs.map { ref -> buildValueRef { valueId = ref.valueId; type = ref.type } })
            }
            val nodesBackup = graph.nodes.toList()
            val inputsBackup = graph.inputs.map { buildValueRef { valueId = it.valueId; type = it.type } }.toMutableList()
            val outputsBackup = graph.outputs.map { buildValueRef { valueId = it.valueId; type = it.type } }.toMutableList()

            val reconstructor = DependencyReconstructor(graph)
            val repairPlan = reconstructor.prepare(removedNodes)
            graph.nodes.removeAll(removedNodes)
            reconstructor.apply(repairPlan)

            // 更新跨图 inputs
            for (repair in repairPlan.repairs) {
                val newValueId = when (repair.type) {
                    RepairType.WIRE_AROUND -> repair.newValueId
                    RepairType.DEFAULT_VALUE -> "${repair.oldValueId}_default"
                }
                if (newValueId != null) {
                    for (otherGraph in program.graphs) {
                        if (otherGraph === graph) continue
                        for (input in otherGraph.inputs) {
                            if (input.valueId == repair.oldValueId) {
                                input.valueId = newValueId
                            }
                        }
                    }
                }
            }

            // 清理 outputs（只保留节点产出的值）
            val producedByNodes = graph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet()
            graph.outputs.removeAll { it.valueId !in producedByNodes }

            if (validateGraph(graph) && propertyChecker.check(program)) {
                val removedCount = allNodes.size - bestSubset!!.size
                steps.add(ReductionStep(
                    type = StepType.DDMIN_REMOVE,
                    description = "DDMin 缩减：移除 $removedCount 个原始节点 → ${graph.nodes.size} 总节点",
                    removedNodes = removedNodes.map { "${it.op}" },
                    remainingNodeCount = graph.nodes.size,
                ))
                log.info { "DDMin 缩减成功: ${allNodes.size} → ${graph.nodes.size} 节点 (移除 $removedCount 个)" }
                return true
            } else {
                rollback(graph, nodesBackup, snapshots)
                graph.inputs.clear(); graph.inputs.addAll(inputsBackup)
                graph.outputs.clear(); graph.outputs.addAll(outputsBackup)
                return true
            }
        }
        return propertyChecker.check(program)
    }

    private fun testSubset(graph: UirGraph, removedNodes: Set<UirNode>): Boolean {
        return try {
            val jsonl = UirSerializer.toJsonl(program)
            val copy = UirSerializer.fromJsonl(jsonl)
            val copyGraph = copy.graphs.firstOrNull { it.name == graph.name } ?: return false

            val copyRemovedNodes = copyGraph.nodes.filter { node ->
                removedNodes.any { it.name == node.name && it.op == node.op }
            }.toSet()
            if (copyRemovedNodes.isEmpty()) return false

            val reconstructor = DependencyReconstructor(copyGraph)
            val repairPlan = reconstructor.prepare(copyRemovedNodes)
            copyGraph.nodes.removeAll(copyRemovedNodes)
            reconstructor.apply(repairPlan)

            // 跨图引用修复
            for (repair in repairPlan.repairs) {
                val newValueId = when (repair.type) {
                    RepairType.WIRE_AROUND -> repair.newValueId
                    RepairType.DEFAULT_VALUE -> "${repair.oldValueId}_default"
                }
                if (newValueId != null) {
                    for (otherGraph in copy.graphs) {
                        if (otherGraph === copyGraph) continue
                        for (input in otherGraph.inputs) {
                            if (input.valueId == repair.oldValueId) {
                                input.valueId = newValueId
                            }
                        }
                    }
                }
            }

            // 清理 outputs
            val producedByNodes = copyGraph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet()
            copyGraph.outputs.removeAll { it.valueId !in producedByNodes }

            if (!validateGraph(copyGraph)) return false
            propertyChecker.check(copy)
        } catch (e: Exception) {
            log.debug { "DDMin 测试异常: ${e.message}" }
            false
        }
    }

    private fun validateGraph(graph: UirGraph): Boolean {
        val allOutputValueIds = graph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet() +
            graph.inputs.map { it.valueId }.toSet()
        for (node in graph.nodes) {
            for (input in node.inputs) {
                if (input.valueId !in allOutputValueIds) return false
            }
        }
        return true
    }

    private data class InputSnapshot(val node: UirNode, val originalInputs: List<UirValueRef>)

    private fun rollback(graph: UirGraph, backup: List<UirNode>, snapshots: List<InputSnapshot>) {
        graph.nodes.clear(); graph.nodes.addAll(backup)
        for (snapshot in snapshots) {
            for (i in snapshot.originalInputs.indices) {
                if (i < snapshot.node.inputs.size) {
                    snapshot.node.inputs[i].valueId = snapshot.originalInputs[i].valueId
                    snapshot.node.inputs[i].type = snapshot.originalInputs[i].type
                }
            }
        }
    }

    private fun cleanupInputsOutputs(graph: UirGraph) {
        val usedValueIds = graph.nodes.flatMap { it.inputs.map { i -> i.valueId } }.toSet()
        graph.inputs.removeAll { it.valueId !in usedValueIds }
        val producedByNodes = graph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet()
        graph.outputs.removeAll { it.valueId !in producedByNodes }
    }
}
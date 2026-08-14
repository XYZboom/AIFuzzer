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
        // 跨图引用：其他图 outputs 的 valueId。cleanup 时必须保留这些 inputs，
        // 否则多图链式程序（graph_1 的输入来自 graph_0 输出）在删除内部消费节点后，
        // 跨图输入被误删 → 图接口改变 → 依赖 bug 的调度不触发 → 缩减被错误回滚。
        val crossGraphRefs = program.graphs
            .filter { it !== graph }
            .flatMap { it.outputs.map { o -> o.valueId } }
            .toSet()
        cleanupInputsOutputs(graph, crossGraphRefs)
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
            // ... DDMin 找到了可删子集，应用删除
            val removedNodes = allNodes.filter { it !in bestSubset!! }.toSet()
            val snapshots = graph.nodes.map {
                InputSnapshot(it, it.inputs.map { ref -> buildValueRef { valueId = ref.valueId; type = ref.type } })
            }
            val nodesBackup = graph.nodes.toList()
            val inputsBackup = graph.inputs.map { buildValueRef { valueId = it.valueId; type = it.type } }.toMutableList()
            val outputsBackup = graph.outputs.map { buildValueRef { valueId = it.valueId; type = it.type } }.toMutableList()

            val reconstructor = DependencyReconstructor(graph, crossGraphRefs)
            val repairPlan = reconstructor.prepare(removedNodes)
            graph.nodes.removeAll(removedNodes)
            reconstructor.apply(repairPlan)
            // 依赖重建后可能存在死代码（如 wire-around 残留的节点），清理之
            DeadCodeEliminator.eliminateToFixpoint(graph)

            // 提升 graph outputs：删除产出 graph output 的节点后，其输出值
            // 必须由替代来源（wire-around 源 / FULL 替代 / 上游输入）重新声明为 output，
            // 否则图变成无输出，依赖 bug 的调度 pass 不再触发，导致缩减被错误回滚
            val outputValueIdsBefore = outputsBackup.map { it.valueId }.toSet()
            repairGraphOutputs(graph, removedNodes, repairPlan, outputValueIdsBefore)

            // 更新跨图 inputs
            for (repair in repairPlan.repairs) {
                val newValueId = when (repair.type) {
                    RepairType.WIRE_AROUND -> repair.newValueId
                    RepairType.DEFAULT_VALUE -> "${repair.oldValueId}_default"
                    RepairType.SHAPE_ABSORB -> repair.targetInputValueId
                    RepairType.CONSTANT_TO_INPUT -> repair.newInputValueId
                }
                if (newValueId != null) {
                    for (otherGraph in program.graphs) {
                        if (otherGraph === graph) continue
                        for (input in otherGraph.inputs) {
                            if (input.valueId == repair.oldValueId) {
                                // 若 newValueId 已存在于该图 inputs（例如新值与另一个跨图输入同名），
                                // 删除旧条目避免 forward 参数重复；否则直接改 valueId
                                if (otherGraph.inputs.any { it.valueId == newValueId }) {
                                    otherGraph.inputs.remove(input)
                                } else {
                                    input.valueId = newValueId
                                }
                            }
                        }
                    }
                }
            }

            // 清理 outputs（保留节点产出、graph input 直通、以及跨图引用的值）
            val crossRefs = program.graphs
                .filter { it !== graph }
                .flatMap { it.inputs.map { i -> i.valueId } }
                .toSet()
            val producedByNodes = graph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet()
            graph.outputs.removeAll {
                it.valueId !in producedByNodes
                    && it.valueId !in graph.inputs.map { i -> i.valueId }.toSet()
                    && it.valueId !in crossRefs
            }

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

            // 跨图引用：其他图 outputs 的 valueId（传给 reconstructor，防止对跨图 input 做 SHAPE_ABSORB）
            val copyCrossGraphRefs = copy.graphs
                .filter { it !== copyGraph }
                .flatMap { it.outputs.map { o -> o.valueId } }
                .toSet()
            val reconstructor = DependencyReconstructor(copyGraph, copyCrossGraphRefs)
            val repairPlan = reconstructor.prepare(copyRemovedNodes)
            copyGraph.nodes.removeAll(copyRemovedNodes)
            reconstructor.apply(repairPlan)
            // 测试副本中也清理死代码，保持与实际缩减逻辑一致
            DeadCodeEliminator.eliminateToFixpoint(copyGraph)

            // 提升 graph outputs（与实际缩减路径保持一致）
            val outputValueIdsBefore = copyGraph.outputs.map { it.valueId }.toSet()
            repairGraphOutputs(copyGraph, copyRemovedNodes, repairPlan, outputValueIdsBefore)

            // 跨图引用修复
            for (repair in repairPlan.repairs) {
                val newValueId = when (repair.type) {
                    RepairType.WIRE_AROUND -> repair.newValueId
                    RepairType.DEFAULT_VALUE -> "${repair.oldValueId}_default"
                    RepairType.SHAPE_ABSORB -> repair.targetInputValueId
                    RepairType.CONSTANT_TO_INPUT -> repair.newInputValueId
                }
                if (newValueId != null) {
                    for (otherGraph in copy.graphs) {
                        if (otherGraph === copyGraph) continue
                        for (input in otherGraph.inputs) {
                            if (input.valueId == repair.oldValueId) {
                                // 若 newValueId 已存在，删除旧条目避免重复参数
                                if (otherGraph.inputs.any { it.valueId == newValueId }) {
                                    otherGraph.inputs.remove(input)
                                } else {
                                    input.valueId = newValueId
                                }
                            }
                        }
                    }
                }
            }

            // 清理 outputs（保留跨图引用的 output：被其他图 inputs 引用）
            val crossRefs = copy.graphs
                .filter { it !== copyGraph }
                .flatMap { it.inputs.map { i -> i.valueId } }
                .toSet()
            val producedByNodes = copyGraph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet() +
                copyGraph.inputs.map { it.valueId }.toSet()
            copyGraph.outputs.removeAll { it.valueId !in producedByNodes && it.valueId !in crossRefs }

            if (!validateGraph(copyGraph)) {
                log.warn { "testSubset: validateGraph 失败 (graph=${copyGraph.name}, 尝试删除 ${copyRemovedNodes.size} 节点)" }
                return false
            }
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

    private fun cleanupInputsOutputs(graph: UirGraph, crossGraphRefs: Set<String> = emptySet()) {
        val usedValueIds = graph.nodes.flatMap { it.inputs.map { i -> i.valueId } }.toSet()
        // 保留：被节点消费的 input，以及被其他图 outputs 引用的跨图 input（多图链式接口）
        graph.inputs.removeAll { it.valueId !in usedValueIds && it.valueId !in crossGraphRefs }
        val producedByNodes = graph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet()
        val graphInputIds = graph.inputs.map { it.valueId }.toSet()
        // 保留：被节点产生的 output、graph input 直通 output，以及被其他图 inputs 引用的跨图 output（下游图接口依赖）。
        // 后两者即使本图内部无 producer 也必须保留，否则下游图 input 断链 → 参数错乱 → shape 越界。
        val crossDownstreamRefs = program.graphs
            .filter { it !== graph }
            .flatMap { it.inputs.map { i -> i.valueId } }
            .toSet()
        graph.outputs.removeAll { it.valueId !in producedByNodes && it.valueId !in graphInputIds && it.valueId !in crossDownstreamRefs }
    }

    /**
     * 修复图输出：删除产出 graph output 的节点后，将其替代值提升为新的 graph output。
     * 否则图变成无输出，依赖 bug 的调度 pass 不再触发，导致缩减被错误回滚。
     *
     * 替代值来源优先级：
     *   1. WIRE_AROUND 修复 → 输入源（newValueId）
     *   2. DEFAULT_VALUE 修复 → FULL 节点输出（${oldValueId}_default）
     *   3. 无修复（graph output 无消费者）→ 递归向上查找幸存 producer 或 graph input
     */
    private fun repairGraphOutputs(
        graph: UirGraph,
        removedNodes: Set<UirNode>,
        repairPlan: DependencyRepairPlan,
        outputValueIdsBefore: Set<String>,
    ) {
        val repairByOldValue = repairPlan.repairs.groupBy { it.oldValueId }
        // producerMap 必须包含被删节点，findSurvivingSource 才能沿被删链向上递归
        val producerMap = (graph.nodes + removedNodes).flatMap { n ->
            n.outputs.map { o -> o.valueId to n }
        }.toMap()
        for (removedNode in removedNodes) {
            for (out in removedNode.outputs) {
                if (out.valueId !in outputValueIdsBefore) continue
                val repairs = repairByOldValue[out.valueId].orEmpty()
                val replacement: Pair<String, io.github.xyzboom.aiFuzzer.ir.types.UirTensorType>? = when {
                    repairs.any { it.type == RepairType.WIRE_AROUND } -> {
                        val r = repairs.first { it.type == RepairType.WIRE_AROUND }
                        val newId = r.newValueId
                        if (newId == null) null else (newId to (r.newType ?: out.type))
                    }
                    repairs.any { it.type == RepairType.DEFAULT_VALUE } -> {
                        val r = repairs.first { it.type == RepairType.DEFAULT_VALUE }
                        "${r.oldValueId}_default" to (r.oldType ?: out.type)
                    }
                    repairs.any { it.type == RepairType.CONSTANT_TO_INPUT } -> {
                        val r = repairs.first { it.type == RepairType.CONSTANT_TO_INPUT }
                        val newId = r.newInputValueId
                        if (newId == null) null else (newId to (r.oldType ?: out.type))
                    }
                    else -> findSurvivingSource(graph, producerMap, removedNodes, removedNode)
                }
                if (replacement != null) {
                    val (newId, newType) = replacement
                    if (graph.outputs.none { it.valueId == newId }) {
                        graph.outputs.add(buildValueRef { valueId = newId; type = newType })
                    }
                }
            }
        }
    }

    /**
     * 从被删节点沿输入链递归向上，找到第一个幸存 producer 的输出，或 graph input。
     * 用于被删节点链整体移除时（如 CONV2D→MAX_POOL2D→TANH→output 全删），
     * 把最上游幸存值提升为 graph output。
     */
    private fun findSurvivingSource(
        graph: UirGraph,
        producerMap: Map<String, UirNode>,
        removedNodes: Set<UirNode>,
        node: UirNode,
    ): Pair<String, io.github.xyzboom.aiFuzzer.ir.types.UirTensorType>? {
        if (node.inputs.isEmpty()) return null
        val sourceRef = node.inputs[0]
        val producer = producerMap[sourceRef.valueId]
        return when {
            producer == null -> {
                // 无 producer → 是 graph input
                if (sourceRef.valueId in graph.inputs.map { it.valueId }) {
                    sourceRef.valueId to sourceRef.type
                } else null
            }
            producer !in removedNodes -> {
                // 幸存 producer → 用它的输出
                producer.outputs.firstOrNull()?.let {
                    it.valueId to it.type
                }
            }
            else -> {
                // producer 也被删 → 递归向上
                findSurvivingSource(graph, producerMap, removedNodes, producer)
            }
        }
    }
}
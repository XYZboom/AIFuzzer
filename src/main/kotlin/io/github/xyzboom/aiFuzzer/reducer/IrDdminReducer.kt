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
 * DDMin 候选类型：可以是图内节点，也可以是 graph output 引用。
 * 把算子本身和算子的输出解耦，允许 DDMin 独立决定删算子或删输出引用。
 */
sealed class DDMinCandidate {
    data class Node(val node: UirNode) : DDMinCandidate()
    data class GraphOutputRef(val graph: UirGraph, val valueId: String) : DDMinCandidate()
}

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
        val crossGraphRefs = program.graphs
            .filter { it !== graph }
            .flatMap { it.outputs.map { o -> o.valueId } }
            .toSet()
        cleanupInputsOutputs(graph, crossGraphRefs)
        val allNodes = graph.nodes.toList()
        if (allNodes.size <= 1) {
            return propertyChecker.check(program)
        }

        // 构建混合候选集：节点 + graph output 引用
        val crossDownstreamRefs = program.graphs
            .filter { it !== graph }
            .flatMap { it.inputs.map { i -> i.valueId } }
            .toSet()
        val producedByNodes = graph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet()
        val allOutputCandidates = graph.outputs
            .map { it.valueId }
            .filter { it !in crossDownstreamRefs && it in producedByNodes }
            .map { DDMinCandidate.GraphOutputRef(graph, it) }
        val allCandidates = allNodes.map { DDMinCandidate.Node(it) } + allOutputCandidates

        var bestSubset: Set<DDMinCandidate>? = null

        val ddmin = DDMin<DDMinCandidate> { candidates ->
            if (candidates.toSet() == allCandidates.toSet()) return@DDMin true
            val testResult = testSubset(graph, allNodes, allOutputCandidates, candidates)
            if (testResult) {
                bestSubset = candidates.toSet()
            }
            testResult
        }

        ddmin.execute(allCandidates)

        // 从 bestSubset 中分离保留的节点和输出引用
        val keptNodes = bestSubset
            ?.filterIsInstance<DDMinCandidate.Node>()
            ?.map { it.node }
            ?.toSet() ?: allNodes.toSet()
        val keptOutputIds = bestSubset
            ?.filterIsInstance<DDMinCandidate.GraphOutputRef>()
            ?.map { it.valueId }
            ?.toSet() ?: allOutputCandidates.map { it.valueId }.toSet()

        val removedNodes = allNodes.filter { it !in keptNodes }.toSet()
        val removedOutputIds = allOutputCandidates.map { it.valueId }.toSet() - keptOutputIds

        if (removedNodes.isNotEmpty() || removedOutputIds.isNotEmpty()) {
            val snapshots = graph.nodes.map {
                InputSnapshot(it, it.inputs.map { ref -> buildValueRef { valueId = ref.valueId; type = ref.type } })
            }
            val nodesBackup = graph.nodes.toList()
            val inputsBackup = graph.inputs.map { buildValueRef { valueId = it.valueId; type = it.type } }.toMutableList()
            val outputsBackup = graph.outputs.map { buildValueRef { valueId = it.valueId; type = it.type } }.toMutableList()

            // 删输出引用
            graph.outputs.removeAll { it.valueId in removedOutputIds }

            // 删节点
            if (removedNodes.isNotEmpty()) {
                val reconstructor = DependencyReconstructor(graph, crossGraphRefs)
                val repairPlan = reconstructor.prepare(removedNodes)
                graph.nodes.removeAll(removedNodes)
                reconstructor.apply(repairPlan)

                // 必须在 DeadCodeEliminator 之前调用 repairGraphOutputs：
                // 新创建的 FULL 节点（DEFAULT_VALUE 修复）如果还没有被 graph output 引用，
                // 会被 DeadCodeEliminator 当作死代码删除，导致后续翻译产生 NameError。
                val outputValueIdsBefore = outputsBackup.map { it.valueId }.toSet() - removedOutputIds
                repairGraphOutputs(graph, removedNodes, repairPlan, outputValueIdsBefore)
                DeadCodeEliminator.eliminateToFixpoint(graph)

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
                                    if (otherGraph.inputs.any { it.valueId == newValueId }) {
                                        otherGraph.inputs.remove(input)
                                    } else {
                                        input.valueId = newValueId
                                    }
                                }
                            }
                            // 同时更新下游图的 outputs——如果该图的 return 直通引用了被重命名的 valueId，
                            // 不改则翻译后代码中变量名不匹配，产生 NameError。
                            for (output in otherGraph.outputs) {
                                if (output.valueId == repair.oldValueId) {
                                    output.valueId = newValueId
                                }
                            }
                        }
                    }
                }
            }

            val crossRefs = program.graphs
                .filter { it !== graph }
                .flatMap { it.inputs.map { i -> i.valueId } }
                .toSet()
            val producedRemaining = graph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet()
            graph.outputs.removeAll {
                it.valueId !in producedRemaining
                    && it.valueId !in graph.inputs.map { i -> i.valueId }.toSet()
                    && it.valueId !in crossRefs
            }

            if (validateGraph(graph) && propertyChecker.check(program)) {
                val removedNodeCount = removedNodes.size
                val desc = if (removedNodes.isNotEmpty() && removedOutputIds.isNotEmpty()) {
                    "DDMin 缩减：移除 $removedNodeCount 个节点 + ${removedOutputIds.size} 个输出引用 → ${graph.nodes.size} 总节点"
                } else if (removedNodes.isNotEmpty()) {
                    "DDMin 缩减：移除 $removedNodeCount 个原始节点 → ${graph.nodes.size} 总节点"
                } else {
                    "DDMin 缩减：移除 ${removedOutputIds.size} 个 graph output 引用"
                }
                steps.add(ReductionStep(
                    type = StepType.DDMIN_REMOVE,
                    description = desc,
                    removedNodes = removedNodes.map { "${it.op}" },
                    remainingNodeCount = graph.nodes.size,
                ))
                log.info { "DDMin 缩减成功: ${allNodes.size} → ${graph.nodes.size} 节点 (移除 $removedNodeCount 个节点, ${removedOutputIds.size} 个输出引用)" }
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

    private fun testSubset(
        graph: UirGraph,
        allNodes: List<UirNode>,
        allOutputCandidates: List<DDMinCandidate.GraphOutputRef>,
        candidates: List<DDMinCandidate>,
    ): Boolean {
        val keptNodes = candidates.filterIsInstance<DDMinCandidate.Node>().map { it.node }.toSet()
        val keptOutputIds = candidates.filterIsInstance<DDMinCandidate.GraphOutputRef>().map { it.valueId }.toSet()
        val removedNodes = allNodes.filter { it !in keptNodes }.toSet()
        val removedOutputIds = allOutputCandidates.map { it.valueId }.toSet() - keptOutputIds
        if (removedNodes.isEmpty() && removedOutputIds.isEmpty()) return true
        val removedOps = removedNodes.map { it.op.name }.sorted()
        val removedNames = removedNodes.map { it.name }.sorted()
        return try {
            val jsonl = UirSerializer.toJsonl(program)
            val copy = UirSerializer.fromJsonl(jsonl)
            val copyGraph = copy.graphs.firstOrNull { it.name == graph.name } ?: return false

            // 删输出引用
            copyGraph.outputs.removeAll { it.valueId in removedOutputIds }

            // 删节点
            if (removedNodes.isNotEmpty()) {
                val copyRemovedNodes = copyGraph.nodes.filter { node ->
                    removedNodes.any { it.name == node.name && it.op == node.op }
                }.toSet()
                if (copyRemovedNodes.isEmpty()) return false

                val copyCrossGraphRefs = copy.graphs
                    .filter { it !== copyGraph }
                    .flatMap { it.outputs.map { o -> o.valueId } }
                    .toSet()
                val reconstructor = DependencyReconstructor(copyGraph, copyCrossGraphRefs)
                val repairPlan = reconstructor.prepare(copyRemovedNodes)
                copyGraph.nodes.removeAll(copyRemovedNodes)
                reconstructor.apply(repairPlan)
                // 必须在 DeadCodeEliminator 之前调用 repairGraphOutputs，
                // 否则新创建的 FULL 节点被死代码消除误删。
                val outputValueIdsBefore = copyGraph.outputs.map { it.valueId }.toSet()
                repairGraphOutputs(copyGraph, copyRemovedNodes, repairPlan, outputValueIdsBefore)
                DeadCodeEliminator.eliminateToFixpoint(copyGraph)

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
                                    if (otherGraph.inputs.any { it.valueId == newValueId }) {
                                        otherGraph.inputs.remove(input)
                                    } else {
                                        input.valueId = newValueId
                                    }
                                }
                            }
                            // 同时更新下游图的 outputs——直通引用的 valueId 重命名。
                            for (output in otherGraph.outputs) {
                                if (output.valueId == repair.oldValueId) {
                                    output.valueId = newValueId
                                }
                            }
                        }
                    }
                }
            }

            val crossRefs = copy.graphs
                .filter { it !== copyGraph }
                .flatMap { it.inputs.map { i -> i.valueId } }
                .toSet()
            val producedByNodes = copyGraph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet() +
                copyGraph.inputs.map { it.valueId }.toSet()
            copyGraph.outputs.removeAll { it.valueId !in producedByNodes && it.valueId !in crossRefs }

            if (!validateGraph(copyGraph)) {
                log.warn { "DDMIN_REJECT: validateGraph 失败 (graph=${copyGraph.name}, 尝试删除 ${removedNodes.size} 节点[$removedOps], 输出[$removedOutputIds])" }
                return false
            }
            val checkResult = try { 
                val pcResult = propertyChecker.check(copy)
                // 记录详细结果——但无法直接获取 success/matched 因为 PropertyChecker 接口只返回 boolean
                pcResult
            } catch (e: Exception) { false }
            if (!checkResult) {
                log.warn { "DDMIN_REJECT: 属性检查失败 (graph=${copyGraph.name}, 节点[$removedOps], 输出[$removedOutputIds], 剩余节点=${copyGraph.nodes.size})" }
            } else {
                log.info { "DDMIN_ACCEPT: 属性保持 (graph=${copyGraph.name}, 节点[$removedOps], 输出[$removedOutputIds], 剩余节点=${copyGraph.nodes.size})" }
            }
            checkResult
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
        // 被下游图 inputs 引用的 valueId——即使本图内部无消费者也必须保留（跨图接口依赖）
        val crossDownstreamRefs = program.graphs
            .filter { it !== graph }
            .flatMap { it.inputs.map { i -> i.valueId } }
            .toSet()
        val usedValueIds = graph.nodes.flatMap { it.inputs.map { i -> i.valueId } }.toSet()
        // 保留：被节点消费的 input、被其他图 outputs 引用的跨图 input、被下游图 inputs 引用的跨图 input
        graph.inputs.removeAll { it.valueId !in usedValueIds && it.valueId !in crossGraphRefs && it.valueId !in crossDownstreamRefs }
        val producedByNodes = graph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet()
        val graphInputIds = graph.inputs.map { it.valueId }.toSet()
        // 保留：被节点产生的 output、graph input 直通 output、被下游图 inputs 引用的跨图 output
        graph.outputs.removeAll { it.valueId !in producedByNodes && it.valueId !in graphInputIds && it.valueId !in crossDownstreamRefs }
    }

    /**
     * 修复图输出：删除产出 graph output 的节点后，将其替代值提升为新的 graph output。
     * 否则图变成无输出，依赖 bug 的调度 pass 不再触发，导致缩减被错误回滚。
     *
     * 替代值来源优先级：
     *   1. WIRE_AROUND 修复 → 输入源（newValueId）
     *   2. DEFAULT_VALUE 修复 → FULL 节点输出（${oldValueId}_default）
     *   3. CONSTANT_TO_INPUT 修复 → 新 graph input 的 valueId
     *   4. 无修复（graph output 无消费者）→ 递归向上查找所有输入的幸存 producer 或 graph input
     */
    private fun repairGraphOutputs(
        graph: UirGraph,
        removedNodes: Set<UirNode>,
        repairPlan: DependencyRepairPlan,
        outputValueIdsBefore: Set<String>,
    ) {
        val repairByOldValue = repairPlan.repairs.groupBy { it.oldValueId }
        // producerMap 必须包含被删节点，findSurvivingSources 才能沿被删链向上递归
        val producerMap = (graph.nodes + removedNodes).flatMap { n ->
            n.outputs.map { o -> o.valueId to n }
        }.toMap()
        for (removedNode in removedNodes) {
            for (out in removedNode.outputs) {
                if (out.valueId !in outputValueIdsBefore) continue
                val repairs = repairByOldValue[out.valueId].orEmpty()
                val replacements: List<Pair<String, io.github.xyzboom.aiFuzzer.ir.types.UirTensorType>> = when {
                    repairs.any { it.type == RepairType.WIRE_AROUND } -> {
                        val r = repairs.first { it.type == RepairType.WIRE_AROUND }
                        val newId = r.newValueId
                        if (newId == null) emptyList() else listOf(newId to (r.newType ?: out.type))
                    }
                    repairs.any { it.type == RepairType.DEFAULT_VALUE } -> {
                        val r = repairs.first { it.type == RepairType.DEFAULT_VALUE }
                        listOf("${r.oldValueId}_default" to (r.oldType ?: out.type))
                    }
                    repairs.any { it.type == RepairType.CONSTANT_TO_INPUT } -> {
                        val r = repairs.first { it.type == RepairType.CONSTANT_TO_INPUT }
                        val newId = r.newInputValueId
                        if (newId == null) emptyList() else listOf(newId to (r.oldType ?: out.type))
                    }
                    else -> findSurvivingSources(graph, producerMap, removedNodes, removedNode)
                }
                for ((newId, newType) in replacements) {
                    if (graph.outputs.none { it.valueId == newId }) {
                        graph.outputs.add(buildValueRef { valueId = newId; type = newType })
                    }
                }
            }
        }
    }

    /**
     * 从被删节点沿所有输入链递归向上，找到所有幸存 producer 的输出或 graph input。
     * 用于被删节点链整体移除时（如 CONV2D→MAX_POOL2D→TANH→output 全删），
     * 把所有最上游幸存值提升为 graph output。
     * 修复：之前只取 node.inputs[0] 漏掉了后续输入（如 mul 有 2 个输入），
     * 导致依赖修复不完整，删 mul 后只提升了一个输入。
     */
    private fun findSurvivingSources(
        graph: UirGraph,
        producerMap: Map<String, UirNode>,
        removedNodes: Set<UirNode>,
        node: UirNode,
    ): List<Pair<String, io.github.xyzboom.aiFuzzer.ir.types.UirTensorType>> {
        val results = mutableListOf<Pair<String, io.github.xyzboom.aiFuzzer.ir.types.UirTensorType>>()
        for (inputRef in node.inputs) {
            val producer = producerMap[inputRef.valueId]
            when {
                producer == null -> {
                    // 无 producer → 是 graph input
                    if (inputRef.valueId in graph.inputs.map { it.valueId }) {
                        results.add(inputRef.valueId to inputRef.type)
                    }
                }
                producer !in removedNodes -> {
                    // 幸存 producer → 用它的输出
                    producer.outputs.firstOrNull()?.let {
                        results.add(it.valueId to it.type)
                    }
                }
                else -> {
                    // producer 也被删 → 递归向上
                    results.addAll(findSurvivingSources(graph, producerMap, removedNodes, producer))
                }
            }
        }
        return results
    }
}
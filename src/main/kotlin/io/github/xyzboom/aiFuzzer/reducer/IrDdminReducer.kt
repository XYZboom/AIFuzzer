package io.github.xyzboom.aiFuzzer.reducer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer

private val log = KotlinLogging.logger {}

/**
 * IR 级 DDMin 缩减器。
 *
 * 核心流程：
 * 1. 初始 DCE（零风险）
 * 2. DDMin：决定哪些原始节点保留
 *    - 每次测试：深拷贝 → 删除非候选节点 → 依赖重建 → DCE → 验证
 *    - 通过 → 接受这个子集；失败 → 拒绝
 * 3. 最终：在原图上应用缩减结果
 *
 * 关键设计：DDMin 测试在深拷贝上进行，原图只在最终成功时修改一次。
 */
class IrDdminReducer(
    private val propertyChecker: PropertyChecker,
    private val program: UirProgram,
) {
    fun reduceGraph(
        graph: UirGraph,
        steps: MutableList<ReductionStep>,
    ): Boolean {
        // 多轮缩减到不动点
        var totalIterations = 0
        var changed = true
        var finalResult = true

        while (changed && totalIterations < 10) {
            changed = false
            totalIterations++
            val prevNodeCount = graph.nodes.size

            val result = reduceGraphOnce(graph, steps)
            finalResult = result

            if (graph.nodes.size < prevNodeCount) {
                changed = true
                log.info { "缩减第 $totalIterations 轮: ${prevNodeCount} → ${graph.nodes.size} nodes" }
            }
        }

        return finalResult
    }

    private fun reduceGraphOnce(
        graph: UirGraph,
        steps: MutableList<ReductionStep>,
    ): Boolean {
        // Stage 0: 初始 DCE（零风险，直接在原图上做）
        val initialDce = DeadCodeEliminator.eliminateToFixpoint(graph)
        if (initialDce.isNotEmpty()) {
            steps.add(ReductionStep(
                type = StepType.DEAD_CODE_ELIMINATION,
                description = "初始死代码消除：移除 ${initialDce.size} 个不可达节点",
                removedNodes = initialDce.map { "${it.op}" },
                remainingNodeCount = graph.nodes.size,
            ))
            log.info { "初始 DCE 移除 ${initialDce.size} 个节点 → ${graph.nodes.size} 剩余" }
        }

        // DCE 后属性检查
        if (!propertyChecker.check(program)) {
            log.warn { "初始 DCE 后属性丢失，回滚 DCE" }
            graph.nodes.addAll(initialDce)
            return false
        }

        // 清理 inputs/outputs（DCE 后可能有新的不可达输入/输出）
        cleanupInputsOutputs(graph)

        // Stage 1: DDMin 阶段——操作当前 graph 的所有节点
        val allNodes = graph.nodes.toList()
        if (allNodes.size <= 1) {
            return propertyChecker.check(program)
        }

        // 记录最优缩减（保留节点集合）
        var bestSubset: Set<UirNode>? = null

        val ddmin = DDMin<UirNode> { candidateNodes ->
            if (candidateNodes.toSet() == allNodes.toSet()) return@DDMin true
            val removedNodes = allNodes.filter { it !in candidateNodes }.toSet()
            if (removedNodes.isEmpty()) return@DDMin true

            // 在深拷贝上测试
            val testResult = testSubset(graph, removedNodes)
            if (testResult) {
                bestSubset = candidateNodes.toSet()
                log.debug { "DDMin 子集通过: ${allNodes.size} → ${candidateNodes.size} nodes (removed: ${removedNodes.map { it.name }})" }
            }
            testResult
        }

        // DDMin 确定性自检：对 bestSubset 做二次验证
        if (bestSubset != null && bestSubset!!.size < allNodes.size) {
            val removedNodes = allNodes.filter { it !in bestSubset!! }.toSet()
            val retest = testSubset(graph, removedNodes)
            if (!retest) {
                log.warn { "DDMin 确定性失败！bestSubset 二次验证不通过，放弃缩减" }
                return false
            }
        }

        ddmin.execute(allNodes)

        // 应用最优缩减结果到原图
        if (bestSubset != null && bestSubset!!.size < allNodes.size) {
            val removedNodes = allNodes.filter { it !in bestSubset!! }.toSet()
            val snapshots = graph.nodes.map {
                InputSnapshot(it, it.inputs.map { ref -> buildValueRef { valueId = ref.valueId; type = ref.type } })
            }
            val nodesBackup = graph.nodes.toList()
            val inputsBackup = graph.inputs.map { buildValueRef { valueId = it.valueId; type = it.type } }.toMutableList()
            val outputsBackup = graph.outputs.map { buildValueRef { valueId = it.valueId; type = it.type } }.toMutableList()

            // 准备依赖重建
            val reconstructor = DependencyReconstructor(graph)
            val repairPlan = reconstructor.prepare(removedNodes)

            // 删除节点
            graph.nodes.removeAll(removedNodes)

            // 执行依赖重建
            reconstructor.apply(repairPlan)

            // 死代码消除
            DeadCodeEliminator.eliminateToFixpoint(graph)

            // 清理 graph.inputs：删除不被任何保留节点使用的输入
            val usedValueIds = graph.nodes.flatMap { it.inputs.map { i -> i.valueId } }.toSet()
            graph.inputs.removeAll { it.valueId !in usedValueIds }

            // 清理 graph.outputs：删除不被任何保留节点产出的输出
            val producedValueIds = graph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet() +
                graph.inputs.map { it.valueId }.toSet()
            graph.outputs.removeAll { it.valueId !in producedValueIds }

            // 最终验证
            if (validateGraph(graph) && propertyChecker.check(program)) {
                val removedCount = allNodes.size - bestSubset!!.size
                steps.add(ReductionStep(
                    type = StepType.DDMIN_REMOVE,
                    description = "DDMin 缩减：移除 $removedCount 个原始节点 → ${graph.nodes.size} 总节点",
                    removedNodes = removedNodes.map { "${it.op}" },
                    remainingNodeCount = graph.nodes.size,
                ))
                log.info { "DDMin reduced ${allNodes.size} → ${graph.nodes.size} nodes (含 ZEROS 替代)" }
                return true
            } else {
                // 最终验证失败，回滚
                rollback(graph, nodesBackup, snapshots)
                graph.inputs.clear()
                graph.inputs.addAll(inputsBackup)
                graph.outputs.clear()
                graph.outputs.addAll(outputsBackup)
                log.warn { "DDMin 最终验证失败，已回滚" }
                return false
            }
        }

        return propertyChecker.check(program)
    }

    /**
     * 在深拷贝上测试一个子集是否保持属性。
     */
    private fun testSubset(graph: UirGraph, removedNodes: Set<UirNode>): Boolean {
        return try {
            // 深拷贝：序列化→反序列化
            val jsonl = UirSerializer.toJsonl(program)
            val copy = UirSerializer.fromJsonl(jsonl)
            val copyGraph = copy.graphs.firstOrNull() ?: return false

            // 在副本上找对应节点
            val copyRemovedNodes = copyGraph.nodes.filter { node ->
                removedNodes.any { it.name == node.name && it.op == node.op }
            }.toSet()

            // 准备依赖重建
            val reconstructor = DependencyReconstructor(copyGraph)
            val repairPlan = reconstructor.prepare(copyRemovedNodes)

            // 删除节点
            copyGraph.nodes.removeAll(copyRemovedNodes)

            // 执行依赖重建
            reconstructor.apply(repairPlan)

            // 死代码消除
            DeadCodeEliminator.eliminateToFixpoint(copyGraph)

            // 清理 inputs/outputs
            val copyUsedValueIds = copyGraph.nodes.flatMap { it.inputs.map { i -> i.valueId } }.toSet()
            copyGraph.inputs.removeAll { it.valueId !in copyUsedValueIds }
            val copyProducedValueIds = copyGraph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet() +
                copyGraph.inputs.map { it.valueId }.toSet()
            copyGraph.outputs.removeAll { it.valueId !in copyProducedValueIds }

            // 验证合法性
            if (!validateGraph(copyGraph)) {
                log.debug { "深拷贝测试: 中间程序不合法" }
                return false
            }

            // 验证属性
            propertyChecker.check(copy)
        } catch (e: Exception) {
            log.debug { "深拷贝测试异常: ${e.message}" }
            false
        }
    }

    /**
     * 验证图的合法性。
     */
    private fun validateGraph(graph: UirGraph): Boolean {
        val allOutputValueIds = graph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet() +
            graph.inputs.map { it.valueId }.toSet()

        for (node in graph.nodes) {
            for (input in node.inputs) {
                if (input.valueId !in allOutputValueIds) {
                    return false
                }
            }
        }
        return true
    }

    private data class InputSnapshot(
        val node: UirNode,
        val originalInputs: List<UirValueRef>,
    )

    private fun rollback(graph: UirGraph, backup: List<UirNode>, snapshots: List<InputSnapshot>) {
        graph.nodes.clear()
        graph.nodes.addAll(backup)
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
        val producedValueIds = graph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet() +
            graph.inputs.map { it.valueId }.toSet()
        graph.outputs.removeAll { it.valueId !in producedValueIds }
    }
}
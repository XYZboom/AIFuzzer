package io.github.xyzboom.aiFuzzer.reducer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.UirDimKind
import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer
import io.github.xyzboom.aiFuzzer.ir.types.UirIntAttr
import io.github.xyzboom.aiFuzzer.ir.types.UirStringAttr
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildStringAttr

private val log = KotlinLogging.logger {}

/**
 * DDMin 候选类型：可以是图内节点，也可以是 graph output 引用。
 * 把算子本身和算子的输出解耦，允许 DDMin 独立决定删算子或删输出引用。
 */
sealed class DDMinCandidate {
    data class Node(val node: UirNode) : DDMinCandidate()
    data class GraphOutputRef(val graph: UirGraph, val valueId: String) : DDMinCandidate()
    /** 跨图 input 边界。删除 = 把上游图合并到本图（消除图边界） */
    data class GraphInputRef(val graph: UirGraph, val valueId: String) : DDMinCandidate()
}

/**
 * 图内 DDMin 缩减器。
 * 每个图各自做节点级 DDMin，不处理跨图。
 */
class IrDdminReducer(
    private val propertyChecker: PropertyChecker,
    private val program: UirProgram,
    /** 翻译器：UirProgram → Python 源码。用于第二层缓存（不同 IR 结构可能生成相同源码） */
    private val translator: ((UirProgram) -> String)? = null,
) {
    /**
     * 两层缩减结果缓存，参考 CrossLangFuzzer MinimizeRunner2 的 groupCache/stringCache。
     *
     * 第一层（programCache）：序列化 IR（JSONL）。不同候选子集可能产生完全相同的修复后 IR，
     * 直接命中跳过 daemon 执行。
     *
     * 第二层（sourceCache）：翻译后的 Python 源码。不同 IR 结构可能生成相同的 Python 代码
     *（如 FULL 节点 fill_value 不同但其他相同，或节点顺序不同但语义等价），
     * 命中后跳过 daemon 执行。
     *
     * 两层串行检查：先查第一层（快），未命中才查第二层（需翻译），都未命中才执行 daemon。
     */
    private val programCache = mutableMapOf<String, Boolean>()
    private val sourceCache = mutableMapOf<String, Boolean>()

    /** 检查缓存；命中则直接返回，未命中则运行 propertyChecker 并缓存到两层 */
    private fun checkCached(copy: UirProgram): Boolean {
        val jsonlKey = try {
            UirSerializer.toJsonl(copy)
        } catch (e: Exception) {
            null
        }
        // 第一层：JSONL 缓存
        if (jsonlKey != null) {
            val cached = programCache[jsonlKey]
            if (cached != null) return cached
        }
        // 第二层：源码缓存（不同 IR 可能生成相同源码）
        if (translator != null && jsonlKey != null) {
            val source = try {
                translator(copy)
            } catch (e: Exception) {
                null
            }
            if (source != null) {
                val cached = sourceCache[source]
                if (cached != null) {
                    // 把结果也缓存到第一层
                    programCache[jsonlKey] = cached
                    return cached
                }
            }
        }
        val result = propertyChecker.check(copy)
        if (jsonlKey != null) {
            programCache[jsonlKey] = result
        }
        if (translator != null && jsonlKey != null) {
            val source = try {
                translator(copy)
            } catch (e: Exception) {
                null
            }
            if (source != null) {
                sourceCache[source] = result
            }
        }
        return result
    }

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
                            // 更新下游图 inputs 声明
                            for (input in otherGraph.inputs) {
                                if (input.valueId == repair.oldValueId) {
                                    if (otherGraph.inputs.any { it.valueId == newValueId }) {
                                        otherGraph.inputs.remove(input)
                                    } else {
                                        input.valueId = newValueId
                                        // 同步更新 type：上游 graph output 的 type 可能已被修复（如 DEFAULT_VALUE 改 shape），
                                        // 下游图 input 的 type 如不更新，GATHER 的 IR shape 还是旧值 → fixAllGatherIndices 无法正确裁剪
                                        input.type = repair.oldType ?: repair.newType ?: input.type
                                    }
                                }
                            }
                            // 更新下游图 outputs 声明（直通引用的 valueId 重命名）
                            for (output in otherGraph.outputs) {
                                if (output.valueId == repair.oldValueId) {
                                    output.valueId = newValueId
                                }
                            }
                            // 更新下游图内部节点的 input 引用
                            // 否则即使 inputs 声明已更新，内部节点引用旧 valueId → NameError
                            for (node in otherGraph.nodes) {
                                for (nodeInput in node.inputs) {
                                    if (nodeInput.valueId == repair.oldValueId) {
                                        nodeInput.valueId = newValueId
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 跨图修复 GATHER indices：删节点可能改变输入 shape（如上游 RESIZE2D 被删）
            // 但 GATHER 的 indices 属性仍为原始值，翻译后 index_select 越界 → IndexError
            log.warn { "fixAllGatherIndices: calling for graph=${graph.name} nodes=${graph.nodes.size}" }
            fixAllGatherIndices(program)

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
                                        // 同步更新 type：上游 graph output 的 type 可能已被修复（如 DEFAULT_VALUE 改 shape），
                                        // 下游图 input 的 type 如不更新，GATHER 的 IR shape 还是旧值 → fixAllGatherIndices 无法正确裁剪
                                        input.type = repair.oldType ?: repair.newType ?: input.type
                                    }
                                }
                            }
                            // 更新下游图 outputs 声明（直通引用的 valueId 重命名）
                            for (output in otherGraph.outputs) {
                                if (output.valueId == repair.oldValueId) {
                                    output.valueId = newValueId
                                }
                            }
                            // 更新下游图内部节点的 input 引用
                            for (node in otherGraph.nodes) {
                                for (nodeInput in node.inputs) {
                                    if (nodeInput.valueId == repair.oldValueId) {
                                        nodeInput.valueId = newValueId
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 跨图修复 GATHER indices（与 reduceGraph 一致）
            fixAllGatherIndices(copy)

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
                val pcResult = checkCached(copy)
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

    /**
     * 程序级节点 DDMin：把所有图的节点合并成统一候选集，在整个 program 上做节点级别缩减。
     * 处理逻辑与 per-graph DDMin 完全一致，只是候选集扩展到所有图。
     * 删除任意图的节点后由依赖重构（每图各自修复）+ 跨图引用更新统一处理。
     */
    fun reduceProgram(steps: MutableList<ReductionStep>): Boolean {
        // node→graph 映射
        val nodeToGraph = mutableMapOf<UirNode, UirGraph>()
        for (graph in program.graphs) {
            for (node in graph.nodes) {
                nodeToGraph[node] = graph
            }
        }
        // 跨图引用集合
        val allGraphInputIds = program.graphs.flatMap { it.inputs.map { i -> i.valueId } }.toSet()
        val allProducedByNodes = program.graphs.flatMap { g ->
            g.nodes.flatMap { n -> n.outputs.map { o -> o.valueId } }
        }.toSet()
        // 候选集
        val allNodes = program.graphs.flatMap { it.nodes.toList() }
        // 所有图的全部输出声明（不管是否被其他图消费）。删除只影响输出声明，不改变图结构。
        val allOutputCandidates = program.graphs.flatMap { g ->
            g.outputs
                .map { it.valueId }
                .map { DDMinCandidate.GraphOutputRef(g, it) }
        }
        // 跨图 input 边界候选：被其他图 outputs 产出的 input 声明。删除 = 图融合（两图变一图）。
        val allGraphInputRefs = program.graphs.flatMap { g ->
            g.inputs
                .map { it.valueId }
                .filter { v -> program.graphs.any { it !== g && it.outputs.any { o -> o.valueId == v } } }
                .map { DDMinCandidate.GraphInputRef(g, it) }
        }
        log.warn { "reduceProgram: 候选集 ${allNodes.size} 节点 + ${allOutputCandidates.size} 输出 + ${allGraphInputRefs.size} 跨图输入边界" }
        if (allNodes.size <= 1) {
            return propertyChecker.check(program)
        }
        // 初始化清理各图
        for (graph in program.graphs) {
            val crossGraphRefs = program.graphs
                .filter { it !== graph }
                .flatMap { it.outputs.map { o -> o.valueId } }
                .toSet()
            cleanupInputsOutputs(graph, crossGraphRefs)
        }

        val allCandidates = allNodes.map { DDMinCandidate.Node(it) } + allOutputCandidates + allGraphInputRefs
        var bestSubset: Set<DDMinCandidate>? = null

        val ddmin = DDMin<DDMinCandidate> { candidates ->
            if (candidates.toSet() == allCandidates.toSet()) return@DDMin true
            val testResult = testProgramSubset(allNodes, allOutputCandidates, allGraphInputRefs, candidates, nodeToGraph)
            if (testResult) bestSubset = candidates.toSet()
            testResult
        }
        ddmin.execute(allCandidates)

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
        val keptInputRefIds = bestSubset
            ?.filterIsInstance<DDMinCandidate.GraphInputRef>()
            ?.map { it.valueId }
            ?.toSet() ?: allGraphInputRefs.map { it.valueId }.toSet()
        val removedInputRefIds = allGraphInputRefs.map { it.valueId }.toSet() - keptInputRefIds
        if (removedNodes.isEmpty() && removedOutputIds.isEmpty() && removedInputRefIds.isEmpty()) return true

        // 备份所有图
        val nodesBackups = mutableMapOf<UirGraph, List<UirNode>>()
        val inputsBackups = mutableMapOf<UirGraph, List<UirValueRef>>()
        val outputsBackups = mutableMapOf<UirGraph, List<UirValueRef>>()
        for (graph in program.graphs) {
            nodesBackups[graph] = graph.nodes.toList()
            inputsBackups[graph] = graph.inputs.map { buildValueRef { valueId = it.valueId; type = it.type } }.toList()
            outputsBackups[graph] = graph.outputs.map { buildValueRef { valueId = it.valueId; type = it.type } }.toList()
        }

        // 删输出引用
        for (graph in program.graphs) {
            graph.outputs.removeAll { it.valueId in removedOutputIds }
        }

        // 图融合：删除跨图 input 边界 = 把上游图合并到本图（与 testProgramSubset 一致）
        if (removedInputRefIds.isNotEmpty()) {
            for (valueId in removedInputRefIds) {
                val upstream = program.graphs.firstOrNull { g -> g.outputs.any { it.valueId == valueId } } ?: continue
                val target = program.graphs.firstOrNull { g -> g.inputs.any { it.valueId == valueId } } ?: continue
                if (upstream === target) continue
                for (node in upstream.nodes) {
                    if (target.nodes.none { it === node }) target.nodes.add(node)
                }
                for (inputRef in upstream.inputs) {
                    if (target.inputs.none { it.valueId == inputRef.valueId }) {
                        val ref = buildValueRef {
                            this.valueId = inputRef.valueId
                            this.type = inputRef.type
                        }
                        target.inputs.add(ref)
                    }
                }
                for (outputRef in upstream.outputs) {
                    if (target.outputs.none { it.valueId == outputRef.valueId }) {
                        val ref = buildValueRef {
                            this.valueId = outputRef.valueId
                            this.type = outputRef.type
                        }
                        target.outputs.add(ref)
                    }
                }
                target.inputs.removeAll { it.valueId == valueId }
                upstream.nodes.clear(); upstream.inputs.clear(); upstream.outputs.clear()
            }
            program.graphs.removeAll { it.nodes.isEmpty() }
        }

        // 按图分组删节点，每图各自修复（与 testSubset 逻辑一致）
        val removedByGraph = removedNodes.groupBy { nodeToGraph[it]!! }
        val allRepairs = mutableListOf<Pair<UirGraph, RepairAction>>()
        for ((graph, gNodes) in removedByGraph) {
            val crossGraphRefs = program.graphs
                .filter { it !== graph }
                .flatMap { it.outputs.map { o -> o.valueId } }
                .toSet()
            val reconstructor = DependencyReconstructor(graph, crossGraphRefs)
            val repairPlan = reconstructor.prepare(gNodes.toSet())
            graph.nodes.removeAll(gNodes.toSet())
            reconstructor.apply(repairPlan)
            val outputValueIdsBefore = (outputsBackups[graph]?.map { it.valueId }?.toSet() ?: emptySet()) - removedOutputIds
            repairGraphOutputs(graph, gNodes.toSet(), repairPlan, outputValueIdsBefore)
            DeadCodeEliminator.eliminateToFixpoint(graph)
            for (repair in repairPlan.repairs) {
                allRepairs.add(graph to repair)
            }
        }

        // 跨图引用更新（与 testSubset 逻辑一致）
        for ((repairGraph, repair) in allRepairs) {
            val newValueId = when (repair.type) {
                RepairType.WIRE_AROUND -> repair.newValueId
                RepairType.DEFAULT_VALUE -> "${repair.oldValueId}_default"
                RepairType.SHAPE_ABSORB -> repair.targetInputValueId
                RepairType.CONSTANT_TO_INPUT -> repair.newInputValueId
            }
            if (newValueId == null) continue
            for (otherGraph in program.graphs) {
                if (otherGraph === repairGraph) continue
                for (input in otherGraph.inputs) {
                    if (input.valueId == repair.oldValueId) {
                        if (otherGraph.inputs.any { it.valueId == newValueId }) {
                            otherGraph.inputs.remove(input)
                        } else {
                            input.valueId = newValueId
                            input.type = repair.oldType ?: repair.newType ?: input.type
                        }
                    }
                }
                for (output in otherGraph.outputs) {
                    if (output.valueId == repair.oldValueId) {
                        output.valueId = newValueId
                    }
                }
                for (node in otherGraph.nodes) {
                    for (nodeInput in node.inputs) {
                        if (nodeInput.valueId == repair.oldValueId) {
                            nodeInput.valueId = newValueId
                        }
                    }
                }
            }
        }

        fixAllGatherIndices(program)

        // 清理各图 outputs（与 testSubset 中 copyGraph.outputs.removeAll 一致）
        for (graph in program.graphs) {
            val crossRefs = program.graphs
                .filter { it !== graph }
                .flatMap { it.inputs.map { i -> i.valueId } }
                .toSet()
            val producedByNodes = graph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet() +
                graph.inputs.map { it.valueId }.toSet()
            graph.outputs.removeAll { it.valueId !in producedByNodes && it.valueId !in crossRefs }
        }
        program.graphs.removeAll { it.nodes.isEmpty() }

        // 验证
        if (!propertyChecker.check(program)) {
            for (graph in program.graphs) {
                nodesBackups[graph]?.let { graph.nodes.clear(); graph.nodes.addAll(it) }
                inputsBackups[graph]?.let { graph.inputs.clear(); graph.inputs.addAll(it) }
                outputsBackups[graph]?.let { graph.outputs.clear(); graph.outputs.addAll(it) }
            }
            return false
        }
        val removedDesc = removedNodes.map { it.op.name }.sorted().joinToString(",")
        steps.add(ReductionStep(
            type = StepType.DDMIN_REMOVE,
            description = "程序级 DDMin: 删 ${removedNodes.size} 节点 + ${removedOutputIds.size} 输出引用 (${removedDesc})",
            removedNodes = removedNodes.map { "${it.op}" },
            remainingNodeCount = program.graphs.sumOf { it.nodes.size },
        ))
        return true
    }

    /** 程序级 DDMin 测试子集，与 testSubset 逻辑一致，扩展到多图 */
    private fun testProgramSubset(
        allNodes: List<UirNode>,
        allOutputCandidates: List<DDMinCandidate.GraphOutputRef>,
        allGraphInputRefs: List<DDMinCandidate.GraphInputRef>,
        candidates: List<DDMinCandidate>,
        nodeToGraph: Map<UirNode, UirGraph>,
    ): Boolean {
        val keptNodes = candidates.filterIsInstance<DDMinCandidate.Node>().map { it.node }.toSet()
        val keptOutputIds = candidates.filterIsInstance<DDMinCandidate.GraphOutputRef>().map { it.valueId }.toSet()
        val keptInputRefIds = candidates.filterIsInstance<DDMinCandidate.GraphInputRef>().map { it.valueId }.toSet()
        val removedNodes = allNodes.filter { it !in keptNodes }.toSet()
        val removedOutputIds = allOutputCandidates.map { it.valueId }.toSet() - keptOutputIds
        val removedInputRefIds = allGraphInputRefs.map { it.valueId }.toSet() - keptInputRefIds
        if (removedNodes.isEmpty() && removedOutputIds.isEmpty() && removedInputRefIds.isEmpty()) return true
        val removedOps = removedNodes.map { it.op.name }.sorted()

        return try {
            val copy = UirSerializer.fromJsonl(UirSerializer.toJsonl(program))
            // 删输出引用（所有图）
            for (graph in copy.graphs) {
                graph.outputs.removeAll { it.valueId in removedOutputIds }
            }
            // 图融合：删除跨图 input 边界 = 把上游图合并到本图
            if (removedInputRefIds.isNotEmpty()) {
                for (valueId in removedInputRefIds) {
                    // 找上游图（产出 valueId 的图）
                    val upstream = copy.graphs.firstOrNull { g -> g.outputs.any { it.valueId == valueId } } ?: continue
                    // 找目标图（消费 valueId 的图）
                    val target = copy.graphs.firstOrNull { g -> g.inputs.any { it.valueId == valueId } } ?: continue
                    if (upstream === target) continue
                    // 整图融合：上游图的所有节点 + inputs + outputs 合并到目标图
                    target.nodes.addAll(upstream.nodes)
                    for (input in upstream.inputs) {
                        if (target.inputs.none { it.valueId == input.valueId }) {
                            target.inputs.add(input)
                        }
                    }
                    for (output in upstream.outputs) {
                        if (target.outputs.none { it.valueId == output.valueId }) {
                            target.outputs.add(output)
                        }
                    }
                    target.inputs.removeAll { it.valueId == valueId }
                    upstream.nodes.clear(); upstream.inputs.clear(); upstream.outputs.clear()
                }
                copy.graphs.removeAll { it.nodes.isEmpty() }
            }
            // 删节点（按图分组）
            if (removedNodes.isNotEmpty()) {
                val removedByGraph = removedNodes.groupBy { nodeToGraph[it]!! }
                val allRepairs = mutableListOf<Pair<UirGraph, RepairAction>>()
                for ((origGraph, gNodes) in removedByGraph) {
                    val copyGraph = copy.graphs.firstOrNull { it.name == origGraph.name } ?: return false
                    val copyRemovedNodes = copyGraph.nodes.filter { node ->
                        gNodes.any { it.name == node.name && it.op == node.op }
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
                    val outputValueIdsBefore = copyGraph.outputs.map { it.valueId }.toSet()
                    repairGraphOutputs(copyGraph, copyRemovedNodes, repairPlan, outputValueIdsBefore)
                    DeadCodeEliminator.eliminateToFixpoint(copyGraph)
                    for (repair in repairPlan.repairs) {
                        allRepairs.add(copyGraph to repair)
                    }
                }
                // 跨图引用更新（与 testSubset 完全一致，但处理所有图的 repair）
                for ((repairGraph, repair) in allRepairs) {
                    val newValueId = when (repair.type) {
                        RepairType.WIRE_AROUND -> repair.newValueId
                        RepairType.DEFAULT_VALUE -> "${repair.oldValueId}_default"
                        RepairType.SHAPE_ABSORB -> repair.targetInputValueId
                        RepairType.CONSTANT_TO_INPUT -> repair.newInputValueId
                    }
                    if (newValueId == null) continue
                    for (otherGraph in copy.graphs) {
                        if (otherGraph === repairGraph) continue
                        for (input in otherGraph.inputs) {
                            if (input.valueId == repair.oldValueId) {
                                if (otherGraph.inputs.any { it.valueId == newValueId }) {
                                    otherGraph.inputs.remove(input)
                                } else {
                                    input.valueId = newValueId
                                    input.type = repair.oldType ?: repair.newType ?: input.type
                                }
                            }
                        }
                        for (output in otherGraph.outputs) {
                            if (output.valueId == repair.oldValueId) {
                                output.valueId = newValueId
                            }
                        }
                        for (node in otherGraph.nodes) {
                            for (nodeInput in node.inputs) {
                                if (nodeInput.valueId == repair.oldValueId) {
                                    nodeInput.valueId = newValueId
                                }
                            }
                        }
                    }
                }
            }
            // 对各图做 output 清理（与 testSubset 一致）
            for (copyGraph in copy.graphs) {
                val crossRefs = copy.graphs
                    .filter { it !== copyGraph }
                    .flatMap { it.inputs.map { i -> i.valueId } }
                    .toSet()
                val producedByNodes = copyGraph.nodes.flatMap { it.outputs.map { o -> o.valueId } }.toSet() +
                    copyGraph.inputs.map { it.valueId }.toSet()
                copyGraph.outputs.removeAll { it.valueId !in producedByNodes && it.valueId !in crossRefs }
            }
            fixAllGatherIndices(copy)
            copy.graphs.removeAll { it.nodes.isEmpty() }
            checkCached(copy)
        } catch (e: Exception) {
            log.debug { "testProgramSubset 异常: ${e.message}" }
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

    /**
     * 跨图修复所有 GATHER 节点的 indices 越界。删节点可能改变输入 shape，
     * 但 GATHER 的 indices 属性是原始值，翻译后 index_select 越界 → IndexError。
     * 缩减器职责：在 IR 层裁剪 indices 保证形状合法，不依赖翻译器兜底。
     */
    private fun fixAllGatherIndices(program: UirProgram) {
        var fixed = 0
        var found = 0
        // 程序级 producer 映射：valueId → (graph, node)，跨图追踪 GATHER 输入的真实 shape。
        // GATHER 在 graph_1 的输入可能来自 graph_0 的输出，本图 producerMap 找不到，
        // 会用 graph_1 声明的 input type——该 type 可能未随上游节点删除更新（残旧大 shape），
        // 导致 indices 不裁剪、运行时越界。跨图追踪到最上游 producer 才能拿到真实 shape。
        val producerMap = mutableMapOf<String, Pair<UirGraph, UirNode>>()
        for (g in program.graphs) {
            for (n in g.nodes) {
                for (o in n.outputs) {
                    producerMap[o.valueId] = g to n
                }
            }
        }
        for (graph in program.graphs) {
            for (node in graph.nodes) {
                if (node.op != UirOpKind.GATHER) continue
                found++
                if (node.inputs.isEmpty()) {
                    log.warn { "fixAllGatherIndices: GATHER ${node.name} in ${graph.name} has no inputs" }
                    continue
                }
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: 0
                // 追踪 GATHER 输入的 producer chain，拿最上游的真实 output shape
                var cursor: Pair<UirGraph, UirNode>? = producerMap[node.inputs[0].valueId]
                var actualShape: io.github.xyzboom.aiFuzzer.ir.types.UirShape? = null
                var visited = 0
                while (cursor != null && visited < 50) {
                    visited++
                    val (producerGraph, producerNode) = cursor
                    val outShape = producerNode.outputs.firstOrNull()?.type?.shape
                    if (outShape != null) {
                        actualShape = outShape
                        // 如果该 producer 的输出 shape 是完整 CONSTANT，停下（已够精确）
                        if (outShape.dims.all { it.dimKind == UirDimKind.CONSTANT && it.value != null }) break
                    }
                    // 否则沿 producer 的第一个输入继续向上
                    val nextInputId = producerNode.inputs.firstOrNull()?.valueId
                    cursor = nextInputId?.let { producerMap[it] }
                }
                if (actualShape == null) {
                    // 无 producer chain → graph input，用其 declared type
                    actualShape = graph.inputs.firstOrNull { it.valueId == node.inputs[0].valueId }?.type?.shape
                }
                if (actualShape == null) {
                    log.warn { "fixAllGatherIndices: GATHER ${node.name} in ${graph.name} no shape found (skip)" }
                    continue
                }
                val axisDim = actualShape.dims.getOrNull(axis)
                val axisDimValue = axisDim?.value
                if (axisDim?.dimKind != UirDimKind.CONSTANT || axisDimValue == null) {
                    log.warn { "fixAllGatherIndices: GATHER ${node.name} in ${graph.name} axis=$axis shape=[${actualShape.dims.map { "d=${it.dimKind}:${it.value}" }.joinToString(",")}] (skip)" }
                    continue
                }
                val indicesAttr = (node.attributes["indices"] as? UirStringAttr)?.value ?: continue
                val indices = indicesAttr.split(",").map { it.trim().toIntOrNull() ?: 0 }
                val maxValid = axisDimValue - 1
                val clipped = indices.map { minOf(it, maxValid) }
                if (clipped != indices) {
                    log.warn { "fixAllGatherIndices: ${node.name} in ${graph.name} indices ${indices.joinToString(",")} → ${clipped.joinToString(",")} (max=$maxValid)" }
                    node.attributes["indices"] = buildStringAttr {
                        value = clipped.joinToString(",")
                    }
                    fixed++
                }
            }
        }
        if (found > 0) log.warn { "fixAllGatherIndices: found $found GATHER, fixed $fixed in program" }
    }
}
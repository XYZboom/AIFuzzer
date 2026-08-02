package io.github.xyzboom.aiFuzzer.generator

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.config.MutationConfig as ConfigMutationConfig
import io.github.xyzboom.aiFuzzer.infer.ShapeInferer
import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer
import kotlin.random.Random

private val log = KotlinLogging.logger {}

/**
 * UIR 程序变异器。
 *
 * 对已有的 UirProgram 应用随机变异，然后用 ShapeAdapter 修复形状不匹配。
 * 变异后的程序保证合法（形状适配会在 DELETE 和 ATTRIBUTE 变异后自动修复）。
 */
class UirMutator(
    private val config: ConfigMutationConfig = ConfigMutationConfig(),
    private val rng: Random = Random,
    /** 去重匹配器：变异后检查是否触发了已知 bug pattern */
    private val patternMatcher: io.github.xyzboom.aiFuzzer.pattern.PatternMatcher? = null,
) {
    /** 计数器，用于生成唯一 ID（起始值设大避免与生成器计数器冲突） */
    private var valueCounter = 10000
    private var nodeCounter = 10000

    /** 种子池：从原始程序序列化/反序列化实现深拷贝 */
    private val seedPool = mutableListOf<String>()

    /** 是否有种子可用 */
    val hasSeeds: Boolean get() = seedPool.isNotEmpty()

    /** 种子数量 */
    val seedCount: Int get() = seedPool.size

    /** 移除最旧的种子 */
    fun removeOldestSeed() {
        if (seedPool.isNotEmpty()) {
            seedPool.removeFirst()
        }
    }

    /**
     * 添加一个程序到种子池（深拷贝保存）。
     */
    fun addSeed(program: UirProgram) {
        val jsonl = UirSerializer.toJsonl(program)
        seedPool.add(jsonl)
        log.debug { "变异种子池: 添加种子, 当前 ${seedPool.size} 个" }
    }

    /**
     * 从种子池中随机选取一个种子，变异后返回。
     * 如果没有种子或变异被禁用，返回 null。
     *
     * @param seed 随机种子，传入时保证相同 seed 产生相同的变异结果。
     *             不传则使用构造时的 rng（不可复现）。
     */
    fun mutate(seed: Long? = null): UirProgram? {
        if (!config.enabled || seedPool.isEmpty()) return null
        val localRng = if (seed != null) Random(seed) else rng
        if (localRng.nextDouble() > config.rate) return null

        // 随机选一个种子
        val seedJsonl = seedPool[localRng.nextInt(seedPool.size)]
        val original = try {
            UirSerializer.fromJsonl(seedJsonl)
        } catch (e: Exception) {
            log.warn { "变异种子反序列化失败: ${e.message}" }
            return null
        }

        // 深拷贝：重新序列化+反序列化
        val program = try {
            UirSerializer.fromJsonl(UirSerializer.toJsonl(original))
        } catch (e: Exception) {
            log.warn { "变异种子深拷贝失败: ${e.message}" }
            return null
        }

        // 重置计数器（基于当前程序中的最大 ID）
        resetCounters(program)

        // 对每个图应用随机变异
        for ((gIdx, graph) in program.graphs.withIndex()) {
            if (graph.nodes.isEmpty()) continue
            // 随机选择变异次数（1-3 次）
            val numMutations = 1 + localRng.nextInt(minOf(3, config.maxMutations))
            repeat(numMutations) {
                applyRandomMutation(graph, localRng, gIdx, program.graphs)
            }
        }

        // 去重检查：扫描所有节点，如果触发了已知 pattern 就丢弃
        if (patternMatcher != null && isDedupMatch(program)) {
            log.trace { "变异程序命中已知 bug pattern，丢弃" }
            return null
        }

        return program
    }

    /**
     * 重置计数器，基于程序中的最大节点/值 ID。
     */
    private fun resetCounters(program: UirProgram) {
        var maxValueNum = 0
        var maxNodeNum = 0
        for (graph in program.graphs) {
            for (node in graph.nodes) {
                // 从节点名中提取数字（如 "relu_0_abcdefgh" → 0）
                val nodeMatch = Regex("""(\d+)""").find(node.name)
                if (nodeMatch != null) {
                    maxNodeNum = maxOf(maxNodeNum, nodeMatch.value.toInt())
                }
                // 从值 ID 中提取数字（如 "v_0_abcdefgh" → 0）
                for (ref in node.inputs + node.outputs) {
                    val valMatch = Regex("""(\d+)""").find(ref.valueId)
                    if (valMatch != null) {
                        maxValueNum = maxOf(maxValueNum, valMatch.value.toInt())
                    }
                }
            }
            for (ref in graph.inputs + graph.outputs) {
                val valMatch = Regex("""(\d+)""").find(ref.valueId)
                if (valMatch != null) {
                    maxValueNum = maxOf(maxValueNum, valMatch.value.toInt())
                }
            }
        }
        valueCounter = maxValueNum + 1
        nodeCounter = maxNodeNum + 1
    }

    /**
     * 检查程序是否命中已知 bug pattern（去重）。
     * 返回 true = 命中，需要丢弃。
     */
    private fun isDedupMatch(program: UirProgram): Boolean {
        if (patternMatcher == null) return false
        patternMatcher.reset()
        for (graph in program.graphs) {
            for (node in graph.nodes) {
                val resolver: (String) -> UirValueRef? = { vid ->
                    graph.nodes.flatMap { n -> n.inputs + n.outputs }
                        .plus(graph.inputs)
                        .plus(graph.outputs)
                        .find { it.valueId == vid }
                }
                val matched = patternMatcher.onNodeGenerated(node, resolver)
                if (matched != null) {
                    log.debug { "变异去重命中: pattern=${matched.id}, 节点=${node.name}(${node.op})" }
                    return true
                }
            }
        }
        return false
    }

    /**
     * 生成程序：如果启用变异且有种子，随机变异；否则返回 null。
     */
    fun generateOrNull(): UirProgram? {
        return mutate()
    }

    // =========================================================================
    // 变异操作
    // =========================================================================

    private val singleInputOps = listOf(
        UirOpKind.RELU, UirOpKind.LEAKY_RELU, UirOpKind.ELU, UirOpKind.SELU,
        UirOpKind.MISH, UirOpKind.HARDTANH, UirOpKind.SIGMOID, UirOpKind.TANH,
        UirOpKind.GELU, UirOpKind.SILU,
        UirOpKind.ABS, UirOpKind.SIGN, UirOpKind.EXP, UirOpKind.LOG,
        UirOpKind.LOG2, UirOpKind.SQRT, UirOpKind.RSQRT, UirOpKind.RECIPROCAL,
        UirOpKind.NEG, UirOpKind.CEIL, UirOpKind.FLOOR, UirOpKind.ROUND,
        UirOpKind.SOFTMAX, UirOpKind.LOG_SOFTMAX,
        UirOpKind.REDUCE_SUM, UirOpKind.REDUCE_MEAN, UirOpKind.REDUCE_MAX, UirOpKind.REDUCE_MIN,
        UirOpKind.ARGMAX, UirOpKind.ARGMIN,
        UirOpKind.CAST, UirOpKind.TRIL, UirOpKind.TRIU,
    )

    private val binaryInputOps = listOf(
        UirOpKind.ADD, UirOpKind.SUBTRACT, UirOpKind.MULTIPLY, UirOpKind.DIVIDE,
        UirOpKind.MAXIMUM, UirOpKind.MINIMUM, UirOpKind.POWER,
        UirOpKind.MATMUL,
    )

    private val zeroInputOps = listOf(
        UirOpKind.ARANGE, UirOpKind.FULL, UirOpKind.ONES, UirOpKind.ZEROS,
    )

    private data class OpFamily(val ops: List<UirOpKind>)

    /** 算子族：同族算子可以互换（输入输出形状约束类似） */
    private val opFamilies = listOf(
        OpFamily(listOf(UirOpKind.RELU, UirOpKind.LEAKY_RELU, UirOpKind.ELU, UirOpKind.SELU,
            UirOpKind.MISH, UirOpKind.HARDTANH, UirOpKind.SIGMOID, UirOpKind.TANH,
            UirOpKind.GELU, UirOpKind.SILU)),
        OpFamily(listOf(UirOpKind.ABS, UirOpKind.NEG)),
        OpFamily(listOf(UirOpKind.CEIL, UirOpKind.FLOOR, UirOpKind.ROUND)),
        OpFamily(listOf(UirOpKind.EXP, UirOpKind.LOG, UirOpKind.LOG2, UirOpKind.SQRT, UirOpKind.RSQRT, UirOpKind.RECIPROCAL)),
        OpFamily(listOf(UirOpKind.SOFTMAX, UirOpKind.LOG_SOFTMAX)),
        OpFamily(listOf(UirOpKind.REDUCE_SUM, UirOpKind.REDUCE_MEAN, UirOpKind.REDUCE_MAX, UirOpKind.REDUCE_MIN)),
        OpFamily(listOf(UirOpKind.ADD, UirOpKind.SUBTRACT, UirOpKind.MULTIPLY, UirOpKind.DIVIDE, UirOpKind.MAXIMUM, UirOpKind.MINIMUM)),
        OpFamily(listOf(UirOpKind.MAX_POOL2D, UirOpKind.AVG_POOL2D)),
        OpFamily(listOf(UirOpKind.TRIL, UirOpKind.TRIU)),
    )

    /** 当前启用的变异操作列表（根据配置过滤） */
    private val enabledMutationTypes: List<MutationType> by lazy {
        MutationType.entries.filter { type ->
            when (type) {
                MutationType.OP -> config.opMutation
                MutationType.INSERT -> config.insertMutation
                MutationType.DELETE -> config.deleteMutation
                MutationType.ATTRIBUTE -> config.attributeMutation
            }
        }
    }

    private fun applyRandomMutation(graph: UirGraph, localRng: Random, graphIdx: Int = -1, allGraphs: List<UirGraph> = emptyList()) {
        if (graph.nodes.isEmpty()) return
        if (enabledMutationTypes.isEmpty()) return

        val mutationType = enabledMutationTypes[localRng.nextInt(enabledMutationTypes.size)]

        try {
            when (mutationType) {
                MutationType.OP -> mutateOp(graph, localRng)
                MutationType.INSERT -> mutateInsert(graph, localRng, graphIdx, allGraphs)
                MutationType.DELETE -> mutateDelete(graph, localRng, graphIdx, allGraphs)
                MutationType.ATTRIBUTE -> mutateAttribute(graph, localRng, graphIdx, allGraphs)
            }
        } catch (e: Exception) {
            // 变异失败（超界、空图等），静默跳过
            log.trace { "变异跳过: ${e.message}" }
        }
    }

    // ---- OP: 同族算子替换 ----
    private fun mutateOp(graph: UirGraph, localRng: Random) {
        val candidates = graph.nodes.filter { node ->
            opFamilies.any { family ->
                node.op in family.ops && family.ops.any { it != node.op }
            }
        }
        if (candidates.isEmpty()) return

        val node = candidates[localRng.nextInt(candidates.size)]
        val family = opFamilies.first { node.op in it.ops }
        val newOp = family.ops.filter { it != node.op }[localRng.nextInt(family.ops.size - 1)]

        node.op = newOp
        log.trace { "变异 OP: ${node.name}: ${node.op} → $newOp" }

        // OP 变异后形状不变（同族算子），不需要 fixGraphConsistency
    }

    // ---- INSERT: 在已有节点后插入新节点 ----
    private fun mutateInsert(graph: UirGraph, localRng: Random, graphIdx: Int = -1, allGraphs: List<UirGraph> = emptyList()) {
        // 选一个有输出的节点
        val candidates = graph.nodes.filter { it.outputs.isNotEmpty() }
        if (candidates.size < 2) return

        val targetNode = candidates[localRng.nextInt(candidates.size)]
        // 选一个输出
        val outputRef = targetNode.outputs[localRng.nextInt(targetNode.outputs.size)]
        val outputShape = outputRef.type.shape

        // 找所有使用该输出的后续节点
        val consumers = graph.nodes.filter { node ->
            node.inputs.any { it.valueId == outputRef.valueId }
        }
        if (consumers.isEmpty()) return

        // 选一个单输入算子插入
        val newOp = singleInputOps[localRng.nextInt(singleInputOps.size)]
        val newNodeName = "mut_${nodeCounter}_${randomSuffix(localRng)}"

        // 创建新节点的输出
        val newOutputId = "v_mut_${valueCounter}_${randomSuffix(localRng)}"
        val newOutputRef = buildValueRef {
            valueId = newOutputId
            type = buildTensorType {
                typeKind = UirTypeKind.TENSOR
                shape = outputShape
                dtype = outputRef.type.dtype
            }
        }

        // 创建新节点
        val newNode = buildNode {
            name = newNodeName
            op = newOp
            inputs.add(outputRef)
            outputs.add(newOutputRef)
        }

        // 将所有消费者的输入从 outputRef 改为 newOutputRef
        for (consumer in consumers) {
            for ((i, input) in consumer.inputs.withIndex()) {
                if (input.valueId == outputRef.valueId) {
                    consumer.inputs[i] = newOutputRef
                }
            }
        }

        // 在 targetNode 后面插入新节点
        val insertIdx = graph.nodes.indexOf(targetNode) + 1
        graph.nodes.add(insertIdx.coerceAtMost(graph.nodes.size), newNode)

        // INSERT 可能插入形状变化的算子（REDUCE/ARGMAX/ARGMIN），需要修复形状一致性
        fixGraphConsistency(graph, graphIdx, allGraphs)
        valueCounter++
        nodeCounter++
        log.trace { "变异 INSERT: 在 ${targetNode.name} 后插入 $newOp" }
    }

    // ---- DELETE: 删除一个节点，然后修复形状一致性 ----
    private fun mutateDelete(graph: UirGraph, localRng: Random, graphIdx: Int = -1, allGraphs: List<UirGraph> = emptyList()) {
        if (graph.nodes.size < 3) return

        // 找一个不是 graph input/output 也不是常量的节点
        val graphInputIds = graph.inputs.map { it.valueId }.toSet()
        val graphOutputIds = graph.outputs.map { it.valueId }.toSet()
        val candidates = graph.nodes.filter { node ->
            node.op !in zeroInputOps &&
            node.inputs.isNotEmpty() &&
            node.outputs.all { it.valueId !in graphOutputIds }
        }
        if (candidates.isEmpty()) return

        val nodeToDelete = candidates[localRng.nextInt(candidates.size)]

        // 对于每个输出，找到消费者并把输入替换为 nodeToDelete 的输入
        for (outputRef in nodeToDelete.outputs) {
            val consumers = graph.nodes.filter { node ->
                node != nodeToDelete && node.inputs.any { it.valueId == outputRef.valueId }
            }
            for (consumer in consumers) {
                for ((i, input) in consumer.inputs.withIndex()) {
                    if (input.valueId == outputRef.valueId) {
                        // 用 nodeToDelete 的第一个输入替换
                        consumer.inputs[i] = nodeToDelete.inputs[0]
                    }
                }
            }
        }

        graph.nodes.remove(nodeToDelete)
        log.trace { "变异 DELETE: 删除 ${nodeToDelete.name}" }

        // DELETE 后修复形状一致性：消费者拿到的形状可能变了
        fixGraphConsistency(graph, graphIdx, allGraphs)
    }

    // ---- ATTRIBUTE: 修改算子属性，然后修复形状一致性 ----
    private fun mutateAttribute(graph: UirGraph, localRng: Random, graphIdx: Int = -1, allGraphs: List<UirGraph> = emptyList()) {
        // 找有 axis 或 keepdims 属性的节点
        val candidates = graph.nodes.filter { node ->
            node.attributes.containsKey("axis") || node.attributes.containsKey("keepdims")
        }
        if (candidates.isEmpty()) return

        val node = candidates[localRng.nextInt(candidates.size)]

        if (node.attributes.containsKey("axis") && (localRng.nextBoolean() || !node.attributes.containsKey("keepdims"))) {
            // 修改 axis
            val attr = node.attributes["axis"] as? io.github.xyzboom.aiFuzzer.ir.types.UirIntAttr
            if (attr != null) {
                val ndim = node.outputs.firstOrNull()?.type?.shape?.dims?.size ?: return
                val oldAxis = attr.value
                val newAxis = localRng.nextInt(-ndim, ndim)
                if (newAxis != oldAxis) {
                    node.attributes["axis"] = buildIntAttr { value = newAxis }
                    log.trace { "变异 ATTRIBUTE: ${node.name}.axis: $oldAxis → $newAxis" }
                } else {
                    return  // 没有实际变化，不需要修复
                }
            }
        } else if (node.attributes.containsKey("keepdims")) {
            // 修改 keepdims
            val attr = node.attributes["keepdims"] as? io.github.xyzboom.aiFuzzer.ir.types.UirIntAttr
            if (attr != null) {
                val oldVal = attr.value
                val newVal = if (oldVal == 0) 1 else 0
                node.attributes["keepdims"] = buildIntAttr { value = newVal }
                log.trace { "变异 ATTRIBUTE: ${node.name}.keepdims: $oldVal → $newVal" }
            }
        } else {
            return  // 没有实际变化
        }

        // ATTRIBUTE 变异后修复形状一致性：输出形状变了，下游需要适配
        fixGraphConsistency(graph, graphIdx, allGraphs)
    }

    /**
     * 修复整个图的形状一致性。
     *
     * 拓扑序遍历所有节点：
     * 1. 检查每个节点的输入形状是否满足算子约束
     * 2. 如果不满足，调用 ShapeAdapter.adaptInputs() 插入 wrapper 节点修复
     * 3. 重新推导每个节点的输出形状，更新输出 ref 和 valueShapes
     *
     * 然后同步图间串联形状：将当前图输出形状更新到后续图的输入 ref。
     */
    private fun fixGraphConsistency(graph: UirGraph, graphIdx: Int = -1, allGraphs: List<UirGraph> = emptyList()) {
        val valueShapes = mutableMapOf<String, UirShape>()

        // 1. 初始化：图输入的形状
        for (input in graph.inputs) {
            valueShapes[input.valueId] = input.type.shape
        }

        // 2. 拓扑序遍历节点（nodes 列表保持拓扑序）
        var i = 0
        while (i < graph.nodes.size) {
            val node = graph.nodes[i]

            // 单输入算子（常数生成算子无输入，跳过）
            if (node.inputs.isEmpty()) {
                // 常数生成算子：输出形状已经在 ref 中，直接注册
                for (output in node.outputs) {
                    valueShapes[output.valueId] = output.type.shape
                }
                i++
                continue
            }

            // 收集输入形状
            val inputShapes = node.inputs.map { ref ->
                valueShapes[ref.valueId] ?: run {
                    log.warn { "修复形状: 输入 ${ref.valueId} 的形状不存在，使用 ref 中的形状" }
                    ref.type.shape
                }
            }

            // 检查是否满足约束
            if (!ShapeConstraints.isApplicable(node.op, inputShapes)) {
                // 不满足：调用 ShapeAdapter 修复
                val result = ShapeAdapter.adaptInputs(
                    node.op, node.inputs, valueShapes,
                    valueCounter, nodeCounter
                )

                // 更新节点输入
                node.inputs.clear()
                node.inputs.addAll(result.adaptedRefs)

                // 插入 wrapper 节点（在当前节点之前）
                if (result.wrapperNodes.isNotEmpty()) {
                    graph.nodes.addAll(i, result.wrapperNodes)

                    // 手动处理每个 wrapper 节点的形状推导：
                    // 1. wrapper 节点的输出 ref shape 由 ShapeAdapter 设置为目标形状，
                    //    但必须用 ShapeInferer 重新推导，确保与实际语义一致
                    // 2. 更新 valueShapes 供后续节点使用
                    // 3. 不要走主循环（避免再次触发 adaptInputs）
                    for (wrapperNode in result.wrapperNodes) {
                        val wrapperInputShapes = wrapperNode.inputs.map {
                            valueShapes[it.valueId] ?: it.type.shape
                        }
                        val wrapperOutputShapes = try {
                            ShapeInferer.inferShape(
                                wrapperNode.op, wrapperInputShapes, wrapperNode.attributes
                            )
                        } catch (e: Exception) {
                            log.warn { "wrapper 形状推导失败: ${wrapperNode.name}: ${e.message}，使用原有形状" }
                            wrapperNode.outputs.map { it.type.shape }
                        }
                        if (wrapperOutputShapes.size == wrapperNode.outputs.size) {
                            for ((output, shape) in wrapperNode.outputs.zip(wrapperOutputShapes)) {
                                output.type.shape = shape
                                valueShapes[output.valueId] = shape
                            }
                        } else {
                            for (output in wrapperNode.outputs) {
                                valueShapes[output.valueId] = output.type.shape
                            }
                        }
                    }

                    valueCounter += result.wrapperNodes.size
                    nodeCounter += result.wrapperNodes.size
                    // 跳过已处理的 wrapper 节点
                    i += result.wrapperNodes.size
                }
            }

            // 重新推导输出形状（使用适配后的输入形状）
            val adaptedInputShapes = node.inputs.map { valueShapes[it.valueId]!! }
            val outputShapes = try {
                ShapeInferer.inferShape(node.op, adaptedInputShapes, node.attributes)
            } catch (e: Exception) {
                log.warn { "形状推导失败: ${node.name}(${node.op}): ${e.message}，使用原有形状" }
                node.outputs.map { it.type.shape }
            }

            // 更新输出 ref 和 valueShapes
            if (outputShapes.size == node.outputs.size) {
                for ((output, shape) in node.outputs.zip(outputShapes)) {
                    output.type.shape = shape
                    valueShapes[output.valueId] = shape
                }
            } else {
                // 输出数量不匹配，保持原有形状
                for (output in node.outputs) {
                    valueShapes[output.valueId] = output.type.shape
                }
            }

            i++
        }

        // 3. 更新图输出的形状（确保图输出 ref 的形状与 valueShapes 一致）
        for (output in graph.outputs) {
            val shape = valueShapes[output.valueId]
            if (shape != null) {
                output.type.shape = shape
            }
        }

        log.trace { "修复形状一致性: 图 ${graph.name} 共 ${graph.nodes.size} 个节点" }

        // 同步图间串联形状：将当前图输出形状更新到后续图的输入 ref
        if (graphIdx >= 0 && allGraphs.isNotEmpty()) {
            for (gIdx in graphIdx + 1 until allGraphs.size) {
                val nextGraph = allGraphs[gIdx]
                for (input in nextGraph.inputs) {
                    val prevOutput = graph.outputs.find { it.valueId == input.valueId }
                    if (prevOutput != null) {
                        input.type.shape = prevOutput.type.shape
                    }
                }
            }
        }
    }

    private fun randomSuffix(localRng: Random): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { chars[localRng.nextInt(chars.length)] }.joinToString("")
    }
}

/** 变异操作类型 */
enum class MutationType {
    OP,         // 同族算子替换
    INSERT,     // 插入新节点
    DELETE,     // 删除节点
    ATTRIBUTE,  // 修改属性
}
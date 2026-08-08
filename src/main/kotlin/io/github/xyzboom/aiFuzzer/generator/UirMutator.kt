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

        // 全局输入引用回传: fixGraphConsistency 后同步输入 ref 形状
        for (graph in program.graphs) {
            val shapesByValueId = mutableMapOf<String, UirShape>()
            for (input in graph.inputs) {
                shapesByValueId[input.valueId] = input.type.shape
            }
            for (output in graph.outputs) {
                shapesByValueId[output.valueId] = output.type.shape
            }
            for (node in graph.nodes) {
                for (output in node.outputs) {
                    shapesByValueId[output.valueId] = output.type.shape
                }
            }
            for (node in graph.nodes) {
                for (input in node.inputs) {
                    val latestShape = shapesByValueId[input.valueId]
                    if (latestShape != null && input.type.shape != latestShape) {
                        input.type.shape = latestShape
                    }
                }
            }
        }

        // 去重检查
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
                val matched = patternMatcher.onNodeGenerated(node, resolver, null)
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
                    valueCounter, nodeCounter, node.attributes
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
                        // TILE/BROADCAST_TO/RESHAPE 是 ShapeAdapter 插入的适配节点，输出 ref 形状已由适配器正确设置。
                        // ZEROS/ONES/FULL/ARANGE 是 CONV2D 等算子特殊处理生成的常量张量，输出 ref 形状也已正确设置。
                        // inferShape 无法从输入推导出这些节点的正确输出（因为缺少必要的属性信息），
                        // 所以直接保持输出 ref 的已有形状。
                        if (wrapperNode.op == UirOpKind.TILE || wrapperNode.op == UirOpKind.BROADCAST_TO || wrapperNode.op == UirOpKind.RESHAPE
                            || wrapperNode.op == UirOpKind.ZEROS || wrapperNode.op == UirOpKind.ONES
                            || wrapperNode.op == UirOpKind.FULL || wrapperNode.op == UirOpKind.ARANGE) {
                            for (output in wrapperNode.outputs) {
                                valueShapes[output.valueId] = output.type.shape
                            }
                            continue
                        }
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

            // TILE: 直接保持输出 ref 形状，但需检查 ndim 是否匹配
            // 变异可能在 TILE 前插入了节点改变输入 ndim，导致输入 ndim ≠ 输出 ndim。
            // 此时需插入 RESHAPE 展平到 1D 对齐。
            if (node.op == UirOpKind.TILE) {
                val inputShape = valueShapes[node.inputs[0].valueId] ?: node.inputs[0].type.shape
                val outShape = node.outputs[0].type.shape
                val oldOutValueId = node.outputs[0].valueId
                if (inputShape.dims.size != outShape.dims.size) {
                    log.warn { "TILE ndim 不匹配: input=$inputShape(${inputShape.dims.size}D) -> output=$outShape(${outShape.dims.size}D)，插入 RESHAPE 展平输入" }
                    val totalElements = inputShape.dims.mapNotNull { it.valueOrNull() }
                        .fold(1L) { acc, v -> acc * v }
                    val flatShape = buildShape {
                        dims.add(buildDim {
                            dimKind = UirDimKind.CONSTANT
                            value = totalElements.toInt()
                        })
                    }
                    val flatOutputValueId = "v_${valueCounter}_${rng.nextInt(100000000).toString(36)}"
                    valueShapes[flatOutputValueId] = flatShape
                    val flatOutputRef = buildValueRef {
                        valueId = flatOutputValueId
                        type = buildTensorType {
                            typeKind = UirTypeKind.TENSOR
                            shape = flatShape
                            dtype = node.inputs[0].type.dtype
                        }
                    }
                    val reshapeNode = buildNode {
                        name = "reshape_${nodeCounter}_${rng.nextInt(100000000).toString(36)}"
                        op = UirOpKind.RESHAPE
                        inputs.add(node.inputs[0])
                        outputs.add(flatOutputRef)
                    }
                    graph.nodes.addAll(i, listOf(reshapeNode))
                    node.inputs.clear()
                    node.inputs.add(flatOutputRef)
                    valueCounter++
                    nodeCounter++
                    i++
                }
                for (output in node.outputs) {
                    valueShapes[output.valueId] = output.type.shape
                }
                i++
                continue
            }

            // BROADCAST_TO: 验证广播兼容性
            if (node.op == UirOpKind.BROADCAST_TO) {
                val inputShape = valueShapes[node.inputs[0].valueId] ?: node.inputs[0].type.shape
                val outShape = node.outputs[0].type.shape
                if (inputShape.dims.size > outShape.dims.size || !canBroadcast(inputShape, outShape)) {
                    log.trace { "BROADCAST_TO 不兼容: 重置为恒等广播" }
                    node.outputs[0].type.shape = inputShape
                    valueShapes[node.outputs[0].valueId] = inputShape
                } else {
                    for (output in node.outputs) {
                        valueShapes[output.valueId] = output.type.shape
                    }
                }
                i++
                continue
            }
            if (node.op == UirOpKind.RESHAPE) {
                val inputShape = valueShapes[node.inputs[0].valueId] ?: node.inputs[0].type.shape
                val outShape = node.outputs[0].type.shape
                val inputElements = inputShape.dims.mapNotNull { it.valueOrNull() }
                    .fold(1L) { acc, v -> acc * v }
                val outputElements = outShape.dims.mapNotNull { it.valueOrNull() }
                    .fold(1L) { acc, v -> acc * v }
                if (inputElements > 0 && outputElements > 0 && inputElements != outputElements) {
                    // 变异后上游形状改变，RESHAPE 的 target shape 属性不再匹配输入元素数。
                    // 直接更新 shape 属性，不插入 wrapper 节点（wrapper 改变语义，违反"生成合法程序"原则）。
                    sanitizeReshapeShapeAttr(node, inputShape)
                    // 从更新后的 shape 属性解析新输出形状
                    val shapeAttr = node.attributes["shape"] as? UirStringAttr
                    if (shapeAttr != null) {
                        val newDims = shapeAttr.value.split(",").mapNotNull { it.trim().toIntOrNull() }
                        if (newDims.isNotEmpty()) {
                            val newShape = buildShape {
                                newDims.forEach { d ->
                                    dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = d })
                                }
                            }
                            node.outputs[0].type.shape = newShape
                        }
                    }
                }
                sanitizeReshapeShapeAttr(node, inputShape)
                for (output in node.outputs) {
                    valueShapes[output.valueId] = output.type.shape
                }
                i++
                continue
            }

            sanitizeAxisAttrs(node, valueShapes)
            sanitizeStridedSliceAttrs(node, valueShapes)
            sanitizePoolPadAttrs(node)

            // 重新推导输出形状（使用适配后的输入形状）
            val adaptedInputShapes = node.inputs.map { valueShapes[it.valueId]!! }
            val outputShapes = try {
                ShapeInferer.inferShape(node.op, adaptedInputShapes, node.attributes)
            } catch (e: Exception) {
                log.warn { "形状推导失败: ${node.name}(${node.op}): ${e.message}，尝试通过 ShapeAdapter 修复" }
                // 不直接 fallback 到旧形状，而是尝试走 ShapeAdapter 修复路径
                // 这样可以真正修复广播不兼容等问题，而不是传播错误形状
                try {
                    val result = ShapeAdapter.adaptInputs(
                        node.op, node.inputs, valueShapes,
                        valueCounter, nodeCounter, node.attributes
                    )
                    node.inputs.clear()
                    node.inputs.addAll(result.adaptedRefs)
                    if (result.wrapperNodes.isNotEmpty()) {
                        graph.nodes.addAll(i, result.wrapperNodes)
                        for (wrapperNode in result.wrapperNodes) {
                            if (wrapperNode.op == UirOpKind.TILE || wrapperNode.op == UirOpKind.BROADCAST_TO
                                || wrapperNode.op == UirOpKind.RESHAPE
                                || wrapperNode.op == UirOpKind.ZEROS || wrapperNode.op == UirOpKind.ONES
                                || wrapperNode.op == UirOpKind.FULL || wrapperNode.op == UirOpKind.ARANGE) {
                                for (output in wrapperNode.outputs) {
                                    valueShapes[output.valueId] = output.type.shape
                                }
                            } else {
                                val wInputShapes = wrapperNode.inputs.map {
                                    valueShapes[it.valueId] ?: it.type.shape
                                }
                                val wOutputShapes = ShapeInferer.inferShape(
                                    wrapperNode.op, wInputShapes, wrapperNode.attributes
                                )
                                for ((output, shape) in wrapperNode.outputs.zip(wOutputShapes)) {
                                    output.type.shape = shape
                                    valueShapes[output.valueId] = shape
                                }
                            }
                        }
                        valueCounter += result.wrapperNodes.size
                        nodeCounter += result.wrapperNodes.size
                        i += result.wrapperNodes.size
                    }
                    // 使用适配后的输入形状重新推导
                    val fixedInputShapes = node.inputs.map { valueShapes[it.valueId]!! }
                    val fixedOutputShapes = ShapeInferer.inferShape(node.op, fixedInputShapes, node.attributes)
                    if (fixedOutputShapes.size == node.outputs.size) {
                        for ((output, shape) in node.outputs.zip(fixedOutputShapes)) {
                            output.type.shape = shape
                            valueShapes[output.valueId] = shape
                        }
                    }
                    i++
                    continue
                } catch (e2: Exception) {
                    log.warn { "ShapeAdapter 修复也失败: ${e2.message}，使用原有形状" }
                    node.outputs.map { it.type.shape }
                }
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

        // 同步图间串联形状：将当前图输出形状更新到后续图的输入 ref，
        // 然后对后续图重新运行 fixGraphConsistency 以更新所有节点输出 ref 形状。
        if (graphIdx >= 0 && allGraphs.isNotEmpty()) {
            for (gIdx in graphIdx + 1 until allGraphs.size) {
                val nextGraph = allGraphs[gIdx]
                var changed = false
                for (input in nextGraph.inputs) {
                    val prevOutput = graph.outputs.find { it.valueId == input.valueId }
                    if (prevOutput != null && input.type.shape != prevOutput.type.shape) {
                        input.type.shape = prevOutput.type.shape
                        changed = true
                    }
                }
                // 如果输入形状有变化，重新修复下游图
                // 注意：递归调用会继续传播到更下游的图
                if (changed) {
                    fixGraphConsistency(nextGraph, gIdx, allGraphs)
                }
            }
        }
    }

    private fun randomSuffix(localRng: Random): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { chars[localRng.nextInt(chars.length)] }.joinToString("")
    }

    private fun canBroadcast(inputShape: UirShape, targetShape: UirShape): Boolean {
        val inDims = inputShape.dims
        val tgtDims = targetShape.dims
        if (inDims.size > tgtDims.size) return false
        val offset = tgtDims.size - inDims.size
        for (i in inDims.indices) {
            val iv = inDims[i].valueOrNull() ?: return false
            val tv = tgtDims[offset + i].valueOrNull() ?: return false
            if (iv != tv && iv != 1) return false
        }
        return true
    }

    private fun sanitizeAxisAttrs(node: UirNode, valueShapes: MutableMap<String, UirShape>) {
        val inputShape = node.inputs.firstOrNull()?.let { valueShapes[it.valueId] } ?: return
        val ndim = inputShape.dims.size
        if (ndim == 0) return
        if (node.op !in setOf(
            UirOpKind.SOFTMAX, UirOpKind.LOG_SOFTMAX,
            UirOpKind.REDUCE_SUM, UirOpKind.REDUCE_MEAN, UirOpKind.REDUCE_MAX, UirOpKind.REDUCE_MIN,
            UirOpKind.ARGMAX, UirOpKind.ARGMIN,
            UirOpKind.CUMSUM, UirOpKind.CUMPROD,
            UirOpKind.SPLIT, UirOpKind.CONCAT, UirOpKind.GATHER
        )) return
        val axisAttr = node.attributes["axis"] as? UirIntAttr ?: return
        val oldAxis = axisAttr.value
        val normalized = if (oldAxis < 0) oldAxis + ndim else oldAxis
        val clamped = normalized.coerceIn(0, ndim - 1)
        if (clamped != oldAxis) {
            node.attributes["axis"] = buildIntAttr { value = clamped }
            log.trace { "sanitize axis: ${node.name}.$oldAxis->$clamped" }
        }
    }

    private fun sanitizeStridedSliceAttrs(node: UirNode, valueShapes: MutableMap<String, UirShape>) {
        if (node.op != UirOpKind.STRIDED_SLICE) return
        val axesAttr = node.attributes["axes"] as? UirStringAttr ?: return
        val beginAttr = node.attributes["begin"] as? UirStringAttr
        val endAttr = node.attributes["end"] as? UirStringAttr
        val inputShape = node.inputs.firstOrNull()?.let { valueShapes[it.valueId] } ?: return
        val ndim = inputShape.dims.size
        if (ndim == 0) return
        val oldAxes = axesAttr.value.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (oldAxes.isEmpty()) return

        // 消毒 axes: 超出 ndim 范围的轴取模
        if (oldAxes.any { it >= ndim || it < -ndim }) {
            val fixedAxes = oldAxes.map { Math.floorMod(it, ndim) }.joinToString(",")
            node.attributes["axes"] = buildStringAttr { value = fixedAxes }
            log.trace { "strided_slice axes: ${node.name} ${axesAttr.value} -> $fixedAxes (ndim=$ndim)" }
        }

        // 消毒 begin/end: 不能超过该轴的实际维度大小
        if (endAttr != null) {
            val ends = endAttr.value.split(",").mapNotNull { it.trim().toIntOrNull() }
            val begins = beginAttr?.value?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: oldAxes.map { 0 }
            if (ends.size == oldAxes.size) {
                val fixedEnds = mutableListOf<Int>()
                val fixedBegins = mutableListOf<Int>()
                var changedEnd = false
                var changedBegin = false
                for (i in oldAxes.indices) {
                    val axis = Math.floorMod(oldAxes[i], ndim)
                    val dimSize = inputShape.dims.getOrNull(axis)?.valueOrNull()
                    if (dimSize != null) {
                        val clampedEnd = ends[i].coerceAtMost(dimSize)
                        if (clampedEnd != ends[i]) changedEnd = true
                        fixedEnds.add(clampedEnd)
                        val clampedBegin = begins.getOrElse(i) { 0 }.coerceAtMost(clampedEnd - 1).coerceAtLeast(0)
                        if (i < begins.size && clampedBegin != begins[i]) changedBegin = true
                        fixedBegins.add(clampedBegin)
                    } else {
                        fixedEnds.add(ends[i])
                        fixedBegins.add(begins.getOrElse(i) { 0 })
                    }
                }
                if (changedEnd) {
                    node.attributes["end"] = buildStringAttr { value = fixedEnds.joinToString(",") }
                    log.trace { "strided_slice end: ${node.name} ${endAttr.value} -> ${fixedEnds.joinToString(",")}" }
                }
                if (changedBegin) {
                    node.attributes["begin"] = buildStringAttr { value = fixedBegins.joinToString(",") }
                    log.trace { "strided_slice begin: ${node.name} ${beginAttr?.value ?: "0"} -> ${fixedBegins.joinToString(",")}" }
                }
            }
        }
    }

    private fun sanitizePoolPadAttrs(node: UirNode) {
        if (node.op != UirOpKind.MAX_POOL2D && node.op != UirOpKind.AVG_POOL2D) return
        val kernelAttr = node.attributes["kernel_size"] as? UirIntAttr ?: return
        val padAttr = node.attributes["padding"] as? UirIntAttr ?: return
        val maxPad = kernelAttr.value / 2
        if (padAttr.value > maxPad) {
            node.attributes["padding"] = buildIntAttr { value = maxPad }
            log.trace { "pool pad: ${node.name} ${padAttr.value} -> $maxPad (kernel=${kernelAttr.value})" }
        }
    }

    private fun sanitizeReshapeShapeAttr(node: UirNode, inputShape: UirShape) {
        val shapeAttr = node.attributes["shape"] as? UirStringAttr ?: return
        val targetDims = shapeAttr.value.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (targetDims.isEmpty()) return
        val inputEl = inputShape.dims.mapNotNull { it.valueOrNull() }.fold(1L) { a, v -> a * v }
        val shapeEl = targetDims.fold(1L) { a, v -> a * v }
        if (inputEl > 0 && inputEl != shapeEl) {
            log.trace { "RESHAPE shape attr更新: ${inputEl}el vs ${targetDims}=${shapeEl}el" }
            val newShape = factorizeToNdim(inputEl.toInt(), targetDims.size)
            node.attributes["shape"] = buildStringAttr {
                value = if (newShape.isNotEmpty()) newShape.joinToString(",") else inputEl.toString()
            }
        }
    }

    private fun factorizeToNdim(total: Int, targetNdim: Int): List<Int> {
        if (total <= 0 || targetNdim <= 0) return emptyList()
        val factors = mutableListOf<Int>()
        var remaining = total
        for (i in 0 until targetNdim - 1) {
            if (remaining <= 1) { factors.add(1); continue }
            val candidates = (2..remaining).filter { remaining % it == 0 }
            factors.add(if (candidates.isNotEmpty()) candidates.random() else 1)
            remaining /= factors.last()
        }
        factors.add(remaining)
        return factors
    }

}

/** 变异操作类型 */
enum class MutationType {
    OP,         // 同族算子替换
    INSERT,     // 插入新节点
    DELETE,     // 删除节点
    ATTRIBUTE,  // 修改属性
}
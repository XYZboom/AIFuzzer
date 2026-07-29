package io.github.xyzboom.aiFuzzer.generator

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.config.MutationConfig as ConfigMutationConfig
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
 * 变异后的程序可能不合法（形状不匹配），但 ShapeAdapter 会插入适配算子修复。
 */
class UirMutator(
    private val config: ConfigMutationConfig = ConfigMutationConfig(),
    private val rng: Random = Random,
    /** 去重匹配器：变异后检查是否触发了已知 bug pattern */
    private val patternMatcher: io.github.xyzboom.aiFuzzer.pattern.PatternMatcher? = null,
) {
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
     */
    fun mutate(): UirProgram? {
        if (!config.enabled || seedPool.isEmpty()) return null
        if (rng.nextDouble() > config.rate) return null

        // 随机选一个种子
        val seedJsonl = seedPool[rng.nextInt(seedPool.size)]
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

        // 对每个图应用随机变异
        for (graph in program.graphs) {
            if (graph.nodes.isEmpty()) continue
            // 随机选择变异次数（1-3 次）
            val numMutations = 1 + rng.nextInt(minOf(3, config.maxMutations))
            repeat(numMutations) {
                applyRandomMutation(graph)
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
        UirOpKind.CONCAT,
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
                MutationType.SHAPE -> config.shapeMutation
                MutationType.OP -> config.opMutation
                MutationType.INSERT -> config.insertMutation
                MutationType.DELETE -> config.deleteMutation
                MutationType.ATTRIBUTE -> config.attributeMutation
            }
        }
    }

    private fun applyRandomMutation(graph: UirGraph) {
        if (graph.nodes.isEmpty()) return
        if (enabledMutationTypes.isEmpty()) return

        val mutationType = enabledMutationTypes[rng.nextInt(enabledMutationTypes.size)]

        try {
            when (mutationType) {
                MutationType.SHAPE -> mutateShape(graph)
                MutationType.OP -> mutateOp(graph)
                MutationType.INSERT -> mutateInsert(graph)
                MutationType.DELETE -> mutateDelete(graph)
                MutationType.ATTRIBUTE -> mutateAttribute(graph)
            }
        } catch (e: Exception) {
            // 变异失败（超界、空图等），静默跳过
            log.trace { "变异跳过: ${e.message}" }
        }
    }

    // ---- SHAPE: 修改维度值 ----
    private fun mutateShape(graph: UirGraph) {
        // 找一个有常量维度的节点
        val candidates = graph.nodes.filter { node ->
            node.outputs.any { ref ->
                val shape = ref.type.shape
                shape.dims.any { it.valueOrNull() != null && it.valueOrNull()!! > 1 }
            }
        }
        if (candidates.isEmpty()) return

        val node = candidates[rng.nextInt(candidates.size)]
        val outputRef = node.outputs[rng.nextInt(node.outputs.size)]
        val shape = outputRef.type.shape
        val dimIndex = shape.dims.indices.filter {
            val v = shape.dims[it].valueOrNull()
            v != null && v > 1
        }
        if (dimIndex.isEmpty()) return

        val idx = dimIndex[rng.nextInt(dimIndex.size)]
        val oldVal = shape.dims[idx].valueOrNull()!!

        // 变异策略：增大、减小、设奇数、设偶数
        val newVal = when (rng.nextInt(4)) {
            0 -> oldVal * 2       // 翻倍
            1 -> (oldVal / 2).coerceAtLeast(1) // 减半
            2 -> oldVal + rng.nextInt(1, 5)   // 加小值
            3 -> (oldVal - rng.nextInt(1, 3)).coerceAtLeast(1) // 减小值
            else -> oldVal
        }

        if (newVal == oldVal) return

        // 修改维度值
        val oldShape = shape
        val newDims = oldShape.dims.mapIndexed { i, dim ->
            if (i == idx) buildDim {
                dimKind = UirDimKind.CONSTANT
                value = newVal
            } else dim
        }
        val newShape = buildShape { newDims.forEach { dims.add(it) } }

        // 更新 outputRef 的 shape
        outputRef.type.shape = newShape

        log.trace { "变异 SHAPE: ${node.name}[$idx]: $oldVal → $newVal" }
    }

    // ---- OP: 同族算子替换 ----
    private fun mutateOp(graph: UirGraph) {
        val candidates = graph.nodes.filter { node ->
            opFamilies.any { family ->
                node.op in family.ops && family.ops.any { it != node.op }
            }
        }
        if (candidates.isEmpty()) return

        val node = candidates[rng.nextInt(candidates.size)]
        val family = opFamilies.first { node.op in it.ops }
        val newOp = family.ops.filter { it != node.op }[rng.nextInt(family.ops.size - 1)]

        node.op = newOp
        log.trace { "变异 OP: ${node.name}: ${node.op} → $newOp" }
    }

    // ---- INSERT: 在已有节点后插入新节点 ----
    private fun mutateInsert(graph: UirGraph) {
        // 选一个有输出的节点
        val candidates = graph.nodes.filter { it.outputs.isNotEmpty() }
        if (candidates.size < 2) return

        val targetNode = candidates[rng.nextInt(candidates.size)]
        // 选一个输出
        val outputRef = targetNode.outputs[rng.nextInt(targetNode.outputs.size)]
        val outputShape = outputRef.type.shape

        // 找所有使用该输出的后续节点
        val consumers = graph.nodes.filter { node ->
            node.inputs.any { it.valueId == outputRef.valueId }
        }
        if (consumers.isEmpty()) return

        // 选一个单输入算子插入
        val newOp = singleInputOps[rng.nextInt(singleInputOps.size)]
        val newNodeName = "mut_${rng.nextInt(10000)}_${randomSuffix()}"

        // 创建新节点的输出
        val newOutputId = "v_mut_${rng.nextInt(10000)}_${randomSuffix()}"
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

        log.trace { "变异 INSERT: 在 ${targetNode.name} 后插入 $newOp" }
    }

    // ---- DELETE: 删除一个节点 ----
    private fun mutateDelete(graph: UirGraph) {
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

        val nodeToDelete = candidates[rng.nextInt(candidates.size)]

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
    }

    // ---- ATTRIBUTE: 修改算子属性 ----
    private fun mutateAttribute(graph: UirGraph) {
        // 找有 axis 或 keepdims 属性的节点
        val candidates = graph.nodes.filter { node ->
            node.attributes.containsKey("axis") || node.attributes.containsKey("keepdims")
        }
        if (candidates.isEmpty()) return

        val node = candidates[rng.nextInt(candidates.size)]

        if (node.attributes.containsKey("axis") && (rng.nextBoolean() || !node.attributes.containsKey("keepdims"))) {
            // 修改 axis
            val attr = node.attributes["axis"] as? io.github.xyzboom.aiFuzzer.ir.types.UirIntAttr
            if (attr != null) {
                val ndim = node.outputs.firstOrNull()?.type?.shape?.dims?.size ?: return
                val oldAxis = attr.value
                val newAxis = rng.nextInt(-ndim, ndim)
                if (newAxis != oldAxis) {
                    node.attributes["axis"] = buildIntAttr { value = newAxis }
                    log.trace { "变异 ATTRIBUTE: ${node.name}.axis: $oldAxis → $newAxis" }
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
        }
    }

    private fun randomSuffix(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { chars[rng.nextInt(chars.length)] }.joinToString("")
    }
}

/** 变异操作类型 */
enum class MutationType {
    SHAPE,      // 修改维度值
    OP,         // 同族算子替换
    INSERT,     // 插入新节点
    DELETE,     // 删除节点
    ATTRIBUTE,  // 修改属性
}
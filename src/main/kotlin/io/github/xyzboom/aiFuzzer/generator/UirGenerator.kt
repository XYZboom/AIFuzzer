package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import kotlin.random.Random

/** 默认算子列表（所有已实现算子） */
val DefaultOps = listOf(
    // 元素级二元
    "add", "subtract", "multiply", "divide",
    "maximum", "minimum", "power",
    // 矩阵乘法
    "matmul",
    // 一元激活
    "relu", "sigmoid", "tanh", "gelu", "silu",
    "softmax",
    // 一元数学
    "neg", "abs", "exp", "log", "sqrt", "ceil", "floor",
    // 形状变换
    "reshape", "transpose",
    // 拼接
    "concat",
    // 归约
    "reduce_sum", "reduce_mean", "reduce_max", "reduce_min",
)

/**
 * 生成合法 UIR 程序的配置。
 */
data class GeneratorConfig(
    val seed: Long = System.currentTimeMillis(),
    /** 每个 graph 的最小节点数 */
    val minNodesPerGraph: Int = 3,
    /** 每个 graph 的最大节点数 */
    val maxNodesPerGraph: Int = 8,
    /** 每个 graph 的最小输入数 */
    val minInputs: Int = 1,
    /** 每个 graph 的最大输入数 */
    val maxInputs: Int = 3,
    /** 输入 tensor 的最小 ndim */
    val minInputNdim: Int = 1,
    /** 输入 tensor 的最大 ndim */
    val maxInputNdim: Int = 3,
    /** 参与生成的算子列表 */
    val ops: List<String> = DefaultOps,
    /** 生成的 graph 数量 */
    val graphCount: Int = 1,
)

/**
 * 合法的 UIR 程序生成器。
 *
 * 生成的 IR 程序保证：
 * - 图是 DAG（拓扑顺序构建）
 * - 每个节点引用之前已定义的 value
 * - 输出节点引用已知的 value
 */
class UirGenerator(private val config: GeneratorConfig = GeneratorConfig()) {

    private val rand = Random(config.seed)

    fun generate(): UirProgram = buildProgram {
        repeat(config.graphCount) { graphIdx ->
            graphs.add(generateGraph("graph_$graphIdx"))
        }
    }

    private fun generateGraph(name: String): UirGraph = buildGraph {
        this.name = name
        var valueIdCounter = 0
        fun nextValueId(): String = "v_${valueIdCounter++}"

        // ndim 追踪：valueId → ndim
        val ndimMap = mutableMapOf<String, Int>()

        // 1. 生成输入（带随机 ndim）
        val inputCount = config.minInputs + rand.nextInt(config.maxInputs - config.minInputs + 1)
        val availableValues = mutableListOf<String>()
        repeat(inputCount) {
            val vid = nextValueId()
            val ndim = config.minInputNdim + rand.nextInt(config.maxInputNdim - config.minInputNdim + 1)
            inputs.add(buildValueRef { valueId = vid })
            ndimMap[vid] = ndim
            availableValues.add(vid)
        }

        // 2. 生成节点（带 ndim-aware 算子过滤）
        val nodeCount = config.minNodesPerGraph + rand.nextInt(config.maxNodesPerGraph - config.minNodesPerGraph + 1)
        repeat(nodeCount) {
            // 过滤：只能选当前可用 ndim 兼容的算子
            val availableNdims = availableValues.mapNotNull { ndimMap[it] }.toSet()
            val compatibleOps = config.ops.filter { op ->
                isOpCompatibleWithNdims(op, availableNdims)
            }
            // 如果没兼容算子，回退到安全算子
            val safeOps = compatibleOps.ifEmpty {
                config.ops.filter { op -> op in singleInputOps && op !in ndimChangingOps }
            }
            val op = safeOps[rand.nextInt(safeOps.size)]

            // 选择兼容的输入
            val chosenInputs = selectCompatibleInputs(op, availableValues, ndimMap)

            // 计算输出 ndim
            val outputNdim = computeOutputNdim(op, chosenInputs.mapNotNull { ndimMap[it] })

            // 生成输出
            val outputCount = if (op in multiOutputOps) 2 else 1
            val outputIds = (1..outputCount).map { nextValueId() }

            // 记录输出 ndim（多输出各自有相同 ndim）
            outputIds.forEach { ndimMap[it] = outputNdim }

            // 生成 attributes（ndim-aware）
            val attrs = generateAttributes(op, chosenInputs.mapNotNull { ndimMap[it] }.firstOrNull() ?: 1)

            nodes.add(buildNode {
                this.name = op
                this.op = op
                chosenInputs.forEach { inputs.add(buildValueRef { valueId = it }) }
                outputIds.forEach { outputs.add(buildValueRef { valueId = it }) }
                if (attrs.isNotEmpty()) {
                    attributes = attrs.toMutableMap()
                }
            })

            // 新 value 加入可用池
            availableValues.addAll(outputIds)
        }

        // 3. 选择输出（从可用值中随机选 1-3 个）
        val outputCount = 1.coerceAtLeast(rand.nextInt(availableValues.size).coerceAtLeast(1))
        val chosenOutputs = availableValues.shuffled(rand).take(outputCount)
        chosenOutputs.forEach { outputs.add(buildValueRef { valueId = it }) }
    }

    /**
     * 检查算子是否与当前可用的 ndim 集合兼容。
     */
    private fun isOpCompatibleWithNdims(op: String, availableNdims: Set<Int>): Boolean {
        if (availableNdims.isEmpty()) return true
        return when (op) {
            // 需要至少 2-D 输入
            "transpose" -> availableNdims.any { it >= 2 }
            // 需要至少 1-D 输入
            "softmax", "reshape", "squeeze", "unsqueeze" -> availableNdims.any { it >= 1 }
            // reduce 类：需要至少 1-D 输入
            "reduce_sum", "reduce_mean", "reduce_max", "reduce_min", "max", "min" -> availableNdims.any { it >= 1 }
            // matmul：需要至少 1-D 输入，且不产生 0-D 输出
            "matmul" -> availableNdims.count { it >= 1 } >= 2
            // concat：需要至少 2 个同 ndim 的值
            "concat" -> {
                val counts = availableNdims.groupingBy { it }.eachCount()
                counts.any { (ndim, count) -> ndim >= 1 && count >= 2 }
            }
            // 元素级二元：需要至少 2 个同 ndim 的值
            "add", "subtract", "multiply", "divide", "maximum", "minimum", "power" -> {
                val counts = availableNdims.groupingBy { it }.eachCount()
                counts.any { (_, count) -> count >= 2 }
            }
            // 单输入：有任意值即可
            else -> availableNdims.isNotEmpty()
        }
    }

    /**
     * 为算子选择 ndim 兼容的输入值。
     */
    private fun selectCompatibleInputs(
        op: String,
        availableValues: List<String>,
        ndimMap: Map<String, Int>
    ): List<String> {
        val isSingle = op in singleInputOps
        val neededCount = if (isSingle) 1 else 2

        return when (op) {
            // concat：需要同 ndim
            "concat" -> {
                val byNdim = availableValues.groupBy { ndimMap[it] ?: 1 }
                val compatibleGroup = byNdim.filter { (ndim, vals) -> ndim >= 1 && vals.size >= 2 }
                if (compatibleGroup.isNotEmpty()) {
                    val (_, vals) = compatibleGroup.entries.random(rand)
                    vals.shuffled(rand).take(neededCount)
                } else {
                    availableValues.shuffled(rand).take(neededCount)
                }
            }
            // 元素级二元：需要同 ndim
            "add", "subtract", "multiply", "divide", "maximum", "minimum", "power" -> {
                val byNdim = availableValues.groupBy { ndimMap[it] ?: 1 }
                val compatibleGroup = byNdim.filter { (_, vals) -> vals.size >= 2 }
                if (compatibleGroup.isNotEmpty()) {
                    val (_, vals) = compatibleGroup.entries.random(rand)
                    vals.shuffled(rand).take(neededCount)
                } else {
                    availableValues.shuffled(rand).take(neededCount)
                }
            }
            // transpose：需要 >= 2-D
            "transpose" -> {
                val valid = availableValues.filter { (ndimMap[it] ?: 1) >= 2 }
                if (valid.isNotEmpty()) valid.shuffled(rand).take(neededCount)
                else availableValues.shuffled(rand).take(neededCount)
            }
            // reduce 类：需要 >= 1-D
            "reduce_sum", "reduce_mean", "reduce_max", "reduce_min", "max", "min" -> {
                val valid = availableValues.filter { (ndimMap[it] ?: 1) >= 1 }
                if (valid.isNotEmpty()) valid.shuffled(rand).take(neededCount)
                else availableValues.shuffled(rand).take(neededCount)
            }
            // matmul：需要 >= 1-D 输入
            "matmul" -> {
                val valid = availableValues.filter { (ndimMap[it] ?: 1) >= 1 }
                if (valid.size >= 2) valid.shuffled(rand).take(2)
                else availableValues.shuffled(rand).take(neededCount)
            }
            // softmax, reshape, squeeze, unsqueeze: 需要 >= 1-D
            "softmax", "reshape", "squeeze", "unsqueeze" -> {
                val valid = availableValues.filter { (ndimMap[it] ?: 1) >= 1 }
                if (valid.isNotEmpty()) valid.shuffled(rand).take(neededCount)
                else availableValues.shuffled(rand).take(neededCount)
            }
            // 单输入一元（relu, sigmoid, abs, exp 等）：不限制 ndim，包括 0-D
            else -> availableValues.shuffled(rand).take(neededCount)
        }
    }

    /**
     * 计算算子输出 ndim。
     */
    private fun computeOutputNdim(op: String, inputNdims: List<Int>): Int {
        if (inputNdims.isEmpty()) return 1
        return when (op) {
            // reduce 类：降低 1 维
            "reduce_sum", "reduce_mean", "reduce_max", "reduce_min", "max", "min" -> (inputNdims.first() - 1).coerceAtLeast(0)
            // reshape：translator 使用 relax.ShapeExpr([-1])，总是输出 1-D
            "reshape" -> 1
            // matmul: 1-D @ 1-D → 0-D, 1-D @ 2-D → 1-D, 2-D @ 2-D → 2-D
            "matmul" -> {
                if (inputNdims.size < 2) return inputNdims.first()
                val (a, b) = inputNdims
                when {
                    a == 1 && b == 1 -> 0  // 标量输出
                    a == 1 -> b - 1
                    b == 1 -> a - 1
                    else -> maxOf(a, b)
                }
            }
            // 元素级：ndim 不变
            else -> inputNdims.first()
        }
    }

    private fun generateAttributes(op: String, inputNdim: Int = 1): Map<String, Attribute> {
        return when (op) {
            "softmax", "split" -> mapOf(
                "axis" to buildIntAttr { value = -1 }
            )
            "reduce_sum", "reduce_mean", "reduce_max", "reduce_min" -> mapOf(
                "axis" to buildIntAttr { value = -1 },
                "keepdims" to buildIntAttr { value = 0 }
            )
            "reshape" -> mapOf(
                "shape" to buildIntAttr { value = -1 }
            )
            else -> emptyMap()
        }
    }

    companion object {
        /** 单输入算子 */
        val singleInputOps = setOf(
            "relu", "sigmoid", "tanh", "gelu", "silu",
            "abs", "exp", "log", "sqrt", "neg",
            "ceil", "floor",
            "softmax",
            "reshape", "squeeze", "unsqueeze",
            "reduce_sum", "reduce_mean", "reduce_max", "reduce_min", "max", "min",
        )

        /** 多输出算子 */
        val multiOutputOps = setOf("topk", "split")

        /** 会改变 ndim 的算子 */
        val ndimChangingOps = setOf(
            "reduce_sum", "reduce_mean", "reduce_max", "reduce_min", "max", "min",
            "matmul",
            "reshape", "squeeze", "unsqueeze",
        )
    }
}
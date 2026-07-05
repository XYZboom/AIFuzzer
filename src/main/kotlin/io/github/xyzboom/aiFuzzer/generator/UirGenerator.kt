package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import kotlin.random.Random

/** 默认算子列表（所有已实现算子，44 个） */
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
    "squeeze", "unsqueeze",
    // 拼接/分割
    "concat", "split",
    // 归约
    "reduce_sum", "reduce_mean", "reduce_max", "reduce_min",
    // 索引/切片
    "gather", "strided_slice",
    // 广播/填充
    "broadcast_to", "tile",
    // 类型转换
    "cast",
    // 常数生成
    "arange", "full", "ones", "zeros",
    // 三角
    "tril", "triu",
)

/**
 * 生成合法 UIR 程序的配置。
 */
data class GeneratorConfig(
    val seed: Long = System.currentTimeMillis(),
    /** 每个 graph 的最小节点数 */
    val minNodesPerGraph: Int = 3,
    /** 每个 graph 的最大节点数 */
    val maxNodesPerGraph: Int = 12,
    /** 每个 graph 的最小输入数 */
    val minInputs: Int = 1,
    /** 每个 graph 的最大输入数 */
    val maxInputs: Int = 4,
    /** 输入 tensor 的最小 ndim */
    val minInputNdim: Int = 1,
    /** 输入 tensor 的最大 ndim */
    val maxInputNdim: Int = 4,
    /** 分支概率（0-1），控制拓扑分支 fork-join 的程度 */
    val branchProbability: Double = 0.3,
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
 * - 支持分支拓扑（fork-join）
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
            val n = config.minInputNdim + rand.nextInt(config.maxInputNdim - config.minInputNdim + 1)
            inputs.add(buildValueRef { valueId = vid; ndim = n })
            ndimMap[vid] = n
            availableValues.add(vid)
        }

        // 分支拓扑追踪：每条活跃路径的末端 valueId
        val liveTips = mutableMapOf<Int, String>() // branchId → last valueId
        availableValues.forEachIndexed { i, vid -> liveTips[i] = vid }

        // 2. 生成节点（ndim-aware + 分支拓扑）
        val nodeCount = config.minNodesPerGraph + rand.nextInt(config.maxNodesPerGraph - config.minNodesPerGraph + 1)
        repeat(nodeCount) {
            val availableNdims = availableValues.mapNotNull { ndimMap[it] }.toSet()
            val compatibleOps = config.ops.filter { op ->
                isOpCompatibleWithNdims(op, availableNdims)
            }
            // 回退到安全算子（不含 arange/full/zeros/ones，避免生成无输入依赖的孤立节点）
            val safeOps = compatibleOps.ifEmpty {
                config.ops.filter { op ->
                    op in safeFallbackOps
                }
            }
            val op = safeOps[rand.nextInt(safeOps.size)]

            // 分支感知的输入选择
            val chosenInputs = if (rand.nextDouble() < config.branchProbability && liveTips.size >= 2) {
                val fromTips = selectInputsFromTips(op, ndimMap, liveTips)
                fromTips ?: selectCompatibleInputs(op, availableValues, ndimMap)
            } else {
                selectCompatibleInputs(op, availableValues, ndimMap)
            }

            // 计算输出 ndim
            val outputNdim = computeOutputNdim(op, chosenInputs.mapNotNull { ndimMap[it] })

            // 生成输出
            val outputCount = if (op in multiOutputOps) 2 else 1
            val outputIds = (1..outputCount).map { nextValueId() }
            outputIds.forEach { ndimMap[it] = outputNdim }

            // 生成 attributes（随机化参数）
            val attrs = generateAttributes(op, chosenInputs.mapNotNull { ndimMap[it] }.firstOrNull() ?: 1)

            nodes.add(buildNode {
                this.name = op
                this.op = op
                chosenInputs.forEach { inputs.add(buildValueRef { valueId = it; ndim = ndimMap[it] ?: 1 }) }
                outputIds.forEach { outputs.add(buildValueRef { valueId = it; ndim = outputNdim }) }
                if (attrs.isNotEmpty()) {
                    attributes = attrs.toMutableMap()
                }
            })

            availableValues.addAll(outputIds)

            // 更新活跃路径
            val inputBranches = chosenInputs.mapNotNull { id ->
                liveTips.entries.find { (_, v) -> v == id }?.key
            }.distinct()

            if (inputBranches.size >= 2) {
                // fork-join：合并到第一个分支
                val targetBranch = inputBranches.first()
                outputIds.forEach { liveTips[targetBranch] = it }
                inputBranches.drop(1).forEach { liveTips.remove(it) }
            } else {
                val activeBranch = inputBranches.firstOrNull() ?: liveTips.keys.maxOrNull() ?: 0
                outputIds.forEach { liveTips[activeBranch] = it }
            }

            // 偶尔创建新分支
            if (rand.nextDouble() < config.branchProbability * 0.5 && outputIds.isNotEmpty()) {
                val newBranchId = (liveTips.keys.maxOrNull() ?: -1) + 1
                val branchSource = outputIds[rand.nextInt(outputIds.size)]
                liveTips[newBranchId] = branchSource
            }
        }

        // 3. 选择输出（从可用值中随机选 1-3 个）
        val outputCount = 1.coerceAtLeast(rand.nextInt(availableValues.size).coerceAtLeast(1))
        val chosenOutputs = availableValues.shuffled(rand).take(outputCount)
        chosenOutputs.forEach { outputs.add(buildValueRef { valueId = it; ndim = ndimMap[it] ?: 1 }) }
    }

    /**
     * 检查算子是否与当前可用的 ndim 集合兼容。
     */
    private fun isOpCompatibleWithNdims(op: String, availableNdims: Set<Int>): Boolean {
        if (availableNdims.isEmpty()) return true
        return when (op) {
            // 需要至少 2-D 输入
            in needNdimGe2 -> availableNdims.any { it >= 2 }
            // 需要至少 1-D 输入（且不能是 0-D，因为 reduce 后 0-D 不能再 reduce）
            "softmax", "reshape", "squeeze", "unsqueeze",
            "broadcast_to", "gather" -> availableNdims.any { it >= 1 }
            // reduce 类：需要至少 1-D 输入，且输出不会变成 0-D
            // 如果输入 ndim > 1，reduce 后 >= 0，可以接受
            // 如果输入 ndim == 1，reduce 后 == 0，后续无法再作为非常数算子的输入
            // 为了解决这个问题，只有当 ndim >= 2 时才允许 reduce，
            // 这样 reduce 后的 ndim >= 1，仍可被后续算子使用
            in reducingOps -> availableNdims.any { it >= 2 }
            // matmul：需要至少 1-D 输入，且至少 2 个
            "matmul" -> availableNdims.count { it >= 1 } >= 2
            // pad：需要至少 1-D
            "pad" -> availableNdims.any { it >= 1 }
            // tile：需要至少 1-D
            "tile" -> availableNdims.any { it >= 1 }
            // concat/split：需要至少 2 个同 ndim 的值
            "concat" -> {
                val counts = availableNdims.groupingBy { it }.eachCount()
                counts.any { (ndim, count) -> ndim >= 1 && count >= 2 }
            }
            "split" -> availableNdims.any { it >= 1 }
            // batch_norm, layer_norm: 需要 ≥ 1-D
            "batch_norm", "layer_norm" -> availableNdims.any { it >= 1 }
            // cast: 需要 ≥ 0-D
            "cast" -> true
            // 常数生成算子：不依赖已有值
            "arange", "full", "zeros", "ones" -> true
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
            "transpose", "tril", "triu" -> {
                val valid = availableValues.filter { (ndimMap[it] ?: 1) >= 2 }
                if (valid.isNotEmpty()) valid.shuffled(rand).take(neededCount)
                else availableValues.sortedByDescending { ndimMap[it] ?: 1 }.take(neededCount)
            }
            // reduce 类：需要 >= 1-D
            "reduce_sum", "reduce_mean", "reduce_max", "reduce_min", "max", "min" -> {
                val valid = availableValues.filter { (ndimMap[it] ?: 1) >= 1 }
                if (valid.isNotEmpty()) valid.shuffled(rand).take(neededCount)
                else availableValues.shuffled(rand).take(neededCount)
            }
            "matmul" -> {
                val valid = availableValues.filter { (ndimMap[it] ?: 1) >= 1 }
                if (valid.size >= 2) valid.shuffled(rand).take(2)
                else availableValues.shuffled(rand).take(neededCount)
            }
            // conv2d, pool: 需要 >= 2-D
            "conv2d", "max_pool2d", "avg_pool2d" -> {
                val valid = availableValues.filter { (ndimMap[it] ?: 1) >= 2 }
                if (valid.isNotEmpty()) valid.shuffled(rand).take(neededCount)
                else availableValues.shuffled(rand).take(neededCount)
            }
            // softmax, reshape, squeeze, unsqueeze, broadcast_to, gather: 需要 >= 1-D
            "softmax", "reshape", "squeeze", "unsqueeze",
            "broadcast_to", "gather", "pad", "tile",
            "batch_norm", "layer_norm", "split",
            "strided_slice" -> {
                val valid = availableValues.filter { (ndimMap[it] ?: 1) >= 1 }
                if (valid.isNotEmpty()) valid.shuffled(rand).take(neededCount)
                else availableValues.shuffled(rand).take(neededCount)
            }
            // 常数生成：不需要已有值
            "arange", "full", "zeros", "ones" -> emptyList()
            // 单输入一元：不限制 ndim
            else -> availableValues.shuffled(rand).take(neededCount)
        }
    }

    /**
     * 从不同分支的 tip 选择输入（fork-join 模式）。
     * 返回 null 表示无法找到合适的分支输入。
     */
    private fun selectInputsFromTips(
        op: String,
        ndimMap: Map<String, Int>,
        liveTips: Map<Int, String>
    ): List<String>? {
        val branches = liveTips.values.toList()
        if (branches.size < 2) return null

        return when {
            // 元素级二元：从两个不同路径取同 ndim 的输入
            op in setOf("add", "subtract", "multiply", "divide", "maximum", "minimum", "power") -> {
                val branchPairs = liveTips.entries.toList()
                for (i in branchPairs.indices) {
                    for (j in i + 1 until branchPairs.size) {
                        val vi = branchPairs[i].value
                        val vj = branchPairs[j].value
                        if ((ndimMap[vi] ?: 1) == (ndimMap[vj] ?: 1)) {
                            return listOf(vi, vj)
                        }
                    }
                }
                null
            }
            op == "matmul" -> {
                val valid = branches.filter { (ndimMap[it] ?: 1) >= 1 }
                if (valid.size >= 2) valid.shuffled(rand).take(2) else null
            }
            op == "concat" -> {
                val byNdim = branches.groupBy { ndimMap[it] ?: 1 }
                val pair = byNdim.entries.firstOrNull { it.value.size >= 2 }
                pair?.value?.take(2)
            }
            op in singleInputOps -> {
                val compatible = branches.filter { b ->
                    val ndim = ndimMap[b] ?: 1
                    isOpCompatibleWithNdims(op, setOf(ndim))
                }
                if (compatible.isNotEmpty()) listOf(compatible.random(rand))
                else null
            }
            else -> null
        }
    }

    /**
     * 计算算子输出 ndim。
     */
    private fun computeOutputNdim(op: String, inputNdims: List<Int>): Int {
        if (inputNdims.isEmpty()) return 1
        return when (op) {
            // reduce 类：降低 1 维
            "reduce_sum", "reduce_mean", "reduce_max", "reduce_min", "max", "min" ->
                (inputNdims.first() - 1).coerceAtLeast(0)
            // reshape → 1-D
            "reshape" -> 1
            // squeeze → 降维
            "squeeze" -> (inputNdims.first() - 1).coerceAtLeast(1)
            // unsqueeze → 升维
            "unsqueeze" -> inputNdims.first() + 1
            // matmul
            "matmul" -> {
                if (inputNdims.size < 2) return inputNdims.first()
                val (a, b) = inputNdims
                when {
                    a == 1 && b == 1 -> 0
                    a == 1 -> b - 1
                    b == 1 -> a - 1
                    else -> maxOf(a, b)
                }
            }
            // split → ndim 不变
            "split" -> inputNdims.first()
            // arange → 1-D
            "arange" -> 1
            // full, zeros, ones → 继承 shape，这里简单处理为 1-D
            "full", "zeros", "ones" -> 1
            // broadcast_to, tile → ndim 不变
            "broadcast_to", "tile" -> inputNdims.first()
            // conv2d, pool: 4-D → 4-D
            "conv2d", "max_pool2d", "avg_pool2d" -> inputNdims.first()
            // pad, batch_norm, layer_norm, cast: ndim 不变
            "pad", "batch_norm", "layer_norm", "cast" -> inputNdims.first()
            // strided_slice → 降维
            "strided_slice" -> maxOf(1, inputNdims.first() - 1)
            // tril, triu → ndim 不变
            "tril", "triu" -> inputNdims.first()
            // 默认：ndim 不变
            else -> inputNdims.first()
        }
    }

    /**
     * 生成算子属性（带随机化参数）。
     */
    private fun generateAttributes(op: String, inputNdim: Int = 1): Map<String, Attribute> {
        return when (op) {
            "softmax", "split" -> {
                val axis = if (inputNdim >= 1) rand.nextInt(inputNdim) else -1
                mapOf("axis" to buildIntAttr { value = axis })
            }
            "reduce_sum", "reduce_mean", "reduce_max", "reduce_min" -> {
                val axis = if (inputNdim >= 1) rand.nextInt(inputNdim) else -1
                val keepdims = if (rand.nextBoolean()) 1 else 0
                mapOf(
                    "axis" to buildIntAttr { value = axis },
                    "keepdims" to buildIntAttr { value = keepdims }
                )
            }
            "reshape" -> mapOf(
                "shape" to buildIntAttr { value = -1 }
            )
            "conv2d" -> {
                val kernelSize = rand.nextInt(2, 6) // 2-5
                val stride = rand.nextInt(1, 4)      // 1-3
                val padding = rand.nextInt(0, 3)      // 0-2
                mapOf(
                    "kernel_size" to buildIntAttr { value = kernelSize },
                    "strides" to buildIntAttr { value = stride },
                    "padding" to buildIntAttr { value = padding }
                )
            }
            "max_pool2d", "avg_pool2d" -> {
                val poolSize = rand.nextInt(2, 5)    // 2-4
                val stride = rand.nextInt(1, 4)       // 1-3
                val padding = rand.nextInt(0, 2)       // 0-1
                mapOf(
                    "pool_size" to buildIntAttr { value = poolSize },
                    "strides" to buildIntAttr { value = stride },
                    "padding" to buildIntAttr { value = padding }
                )
            }
            "pad" -> {
                val padWidth = rand.nextInt(1, 3)
                mapOf("pad_width" to buildIntAttr { value = padWidth })
            }
            "strided_slice" -> {
                val begin = rand.nextInt(0, 2)
                val end = -1
                val strides = rand.nextInt(1, 3)
                mapOf(
                    "begin" to buildIntAttr { value = begin },
                    "end" to buildIntAttr { value = end },
                    "strides" to buildIntAttr { value = strides }
                )
            }
            "gather" -> {
                val axis = if (inputNdim >= 1) rand.nextInt(inputNdim) else 0
                mapOf("axis" to buildIntAttr { value = axis })
            }
            "transpose" -> {
                // 只生成简单 permutation
                mapOf("axes" to buildIntAttr { value = -1 }) // -1 = reverse
            }
            "squeeze" -> {
                val axis = if (inputNdim >= 1) rand.nextInt(inputNdim) else 0
                mapOf("axis" to buildIntAttr { value = axis })
            }
            "unsqueeze" -> {
                val axis = rand.nextInt(0, inputNdim + 1)
                mapOf("axis" to buildIntAttr { value = axis })
            }
            "broadcast_to" -> {
                mapOf("shape" to buildIntAttr { value = -1 })
            }
            "tile" -> {
                mapOf("reps" to buildIntAttr { value = rand.nextInt(1, 4) })
            }
            "cast" -> {
                mapOf("dtype" to buildStringAttr { value = "float32" })
            }
            "arange" -> {
                val start = rand.nextInt(0, 5)
                val stop = start + rand.nextInt(5, 20)
                mapOf(
                    "start" to buildIntAttr { value = start },
                    "stop" to buildIntAttr { value = stop }
                )
            }
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
            "cast", "tril", "triu",
            "ones", "zeros",
            "transpose", "broadcast_to",
            "arange", "full",
            "tile", "split",
            "strided_slice",
        )

        /** 多输出算子 */
        val multiOutputOps = setOf("topk", "split")

        /** 会改变 ndim 的算子 */
        val ndimChangingOps = setOf(
            "reduce_sum", "reduce_mean", "reduce_max", "reduce_min", "max", "min",
            "matmul",
            "reshape", "squeeze", "unsqueeze",
            "arange", "full", "ones", "zeros",
            "strided_slice",
        )

        /** 需要 ndim >= 2 的算子 */
        val needNdimGe2 = setOf(
            "transpose", "tril", "triu", "strided_slice",
            "conv2d", "max_pool2d", "avg_pool2d",
        )

        /** reduce 类算子（输出 ndim = input - 1，可能降到 0） */
        val reducingOps = setOf(
            "reduce_sum", "reduce_mean", "reduce_max", "reduce_min", "max", "min",
        )

        /** 安全回退算子（兼容所有 ndim 且不改变 ndim） */
        val safeFallbackOps = setOf(
            "relu", "sigmoid", "tanh", "gelu", "silu",
            "abs", "exp", "log", "sqrt", "neg",
            "ceil", "floor",
            "cast",
        )
    }
}

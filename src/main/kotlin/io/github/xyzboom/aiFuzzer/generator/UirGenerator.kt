package io.github.xyzboom.aiFuzzer.generator

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.infer.ShapeInferer
import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import kotlin.random.Random

private val log = KotlinLogging.logger {}

/** 默认算子列表（所有已实现算子，除外适配算子） */
val DefaultOps: List<UirOpKind> = UirOpKind.entries.filter { it !in UirOpKind.adapterOps }

/**
 * 形状档位配置：控制形状大小范围，确保可执行性。
 *
 * @param minDim 每个维度的最小值
 * @param maxDim 每个维度的最大值（关键参数——设小可避免 OOM）
 * @param minNdim 最小维度数
 * @param maxNdim 最大维度数
 * @param maxTotalElements 单个图所有张量的总元素上限
 * @param label 人类可读标签
 */
data class ShapeTier(
    val minDim: Int = 1,
    val maxDim: Int = 6,
    val minNdim: Int = 1,
    val maxNdim: Int = 3,
    val maxTotalElements: Long = 8_000,
    val label: String = "tiny",
)

/** 预定义形状档位注册表 */
object ShapeTiers {
    val TIERS: Map<String, ShapeTier> = mapOf(
        "tiny" to ShapeTier(1, 6, 1, 3, 8_000, "tiny"),
        "small" to ShapeTier(1, 16, 1, 4, 64_000, "small"),
        "medium" to ShapeTier(1, 32, 1, 4, 256_000, "medium"),
        "conv" to ShapeTier(2, 8, 4, 4, 16_384, "conv"),
        "extreme" to ShapeTier(0, 1, 0, 5, 1_000, "extreme"),
    )

    fun resolve(name: String): ShapeTier = TIERS[name] ?: TIERS["tiny"]!!
}

/**
 * UIR 程序生成器配置。
 */
data class GeneratorConfig(
    val seed: Long = System.currentTimeMillis(),
    val minNodesPerGraph: Int = 3,
    val maxNodesPerGraph: Int = 12,
    val minInputs: Int = 1,
    val maxInputs: Int = 4,
    val branchProbability: Double = 0.3,
    val ops: List<String> = DefaultOps.map { it.name },
    val graphCount: IntRange = 3..5,
    val minNdim: Int = 2,  // 至少 2D
    val maxNdim: Int = 4,
    val dtype: String = "float32",
    val dtypeBits: Int = 32,
    /** 形状档位名称，控制形状大小以避免 OOM */
    val shapeTier: String = "tiny",
    /** 避免生成可能导致 NaN/Inf 的算子。默认开启。
     * 开启后排除 LOG, LOG2, SQRT, RSQRT, RECIPROCAL, DIVIDE, POWER, EXP, CUMPROD */
    val avoidNaNInf: Boolean = true,
    /**
     * 避免生成向上/向下取整、argmin/argmax 等极端算子。
     * 这些算子会放大极其微小的浮点精度误差（如 1.0000001 vs 0.9999999 → 取整后 1 vs 0）。
     * 默认开启，排除 CEIL, FLOOR, ROUND, ARGMAX, ARGMIN。
     */
    val avoidExtremeOps: Boolean = true,
    /** CONCAT 最小输入数量（随机选择输入个数的下限） */
    val concatMinInputs: Int = 2,
    /** CONCAT 最大输入数量（随机选择输入个数的上限） */
    val concatMaxInputs: Int = 5,
    /** 去重配置：在生成阶段规避已知 bug pattern */
    val dedup: DedupConfig = DedupConfig(),
    /** 变异配置 */
    val mutationConfig: io.github.xyzboom.aiFuzzer.config.MutationConfig = io.github.xyzboom.aiFuzzer.config.MutationConfig(),
)

/** 去重配置 */
data class DedupConfig(
    val enabled: Boolean = false,
    val patternDatabase: io.github.xyzboom.aiFuzzer.pattern.PatternDatabase? = null,
    val compiler: String = "tvm",
    val target: String? = "llvm",
    val maxRetries: Int = 10,
    /** 值域分析开关：启用后 pattern 可匹配值的范围（如含有零、负数等） */
    val valueRangeAnalysis: Boolean = false,
)

/**
 * UIR 程序生成器。
 *
 * 生成形状兼容的 DAG 图，直接输出可执行的 UIR 程序。
 * 形状大小自动受 [shapeTier] 预算控制，不超限，不重试。
 */
open class UirGenerator(private val config: GeneratorConfig = GeneratorConfig()) {

    private val rand = Random(config.seed)
    private val opsEnum: List<UirOpKind> = run {
        var baseOps = config.ops.mapNotNull {
            try { UirOpKind.valueOf(it) } catch (_: IllegalArgumentException) { null }
        }.ifEmpty { DefaultOps }.toMutableList()
        if (config.avoidNaNInf) {
            baseOps.removeAll(nanInfProneOps)
        }
        if (config.avoidExtremeOps) {
            baseOps.removeAll(extremeOps)
        }
        baseOps
    }

    /** 去重匹配器（如果启用） */
    val patternMatcher: io.github.xyzboom.aiFuzzer.pattern.PatternMatcher? =
        if (config.dedup.patternDatabase != null) {
            io.github.xyzboom.aiFuzzer.pattern.PatternMatcher(
                config.dedup.patternDatabase!!,
                config.dedup.compiler,
                config.dedup.target,
            )
        } else null

    /** 去重成功阻止生成的次数（pattern 匹配导致重试的计数） */
    var dedupPreventedCount: Int = 0

    /** 当前节点去重重试中禁止使用的算子（节点结束后会清空） */
    private val dedupBlockedOps = mutableSetOf<UirOpKind>()

    private var valueCounter = 0
    private var nodeCounter = 0
    
    // 形状管理：valueId -> shape
    private val valueShapes = mutableMapOf<String, UirShape>()

    // 值域管理：valueId -> ValueRange（仅当 valueRangeAnalysis 启用时维护）
    private val valueRanges = mutableMapOf<String, io.github.xyzboom.aiFuzzer.pattern.ValueRange>()

    /** 本次生成已使用的元素总数，生成时动态压缩形状不超 [shapeTier] 预算 */
    private var usedElements = 0L

    /** 缓存的形状档位 */
    private val shapeTier: ShapeTier = ShapeTiers.resolve(config.shapeTier)

    companion object {
        /** 已知会导致 NaN/Inf 的高风险算子。当 avoidNaNInf=true 时排除。 */
        val nanInfProneOps = setOf(
            UirOpKind.LOG, UirOpKind.LOG2,
            UirOpKind.SQRT, UirOpKind.RSQRT,
            UirOpKind.RECIPROCAL,
            UirOpKind.DIVIDE,
            UirOpKind.POWER,
            UirOpKind.EXP,
            UirOpKind.CUMPROD,
        )

        /**
         * 已知会放大浮点精度误差的极端算子。
         * 当 avoidExtremeOps=true 时排除。
         *
         * 这些算子把极其微小的浮点差异放大为完全不同的输出：
         * - CEIL / FLOOR / ROUND: 微小误差（1.0000001 vs 0.9999999）→ 离散整数跳变（1 vs 0）
         * - ARGMAX / ARGMIN: 微小误差 → 选出完全不同的索引
         * - SIGN: 微小的正/负差异（±1e-45）→ 输出 ±1 跳变；+0.0/-0.0 的后端行为不一致
         * - CUMSUM: 浮点累加顺序不同 → 误差随序列长度累计增长
         * - REDUCE_SUM: 浮点加法不满足结合律，不同归约顺序产生不同结果
         * - REDUCE_MEAN: 同 REDUCE_SUM，累加差异+除法
         */
        val extremeOps = setOf(
            UirOpKind.CEIL, UirOpKind.FLOOR, UirOpKind.ROUND,
            UirOpKind.ARGMAX, UirOpKind.ARGMIN,
            UirOpKind.SIGN,
            UirOpKind.CUMSUM,
            UirOpKind.REDUCE_SUM,
            UirOpKind.REDUCE_MEAN,
        )
    }

    /** 生成随机 ID 后缀（用于追踪） */
    private fun randomIdSuffix(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { chars.random(rand) }.joinToString("")
    }

    /** 创建 dtype（从 config.dtype/config.dtypeBits） */
    private fun mkDataType(): UirDataType = buildDataType {
        this.name = config.dtype
        this.bits = config.dtypeBits
    }

    /**
     * 生成完整的 UIR 程序。
     *
     * 多张图之间有数据流连接：graph_{i-1} 的输出作为 graph_i 的输入。
     * translator 应将图串联执行，后端会看到完整的计算链并有机会做图融合。
     * 形状大小由 [generateRandomShape] 动态控制，保证不超出预算。
     */
    fun generate(): UirProgram {
        usedElements = 0
        val actualCount = config.graphCount.random(rand)
        val program = buildProgram {
            var prevGraphOutputs: List<UirValueRef>? = null
            for (i in 0 until actualCount) {
                log.debug { "生成图 $i/$actualCount (range=${config.graphCount})" }
                val graph = if (i == 0) {
                    generateGraph("graph_$i", null)
                } else {
                    generateGraph("graph_$i", prevGraphOutputs)
                }
                prevGraphOutputs = graph.outputs.toList()
                graphs.add(graph)
            }
        }
        log.debug { "程序生成完成，共 $actualCount 个串联图，使用 ${usedElements}/${shapeTier.maxTotalElements} 元素 (tier=${shapeTier.label})" }
        return program
    }

    /**
     * 生成单个计算图。
     *
     * @param prevGraphOutputs 前一张图的输出，作为本图的额外输入（实现图间串联）。
     *                          传 null 表示第一张图，无前驱。
     */
    private fun generateGraph(name: String, prevGraphOutputs: List<UirValueRef>?): UirGraph {
        log.debug { "生成图: $name" }
        // 不重置 valueCounter，确保跨图 valueId 全局唯一
        
        // 1. 生成图输入
        val availableValues = mutableListOf<String>()
        val graphInputs = mutableListOf<UirValueRef>()

        // 1a. 前图的输出作为本图输入（串联）
        var chainedInputCount = 0
        if (prevGraphOutputs != null) {
            for (prevOutput in prevGraphOutputs) {
                availableValues.add(prevOutput.valueId)
                // 前图输出的 shape 已在 valueShapes 中，直接引用
                valueShapes[prevOutput.valueId] = prevOutput.type.shape
                graphInputs.add(prevOutput)
                chainedInputCount++
            }
            log.debug { "图 $name: 从前图串联 ${prevGraphOutputs.size} 个输入" }
        }

        // 1b. 新增随机输入（第一张图全量生成，后续图适当减少避免输入过多）
        val remainingBudget = if (prevGraphOutputs != null) {
            // 串联模式下，额外输入数取最小值
            minOf(1, config.maxInputs)
        } else {
            config.maxInputs
        }
        val newInputCount = if (prevGraphOutputs != null) {
            // 有前图时，额外生成 0~1 个随机输入
            if (rand.nextBoolean()) 1 else 0
        } else {
            rand.nextInt(config.minInputs, remainingBudget + 1)
        }
        log.trace { "图 $name: 新增随机输入 $newInputCount 个" }

        val freshInputs = (0 until newInputCount).map {
            val valueId = newValueId()
            availableValues.add(valueId)
            
            val shape = generateRandomShape(config.minNdim, config.maxNdim)
            valueShapes[valueId] = shape
            // 初始化输入值域：输入为随机浮点，假设均匀分布在 [-1, 1]（可过 VRA-aware 规避）
            if (config.dedup.valueRangeAnalysis) {
                valueRanges[valueId] = io.github.xyzboom.aiFuzzer.pattern.ValueRange.range(-1.0, 1.0)
            }
            log.trace { "新输入值 $valueId: 形状=${shapeDims(shape)}" }
            
            buildValueRef {
                this.valueId = valueId
                this.type = buildTensorType {
                    this.typeKind = UirTypeKind.TENSOR
                    this.shape = shape
                    this.dtype = mkDataType()
                }
            }
        }
        graphInputs.addAll(freshInputs)
        
        // 2. 生成节点
        val numNodes = rand.nextInt(config.minNodesPerGraph, config.maxNodesPerGraph + 1)
        val nodeList = mutableListOf<UirNode>()
        val liveTips = mutableMapOf<Int, String>()  // branchId -> tip valueId
        var currentBranch = 0
        liveTips[currentBranch] = availableValues.last()
        
        repeat(numNodes) { nodeIndex ->
            // 去重重试循环
            val maxRetries = if (config.dedup.enabled) config.dedup.maxRetries else 1
            var finalNodes: List<UirNode>? = null
            for (retry in 0 until maxRetries) {
                // 回退：如果重试，需要移除上次生成的节点及其对 availableValues 的贡献
                if (retry > 0 && finalNodes != null) {
                    for (n in finalNodes!!) {
                        n.outputs.forEach { o -> availableValues.remove(o.valueId) }
                    }
                }
                val nodes = generateNode(nodeIndex, availableValues, liveTips, currentBranch)
                val mainNode = nodes.last()

                // 更新值域（值域分析启用时）
                if (config.dedup.valueRangeAnalysis) {
                    for (n in nodes) {
                        val inputRanges = n.inputs.map { ref ->
                            valueRanges[ref.valueId] ?: io.github.xyzboom.aiFuzzer.pattern.ValueRange.UNKNOWN
                        }
                        val attrs = n.attributes.mapValues { (_, v) -> v.toString() }
                        for (output in n.outputs) {
                            val range = io.github.xyzboom.aiFuzzer.pattern.ValueRangeAnalyzer.outputRange(
                                n.op.name, inputRanges, attrs
                            )
                            valueRanges[output.valueId] = range
                        }
                    }
                }

                // 去重检查（仅对主节点，跳过 wrapper 节点）
                if (patternMatcher != null) {
                    val resolver: (String) -> UirValueRef? = { vid ->
                        nodes.flatMap { n -> n.inputs + n.outputs }.find { it.valueId == vid }
                    }
                    // 值域解析器（值域分析启用时）
                    val rangeResolver: ((String) -> io.github.xyzboom.aiFuzzer.pattern.ValueRange?)? =
                        if (config.dedup.valueRangeAnalysis) { { vid -> valueRanges[vid] } }
                        else null
                    log.trace { "节点 $nodeIndex (${mainNode.op}): 检查去重" }
                    val matched = patternMatcher.onNodeGenerated(mainNode, resolver, rangeResolver)
                    if (matched != null) {
                        println("[seed=${config.seed}] 节点 $nodeIndex (${mainNode.op}): 匹配 pattern ${matched.id}")
                        log.trace { "节点 $nodeIndex: 与已知 pattern ${matched.id} 匹配！重试第 ${retry + 1} 次" }
                        // 把当前算子加入黑名单，下次重试避开它
                        dedupBlockedOps.add(mainNode.op)
                        if (retry < maxRetries - 1) {
                            dedupPreventedCount++
                            // 先保存为 finalNodes 再清理，确保下一轮能正确清理
                            val prevNodes = finalNodes
                            finalNodes = nodes
                            for (n in nodes) {
                                n.outputs.forEach { o -> valueShapes.remove(o.valueId) }
                                if (config.dedup.valueRangeAnalysis) {
                                    n.outputs.forEach { o -> valueRanges.remove(o.valueId) }
                                }
                            }
                            // 清理上一轮的 availableValues（如果 prevNodes 存在）
                            if (prevNodes != null) {
                                for (n in prevNodes) {
                                    n.outputs.forEach { o -> availableValues.remove(o.valueId) }
                                }
                            }
                            continue
                        }
                        log.warn { "节点 $nodeIndex: 重试 $maxRetries 次后仍匹配，接受" }
                    }
                }

                finalNodes = nodes
                break
            }

            val nodes = finalNodes ?: generateNode(nodeIndex, availableValues, liveTips, currentBranch)
            nodeList.addAll(nodes)
            
            // 更新可用值（只添加最后一个节点的输出）
            val lastNode = nodes.last()
            for (output in lastNode.outputs) {
                availableValues.add(output.valueId)
            }
            
            // 更新当前分支的 tip 为最新输出的值（实现链式推进）
            if (lastNode.outputs.isNotEmpty()) {
                liveTips[currentBranch] = lastNode.outputs.last().valueId
            }
            
            // 随机创建新分支
            if (rand.nextDouble() < config.branchProbability && availableValues.size >= 2) {
                currentBranch++
                liveTips[currentBranch] = availableValues[availableValues.size - 2]
            }
            
            // 节点级黑名单在此节点结束后清空，不影响后续节点
            dedupBlockedOps.clear()
        }
        
        // 3. 选择图输出：选择所有未被使用的值
        // 找出未被任何节点使用的值（即图的叶子节点）
        val usedValues = mutableSetOf<String>()
        nodeList.forEach { node ->
            node.inputs.forEach { input ->
                usedValues.add(input.valueId)
            }
        }
        
        // 未被使用的值 = 所有可用值 - 被使用的值
        val unusedValues = availableValues.filter { it !in usedValues }
        
        // 如果没有未被使用的值，则使用最后一个值作为输出（避免空输出）
        val outputValues = if (unusedValues.isNotEmpty()) {
            unusedValues
        } else {
            listOf(availableValues.last())
        }
        
        log.debug { "图输出: ${outputValues.size} 个未被使用的值" }
        
        val graphOutputs = outputValues.map { valueId ->
            buildValueRef {
                this.valueId = valueId
                this.type = buildTensorType {
                    this.typeKind = UirTypeKind.TENSOR
                    this.shape = valueShapes[valueId] ?: buildShape { }
                    this.dtype = mkDataType()
                }
            }
        }
        
        return buildGraph {
            this.name = name
            graphInputs.forEach { inputs.add(it) }
            graphOutputs.forEach { outputs.add(it) }
            nodeList.forEach { nodes.add(it) }
        }
    }
    
    /**
     * 选择算子，带约束检查。
     * 如果选中的算子不满足当前可用值的约束（如 UNSQUEEZE 在 4D 输入上），
     * 则重试选择其他算子，最多重试 10 次。
     * 
     * 对于单输入算子，检查是否有至少一个可用值满足约束；
     * 对于双输入算子，检查是否有至少一对可用值满足约束。
     */
    open fun selectOpWithConstraints(availableValues: MutableList<String>): UirOpKind {
        val maxRetries = 10
        for (retry in 0 until maxRetries) {
            val available = opsEnum.filter { it !in dedupBlockedOps }
            // 如果所有 op 都被拉了黑名单，忽略黑名单继续
            val candidates = if (available.isEmpty()) opsEnum else available
            
            val candidate = when {
                candidates.isEmpty() -> return UirOpKind.RELU
                availableValues.isEmpty() -> candidates.filter { it in UirOpKind.constantOps }.randomOrNull(rand) ?: UirOpKind.RELU
                availableValues.size == 1 -> candidates.filter { it in UirOpKind.constantOps || it in UirOpKind.singleInputOps }.randomOrNull(rand) ?: UirOpKind.RELU
                else -> candidates.random(rand)
            }

            // VRA-aware NaN/Inf 规避（valueRangeAnalysis 启用时）：
            // 若候选 op 是 NaN/Inf 高风险算子，要求至少有一个可用值的值域对该 op 安全，
            // 否则跳过该 op（等价于 smart avoid_nan_inf，但不是粗暴移除算子，而是依赖值域判断）。
            if (config.dedup.valueRangeAnalysis && candidate in nanInfProneOps && availableValues.isNotEmpty()) {
                val hasSafeInput = availableValues.any { vid ->
                    val range = valueRanges[vid]
                    if (range == null) true  // 未知值域 → 保守放行
                    else isRangeSafeFor(candidate, range)
                }
                if (!hasSafeInput) continue
            }
            // VRA-aware extreme op 规避（valueRangeAnalysis 启用时）：
            // 对 SIGN 等极端算子，当输入值域可能接近 0 时跳过，避免极值行为。
            if (config.dedup.valueRangeAnalysis && candidate in extremeOps && availableValues.isNotEmpty()) {
                val hasSafeInput = availableValues.any { vid ->
                    val range = valueRanges[vid]
                    if (range == null) true
                    else isRangeSafeFor(candidate, range)
                }
                if (!hasSafeInput) continue
            }
            
            // Check constraint: get the input shape(s) this op would receive
            val numInputs = when (candidate) {
                in UirOpKind.constantOps -> 0
                in UirOpKind.singleInputOps -> 1
                in UirOpKind.multiInputOps -> minOf(2, availableValues.size)
                in UirOpKind.binaryInputOps -> minOf(2, availableValues.size)
                else -> 1
            }
            
            if (numInputs == 0) return candidate  // Constant ops have no constraints

            // CONV2D: 只需 1 个有效 4D 输入，权重由 selectInputValues 自生成
            if (candidate == UirOpKind.CONV2D) {
                val hasValidInput = availableValues.any { vid ->
                    val shape = valueShapes[vid]
                    shape != null && shape.dims.size == 4
                }
                if (hasValidInput) return candidate
                continue
            }

            // For single-input ops: check if ANY available value satisfies the constraint
            if (numInputs == 1) {
                val hasValidInput = availableValues.any { vid ->
                    val shape = valueShapes[vid]
                    shape != null && ShapeConstraints.isApplicable(candidate, listOf(shape))
                }
                if (hasValidInput) return candidate
                continue  // No valid input for this op, retry
            }
            
            // For binary-input ops: check if ANY pair of available values satisfies the constraint
            if (numInputs == 2 && availableValues.size >= 2) {
                val hasValidPair = availableValues.any { vid1 ->
                    val shape1 = valueShapes[vid1]
                    shape1 != null && availableValues.any { vid2 ->
                        vid2 != vid1 && valueShapes[vid2]?.let { shape2 ->
                            ShapeConstraints.isApplicable(candidate, listOf(shape1, shape2))
                        } ?: false
                    }
                }
                if (hasValidPair) return candidate
                continue
            }
            
            // Fallback: sample first N values
            val sampleShapes = availableValues.take(numInputs).mapNotNull { valueShapes[it] }
            if (sampleShapes.size >= numInputs && ShapeConstraints.isApplicable(candidate, sampleShapes)) {
                return candidate
            }
        }
        
        // Fallback: pick a safe op (RELU works on any shape)
        return UirOpKind.RELU
    }

    /**
     * 判断某个值域对于一个 NaN/Inf 高风险算子是否安全（即不会因此产生 NaN/Inf）。
     * 仅用于 VRA-aware 规避（valueRangeAnalysis 启用时）。
     * 若值域未知（UNKNOWN），保守放行（返回 true）。
     */
    private fun isRangeSafeFor(op: UirOpKind, range: io.github.xyzboom.aiFuzzer.pattern.ValueRange): Boolean {
        return when (op) {
            UirOpKind.SQRT -> range.min >= 0.0          // 输入非负 → sqrt 安全
            UirOpKind.RSQRT -> range.min > 0.0          // 输入 >0 → rsqrt 安全
            UirOpKind.LOG, UirOpKind.LOG2 -> range.min > 0.0  // 输入 >0 → log 安全
            UirOpKind.RECIPROCAL -> !range.containsZero()     // 不包含0 → 倒数安全
            UirOpKind.DIVIDE -> !range.containsZero()          // 输入不含0 → 用作除数安全
            UirOpKind.SIGN -> !range.containsZero()            // 不含0 → sign 行为确定
            // EXP / POWER / CUMPROD 对有限输入不会产生 NaN/Inf（可能 Overflow 但可接受）
            else -> true
        }
    }
    
    open fun generateNode(
        nodeIndex: Int,
        availableValues: MutableList<String>,
        liveTips: Map<Int, String>,
        currentBranch: Int
    ): List<UirNode> {
        // 1. 选择算子（随机），带约束检查重试
        val op = selectOpWithConstraints(availableValues)
        log.trace { "节点 $nodeIndex: 选择算子 $op (可用值=${availableValues.size})" }
        
        // 2. 确定输入数量
        val numInputs = when (op) {
            in UirOpKind.constantOps -> 0
            in UirOpKind.singleInputOps -> 1
            in UirOpKind.multiInputOps -> {
                if (availableValues.size < 2) 1
                else rand.nextInt(config.concatMinInputs, minOf(config.concatMaxInputs, availableValues.size) + 1)
            }
            in UirOpKind.binaryInputOps -> {
                // CONV2D 始终需要 2 个输入（权重由 selectInputValues 自生成）
                if (op == UirOpKind.CONV2D) 2
                else minOf(2, availableValues.size)
            }
            else -> 1
        }
        log.trace { "节点 $nodeIndex: 输入数量 $numInputs" }
        
        // 3. 选择输入值（可能插入转换节点）
        val conversionNodes = mutableListOf<UirNode>()
        val inputValueRefs = selectInputValues(op, numInputs, availableValues, liveTips, currentBranch, conversionNodes)
        
        // 记录输入详情
        if (inputValueRefs.isNotEmpty()) {
            log.trace { 
                "节点 $nodeIndex: 输入值 ${inputValueRefs.map { "${it.valueId}:${shapeDims(valueShapes[it.valueId]!!)}" }}" 
            }
        }
        
        // 4. 先生成属性（形状推导需要属性信息）
        val attributes = generateAttributes(op)
        if (attributes.isNotEmpty()) {
            log.trace { "节点 $nodeIndex: 基础属性 $attributes" }
        }
        
        // 4.5: CONCAT 特殊处理：在适配前随机化 axis（ShapeAdapter 需要 axis 确定拼接维度）
        if (op == UirOpKind.CONCAT && inputValueRefs.size >= 2) {
            val ndims = inputValueRefs.mapNotNull { valueShapes[it.valueId]?.dims?.size }
            if (ndims.isNotEmpty()) {
                val maxNdim = ndims.max()
                if (maxNdim >= 1) {
                    val axis = rand.nextInt(maxNdim)
                    attributes["axis"] = buildIntAttr { value = axis }
                    log.trace { "CONCAT: 随机化 axis=$axis (maxNdim=$maxNdim)" }
                }
            }
        }
        
        // 5. 形状适配：检查输入形状是否满足算子约束，必要时插入 wrapper
        val adaptResult = ShapeAdapter.adaptInputs(
            op, inputValueRefs, valueShapes, valueCounter, nodeCounter, attributes
        )
        
        // 记录适配信息
        if (adaptResult.wrapperNodes.isNotEmpty()) {
            log.debug { "节点 $nodeIndex: 插入 ${adaptResult.wrapperNodes.size} 个 wrapper 节点" }
        }
        
        val adaptedInputRefs = adaptResult.adaptedRefs
        val adaptedInputShapes = adaptResult.adaptedShapes
        conversionNodes.addAll(adaptResult.wrapperNodes)
        
        // 5.5: CONV2D 特殊处理：根据适配后的输入形状动态随机化 stride/padding
        if (op == UirOpKind.CONV2D && adaptedInputShapes.size == 2) {
            val inputShape = adaptedInputShapes[0]
            val weightShape = adaptedInputShapes[1]
            if (inputShape.dims.size == 4 && weightShape.dims.size == 4) {
                val h = inputShape.dims[2].valueOrNull() ?: 1
                val w = inputShape.dims[3].valueOrNull() ?: 1

                // ShapeAdapter 已保证 kH ≤ H, kW ≤ W，所以任意 stride 都满足 H_out ≥ 1
                val maxStride = maxOf(1, minOf(h, w))
                val stride = if (maxStride > 1) rand.nextInt(1, maxStride + 1) else 1
                attributes["stride"] = buildIntAttr { value = stride }

                // padding ∈ [0, min(H, W) / 2]，软限制避免输出过大
                val maxPadding = maxOf(0, minOf(h, w) / 2)
                val padding = if (maxPadding > 0) rand.nextInt(0, maxPadding + 1) else 0
                attributes["padding"] = buildIntAttr { value = padding }
            }
        }

        // 5.6: 通用属性随机化：根据适配后的输入形状动态随机化算子属性
        when (op) {
            // SOFTMAX/LOG_SOFTMAX: 随机 axis（默认 -1 表示最后一维）
            UirOpKind.SOFTMAX, UirOpKind.LOG_SOFTMAX -> {
                if (adaptedInputShapes.isNotEmpty()) {
                    val ndim = adaptedInputShapes[0].dims.size
                    if (ndim >= 1) {
                        // axis 范围 [-ndim, ndim)，随机选一个
                        val axis = if (rand.nextBoolean()) {
                            rand.nextInt(ndim)  // 非负 0..ndim-1
                        } else {
                            -rand.nextInt(1, ndim + 1)  // 负 -ndim..-1
                        }
                        attributes["axis"] = buildIntAttr { value = axis }
                        log.trace { "SOFTMAX: 随机化 axis=$axis (ndim=$ndim)" }
                    }
                }
            }
            // POOL2D: 随机 padding
            UirOpKind.MAX_POOL2D, UirOpKind.AVG_POOL2D -> {
                if (adaptedInputShapes.isNotEmpty() && adaptedInputShapes[0].dims.size >= 4) {
                    val h = adaptedInputShapes[0].dims[2].valueOrNull() ?: 1
                    val w = adaptedInputShapes[0].dims[3].valueOrNull() ?: 1
                    // 从 attributes 读取 kernel_size（默认 1）
                    val kernelSize = (attributes["kernel_size"] as? UirIntAttr)?.value ?: 1
                    // padding 不能超过 kernel_size / 2（PyTorch 约束 kernel_size=1→padding=0）
                    val maxPadding = maxOf(0, minOf(minOf(h, w), kernelSize) / 2)
                    if (maxPadding > 0) {
                        val padding = rand.nextInt(0, maxPadding + 1)
                        attributes["padding"] = buildIntAttr { value = padding }
                        log.trace { "POOL2D: 随机化 padding=$padding (maxPadding=$maxPadding)" }
                    }
                }
            }
            // REDUCE_*: 随机 axis + keepdims
            UirOpKind.REDUCE_SUM, UirOpKind.REDUCE_MEAN, UirOpKind.REDUCE_MAX, UirOpKind.REDUCE_MIN -> {
                if (adaptedInputShapes.isNotEmpty()) {
                    val ndim = adaptedInputShapes[0].dims.size
                    if (ndim >= 1) {
                        val axis = rand.nextInt(ndim)
                        attributes["axis"] = buildIntAttr { value = axis }
                        val keepdims = if (rand.nextDouble() < 0.3) 1 else 0
                        attributes["keepdims"] = buildIntAttr { value = keepdims }
                        log.trace { "REDUCE: 随机化 axis=$axis keepdims=$keepdims (ndim=$ndim)" }
                    }
                }
            }
            // ARGMAX/ARGMIN: 随机 axis + keepdims
            UirOpKind.ARGMAX, UirOpKind.ARGMIN -> {
                if (adaptedInputShapes.isNotEmpty()) {
                    val ndim = adaptedInputShapes[0].dims.size
                    if (ndim >= 1) {
                        val axis = rand.nextInt(ndim)
                        attributes["axis"] = buildIntAttr { value = axis }
                        log.trace { "ARGMAX: 随机化 axis=$axis (ndim=$ndim)" }
                    }
                }
            }
            // SPLIT: 随机 axis + 随机 splits（基于适配后的输入形状）
            UirOpKind.SPLIT -> {
                if (adaptedInputShapes.isNotEmpty()) {
                    val ndim = adaptedInputShapes[0].dims.size
                    if (ndim >= 1) {
                        val axis = rand.nextInt(ndim)
                        attributes["axis"] = buildIntAttr { value = axis }
                        // 随机生成 splits：基于 axis 维度值
                        val axisDimVal = adaptedInputShapes[0].dims[axis].valueOrNull() ?: 2
                        if (axisDimVal >= 4) {
                            // 随机分成 2~4 段，每段至少 1
                            val numSplits = rand.nextInt(2, minOf(5, axisDimVal + 1))
                            val parts = mutableListOf<Int>()
                            var remaining = axisDimVal
                            for (i in 0 until numSplits) {
                                if (i == numSplits - 1) {
                                    parts.add(remaining)
                                } else {
                                    // 每段至少留 1 给后面的
                                    val maxTake = remaining - (numSplits - i - 1)
                                    val take = if (maxTake > 1) rand.nextInt(1, maxTake + 1) else 1
                                    parts.add(take)
                                    remaining -= take
                                }
                            }
                            attributes["splits"] = buildStringAttr { value = parts.joinToString(",") }
                            log.trace { "SPLIT: 随机化 axis=$axis splits=$parts (ndim=$ndim axisDim=$axisDimVal)" }
                        } else {
                            // 维度太小，等分 2 份
                            attributes["splits"] = buildStringAttr { value = "2" }
                            log.trace { "SPLIT: 随机化 axis=$axis splits=2 (ndim=$ndim, axis dim too small)" }
                        }
                    }
                }
            }
            // GATHER: 随机 axis + indices
            UirOpKind.GATHER -> {
                if (adaptedInputShapes.isNotEmpty()) {
                    val ndim = adaptedInputShapes[0].dims.size
                    if (ndim >= 1) {
                        val axis = rand.nextInt(ndim)
                        attributes["axis"] = buildIntAttr { value = axis }
                        // 随机 indices：单标量 or 多索引
                        val axisDimVal = adaptedInputShapes[0].dims[axis].valueOrNull() ?: 4
                        if (axisDimVal >= 3 && rand.nextBoolean()) {
                            // 多索引：随机选 2~min(5, axisDimVal) 个不重复索引
                            val numIndices = rand.nextInt(2, minOf(6, axisDimVal + 1))
                            val indices = (0 until axisDimVal).shuffled(rand).take(numIndices).sorted()
                            attributes["indices"] = buildStringAttr { value = indices.joinToString(",") }
                            log.trace { "GATHER: 随机化 axis=$axis indices=$indices (ndim=$ndim axisDim=$axisDimVal)" }
                        } else {
                            // 单标量
                            val idx = if (axisDimVal > 1) rand.nextInt(axisDimVal) else 0
                            attributes["indices"] = buildStringAttr { value = idx.toString() }
                            log.trace { "GATHER: 随机化 axis=$axis indices=$idx (ndim=$ndim)" }
                        }
                    }
                }
            }
            // STRIDED_SLICE: 随机 axes + begin + end
            UirOpKind.STRIDED_SLICE -> {
                if (adaptedInputShapes.isNotEmpty()) {
                    val ndim = adaptedInputShapes[0].dims.size
                    if (ndim >= 1) {
                        // 随机选 1~min(3, ndim) 个轴
                        val numAxes = rand.nextInt(1, minOf(4, ndim + 1))
                        val selectedAxes = (0 until ndim).shuffled(rand).take(numAxes).sorted()
                        val begins = mutableListOf<Int>()
                        val ends = mutableListOf<Int>()
                        for (a in selectedAxes) {
                            val dimVal = adaptedInputShapes[0].dims[a].valueOrNull() ?: 4
                            val maxBegin = maxOf(1, dimVal - 1)
                            val b = if (maxBegin > 0) rand.nextInt(maxBegin) else 0
                            val e = if (dimVal > b) rand.nextInt(b + 1, dimVal + 1) else b + 1
                            begins.add(b)
                            ends.add(e)
                        }
                        attributes["axes"] = buildStringAttr { value = selectedAxes.joinToString(",") }
                        attributes["begin"] = buildStringAttr { value = begins.joinToString(",") }
                        attributes["end"] = buildStringAttr { value = ends.joinToString(",") }
                        log.trace { "STRIDED_SLICE: 随机化 axes=$selectedAxes begin=$begins end=$ends (ndim=$ndim)" }
                    }
                }
            }
            // RESHAPE: 生成随机目标形状（总元素数保持不变）
            UirOpKind.RESHAPE -> {
                if (adaptedInputShapes.isNotEmpty()) {
                    val inputShape = adaptedInputShapes[0]
                    val totalElements = inputShape.dims.fold(1L) { acc, dim ->
                        acc * (dim.valueOrNull()?.toLong() ?: 1L)
                    }
                    if (totalElements in 1..Int.MAX_VALUE) {
                        // 分解 totalElements 为合法 ndim
                        val origNdim = inputShape.dims.size
                        val targetNdim = rand.nextInt(1, minOf(5, origNdim + 2))
                        val factors = factorizeRandomly(totalElements.toInt(), targetNdim, rand)
                        if (factors.isNotEmpty()) {
                            attributes["shape"] = buildStringAttr { value = factors.joinToString(",") }
                            log.trace { "RESHAPE: 随机化 target shape=$factors (total=$totalElements ndim=$targetNdim)" }
                        }
                    }
                }
            }
            // CUMSUM/CUMPROD: 随机 axis
            UirOpKind.CUMSUM, UirOpKind.CUMPROD -> {
                if (adaptedInputShapes.isNotEmpty()) {
                    val ndim = adaptedInputShapes[0].dims.size
                    if (ndim >= 1) {
                        val axis = rand.nextInt(ndim)
                        attributes["axis"] = buildIntAttr { value = axis }
                        log.trace { "CUMSUM: 随机化 axis=$axis (ndim=$ndim)" }
                    }
                }
            }
            else -> { /* 无特殊属性随机化 */ }
        }

        // 6. 推导并生成输出值（委托给 ShapeInferer）
        val outputShapes = inferOutputShapes(op, adaptedInputShapes, attributes)
        
        log.trace { "节点 $nodeIndex: 输出形状 ${outputShapes.map(::shapeDims)}" }
        
        val outputValueRefs = outputShapes.map { shape ->
            val valueId = newValueId()
            valueShapes[valueId] = shape
            buildValueRef {
                this.valueId = valueId
                this.type = buildTensorType {
                    this.typeKind = UirTypeKind.TENSOR
                    this.shape = shape
                    this.dtype = mkDataType()
                }
            }
        }
        
        // 7. 创建主节点（使用适配后的输入）
        val mainNode = buildNode {
            name = "${op.name.lowercase()}_${nodeIndex}_${randomIdSuffix()}"
            this.op = op
            adaptedInputRefs.forEach { ref -> inputs.add(ref) }  // 使用适配后的输入
            outputValueRefs.forEach { ref -> outputs.add(ref) }
            this.attributes = attributes
        }
        
        log.debug { "创建节点: ${mainNode.name} (op=$op)" }
        log.debug { "  输入: ${adaptedInputRefs.map { "${it.valueId} ${shapeDims(valueShapes[it.valueId]!!)}" }}" }
        log.debug { "  输出: ${outputValueRefs.map { "${it.valueId} ${shapeDims(valueShapes[it.valueId]!!)}" }}" }
        
        // 8. 返回：转换节点 + wrapper节点 + 主节点
        return conversionNodes + mainNode
    }
    
    private fun selectInputValues(
        op: UirOpKind,
        numInputs: Int,
        availableValues: MutableList<String>,
        liveTips: Map<Int, String>,
        currentBranch: Int,
        nodeList: MutableList<UirNode>
    ): List<UirValueRef> {
        if (numInputs == 0) return emptyList()
        
        // 优先从当前分支的 tip 选择
        val tipValue = liveTips[currentBranch]
        log.trace { "选择输入: op=$op, numInputs=$numInputs, tip=$tipValue, 可用=${availableValues.take(5)}${if (availableValues.size > 5) "..." else ""}" }
        
        // 特殊处理：多输入运算（CONCAT）—— 随机选择输入，ShapeAdapter 会裁剪到最小形状
        if (op in UirOpKind.multiInputOps && numInputs >= 2 && availableValues.size >= 2) {
            val actualNumInputs = minOf(numInputs, availableValues.size)
            val selectedIds = availableValues.shuffled(rand).take(actualNumInputs)
            log.trace { "多输入运算: 随机选中 ${selectedIds.size} 个输入" }

            return selectedIds.map { valueId ->
                buildValueRef {
                    this.valueId = valueId
                    this.type = buildTensorType {
                        this.typeKind = UirTypeKind.TENSOR
                        this.shape = valueShapes[valueId] ?: buildShape { }
                        this.dtype = mkDataType()
                    }
                }
            }
        }

        // 特殊处理：二元运算
        if (op in UirOpKind.binaryInputOps && numInputs == 2 && availableValues.size >= 2) {
            // 选择第一个输入：加权随机，越新的值权重越高
            // 利用 availableValues 的顺序（越早生成的越靠前，越新的越靠后）
            // 权重 = 位置索引 + 1（线性衰减），最新值有最大概率被选中
            // 这样既保留了链式推进的倾向，又允许跨分支汇聚
            val weights = availableValues.indices.map { (it + 1).toDouble() }
            val totalWeight = weights.sum()
            var roll = rand.nextDouble() * totalWeight
            var input1ValueId = availableValues.last()  // fallback
            for (i in availableValues.indices) {
                roll -= weights[i]
                if (roll <= 0.0) {
                    input1ValueId = availableValues[i]
                    break
                }
            }
            log.trace { "二元运算: 加权随机选 input1=$input1ValueId (可用值=${availableValues.size})" }
            
            // 特殊处理：CONV2D 需要生成匹配的权重常量
            if (op == UirOpKind.CONV2D) {
                val inputShape = valueShapes[input1ValueId]!!
                // 确保输入是 4D (NCHW)
                if (inputShape.dims.size == 4) {
                    val cIn = inputShape.dims[1].valueOrNull() ?: 1
                    val h = inputShape.dims[2].valueOrNull() ?: 1
                    val w = inputShape.dims[3].valueOrNull() ?: 1
                    val cOut = rand.nextInt(1, minOf(cIn + 1, 5))
                    val kH = minOf(rand.nextInt(1, 4), h)
                    val kW = minOf(rand.nextInt(1, 4), w)
                    
                    // 生成权重常量节点
                    val weightValueId = newValueId()
                    val weightShape = buildShape {
                        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = cOut })
                        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = cIn })
                        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = kH })
                        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = kW })
                    }
                    valueShapes[weightValueId] = weightShape
                    
                    val weightNode = buildNode {
                        name = "conv2d_weight_${randomIdSuffix()}"
                        this.op = UirOpKind.FULL
                        attributes["fill_value"] = buildStringAttr { value = "0.1" }
                        attributes["shape"] = buildStringAttr { value = "($cOut, $cIn, $kH, $kW)" }
                        attributes["dtype"] = buildStringAttr { value = "float32" }
                        val outputRef = buildValueRef {
                            this.valueId = weightValueId
                            this.type = buildTensorType {
                                typeKind = UirTypeKind.TENSOR
                                shape = weightShape
                                dtype = mkDataType()
                            }
                        }
                        this.outputs.add(outputRef)
                    }
                    nodeList.add(weightNode)
                    availableValues.add(weightValueId)
                    
                    val input1Ref = buildValueRef {
                        this.valueId = input1ValueId
                        this.type = buildTensorType {
                            this.typeKind = UirTypeKind.TENSOR
                            this.shape = valueShapes[input1ValueId]!!
                            this.dtype = mkDataType()
                        }
                    }
                    
                    val input2Ref = buildValueRef {
                        this.valueId = weightValueId
                        this.type = buildTensorType {
                            this.typeKind = UirTypeKind.TENSOR
                            this.shape = weightShape
                            this.dtype = mkDataType()
                        }
                    }
                    
                    return listOf(input1Ref, input2Ref)
                }
                // 如果输入不是 4D，回退到随机选择（ShapeAdapter 会处理）
            }
            
            // 选择第二个输入 — prefer broadcast-compatible shapes, fall back to random
            val broadcastCompatibleValues = availableValues.filter { vid ->
                vid != input1ValueId && valueShapes[vid]?.let { shape2 ->
                    ShapeConstraints.areBroadcastable(valueShapes[input1ValueId]!!, shape2)
                } ?: false
            }
            val input2ValueId = if (broadcastCompatibleValues.isNotEmpty()) {
                broadcastCompatibleValues.random(rand)
            } else {
                // No broadcast-compatible value found — pick an existing value from the graph.
                // ShapeAdapter will handle shape adaptation (expand dims, broadcast, reshape),
                // so there's no need to generate a ZEROS constant even as last resort.
                // If only input1 is available, it's fine — the binary op will have the same
                // value as both inputs, but that's a valid edge case for fuzzing.
                val otherValues = availableValues.filter { it != input1ValueId }
                log.trace { "二元运算: 无广播兼容值，从已有值中随机选 (${otherValues.size} 个候选)" }
                if (otherValues.isNotEmpty()) otherValues.random(rand)
                else input1ValueId  // same value for both inputs — valid edge case
            }
            
            val input1Ref = buildValueRef {
                this.valueId = input1ValueId
                this.type = buildTensorType {
                    this.typeKind = UirTypeKind.TENSOR
                    this.shape = valueShapes[input1ValueId]!!
                    this.dtype = mkDataType()
                }
            }
            
            val input2Ref = buildValueRef {
                this.valueId = input2ValueId
                this.type = buildTensorType {
                    this.typeKind = UirTypeKind.TENSOR
                    this.shape = valueShapes[input2ValueId]!!
                    this.dtype = mkDataType()
                }
            }
            
            return listOf(input1Ref, input2Ref)
        }
        
        // 其他情况：随机选择，但优先选择满足约束的值
        val selectedIds: List<String> = when {
            availableValues.isEmpty() -> emptyList()
            numInputs == 1 -> {
                // For single-input ops: prefer values that satisfy the op's constraints
                val validValues = availableValues.filter { vid ->
                    val shape = valueShapes[vid]
                    shape != null && ShapeConstraints.isApplicable(op, listOf(shape))
                }
                when {
                    validValues.isNotEmpty() && tipValue != null && tipValue in validValues -> listOf(tipValue)
                    validValues.isNotEmpty() -> listOf(validValues.random(rand))
                    // No valid values satisfy constraint — fall back to any value (ShapeAdapter will try to fix)
                    tipValue != null && tipValue in availableValues -> listOf(tipValue)
                    else -> availableValues.shuffled(rand).take(1)
                }
            }
            else -> availableValues.shuffled(rand).take(numInputs)
        }
        
        val inputRefs = selectedIds.map { valueId ->
            buildValueRef {
                this.valueId = valueId
                this.type = buildTensorType {
                    this.typeKind = UirTypeKind.TENSOR
                    this.shape = valueShapes[valueId] ?: buildShape { }
                    this.dtype = mkDataType()
                }
            }
        }
        
        // 形状适配由 ShapeAdapter 处理，这里直接返回
        return inputRefs
    }
    
    private fun generateAttributes(op: UirOpKind): MutableMap<String, Attribute> {
        val attrs = mutableMapOf<String, Attribute>()
        
        when (op) {
            UirOpKind.SOFTMAX -> {
                attrs["axis"] = buildIntAttr { value = -1 }
            }
            UirOpKind.LOG_SOFTMAX -> {
                attrs["axis"] = buildIntAttr { value = -1 }
            }
            UirOpKind.LEAKY_RELU -> {
                // negative_slope: random small value 0.01-0.3
                val negativeSlope = String.format("%.2f", rand.nextDouble() * 0.3 + 0.01)
                attrs["negative_slope"] = buildStringAttr { value = negativeSlope }
            }
            UirOpKind.ELU -> {
                // alpha: random value 0.5-2.0
                val alpha = String.format("%.2f", rand.nextDouble() * 1.5 + 0.5)
                attrs["alpha"] = buildStringAttr { value = alpha }
            }
            UirOpKind.HARDTANH -> {
                // HardTanh: min_val and max_val (default -1.0 to 1.0)
                val minVal = String.format("%.2f", rand.nextDouble() * -2.0 - 0.5)  // -2.5 to -0.5
                val maxVal = String.format("%.2f", rand.nextDouble() * 2.0 + 0.5)   // 0.5 to 2.5
                attrs["min_val"] = buildStringAttr { value = minVal }
                attrs["max_val"] = buildStringAttr { value = maxVal }
            }
            UirOpKind.CLAMP -> {
                // Random min/max for torch.clamp — stored as string attrs
                val minVal = rand.nextDouble() * -2.0  // -2.0 to 0.0
                val maxVal = rand.nextDouble() * 2.0 + 0.5  // 0.5 to 2.5
                attrs["min"] = buildStringAttr { value = String.format("%.2f", minVal) }
                attrs["max"] = buildStringAttr { value = String.format("%.2f", maxVal) }
            }
            UirOpKind.REDUCE_SUM, UirOpKind.REDUCE_MEAN, UirOpKind.REDUCE_MAX, UirOpKind.REDUCE_MIN -> {
                attrs["axis"] = buildIntAttr { value = -1 }
                attrs["keepdims"] = buildIntAttr { value = 0 }
                
                // P0: 随机添加显式 dtype 参数（10% 概率）
                if (rand.nextDouble() < 0.1) {
                    attrs["dtype"] = buildStringAttr { value = randomReduceDtype(op) }
                }
            }
            // P0: cumsum/cumprod 支持 dtype（Issue #189518）
            UirOpKind.CUMSUM, UirOpKind.CUMPROD -> {
                attrs["axis"] = buildIntAttr { value = -1 }
                
                // 30% 概率添加显式 dtype
                if (rand.nextDouble() < 0.3) {
                    attrs["dtype"] = buildStringAttr { value = randomCumulativeDtype(op) }
                }
            }
            UirOpKind.ARGMAX, UirOpKind.ARGMIN -> {
                attrs["axis"] = buildIntAttr { value = -1 }
            }
            UirOpKind.SPLIT -> {
                attrs["axis"] = buildIntAttr { value = 0 }
                attrs["splits"] = buildStringAttr { value = "2" }  // 默认等分 2 份
            }
            UirOpKind.CONCAT -> {
                attrs["axis"] = buildIntAttr { value = 0 }
            }
            UirOpKind.GATHER -> {
                attrs["axis"] = buildIntAttr { value = 0 }
            }
            UirOpKind.CONV2D -> {
                attrs["stride"] = buildIntAttr { value = 1 }
                attrs["padding"] = buildIntAttr { value = 0 }
                attrs["dilation"] = buildIntAttr { value = 1 }
                attrs["groups"] = buildIntAttr { value = 1 }
            }
            UirOpKind.MAX_POOL2D, UirOpKind.AVG_POOL2D -> {
                // 随机 kernel_size，范围 1-2（更大概率用 1，避免空间维太小导致输出为 0）
                val ks = if (rand.nextDouble() < 0.6) 1 else 2
                attrs["kernel_size"] = buildIntAttr { value = ks }
                attrs["stride"] = buildIntAttr { value = rand.nextInt(1, ks + 1) }
                attrs["padding"] = buildIntAttr { value = 0 }
            }
            UirOpKind.LAYER_NORM -> {
                attrs["eps"] = buildIntAttr { value = 1 }  // 1e-5
            }
            UirOpKind.BATCH_NORM -> {
                attrs["eps"] = buildIntAttr { value = 1 }  // 1e-5
            }
            // P2: Resize 算子属性
            UirOpKind.INTERPOLATE, UirOpKind.RESIZE2D -> {
                attrs["mode"] = buildStringAttr { value = "nearest" }
                attrs["coordinate_transformation_mode"] = buildStringAttr { value = "half_pixel" }
            }
            else -> { /* 无特殊属性 */ }
        }
        
        return attrs
    }
    
    /**
     * 推导输出形状（委托给 ShapeInferer）。
     */
    private fun inferOutputShapes(
        op: UirOpKind,
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        // 特殊处理：常数生成算子直接生成随机形状
        if (op in UirOpKind.constantOps) {
            return when (op) {
                UirOpKind.ARANGE -> {
                    // ARANGE 生成 1-D 张量，随机长度
                    val length = rand.nextInt(16, 257)  // 16-256
                    listOf(buildShape {
                        dims.add(buildDim {
                            dimKind = UirDimKind.CONSTANT
                            value = length
                        })
                    })
                }
                UirOpKind.FULL, UirOpKind.ONES, UirOpKind.ZEROS -> {
                    // 生成随机形状（至少 2D）
                    listOf(generateRandomShape(config.minNdim, config.maxNdim))
                }
                else -> listOf(generateRandomShape(1, 4))
            }
        }
        
        if (inputShapes.isEmpty()) {
            return listOf(generateRandomShape(1, 4))
        }
        return ShapeInferer.inferShape(op, inputShapes, attributes)
    }
    
    /**
     * 生成随机形状。
     * 根据当前 [usedElements] 剩余预算动态缩减每维上限，从源头保证不超 [shapeTier]。
     * 当剩余预算紧张时，优先缩小维度值而非缩减维度数，保持图结构多样性。
     */
    open fun generateRandomShape(minNdim: Int, maxNdim: Int): UirShape {
        // 至少 2D，避免很多算子不支持 1D
        val ndim = rand.nextInt(maxOf(2, minNdim), maxOf(2, maxNdim) + 1)
        // 根据剩余预算计算此张量每维上限
        val safeMax = budgetAwareMaxDim(ndim)
        return buildShape {
            repeat(ndim) {
                this.dims.add(buildDim {
                    this.dimKind = UirDimKind.CONSTANT
                    this.value = rand.nextInt(shapeTier.minDim, safeMax + 1)
                })
            }
        }.also { shape ->
            val n = shape.dims.fold(1L) { acc, dim -> acc * (dim.value?.toLong() ?: 1L) }
            usedElements += n
            log.trace { "形状 ${shapeDims(shape)} 元素=$n 累计=${usedElements}/${shapeTier.maxTotalElements}" }
        }
    }

    /**
     * 计算 [ndim] 维下不超过剩余预算的最大维度值。
     * 从 [shapeTier.maxDim] 向下试探确定可行的最大维值。
     */
    private fun budgetAwareMaxDim(ndim: Int): Int {
        val remaining = shapeTier.maxTotalElements - usedElements
        if (remaining <= 0) return shapeTier.minDim
        var d = shapeTier.maxDim
        while (d > shapeTier.minDim) {
            var p = 1L
            repeat(ndim) {
                p = if (p > remaining / d) remaining + 1 else p * d
            }
            if (p <= remaining) break
            d--
        }
        return maxOf(shapeTier.minDim, d)
    }
    
    /**
     * 生成可广播到 target 的形状。
     * 受预算控制，若预算不足则生成 1-D 小形状。
     */
    private fun generateBroadcastableShape(target: UirShape): UirShape {
        val remaining = shapeTier.maxTotalElements - usedElements
        if (remaining <= shapeTier.minDim.toLong() * target.dims.size) {
            // 预算不足时退化为 1-D 小形状
            return buildShape {
                this.dims.add(buildDim {
                    this.dimKind = UirDimKind.CONSTANT
                    this.value = shapeTier.minDim
                })
            }
        }
        return buildShape {
            target.dims.forEach { dim ->
                val targetValue = dim.valueOrNull() ?: rand.nextInt(shapeTier.minDim, shapeTier.maxDim + 1)
                val value = if (rand.nextDouble() < 0.7) minOf(targetValue.toLong(), remaining).toInt() else shapeTier.minDim
                this.dims.add(buildDim {
                    this.dimKind = UirDimKind.CONSTANT
                    this.value = maxOf(shapeTier.minDim, minOf(shapeTier.maxDim, value))
                })
            }
        }
    }
    
    /**
     * 随机选择数据类型（用于 dtype variation）。
     * 
     * 重点测试容易出错的类型组合：
     * - bool -> bfloat16 (Issue #189518)
     * - float16
     * - int32 -> float32
     */
    private fun randomDtype(): String {
        val dtypes = listOf(
            "float32",    // 默认
            "float16",    // 容易溢出
            "bfloat16",   // type promotion bug 高发
            "int32",      // 整数累加
            "int64",      // 大整数
            "bool",       // 布尔 -> 浮点转换
        )
        return dtypes.random(rand)
    }
    
    /**
     * 随机选择 reduce 算子的 dtype（排除 bool，因为 mean 不支持 bool dtype）。
     */
    private fun randomReduceDtype(op: UirOpKind): String {
        // Only use float32 for reduce ops — float16/bfloat16 cause downstream dtype mismatches
        // (e.g., sum(dtype=float16) → conv2d gets float16 input but float32 weight → Half vs Float error)
        val dtypes = mutableListOf("float32", "int32", "int64")
        // mean requires floating-point dtype
        if (op == UirOpKind.REDUCE_MEAN) {
            dtypes.removeAll { it.startsWith("int") }
        }
        return dtypes.random(rand)
    }
    
    /**
     * 随机选择累积算子的 dtype（排除 bool，因为 cumprod 不支持 bool）。
     * cumprod 也不支持整数类型（容易溢出），所以只返回浮点类型。
     */
    private fun randomCumulativeDtype(op: UirOpKind): String {
        // Only use float32 — float16/bfloat16 cause downstream dtype mismatches
        val dtypes = mutableListOf("float32")
        // cumsum supports integer, cumprod does not
        if (op == UirOpKind.CUMSUM) {
            dtypes.addAll(listOf("int32", "int64"))
        }
        return dtypes.random(rand)
    }
    
    private fun newValueId(): String = "v_${valueCounter++}_${randomIdSuffix()}"
    
    /**
     * 对算子的所有输入进行形状适配，必要时插入 wrapper 节点。
     *
     * 策略：
     * - 如果输入维度不足，插入 EXPAND_DIMS 增维
     * - 如果输入维度多余，插入 RESHAPE 减维
     */
    /**
     * 格式化形状为易读字符串
     */
    private fun shapeDims(shape: UirShape): String {
        return shape.dims.map { it.valueOrNull() ?: "?" }.joinToString(", ", "[", "]")
    }
    
    /**
     * 将 totalElements 随机分解为 targetNdim 个正整数的乘积。
     * 例如：totalElements=12, targetNdim=3 → [2, 2, 3] 或 [3, 4, 1]。
     */
    private fun factorizeRandomly(totalElements: Int, targetNdim: Int, rand: Random): List<Int> {
        if (totalElements <= 0 || targetNdim <= 0) return emptyList()
        var remaining = totalElements
        val factors = mutableListOf<Int>()
        for (i in 0 until targetNdim - 1) {
            if (remaining <= 1) {
                factors.add(1)
                continue
            }
            // 找一个随机因数
            val candidates = (1..remaining).filter { remaining % it == 0 }
            if (candidates.isEmpty()) {
                factors.add(1)
            } else {
                factors.add(candidates.random(rand))
                remaining /= factors.last()
            }
        }
        factors.add(remaining)  // 最后一个维度吞掉所有余数
        return factors
    }
    
    // generateBroadcastCompatibleShape removed — no longer needed since
    // ShapeAdapter handles all shape adaptation (expand dims, broadcast, reshape).
}
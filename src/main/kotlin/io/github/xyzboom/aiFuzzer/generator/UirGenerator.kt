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
)

/**
 * UIR 程序生成器。
 *
 * 生成形状兼容的 DAG 图，直接输出可执行的 UIR 程序。
 * 形状大小自动受 [shapeTier] 预算控制，不超限，不重试。
 */
class UirGenerator(private val config: GeneratorConfig = GeneratorConfig()) {

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

    private var valueCounter = 0
    private var nodeCounter = 0
    
    // 形状管理：valueId -> shape
    private val valueShapes = mutableMapOf<String, UirShape>()

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
     * 形状大小由 [generateRandomShape] 动态控制，保证不超出预算。
     */
    fun generate(): UirProgram {
        usedElements = 0
        val actualCount = config.graphCount.random(rand)
        val program = buildProgram {
            for (i in 0 until actualCount) {
                log.debug { "生成图 $i/$actualCount (range=${config.graphCount})" }
                graphs.add(generateGraph("graph_$i"))
            }
        }
        log.debug { "程序生成完成，共 $actualCount 个图，使用 ${usedElements}/${shapeTier.maxTotalElements} 元素 (tier=${shapeTier.label})" }
        return program
    }

    private fun generateGraph(name: String): UirGraph {
        log.debug { "生成图: $name" }
        valueCounter = 0
        
        // 1. 生成图输入
        val numInputs = rand.nextInt(config.minInputs, config.maxInputs + 1)
        log.trace { "图输入数量: $numInputs" }
        val availableValues = mutableListOf<String>()
        
        val graphInputs = (0 until numInputs).map {
            val valueId = newValueId()
            availableValues.add(valueId)
            
            // 为图输入生成形状
            val shape = generateRandomShape(config.minNdim, config.maxNdim)
            valueShapes[valueId] = shape
            log.trace { "输入值 $valueId: 形状=${shapeDims(shape)}" }
            
            buildValueRef {
                this.valueId = valueId
                this.type = buildTensorType {
                    this.typeKind = UirTypeKind.TENSOR
                    this.shape = shape
                    this.dtype = mkDataType()
                }
            }
        }
        
        // 2. 生成节点
        val numNodes = rand.nextInt(config.minNodesPerGraph, config.maxNodesPerGraph + 1)
        val nodeList = mutableListOf<UirNode>()
        val liveTips = mutableMapOf<Int, String>()  // branchId -> tip valueId
        var currentBranch = 0
        liveTips[currentBranch] = availableValues.last()
        
        repeat(numNodes) { nodeIndex ->
            val nodes = generateNode(nodeIndex, availableValues, liveTips, currentBranch)
            nodeList.addAll(nodes)
            
            // 更新可用值（只添加最后一个节点的输出）
            val lastNode = nodes.last()
            for (output in lastNode.outputs) {
                availableValues.add(output.valueId)
            }
            
            // 随机创建新分支
            if (rand.nextDouble() < config.branchProbability && availableValues.size >= 2) {
                currentBranch++
                liveTips[currentBranch] = availableValues[availableValues.size - 2]
            }
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
    private fun selectOpWithConstraints(availableValues: MutableList<String>): UirOpKind {
        val maxRetries = 10
        for (retry in 0 until maxRetries) {
            val candidate = when {
                availableValues.isEmpty() -> opsEnum.filter { it in UirOpKind.constantOps }.random(rand)
                availableValues.size == 1 -> opsEnum.filter { it in UirOpKind.constantOps || it in UirOpKind.singleInputOps }.random(rand)
                else -> opsEnum.random(rand)
            }
            
            // Check constraint: get the input shape(s) this op would receive
            val numInputs = when (candidate) {
                in UirOpKind.constantOps -> 0
                in UirOpKind.singleInputOps -> 1
                in UirOpKind.binaryInputOps -> minOf(2, availableValues.size)
                else -> 1
            }
            
            if (numInputs == 0) return candidate  // Constant ops have no constraints
            
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
    
    private fun generateNode(
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
            in UirOpKind.binaryInputOps -> minOf(2, availableValues.size)
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
            log.trace { "节点 $nodeIndex: 属性 $attributes" }
        }
        
        // 5. 形状适配：检查输入形状是否满足算子约束，必要时插入 wrapper
        val adaptResult = ShapeAdapter.adaptInputs(
            op, inputValueRefs, valueShapes, valueCounter, nodeCounter
        )
        
        // 记录适配信息
        if (adaptResult.wrapperNodes.isNotEmpty()) {
            log.debug { "节点 $nodeIndex: 插入 ${adaptResult.wrapperNodes.size} 个 wrapper 节点" }
        }
        
        val adaptedInputRefs = adaptResult.adaptedRefs
        val adaptedInputShapes = adaptResult.adaptedShapes
        conversionNodes.addAll(adaptResult.wrapperNodes)
        
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
        
        // 特殊处理：二元运算
        if (op in UirOpKind.binaryInputOps && numInputs == 2 && availableValues.size >= 2) {
            // 选择第一个输入
            val input1ValueId = if (tipValue != null && tipValue in availableValues) {
                log.trace { "二元运算: 使用 tip 作为第一个输入" }
                tipValue
            } else {
                availableValues.random(rand)
            }
            
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
                // No broadcast-compatible value found — generate a ZEROS constant
                // with a shape that IS compatible with input1
                val input1Shape = valueShapes[input1ValueId]!!
                val compatibleShape = generateBroadcastCompatibleShape(input1Shape)
                val zerosValueId = newValueId()
                valueShapes[zerosValueId] = compatibleShape
                
                val zerosNode = buildNode {
                    name = "broadcast_compat_zeros_${randomIdSuffix()}"
                    this.op = UirOpKind.ZEROS
                    val outputRef = buildValueRef {
                        this.valueId = zerosValueId
                        this.type = buildTensorType {
                            typeKind = UirTypeKind.TENSOR
                            shape = compatibleShape
                            dtype = mkDataType()
                        }
                    }
                    this.outputs.add(outputRef)
                }
                nodeList.add(zerosNode)
                availableValues.add(zerosValueId)
                zerosValueId
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
    private fun generateRandomShape(minNdim: Int, maxNdim: Int): UirShape {
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
     * Generate a shape that is broadcast-compatible with the given input shape.
     * Strategy: create a shape that is either:
     *   1. A scalar-compatible shape (all 1s) — always broadcastable
     *   2. A shape with same ndim where each dim is either same as input or 1
     *   3. A lower-ndim shape where trailing dims match or are 1
     */
    private fun generateBroadcastCompatibleShape(inputShape: UirShape): UirShape {
        val ndim = inputShape.dims.size
        if (ndim == 0) return buildShape { }  // scalar
        
        val strategy = rand.nextInt(3)
        return when (strategy) {
            0 -> {
                // Strategy 1: all-1s shape (always broadcastable)
                buildShape {
                    for (i in 0 until ndim) {
                        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 1 })
                    }
                }
            }
            1 -> {
                // Strategy 2: same ndim, each dim either same or 1
                buildShape {
                    for (i in 0 until ndim) {
                        val inputDim = inputShape.dims[i].valueOrNull() ?: 1
                        val dim = if (rand.nextBoolean()) inputDim else 1
                        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = dim })
                    }
                }
            }
            else -> {
                // Strategy 3: fewer dims (1 to ndim-1), trailing dims match or are 1
                val outNdim = rand.nextInt(1, ndim + 1)
                buildShape {
                    val offset = ndim - outNdim
                    for (i in 0 until outNdim) {
                        val inputDim = inputShape.dims[offset + i].valueOrNull() ?: 1
                        val dim = if (rand.nextBoolean()) inputDim else 1
                        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = dim })
                    }
                }
            }
        }
    }
}